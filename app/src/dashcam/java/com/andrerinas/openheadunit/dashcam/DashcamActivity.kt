package com.andrerinas.openheadunit.dashcam

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.text.format.Formatter
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.andrerinas.openheadunit.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.text.DateFormat
import java.util.Date

class DashcamActivity : AppCompatActivity() {
    private val prefs by lazy { getSharedPreferences(DashcamSettingsKeys.PREFS, Context.MODE_PRIVATE) }
    private var cameraChoices = emptyList<DashcamCameraChoice>()
    private var availableCameraInfos = emptyList<androidx.camera.core.CameraInfo>()
    private var supportedQualities = emptyList<String>()
    private var supportedFps = emptyList<Int>()
    private var selectedCameraInfo: androidx.camera.core.CameraInfo? = null
    private var showLibrary = false
    private var activeFilter = LibraryFilter.TRIPS
    private var clips = emptyList<DashcamClipRecord>()
    private var previewProvider: ProcessCameraProvider? = null
    private var previewUseCase: Preview? = null
    private var pendingExport: DashcamClipRecord? = null
    private var exportLease: DashcamLibrary.Lease? = null
    private var exportInProgress = false
    private var enableAfterRecordingPermissions = false
    private var previewAfterCameraPermission = false

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) {
            toast(getString(R.string.dashcam_permission_rationale))
            enableAfterRecordingPermissions = false
            renderSettings()
            return@registerForActivityResult
        }
        loadCameraChoices()
        if (enableAfterRecordingPermissions) {
            if (hasLocationPermission()) enableAutoRecording()
            else locationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (previewAfterCameraPermission) showPreview()
        previewAfterCameraPermission = false
        renderSettings()
    }

    private val locationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (enableAfterRecordingPermissions) {
            if (granted || hasLocationPermission()) enableAutoRecording()
            else {
                enableAfterRecordingPermissions = false
                toast(getString(R.string.dashcam_location_permission_rationale))
            }
        }
        renderSettings()
    }

    private val microphonePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) prefs.edit().putBoolean(DashcamSettingsKeys.AUDIO, true).apply()
        else toast(getString(R.string.dashcam_mic_unavailable))
        renderSettings()
    }

    private val exportDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("video/mp4")) { uri ->
        val clip = pendingExport
        pendingExport = null
        if (clip == null) return@registerForActivityResult
        if (uri == null) {
            val lease = exportLease
            exportLease = null
            lease?.close()
            lifecycleScope.launch(Dispatchers.IO) {
                DashcamLibrary.setExporting(this@DashcamActivity, clip.id, false)
                withContext(Dispatchers.Main) { refreshLibrary() }
            }
            return@registerForActivityResult
        }
        exportInProgress = true
        val lease = exportLease
        lifecycleScope.launch {
            var copied = false
            try {
                copied = withContext(Dispatchers.IO) {
                    try {
                        contentResolver.openOutputStream(uri)?.use { output ->
                            FileInputStream(clip.file).use { input -> input.copyTo(output) }
                        } ?: throw IllegalStateException("Could not open export target")
                        DashcamLibrary.setExported(this@DashcamActivity, clip.id)
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        DashcamLibrary.setExporting(this@DashcamActivity, clip.id, false)
                        false
                    }
                }
                toast(if (copied) "Clip exported" else "Export failed")
            } finally {
                withContext(NonCancellable + Dispatchers.IO) {
                    if (!copied) DashcamLibrary.setExporting(this@DashcamActivity, clip.id, false)
                    lease?.close()
                }
                exportLease = null
                exportInProgress = false
            }
            refreshLibrary()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.dashcam_screen_title)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            loadCameraChoices()
        }
        if (intent.getBooleanExtra(EXTRA_SHOW_LIBRARY, false)) showLibrary = true
        render()
        if (showLibrary) refreshLibrary()
    }

    override fun onDestroy() {
        previewUseCase?.let { useCase -> previewProvider?.unbind(useCase) }
        if (!exportInProgress) {
            try { exportLease?.close() } catch (_: Exception) { }
            exportLease = null
        }
        super.onDestroy()
    }

    private fun render() {
        if (showLibrary) renderLibrary() else renderSettings()
    }

    private fun renderSettings() {
        val root = pageRoot()
        addHeader(root, getString(R.string.dashcam_screen_title), getString(R.string.dashcam_auto_record_help))
        val content = contentColumn(root)

        val automatic = SwitchCompat(this).apply {
            text = getString(R.string.dashcam_auto_record)
            isChecked = prefs.getBoolean(DashcamSettingsKeys.ENABLED, false)
            setTextColor(Color.WHITE)
            textSize = 16f
            setOnCheckedChangeListener { _, checked ->
                if (!checked) {
                    DashcamIntegration.setEnabled(this@DashcamActivity, false)
                } else if (ContextCompat.checkSelfPermission(this@DashcamActivity, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                    enableAfterRecordingPermissions = true
                    cameraPermission.launch(Manifest.permission.CAMERA)
                    isChecked = false
                } else if (!hasLocationPermission()) {
                    enableAfterRecordingPermissions = true
                    locationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                    isChecked = false
                } else {
                    DashcamIntegration.setEnabled(this@DashcamActivity, true)
                    if (cameraChoices.isEmpty()) loadCameraChoices()
                }
            }
        }
        content.addView(automatic, rowLayout())

        addSection(content, getString(R.string.dashcam_overlay_section))
        addValueRow(content, getString(R.string.dashcam_overlay_mode), overlayModeLabel()) { showOverlayMode() }

        addSection(content, "Camera")
        val cameraValue = cameraChoices.firstOrNull { it.id == prefs.getString(DashcamSettingsKeys.CAMERA_ID, "") }?.label
            ?: cameraChoices.firstOrNull { it.facing == CameraSelector.LENS_FACING_BACK }?.label
            ?: "Rear camera"
        addValueRow(content, getString(R.string.dashcam_camera), cameraValue) { showCameraChoices() }

        val quality = prefs.getString(DashcamSettingsKeys.QUALITY, "HD") ?: "HD"
        addValueRow(content, getString(R.string.dashcam_quality), qualityLabel(quality)) { showQualityChoices() }
        val fps = prefs.getInt(DashcamSettingsKeys.FPS, 30)
        addValueRow(content, getString(R.string.dashcam_fps), if (fps == 0) "Camera default" else "$fps fps") { showFpsChoices() }
        val segmentMinutes = DashcamSegmentPolicy.normalizeMinutes(
            prefs.getInt(DashcamSettingsKeys.SEGMENT_MINUTES, DashcamSettingsKeys.DEFAULT_SEGMENT_MINUTES)
        )
        addValueRow(content, getString(R.string.dashcam_segment_length), getString(R.string.dashcam_segment_minutes, segmentMinutes)) {
            showSegmentLengthChoices()
        }
        addValueRow(content, getString(R.string.dashcam_orientation), orientationLabel()) { showOrientationChoices() }

        val audioSwitch = SwitchCompat(this).apply {
            text = getString(R.string.dashcam_audio)
            isChecked = prefs.getBoolean(DashcamSettingsKeys.AUDIO, false)
            setTextColor(Color.WHITE)
            setOnCheckedChangeListener { _, checked ->
                if (checked && ContextCompat.checkSelfPermission(this@DashcamActivity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                    isChecked = false
                } else {
                    prefs.edit().putBoolean(DashcamSettingsKeys.AUDIO, checked).apply()
                }
            }
        }
        content.addView(audioSwitch, rowLayout())

        addSection(content, getString(R.string.dashcam_storage_policy))
        val mode = retentionMode()
        addValueRow(content, getString(R.string.dashcam_storage_policy), retentionModeLabel(mode)) { showRetentionMode() }
        addValueRow(content, getString(R.string.dashcam_retention_days), "${prefs.getInt(DashcamSettingsKeys.RETENTION_DAYS, 7)} days") { showRetentionDays() }
        addValueRow(content, getString(R.string.dashcam_storage_limit), "${formatGb(prefs.getLong(DashcamSettingsKeys.MAX_BYTES, DashcamSettingsKeys.DEFAULT_MAX_BYTES))} GB") {
            showGbDialog(getString(R.string.dashcam_storage_limit), DashcamSettingsKeys.MAX_BYTES, DashcamSettingsKeys.DEFAULT_MAX_BYTES, 1)
        }
        addValueRow(content, getString(R.string.dashcam_free_reserve), "${formatGb(prefs.getLong(DashcamSettingsKeys.RESERVE_BYTES, DashcamSettingsKeys.DEFAULT_RESERVE_BYTES))} GB") {
            showGbDialog(getString(R.string.dashcam_free_reserve), DashcamSettingsKeys.RESERVE_BYTES, DashcamSettingsKeys.DEFAULT_RESERVE_BYTES, 0)
        }
        addNote(content, getString(when (mode) {
            DashcamRetentionMode.AGE -> R.string.dashcam_age_policy_help
            DashcamRetentionMode.SMART -> R.string.dashcam_smart_policy_help
            DashcamRetentionMode.COMBINED -> R.string.dashcam_combined_policy_help
        }))
        addNote(content, "Camera, quality, clip length and storage changes apply to the next Android Auto trip.")

        addSection(content, getString(R.string.dashcam_preview))
        addButton(content, getString(R.string.dashcam_preview)) {
            if (DashcamIntegration.activeSessionId() != null) {
                toast("Close the active Android Auto trip before previewing.")
            } else if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                previewAfterCameraPermission = true
                cameraPermission.launch(Manifest.permission.CAMERA)
            } else showPreview()
        }
        addButton(content, getString(R.string.dashcam_library)) {
            showLibrary = true
            renderLibrary()
            refreshLibrary()
        }
        setContentView(root)
    }

    private fun renderLibrary() {
        val root = pageRoot()
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        addButton(header, "‹") { showLibrary = false; renderSettings() }
        val title = TextView(this).apply {
            text = getString(R.string.dashcam_library)
            setTextColor(Color.WHITE)
            textSize = 21f
        }
        header.addView(title, LinearLayout.LayoutParams(0, dp(52), 1f))
        root.addView(header, rowLayout())

        val filters = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        LibraryFilter.values().forEach { filter ->
            addButton(filters, when (filter) {
                LibraryFilter.TRIPS -> getString(R.string.dashcam_filter_trips)
                LibraryFilter.LOCKED -> getString(R.string.dashcam_filter_locked)
                LibraryFilter.EVENTS -> getString(R.string.dashcam_filter_events)
            }) { activeFilter = filter; renderLibrary() }
        }
        root.addView(filters, rowLayout())

        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(12), dp(20)) }
        val visible = when (activeFilter) {
            LibraryFilter.TRIPS -> clips
            LibraryFilter.LOCKED -> clips.filter { it.locked }
            LibraryFilter.EVENTS -> clips.filter { it.eventIds.isNotEmpty() }
        }
        if (visible.isEmpty()) addNote(content, getString(R.string.dashcam_empty_library))
        var previousTrip: String? = null
        visible.sortedByDescending { it.recordedAtMs }.forEach { clip ->
            if (activeFilter == LibraryFilter.TRIPS && previousTrip != clip.sessionId) {
                previousTrip = clip.sessionId
                addSection(content, "Trip · ${DateFormat.getDateTimeInstance().format(Date(clip.sessionStartedAtMs))}")
            }
            addClipCard(content, clip)
        }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    private fun addClipCard(parent: LinearLayout, clip: DashcamClipRecord) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(0xFF20242B.toInt())
        }
        val title = TextView(this).apply {
            text = "${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(clip.recordedAtMs))} · ${if (clip.locked) "Locked" else "Normal"}${if (clip.eventIds.isNotEmpty()) " · Event" else ""}"
            setTextColor(Color.WHITE)
            textSize = 15f
        }
        card.addView(title)
        val gap = when {
            clip.gapBeforeMs > 0 -> " · gap ${clip.gapBeforeMs / 1000}s"
            clip.gapBeforeMs == DashcamSegmentPolicy.UNKNOWN_GAP_MS -> " · ${getString(R.string.dashcam_gap_unmeasured)}"
            else -> ""
        }
        val eventTime = clip.eventSummaries.firstOrNull()?.let {
            " · event ${if (it.complete) "${it.durationMs / 1000}s" else "up to ${it.durationMs / 1000}s"}"
        }.orEmpty()
        val meta = TextView(this).apply {
            text = "${clip.durationMs / 1000}s · ${Formatter.formatFileSize(this@DashcamActivity, clip.bytes)} · ${clip.width}×${clip.height} · target ${clip.targetFps} fps$gap$eventTime"
            setTextColor(0xFFB9C0CA.toInt())
            textSize = 12f
        }
        card.addView(meta)
        if (clip.galleryUri == null) addNote(card, getString(R.string.dashcam_gallery_unavailable))
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        addButton(actions, "Play") { playClip(clip) }
        if (clip.eventIds.isEmpty()) {
            addButton(actions, getString(if (clip.locked) R.string.dashcam_unlock else R.string.dashcam_lock)) {
                lifecycleScope.launch(Dispatchers.IO) { DashcamLibrary.setLocked(this@DashcamActivity, clip.id, !clip.locked) }
                    .invokeOnCompletion { runOnUiThread { refreshLibrary() } }
            }
        }
        addButton(actions, getString(R.string.dashcam_export)) { exportClip(clip) }
        if (!clip.exporting) addButton(actions, getString(R.string.dashcam_delete)) { confirmDelete(clip) }
        card.addView(actions)
        parent.addView(card, rowLayout().apply { bottomMargin = dp(8) })
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun enableAutoRecording() {
        enableAfterRecordingPermissions = false
        DashcamIntegration.setEnabled(this, true)
        if (cameraChoices.isEmpty()) loadCameraChoices()
    }

    private fun loadCameraChoices() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        try {
            val future = ProcessCameraProvider.getInstance(this)
            future.addListener({
                try {
                    val provider = future.get()
                    availableCameraInfos = provider.availableCameraInfos
                    cameraChoices = DashcamCameraCapabilities.cameraChoices(availableCameraInfos)
                    val selectedId = prefs.getString(DashcamSettingsKeys.CAMERA_ID, "").orEmpty()
                    val selected = cameraChoices.firstOrNull { it.id == selectedId }
                        ?: DashcamCameraCapabilities.defaultRearCamera(cameraChoices)
                        ?: cameraChoices.firstOrNull()
                    selectedCameraInfo = selected?.let { choice ->
                        availableCameraInfos.firstOrNull { info ->
                            try { androidx.camera.camera2.interop.Camera2CameraInfo.from(info).cameraId == choice.id } catch (_: Exception) { false }
                        }
                    }
                    if (selected != null && selected.id != selectedId) prefs.edit().putString(DashcamSettingsKeys.CAMERA_ID, selected.id).apply()
                    refreshQualityChoices()
                    render()
                } catch (e: Exception) {
                    toast(e.message ?: getString(R.string.dashcam_camera_unavailable))
                }
            }, ContextCompat.getMainExecutor(this))
        } catch (e: Exception) {
            toast(e.message ?: getString(R.string.dashcam_camera_unavailable))
        }
    }

    private fun refreshQualityChoices() {
        val info = selectedCameraInfo ?: return
        val supported = DashcamCameraCapabilities.supportedQualities(info).map(DashcamCameraCapabilities::qualityName)
        supportedQualities = supported
        val current = prefs.getString(DashcamSettingsKeys.QUALITY, "HD") ?: "HD"
        val chosen = current.takeIf { it in supported } ?: if ("HD" in supported) "HD" else supported.firstOrNull() ?: "SD"
        prefs.edit().putString(DashcamSettingsKeys.QUALITY, chosen).apply()
        supportedFps = try { DashcamCameraCapabilities.supportedFrameRates(info, DashcamCameraCapabilities.quality(chosen)) } catch (_: Exception) { emptyList() }
        val savedFps = prefs.getInt(DashcamSettingsKeys.FPS, 30)
        if (savedFps != 0 && savedFps !in supportedFps) prefs.edit().putInt(DashcamSettingsKeys.FPS, supportedFps.firstOrNull() ?: 0).apply()
    }

    private fun showCameraChoices() {
        if (cameraChoices.isEmpty()) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                cameraPermission.launch(Manifest.permission.CAMERA)
            } else loadCameraChoices()
            return
        }
        val labels = cameraChoices.map { it.label }.toTypedArray()
        val current = cameraChoices.indexOfFirst { it.id == prefs.getString(DashcamSettingsKeys.CAMERA_ID, "") }.coerceAtLeast(0)
        MaterialAlertDialogBuilder(this, R.style.DarkAlertDialog).setTitle(R.string.dashcam_camera)
            .setSingleChoiceItems(labels, current) { dialog, which ->
                prefs.edit().putString(DashcamSettingsKeys.CAMERA_ID, cameraChoices[which].id).apply()
                selectedCameraInfo = availableCameraInfos.firstOrNull { info ->
                    try { androidx.camera.camera2.interop.Camera2CameraInfo.from(info).cameraId == cameraChoices[which].id } catch (_: Exception) { false }
                }
                refreshQualityChoices()
                dialog.dismiss()
                renderSettings()
            }.setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun showQualityChoices() {
        if (supportedQualities.isEmpty()) { showCameraChoices(); return }
        val labels = supportedQualities.map(::qualityLabel)
        val current = supportedQualities.indexOf(prefs.getString(DashcamSettingsKeys.QUALITY, "HD")).coerceAtLeast(0)
        showChoiceDialog(getString(R.string.dashcam_quality), labels, current) { index ->
            val quality = supportedQualities[index]
            prefs.edit().putString(DashcamSettingsKeys.QUALITY, quality).apply()
            selectedCameraInfo?.let { info ->
                supportedFps = try { DashcamCameraCapabilities.supportedFrameRates(info, DashcamCameraCapabilities.quality(quality)) } catch (_: Exception) { emptyList() }
                val saved = prefs.getInt(DashcamSettingsKeys.FPS, 30)
                if (saved != 0 && saved !in supportedFps) prefs.edit().putInt(DashcamSettingsKeys.FPS, supportedFps.firstOrNull() ?: 0).apply()
            }
            renderSettings()
        }
    }

    private fun showFpsChoices() {
        val values = supportedFps.ifEmpty { listOf(0) }
        val labels = values.map { if (it == 0) "Camera default" else "$it fps" }
        val current = values.indexOf(prefs.getInt(DashcamSettingsKeys.FPS, 30)).coerceAtLeast(0)
        showChoiceDialog(getString(R.string.dashcam_fps), labels, current) { index ->
            prefs.edit().putInt(DashcamSettingsKeys.FPS, values[index]).apply()
            renderSettings()
        }
    }

    private fun showSegmentLengthChoices() {
        val values = DashcamSegmentPolicy.ALLOWED_MINUTES
        val current = values.indexOf(
            DashcamSegmentPolicy.normalizeMinutes(
                prefs.getInt(DashcamSettingsKeys.SEGMENT_MINUTES, DashcamSettingsKeys.DEFAULT_SEGMENT_MINUTES)
            )
        )
        showChoiceDialog(
            getString(R.string.dashcam_segment_length),
            values.map { getString(R.string.dashcam_segment_minutes, it) },
            current
        ) { index ->
            prefs.edit().putInt(DashcamSettingsKeys.SEGMENT_MINUTES, values[index]).apply()
            renderSettings()
        }
    }

    private fun showOverlayMode() {
        val modes = listOf(
            DashcamSettingsKeys.OVERLAY_COMPACT,
            DashcamSettingsKeys.OVERLAY_FULL,
            DashcamSettingsKeys.OVERLAY_HIDDEN
        )
        showChoiceDialog(getString(R.string.dashcam_overlay_mode), modes.map(::overlayModeLabel), modes.indexOf(overlayMode())) { index ->
            prefs.edit().putString(DashcamSettingsKeys.OVERLAY_MODE, modes[index]).apply()
            renderSettings()
        }
    }

    private fun overlayMode(): String = prefs.getString(DashcamSettingsKeys.OVERLAY_MODE, DashcamSettingsKeys.DEFAULT_OVERLAY_MODE)
        ?.takeIf { it in setOf(DashcamSettingsKeys.OVERLAY_COMPACT, DashcamSettingsKeys.OVERLAY_FULL, DashcamSettingsKeys.OVERLAY_HIDDEN) }
        ?: DashcamSettingsKeys.DEFAULT_OVERLAY_MODE

    private fun overlayModeLabel(mode: String = overlayMode()): String = getString(when (mode) {
        DashcamSettingsKeys.OVERLAY_FULL -> R.string.dashcam_overlay_full
        DashcamSettingsKeys.OVERLAY_HIDDEN -> R.string.dashcam_overlay_hidden
        else -> R.string.dashcam_overlay_compact
    })

    private fun showOrientationChoices() {
        val values = listOf("auto", "portrait", "landscape")
        val labels = values.map { mode -> when (mode) {
            "portrait" -> getString(R.string.dashcam_orientation_portrait)
            "landscape" -> getString(R.string.dashcam_orientation_landscape)
            else -> getString(R.string.dashcam_orientation_auto)
        } }
        showChoiceDialog(getString(R.string.dashcam_orientation), labels, values.indexOf(prefs.getString(DashcamSettingsKeys.ORIENTATION, "auto")).coerceAtLeast(0)) { index ->
            prefs.edit().putString(DashcamSettingsKeys.ORIENTATION, values[index]).apply()
            renderSettings()
        }
    }

    private fun showRetentionMode() {
        val modes = DashcamRetentionMode.values()
        showChoiceDialog(getString(R.string.dashcam_storage_policy), modes.map(::retentionModeLabel), modes.indexOf(retentionMode())) { index ->
            prefs.edit().putString(DashcamSettingsKeys.RETENTION_MODE, modes[index].name).apply()
            renderSettings()
        }
    }

    private fun showRetentionDays() {
        val fixed = listOf(1, 3, 7, 14, 30)
        val names = fixed.map { "$it days" } + "Custom…"
        val current = fixed.indexOf(prefs.getInt(DashcamSettingsKeys.RETENTION_DAYS, 7)).let { if (it < 0) fixed.size else it }
        showChoiceDialog(getString(R.string.dashcam_retention_days), names, current) { index ->
            if (index < fixed.size) {
                prefs.edit().putInt(DashcamSettingsKeys.RETENTION_DAYS, fixed[index]).apply()
                renderSettings()
            } else showNumberDialog(getString(R.string.dashcam_retention_days), prefs.getInt(DashcamSettingsKeys.RETENTION_DAYS, 7), 1) { days ->
                prefs.edit().putInt(DashcamSettingsKeys.RETENTION_DAYS, days.coerceIn(1, 36_500)).apply()
                renderSettings()
            }
        }
    }

    private fun showGbDialog(title: String, key: String, defaultBytes: Long, minimum: Int) {
        val initial = prefs.getLong(key, defaultBytes) / GIGABYTE
        showNumberDialog(title, initial.toInt().coerceAtLeast(minimum), minimum, decimal = true) { value ->
            prefs.edit().putLong(key, (value * GIGABYTE).toLong()).apply()
            renderSettings()
        }
    }

    private fun showNumberDialog(title: String, initial: Int, minimum: Int, decimal: Boolean = false, onValue: (Int) -> Unit) {
        val input = EditText(this).apply {
            inputType = if (decimal) InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL else InputType.TYPE_CLASS_NUMBER
            setText(initial.toString())
            selectAll()
        }
        MaterialAlertDialogBuilder(this, R.style.DarkAlertDialog).setTitle(title).setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val value = input.text.toString().toDoubleOrNull()?.toInt() ?: initial
                onValue(value.coerceAtLeast(minimum))
            }.setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun showChoiceDialog(title: String, labels: List<String>, current: Int, onChoice: (Int) -> Unit) {
        MaterialAlertDialogBuilder(this, R.style.DarkAlertDialog).setTitle(title)
            .setSingleChoiceItems(labels.toTypedArray(), current) { dialog, which -> dialog.dismiss(); onChoice(which) }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun showPreview() {
        if (DashcamIntegration.activeSessionId() != null) {
            toast("Close the active Android Auto trip before previewing.")
            return
        }
        val previewView = PreviewView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(260))
        }
        val dialog = MaterialAlertDialogBuilder(this, R.style.DarkAlertDialog)
            .setTitle(R.string.dashcam_preview).setView(previewView)
            .setNegativeButton(android.R.string.cancel, null).create()
        dialog.setOnDismissListener {
            previewUseCase?.let { preview -> previewProvider?.unbind(preview) }
            previewUseCase = null
        }
        dialog.show()
        try {
            val future = ProcessCameraProvider.getInstance(this)
            future.addListener({
                try {
                    val provider = future.get()
                    val id = prefs.getString(DashcamSettingsKeys.CAMERA_ID, "").orEmpty()
                    val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                    provider.bindToLifecycle(this, DashcamCameraCapabilities.selector(id), preview)
                    previewProvider = provider
                    previewUseCase = preview
                } catch (_: Exception) {
                    if (dialog.isShowing) toast(getString(R.string.dashcam_preview_unavailable))
                }
            }, ContextCompat.getMainExecutor(this))
        } catch (_: Exception) {
            toast(getString(R.string.dashcam_preview_unavailable))
        }
    }

    private fun refreshLibrary() {
        lifecycleScope.launch {
            try {
                clips = withContext(Dispatchers.IO) {
                    DashcamLibrary.listClips(this@DashcamActivity, DashcamIntegration.activeSessionId())
                }
                if (showLibrary) renderLibrary()
            } catch (e: Exception) {
                toast(e.message ?: "Dashcam library unavailable")
            }
        }
    }

    private fun playClip(clip: DashcamClipRecord) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", clip.file)
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (e: Exception) {
            toast(e.message ?: "No video player available.")
        }
    }

    private fun exportClip(clip: DashcamClipRecord) {
        lifecycleScope.launch(Dispatchers.IO) {
            val marked = DashcamLibrary.setExporting(this@DashcamActivity, clip.id, true)
            val lease = if (marked) DashcamLibrary.acquireExportLease(this@DashcamActivity, clip.id) else null
            if (lease == null) {
                DashcamLibrary.setExporting(this@DashcamActivity, clip.id, false)
                withContext(Dispatchers.Main) { toast("Clip is no longer available for export.") }
                return@launch
            }
            withContext(Dispatchers.Main) {
                exportLease = lease
                pendingExport = clip
                exportDocument.launch(clip.file.name)
            }
        }
    }

    private fun confirmDelete(clip: DashcamClipRecord) {
        MaterialAlertDialogBuilder(this, R.style.DarkAlertDialog).setTitle(R.string.dashcam_delete)
            .setMessage(R.string.dashcam_delete_confirm)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.dashcam_delete) { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    DashcamLibrary.deleteClip(this@DashcamActivity, clip.id)
                    withContext(Dispatchers.Main) { refreshLibrary() }
                }
            }.show()
    }

    private fun retentionMode(): DashcamRetentionMode = try {
        DashcamRetentionMode.valueOf(prefs.getString(DashcamSettingsKeys.RETENTION_MODE, "COMBINED") ?: "COMBINED")
    } catch (_: Exception) { DashcamRetentionMode.COMBINED }

    private fun retentionModeLabel(mode: DashcamRetentionMode): String = getString(when (mode) {
        DashcamRetentionMode.AGE -> R.string.dashcam_age
        DashcamRetentionMode.SMART -> R.string.dashcam_smart
        DashcamRetentionMode.COMBINED -> R.string.dashcam_combined
    })

    private fun orientationLabel(): String = getString(when (prefs.getString(DashcamSettingsKeys.ORIENTATION, "auto")) {
        "portrait" -> R.string.dashcam_orientation_portrait
        "landscape" -> R.string.dashcam_orientation_landscape
        else -> R.string.dashcam_orientation_auto
    })

    private fun qualityLabel(name: String): String = when (name) {
        "SD" -> "480p"
        "HD" -> "720p"
        "FHD" -> "1080p"
        "UHD" -> "4K"
        else -> name
    }

    private fun formatGb(bytes: Long): String = String.format("%.1f", bytes.toDouble() / GIGABYTE)

    private fun pageRoot(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(0xFF111318.toInt())
        setPadding(dp(14), dp(8), dp(14), dp(10))
    }

    private fun contentColumn(root: LinearLayout): LinearLayout {
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(4), 0, dp(20)) }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        return content
    }

    private fun addHeader(root: LinearLayout, titleText: String, subtitle: String) {
        val title = TextView(this).apply { text = titleText; setTextColor(Color.WHITE); textSize = 22f; setPadding(dp(4), dp(8), 0, dp(2)) }
        root.addView(title)
        addNote(root, subtitle)
    }

    private fun addSection(parent: LinearLayout, title: String) {
        parent.addView(TextView(this).apply {
            text = title.uppercase()
            setTextColor(0xFFFFB86B.toInt())
            textSize = 12f
            setPadding(dp(4), dp(14), 0, dp(6))
        })
    }

    private fun addValueRow(parent: LinearLayout, title: String, value: String, click: () -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(9), dp(12), dp(8))
            setBackgroundColor(0xFF20242B.toInt())
            isClickable = true
            isFocusable = true
            setOnClickListener { click() }
        }
        row.addView(TextView(this).apply { text = title; setTextColor(Color.WHITE); textSize = 15f })
        row.addView(TextView(this).apply { text = value; setTextColor(0xFFB9C0CA.toInt()); textSize = 12f })
        parent.addView(row, rowLayout().apply { bottomMargin = dp(6) })
    }

    private fun addNote(parent: LinearLayout, text: String) {
        parent.addView(TextView(this).apply {
            this.text = text
            setTextColor(0xFFB9C0CA.toInt())
            textSize = 13f
            setPadding(dp(4), dp(3), dp(4), dp(8))
        })
    }

    private fun addButton(parent: LinearLayout, text: String, click: () -> Unit) {
        val button = Button(this).apply {
            this.text = text
            textSize = 12f
            setOnClickListener { click() }
        }
        parent.addView(button, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(44)).apply { marginEnd = dp(5) })
    }

    private fun rowLayout() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    private enum class LibraryFilter { TRIPS, LOCKED, EVENTS }

    companion object {
        const val EXTRA_SHOW_LIBRARY = "dashcam.show_library"
        private const val GIGABYTE = 1_000_000_000L
    }
}
