package com.andrerinas.openheadunit.dashcam

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.WindowManager
import android.util.Range
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.core.CameraSelector
import androidx.camera.core.SessionConfig
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.andrerinas.openheadunit.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class DashcamService : LifecycleService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private val sessionPolicy = DashcamSessionPolicy()
    private val configs = mutableMapOf<String, DashcamServiceConfig>()
    private val finishJobs = mutableMapOf<String, Deferred<Unit>>()
    private val tripLeases = mutableMapOf<String, DashcamLibrary.Lease>()

    private var libraryTail: Deferred<Unit>? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var videoOverlay: DashcamVideoOverlay? = null
    private var boundSessionId: String? = null
    private var recording: Recording? = null
    private var recordingSessionId: String? = null
    private var currentFile: File? = null
    private var recordingStartedAtMs = 0L
    private var recordingTargetFps = 0
    private var recordingWidth = 0
    private var recordingHeight = 0
    private var storageCheckRunnable: Runnable? = null
    private var audioMonitorRunnable: Runnable? = null
    private var audioMutedForCall = false
    private var lastStartId = 0
    private var foreground = false
    private var foregroundAudioAllowed = false

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.dashcam_notification_title), NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
                setSound(null, null)
            })
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        lastStartId = startId
        when (intent?.action) {
            DashcamActions.ACTION_START -> startRequestedSession(intent)
            DashcamActions.ACTION_STOP -> stopRequestedSession(intent.getStringExtra(DashcamActions.EXTRA_SESSION_ID))
            DashcamActions.ACTION_EVENT -> markEvent(
                intent.getStringExtra(DashcamActions.EXTRA_SESSION_ID),
                intent.getLongExtra(DashcamActions.EXTRA_EVENT_MARKED_AT, System.currentTimeMillis())
            )
            DashcamActions.ACTION_STATUS_REQUEST -> {
                publishStatus(
                    if (recording != null) "recording" else "idle",
                    if (recording != null) recordingStatus() else ""
                )
                if (recording == null && sessionPolicy.currentSessionId == null) stopSelfResult(startId)
            }
        }
        if (sessionPolicy.currentSessionId == null && recording == null && !foreground) stopSelfResult(startId)
        return START_NOT_STICKY
    }

    private fun startRequestedSession(intent: Intent) {
        val config = DashcamServiceConfig.from(intent) ?: return
        configs[config.sessionId] = config
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            publishStatus("permission", getString(R.string.dashcam_permission_rationale))
            configs.remove(config.sessionId)
            return
        }
        if (!promoteToForeground(config)) {
            publishStatus("error", getString(R.string.dashcam_camera_unavailable))
            configs.remove(config.sessionId)
            return
        }

        val command = sessionPolicy.start(config.sessionId)
        command.stopSessionId?.let { stopRecordingSession(it, finishTrip = true) }
        command.startSessionId?.let(::prepareSession)
    }

    private fun stopRequestedSession(sessionId: String?) {
        if (sessionId == null) return
        val command = sessionPolicy.stop(sessionId)
        if (command.stopSessionId != null) {
            stopRecordingSession(command.stopSessionId, finishTrip = true)
        } else if (sessionPolicy.isFinalizing(sessionId)) {
            clearStorageCheck()
            enqueueFinishTrip(sessionId, System.currentTimeMillis())
            configs.remove(sessionId)
        } else if (sessionPolicy.pendingSessionId != sessionId) {
            configs.remove(sessionId)
        }
    }

    private fun prepareSession(sessionId: String) {
        val config = configs[sessionId] ?: run {
            failSession(sessionId, getString(R.string.dashcam_camera_unavailable))
            return
        }
        publishStatus("starting", getString(R.string.dashcam_status_waiting))
        enqueueLibrary {
            val retention = DashcamLibrary.enforceRetention(
                this, config.retentionMode, config.retentionDays, config.maxBytes, config.reserveBytes
            )
            if (retention.canRecord) {
                DashcamLibrary.beginTrip(this, config.sessionId, config.startedAtMs)
                tripLeases[config.sessionId] = DashcamLibrary.acquireTripLease(this, config.sessionId)
            }
            retention
        }.let { prepared ->
            serviceScope.launch {
                val retention = try {
                    prepared.await()
                } catch (e: Exception) {
                    failSession(sessionId, e.message ?: getString(R.string.dashcam_storage_full))
                    return@launch
                }
                if (!sessionPolicy.isRecording(sessionId)) return@launch
                if (!retention.canRecord) {
                    failSession(sessionId, getString(R.string.dashcam_storage_full))
                    return@launch
                }
                openCamera(sessionId, config)
            }
        }
    }

    private fun openCamera(sessionId: String, config: DashcamServiceConfig) {
        if (!sessionPolicy.isRecording(sessionId)) return
        val existing = cameraProvider
        if (existing != null) {
            bindAndRecord(existing, sessionId, config)
            return
        }

        val future = try {
            ProcessCameraProvider.getInstance(this)
        } catch (e: Exception) {
            failSession(sessionId, e.message ?: getString(R.string.dashcam_camera_unavailable))
            return
        }
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider
                if (sessionPolicy.isRecording(sessionId)) bindAndRecord(provider, sessionId, config)
            } catch (e: Exception) {
                failSession(sessionId, e.cause?.message ?: e.message ?: getString(R.string.dashcam_camera_unavailable))
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindAndRecord(provider: ProcessCameraProvider, sessionId: String, config: DashcamServiceConfig) {
        if (!sessionPolicy.isRecording(sessionId)) return
        try {
            if (videoCapture == null || boundSessionId != sessionId) {
                provider.unbindAll()
                videoOverlay?.close()
                videoOverlay = null
                val cameraInfo = provider.availableCameraInfos.firstOrNull { info ->
                    val id = try { androidx.camera.camera2.interop.Camera2CameraInfo.from(info).cameraId } catch (_: Exception) { "" }
                    if (config.cameraId.isBlank()) info.lensFacing == CameraSelector.LENS_FACING_BACK else id == config.cameraId
                } ?: throw IllegalStateException(getString(R.string.dashcam_camera_unavailable))

                val supported = DashcamCameraCapabilities.supportedQualities(cameraInfo)
                val chosenQuality = DashcamCameraCapabilities.quality(config.quality).takeIf { it in supported }
                    ?: listOf(Quality.HD, Quality.SD, Quality.FHD, Quality.UHD).firstOrNull { it in supported }
                    ?: throw IllegalStateException(getString(R.string.dashcam_camera_unavailable))
                val frameRates = try {
                    DashcamCameraCapabilities.supportedFrameRates(cameraInfo, chosenQuality)
                } catch (_: Exception) {
                    emptyList()
                }
                val targetFps = config.fps.takeIf { it in frameRates }
                    ?: 30.takeIf { 30 in frameRates }
                    ?: frameRates.firstOrNull()
                    ?: 0
                val recorder = Recorder.Builder()
                    .setQualitySelector(QualitySelector.from(chosenQuality))
                    .setTargetVideoEncodingBitRate(targetVideoBitrate(chosenQuality))
                    .build()
                val capture = VideoCapture.Builder(recorder).setTargetRotation(targetRotation(config.orientation)).build()
                val overlay = DashcamVideoOverlay(this)
                videoCapture = capture
                videoOverlay = overlay
                val session = SessionConfig.Builder(listOf(capture)).addEffect(overlay.effect).apply {
                    if (targetFps > 0) setFrameRateRange(Range(targetFps, targetFps))
                }.build()
                provider.bindToLifecycle(this, DashcamCameraCapabilities.selector(config.cameraId), session)
                boundSessionId = sessionId
                recordingTargetFps = targetFps
                val size = capture.resolutionInfo?.resolution
                recordingWidth = size?.width ?: 0
                recordingHeight = size?.height ?: 0
            }
            startRecording(sessionId, config)
        } catch (e: Exception) {
            provider.unbindAll()
            videoOverlay?.close()
            videoOverlay = null
            videoCapture = null
            boundSessionId = null
            failSession(sessionId, e.message ?: getString(R.string.dashcam_camera_unavailable))
        }
    }

    private fun startRecording(sessionId: String, config: DashcamServiceConfig) {
        if (!sessionPolicy.isRecording(sessionId)) return
        val capture = videoCapture ?: run {
            failSession(sessionId, getString(R.string.dashcam_camera_unavailable))
            return
        }
        recordingStartedAtMs = System.currentTimeMillis()
        videoOverlay?.startLocationUpdates()
        val file = DashcamLibrary.newRecordingFile(this, sessionId, recordingStartedAtMs)
        val output = FileOutputOptions.Builder(file).build()
        val audioAllowed = audioAllowed(config)
        if (config.audio && !audioAllowed) publishStatus("recording", getString(R.string.dashcam_mic_unavailable))
        try {
            var pending = capture.output.prepareRecording(this, output)
            if (audioAllowed) pending = pending.withAudioEnabled()
            currentFile = file
            recordingSessionId = sessionId
            recording = pending.start(ContextCompat.getMainExecutor(this)) { event ->
                handleRecordEvent(sessionId, config, file, event)
            }
        } catch (e: Exception) {
            if (audioAllowed) {
                publishStatus("recording", getString(R.string.dashcam_mic_unavailable))
                try {
                    currentFile = file
                    recordingSessionId = sessionId
                    recording = capture.output.prepareRecording(this, output)
                        .start(ContextCompat.getMainExecutor(this)) { event -> handleRecordEvent(sessionId, config, file, event) }
                    return
                } catch (_: Exception) {
                }
            }
            file.delete()
            recordingSessionId = null
            currentFile = null
            failSession(sessionId, e.message ?: getString(R.string.dashcam_camera_unavailable))
        }
    }

    private fun handleRecordEvent(sessionId: String, config: DashcamServiceConfig, file: File, event: VideoRecordEvent) {
        when (event) {
            is VideoRecordEvent.Start -> {
                if (sessionPolicy.isRecording(sessionId)) {
                    scheduleStorageCheck(sessionId, config)
                    if (config.audio && audioAllowed(config)) scheduleAudioMonitor(sessionId, config)
                    publishStatus("recording", recordingStatus())
                    updateNotification(config)
                }
            }
            is VideoRecordEvent.Finalize -> finalizeRecording(sessionId, config, file, event)
            else -> Unit
        }
    }

    private fun scheduleStorageCheck(sessionId: String, config: DashcamServiceConfig) {
        clearStorageCheck()
        val task = Runnable {
            val file = currentFile
            if (!sessionPolicy.isRecording(sessionId) || recordingSessionId != sessionId || file == null) return@Runnable
            serviceScope.launch {
                val canContinue = try {
                    enqueueLibrary {
                        DashcamLibrary.enforceRetention(
                            this@DashcamService, config.retentionMode, config.retentionDays,
                            config.maxBytes, config.reserveBytes, currentFile = file,
                            currentRecordingStartedAtMs = recordingStartedAtMs
                        ).canRecord
                    }.await()
                } catch (_: Exception) {
                    false
                }
                if (recordingSessionId != sessionId || recording == null) return@launch
                if (!canContinue) {
                    publishStatus("error", getString(R.string.dashcam_storage_full))
                    if (sessionPolicy.stop(sessionId).stopSessionId != null) {
                        enqueueFinishTrip(sessionId, System.currentTimeMillis())
                        stopActiveRecording(sessionId)
                    }
                } else {
                    scheduleStorageCheck(sessionId, config)
                }
            }
        }
        storageCheckRunnable = task
        handler.postDelayed(task, STORAGE_CHECK_MS)
    }

    private fun stopRecordingSession(sessionId: String, finishTrip: Boolean) {
        clearStorageCheck()
        clearAudioMonitor()
        if (recordingSessionId == sessionId && recording != null) {
            if (finishTrip) enqueueFinishTrip(sessionId, System.currentTimeMillis())
            stopActiveRecording(sessionId)
        } else {
            finishWithoutRecording(sessionId, endTrip = finishTrip)
        }
    }

    private fun stopActiveRecording(sessionId: String) {
        if (recordingSessionId != sessionId) {
            finishWithoutRecording(sessionId, endTrip = false)
            return
        }
        try {
            recording?.stop()
        } catch (e: Exception) {
            finishWithoutRecording(sessionId, e.message, endTrip = false)
        }
    }

    private fun finalizeRecording(sessionId: String, config: DashcamServiceConfig, file: File, event: VideoRecordEvent.Finalize) {
        if (recordingSessionId != sessionId) return
        clearStorageCheck()
        clearAudioMonitor()
        videoOverlay?.stopLocationUpdates()
        recording = null
        recordingSessionId = null
        currentFile = null

        val unexpectedEnd = !sessionPolicy.isFinalizing(sessionId)
        if (unexpectedEnd) {
            sessionPolicy.stop(sessionId)
            enqueueFinishTrip(sessionId, System.currentTimeMillis())
        }
        publishStatus("saving", "")

        enqueueLibrary {
            val info = if (file.isFile) DashcamLibrary.videoInfo(file) else null
            val clip = if (info != null) {
                val canPublishGalleryCopy = DashcamLibrary.enforceRetention(
                    this, config.retentionMode, config.retentionDays, config.maxBytes, config.reserveBytes,
                    currentFile = file, currentRecordingStartedAtMs = recordingStartedAtMs,
                    currentGalleryCopyBytes = file.length()
                ).canRecord
                DashcamLibrary.addFinalizedClip(
                    this, sessionId, file, recordingStartedAtMs, info.durationMs,
                    info.width, info.height, recordingTargetFps, 0L, canPublishGalleryCopy
                )
            } else {
                file.delete()
                null
            }
            val retention = DashcamLibrary.enforceRetention(
                this, config.retentionMode, config.retentionDays, config.maxBytes, config.reserveBytes,
                protectedClipId = file.nameWithoutExtension
            )
            RecordingResult(clip != null, event.hasError(), retention)
        }.let { saved ->
            serviceScope.launch {
                val result = try {
                    saved.await()
                } catch (e: Exception) {
                    publishStatus("error", e.message ?: getString(R.string.dashcam_storage_full))
                    finishTransition(sessionId, stopPending = true)
                    return@launch
                }
                if (!result.retention.canRecord) {
                    publishStatus("error", getString(R.string.dashcam_storage_full))
                    finishTransition(sessionId, stopPending = true)
                } else if (!result.valid || result.recordingError) {
                    publishStatus("error", getString(R.string.dashcam_camera_unavailable))
                    finishTransition(sessionId, stopPending = false)
                } else {
                    finishTransition(sessionId, stopPending = false)
                }
            }
        }
    }

    private fun finishTransition(sessionId: String, stopPending: Boolean) {
        if (stopPending) sessionPolicy.pendingSessionId?.let { pending ->
            sessionPolicy.stop(pending)
            configs.remove(pending)
        }
        val command = sessionPolicy.finalized(sessionId)
        if (command.startSessionId != null) {
            configs.remove(sessionId)
            releaseTripLease(sessionId)
            unbindCamera()
            prepareSession(command.startSessionId)
        } else {
            configs.remove(sessionId)
            releaseTripLease(sessionId)
            unbindCamera()
            publishStatus("idle", "")
            finishForegroundAndStop()
        }
    }

    private fun finishWithoutRecording(sessionId: String, error: String? = null, endTrip: Boolean = true) {
        val finished = if (endTrip) enqueueFinishTrip(sessionId, System.currentTimeMillis()) else enqueueLibrary { Unit }
        serviceScope.launch {
            try { finished.await() } catch (_: Exception) { }
            if (!sessionPolicy.isFinalizing(sessionId)) return@launch
            if (!error.isNullOrBlank()) publishStatus("error", error)
            finishTransition(sessionId, stopPending = false)
        }
    }

    private fun failSession(sessionId: String, message: String) {
        publishStatus("error", message)
        val command = sessionPolicy.stop(sessionId)
        if (command.stopSessionId != null) stopRecordingSession(sessionId, finishTrip = true)
        else if (sessionPolicy.isFinalizing(sessionId) && recordingSessionId != sessionId) finishWithoutRecording(sessionId)
    }

    private fun markEvent(sessionId: String?, markedAtMs: Long) {
        if (sessionId == null || sessionPolicy.currentSessionId != sessionId) return
        enqueueLibrary { DashcamLibrary.markEvent(this, sessionId, markedAtMs) }
            .let { saved -> serviceScope.launch {
                if (try { saved.await() } catch (_: Exception) { false }) {
                    publishStatus(if (recording != null) "recording" else "event", "Event saved")
                    handler.postDelayed({
                        if (recording != null) publishStatus("recording", recordingStatus())
                    }, 1200L)
                }
            } }
    }

    private fun enqueueFinishTrip(sessionId: String, endedAtMs: Long): Deferred<Unit> =
        enqueueLibrary { DashcamLibrary.finishTrip(this, sessionId, endedAtMs) }

    private fun <T> enqueueLibrary(block: () -> T): Deferred<T> {
        val previous = libraryTail
        val next = serviceScope.async(Dispatchers.IO) {
            try { previous?.await() } catch (_: Exception) { }
            block()
        }
        libraryTail = serviceScope.async(Dispatchers.IO) {
            try { next.await() } catch (_: Exception) { }
        }
        return next
    }

    private fun audioAllowed(config: DashcamServiceConfig): Boolean {
        return foregroundAudioAllowed && audioAvailable(config)
    }

    private fun audioAvailable(config: DashcamServiceConfig): Boolean {
        if (!config.audio || ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return false
        val manager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return manager.mode != AudioManager.MODE_IN_CALL && manager.mode != AudioManager.MODE_IN_COMMUNICATION
    }

    private fun promoteToForeground(config: DashcamServiceConfig): Boolean {
        val audio = audioAvailable(config)
        val location = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val cameraType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                (if (location) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0)
        } else 0
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                (if (location) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0) or
                (if (audio) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
        } else 0
        return try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(config), type)
            foregroundAudioAllowed = audio
            foreground = true
            true
        } catch (e: Exception) {
            if (!audio) return false
            try {
                ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(config), cameraType)
                foregroundAudioAllowed = false
                foreground = true
                publishStatus("starting", getString(R.string.dashcam_mic_unavailable))
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    private fun notification(config: DashcamServiceConfig): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL)
            else Notification.Builder(this)
        return builder.setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle(getString(R.string.dashcam_notification_title))
            .setContentText(getString(R.string.dashcam_notification_recording))
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(config: DashcamServiceConfig) {
        if (foreground) NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification(config))
    }

    private fun recordingStatus(): String {
        val size = if (recordingWidth > 0 && recordingHeight > 0) "${recordingWidth}×${recordingHeight}" else ""
        val fps = if (recordingTargetFps > 0) "target $recordingTargetFps fps" else "camera frame rate"
        return listOf(size, fps).filter(String::isNotBlank).joinToString(" · ")
    }

    private fun publishStatus(status: String, message: String) {
        sendBroadcast(Intent(DashcamActions.ACTION_STATUS).setPackage(packageName)
            .putExtra(DashcamActions.EXTRA_STATUS, status)
            .putExtra(DashcamActions.EXTRA_MESSAGE, message))
    }

    private fun targetRotation(mode: String): Int = when (mode) {
        "portrait" -> Surface.ROTATION_0
        "landscape" -> Surface.ROTATION_90
        else -> (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
    }

    private fun targetVideoBitrate(quality: Quality): Int = when (quality) {
        Quality.SD -> 6_000_000
        Quality.HD -> 12_000_000
        Quality.FHD -> 24_000_000
        Quality.UHD -> 50_000_000
        else -> 24_000_000
    }

    private fun unbindCamera() {
        clearStorageCheck()
        try { cameraProvider?.unbindAll() } catch (_: Exception) { }
        videoOverlay?.close()
        videoOverlay = null
        videoCapture = null
        boundSessionId = null
    }

    private fun releaseTripLease(sessionId: String) {
        try { tripLeases.remove(sessionId)?.close() } catch (_: Exception) { }
    }

    private fun clearStorageCheck() {
        storageCheckRunnable?.let(handler::removeCallbacks)
        storageCheckRunnable = null
    }

    private fun scheduleAudioMonitor(sessionId: String, config: DashcamServiceConfig) {
        clearAudioMonitor()
        audioMutedForCall = false
        val task = object : Runnable {
            override fun run() {
                if (recordingSessionId != sessionId || recording == null) return
                val mute = !audioAvailable(config)
                if (mute != audioMutedForCall) {
                    audioMutedForCall = mute
                    try { recording?.mute(mute) } catch (_: Exception) { }
                    publishStatus("recording", if (mute) getString(R.string.dashcam_mic_unavailable) else recordingStatus())
                }
                handler.postDelayed(this, AUDIO_CHECK_MS)
            }
        }
        audioMonitorRunnable = task
        handler.postDelayed(task, AUDIO_CHECK_MS)
    }

    private fun clearAudioMonitor() {
        audioMonitorRunnable?.let(handler::removeCallbacks)
        audioMonitorRunnable = null
        audioMutedForCall = false
    }

    private fun finishForegroundAndStop() {
        if (foreground) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            foreground = false
        }
        stopSelfResult(lastStartId)
    }

    override fun onDestroy() {
        clearStorageCheck()
        clearAudioMonitor()
        try { recording?.stop() } catch (_: Exception) { }
        unbindCamera()
        tripLeases.keys.toList().forEach(::releaseTripLease)
        serviceScope.cancel()
        super.onDestroy()
    }

    private data class RecordingResult(
        val valid: Boolean,
        val recordingError: Boolean,
        val retention: DashcamRetentionResult
    )

    companion object {
        private const val CHANNEL = "dashcam_recording"
        private const val NOTIFICATION_ID = 930021
        private const val AUDIO_CHECK_MS = 1_000L
        private const val STORAGE_CHECK_MS = 30_000L
    }
}
