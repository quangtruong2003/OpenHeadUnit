package com.andrerinas.openheadunit.dashcam

import android.content.Intent

data class DashcamServiceConfig(
    val sessionId: String,
    val startedAtMs: Long,
    val cameraId: String,
    val quality: String,
    val fps: Int,
    val segmentMinutes: Int,
    val orientation: String,
    val audio: Boolean,
    val retentionMode: DashcamRetentionMode,
    val retentionDays: Int,
    val maxBytes: Long,
    val reserveBytes: Long
) {
    companion object {
        fun from(intent: Intent): DashcamServiceConfig? {
            val sessionId = intent.getStringExtra(DashcamActions.EXTRA_SESSION_ID) ?: return null
            val mode = try {
                DashcamRetentionMode.valueOf(intent.getStringExtra(DashcamActions.EXTRA_RETENTION_MODE) ?: "COMBINED")
            } catch (_: Exception) {
                DashcamRetentionMode.COMBINED
            }
            val quality = intent.getStringExtra(DashcamActions.EXTRA_QUALITY) ?: "HD"
            return DashcamServiceConfig(
                sessionId = sessionId,
                startedAtMs = intent.getLongExtra(DashcamActions.EXTRA_SESSION_STARTED_AT, System.currentTimeMillis()),
                cameraId = intent.getStringExtra(DashcamActions.EXTRA_CAMERA_ID).orEmpty(),
                quality = quality.takeIf { it in setOf("SD", "HD", "FHD", "UHD") } ?: "HD",
                fps = intent.getIntExtra(DashcamActions.EXTRA_FPS, 30).takeIf { it == 30 || it == 60 } ?: 30,
                segmentMinutes = DashcamSegmentPolicy.normalizeMinutes(
                    intent.getIntExtra(DashcamActions.EXTRA_SEGMENT_MINUTES, DashcamSettingsKeys.DEFAULT_SEGMENT_MINUTES)
                ),
                orientation = intent.getStringExtra(DashcamActions.EXTRA_ORIENTATION).orEmpty().takeIf { it in setOf("auto", "portrait", "landscape") } ?: "auto",
                audio = intent.getBooleanExtra(DashcamActions.EXTRA_AUDIO, false),
                retentionMode = mode,
                retentionDays = intent.getIntExtra(DashcamActions.EXTRA_RETENTION_DAYS, DashcamSettingsKeys.DEFAULT_RETENTION_DAYS).coerceIn(1, 36_500),
                maxBytes = intent.getLongExtra(DashcamActions.EXTRA_MAX_BYTES, DashcamSettingsKeys.DEFAULT_MAX_BYTES).coerceAtLeast(1L),
                reserveBytes = intent.getLongExtra(DashcamActions.EXTRA_RESERVE_BYTES, DashcamSettingsKeys.DEFAULT_RESERVE_BYTES).coerceAtLeast(0L)
            )
        }
    }
}
