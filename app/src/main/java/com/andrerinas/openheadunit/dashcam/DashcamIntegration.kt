package com.andrerinas.openheadunit.dashcam

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.andrerinas.openheadunit.BuildConfig
import com.andrerinas.openheadunit.connection.CommManager
import com.andrerinas.openheadunit.utils.Settings
import java.util.IdentityHashMap
import java.util.UUID

object DashcamSettingsKeys {
    const val PREFS = "dashcam_settings"
    const val ENABLED = "auto_record"
    const val SETUP_COMPLETE = "initial_setup_complete"
    const val OVERLAY_MODE = "record_overlay_mode"
    const val OVERLAY_COMPACT = "COMPACT"
    const val OVERLAY_FULL = "FULL"
    const val OVERLAY_HIDDEN = "HIDDEN"
    const val DEFAULT_OVERLAY_MODE = OVERLAY_COMPACT
    const val CAMERA_ID = "camera_id"
    const val QUALITY = "quality"
    const val FPS = "fps"
    const val ORIENTATION = "orientation"
    const val AUDIO = "audio"
    const val RETENTION_MODE = "retention_mode"
    const val RETENTION_DAYS = "retention_days"
    const val MAX_BYTES = "max_bytes"
    const val RESERVE_BYTES = "reserve_bytes"

    const val DEFAULT_RETENTION_DAYS = 7
    const val DEFAULT_MAX_BYTES = 5_000_000_000L
    const val DEFAULT_RESERVE_BYTES = 1_000_000_000L
}

object DashcamSetupGate {
    fun isComplete(context: Context): Boolean {
        if (BuildConfig.FLAVOR != "dashcam") return true
        return try {
            val preferences = context.getSharedPreferences(DashcamSettingsKeys.PREFS, Context.MODE_PRIVATE)
            preferences.getBoolean(DashcamSettingsKeys.SETUP_COMPLETE, false) || Settings(context).hasCompletedSetupWizard
        } catch (_: Exception) {
            false
        }
    }

    fun shouldDeferAutomaticConnection(context: Context): Boolean =
        DashcamSetupPolicy.shouldDeferAutomaticConnection(
            isDashcamBuild = BuildConfig.FLAVOR == "dashcam",
            setupComplete = isComplete(context)
        )

    fun markComplete(context: Context) {
        if (BuildConfig.FLAVOR == "dashcam") {
            context.getSharedPreferences(DashcamSettingsKeys.PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(DashcamSettingsKeys.SETUP_COMPLETE, true).apply()
        }
    }
}

object DashcamActions {
    const val ACTION_START = "com.andrerinas.openheadunit.dashcam.START"
    const val ACTION_STOP = "com.andrerinas.openheadunit.dashcam.STOP"
    const val ACTION_EVENT = "com.andrerinas.openheadunit.dashcam.EVENT"
    const val ACTION_STATUS_REQUEST = "com.andrerinas.openheadunit.dashcam.STATUS_REQUEST"
    const val ACTION_STATUS = "com.andrerinas.openheadunit.dashcam.STATUS"
    const val EXTRA_SESSION_ID = "session_id"
    const val EXTRA_SESSION_STARTED_AT = "session_started_at"
    const val EXTRA_EVENT_MARKED_AT = "event_marked_at"
    const val EXTRA_STATUS = "status"
    const val EXTRA_MESSAGE = "message"

    const val EXTRA_CAMERA_ID = "camera_id"
    const val EXTRA_QUALITY = "quality"
    const val EXTRA_FPS = "fps"
    const val EXTRA_ORIENTATION = "orientation"
    const val EXTRA_AUDIO = "audio"
    const val EXTRA_RETENTION_MODE = "retention_mode"
    const val EXTRA_RETENTION_DAYS = "retention_days"
    const val EXTRA_MAX_BYTES = "max_bytes"
    const val EXTRA_RESERVE_BYTES = "reserve_bytes"
}

/** Bridges the authoritative AAP transport state to the optional Dashcam build. */
object DashcamIntegration {
    private const val SERVICE_CLASS = "com.andrerinas.openheadunit.dashcam.DashcamService"
    private val lock = Any()
    private val sessionsByAttempt = IdentityHashMap<Any, String>()
    private var activeAttempt: Any? = null
    private var activeSessionId: String? = null

    fun onConnectionState(context: Context, state: CommManager.ConnectionState, attempt: Any?) {
        if (BuildConfig.FLAVOR != "dashcam") return

        if (state is CommManager.ConnectionState.TransportStarted) {
            DashcamSetupGate.markComplete(context)
            startProjection(context, attempt)
            return
        }

        val endedAttempt = (state as? CommManager.ConnectionState.Disconnected)?.endedAttempt
        val sessionToStop = synchronized(lock) {
            if (state is CommManager.ConnectionState.Disconnected) {
                sessionsByAttempt.remove(endedAttempt).also { id ->
                    if (activeAttempt === endedAttempt) {
                        activeAttempt = null
                        activeSessionId = null
                    }
                }
            } else if (activeAttempt != null && activeAttempt !== attempt) {
                activeSessionId.also {
                    sessionsByAttempt.clear()
                    activeAttempt = null
                    activeSessionId = null
                }
            } else null
        }
        sessionToStop?.let { sendStop(context, it) }
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        if (BuildConfig.FLAVOR != "dashcam") return
        context.getSharedPreferences(DashcamSettingsKeys.PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(DashcamSettingsKeys.ENABLED, enabled).apply()

        if (!enabled) {
            stopCurrent(context)
            return
        }

        val commManager = com.andrerinas.openheadunit.App.provide(context).commManager
        if (commManager.connectionState.value is CommManager.ConnectionState.TransportStarted) {
            startProjection(context, commManager.projectionAttemptToken)
        }
    }

    fun saveEvent(context: Context) {
        if (BuildConfig.FLAVOR != "dashcam") return
        val sessionId = synchronized(lock) { activeSessionId } ?: return
        try {
            context.startService(serviceIntent(context, DashcamActions.ACTION_EVENT)
                .putExtra(DashcamActions.EXTRA_SESSION_ID, sessionId)
                .putExtra(DashcamActions.EXTRA_EVENT_MARKED_AT, System.currentTimeMillis()))
        } catch (e: Exception) {
            publishStatus(context, "error", e.message ?: "Could not mark event")
        }
    }

    fun requestStatus(context: Context) {
        if (BuildConfig.FLAVOR != "dashcam") return
        val commManager = com.andrerinas.openheadunit.App.provide(context).commManager
        onConnectionState(context, commManager.connectionState.value, commManager.projectionAttemptToken)
        if (activeSessionId() == null) return
        val intent = serviceIntent(context, DashcamActions.ACTION_STATUS_REQUEST)
        try {
            context.startService(intent)
        } catch (_: Exception) {
        }
    }

    fun activeSessionId(): String? = synchronized(lock) { activeSessionId }

    private fun startProjection(context: Context, attempt: Any?) {
        val token = attempt ?: run {
            publishStatus(context, "error", "Android Auto session token unavailable")
            return
        }
        val previous = synchronized(lock) {
            if (activeAttempt === token && activeSessionId != null) return
            activeSessionId.also {
                sessionsByAttempt.clear()
                activeAttempt = token
                activeSessionId = null
            }
        }
        previous?.let { sendStop(context, it) }

        val prefs = context.getSharedPreferences(DashcamSettingsKeys.PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(DashcamSettingsKeys.ENABLED, false)) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            publishStatus(context, "permission", "Camera permission required: Settings > Dashcam")
            return
        }

        val sessionId = UUID.randomUUID().toString()
        synchronized(lock) {
            activeSessionId = sessionId
            sessionsByAttempt[token] = sessionId
        }
        val intent = serviceIntent(context, DashcamActions.ACTION_START)
            .putExtra(DashcamActions.EXTRA_SESSION_ID, sessionId)
            .putExtra(DashcamActions.EXTRA_SESSION_STARTED_AT, System.currentTimeMillis())
            .putExtra(DashcamActions.EXTRA_CAMERA_ID, prefs.getString(DashcamSettingsKeys.CAMERA_ID, ""))
            .putExtra(DashcamActions.EXTRA_QUALITY, prefs.getString(DashcamSettingsKeys.QUALITY, "HD"))
            .putExtra(DashcamActions.EXTRA_FPS, prefs.getInt(DashcamSettingsKeys.FPS, 30))
            .putExtra(DashcamActions.EXTRA_ORIENTATION, prefs.getString(DashcamSettingsKeys.ORIENTATION, "auto"))
            .putExtra(DashcamActions.EXTRA_AUDIO, prefs.getBoolean(DashcamSettingsKeys.AUDIO, false))
            .putExtra(DashcamActions.EXTRA_RETENTION_MODE, prefs.getString(DashcamSettingsKeys.RETENTION_MODE, "COMBINED"))
            .putExtra(DashcamActions.EXTRA_RETENTION_DAYS, prefs.getInt(DashcamSettingsKeys.RETENTION_DAYS, DashcamSettingsKeys.DEFAULT_RETENTION_DAYS))
            .putExtra(DashcamActions.EXTRA_MAX_BYTES, prefs.getLong(DashcamSettingsKeys.MAX_BYTES, DashcamSettingsKeys.DEFAULT_MAX_BYTES))
            .putExtra(DashcamActions.EXTRA_RESERVE_BYTES, prefs.getLong(DashcamSettingsKeys.RESERVE_BYTES, DashcamSettingsKeys.DEFAULT_RESERVE_BYTES))

        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (e: Exception) {
            synchronized(lock) {
                if (activeSessionId == sessionId) {
                    activeAttempt = null
                    activeSessionId = null
                    sessionsByAttempt.remove(token)
                }
            }
            publishStatus(context, "error", e.message ?: "Could not start Dashcam")
        }
    }

    private fun stopCurrent(context: Context) {
        val sessionId = synchronized(lock) {
            sessionsByAttempt.clear()
            activeAttempt = null
            activeSessionId.also { activeSessionId = null }
        }
        sessionId?.let { sendStop(context, it) }
    }

    private fun sendStop(context: Context, sessionId: String) {
        send(context, DashcamActions.ACTION_STOP, sessionId)
    }

    private fun send(context: Context, action: String, sessionId: String) {
        try {
            context.startService(serviceIntent(context, action).putExtra(DashcamActions.EXTRA_SESSION_ID, sessionId))
        } catch (e: Exception) {
            publishStatus(context, "error", e.message ?: "Dashcam service is unavailable")
        }
    }

    private fun serviceIntent(context: Context, action: String): Intent =
        Intent(action).setClassName(context, SERVICE_CLASS)

    private fun publishStatus(context: Context, status: String, message: String) {
        context.sendBroadcast(Intent(DashcamActions.ACTION_STATUS).setPackage(context.packageName)
            .putExtra(DashcamActions.EXTRA_STATUS, status)
            .putExtra(DashcamActions.EXTRA_MESSAGE, message))
    }
}
