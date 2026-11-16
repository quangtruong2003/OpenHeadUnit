package com.andrerinas.openheadunit.dashcam

import android.hardware.camera2.CameraCharacteristics
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.SessionConfig
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture

data class DashcamCameraChoice(val id: String, val label: String, val facing: Int)

object DashcamCameraCapabilities {
    private val qualityOrder = listOf(Quality.SD, Quality.HD, Quality.FHD, Quality.UHD)

    fun cameraChoices(cameraInfos: List<CameraInfo>): List<DashcamCameraChoice> {
        var rearIndex = 0
        return cameraInfos.mapNotNull { info ->
            try {
                val id = Camera2CameraInfo.from(info).cameraId
                val facing = info.lensFacing ?: CameraCharacteristics.LENS_FACING_BACK
                val label = when (facing) {
                    CameraSelector.LENS_FACING_BACK -> if (++rearIndex == 1) "Rear camera" else "Rear $rearIndex"
                    CameraSelector.LENS_FACING_FRONT -> "Selfie camera"
                    else -> "Camera · $id"
                }
                DashcamCameraChoice(id, label, facing)
            } catch (_: Exception) {
                null
            }
        }
    }

    fun defaultRearCamera(cameras: List<DashcamCameraChoice>): DashcamCameraChoice? =
        cameras.firstOrNull { it.facing == CameraSelector.LENS_FACING_BACK }

    fun selector(cameraId: String): CameraSelector {
        if (cameraId.isBlank()) return CameraSelector.DEFAULT_BACK_CAMERA
        return CameraSelector.Builder().addCameraFilter { infos ->
            infos.filter { Camera2CameraInfo.from(it).cameraId == cameraId }
        }.build()
    }

    fun supportedQualities(info: CameraInfo): List<Quality> =
        QualitySelector.getSupportedQualities(info).sortedBy { qualityOrder.indexOf(it).let { index -> if (index < 0) Int.MAX_VALUE else index } }

    fun quality(name: String): Quality = when (name) {
        "SD" -> Quality.SD
        "FHD" -> Quality.FHD
        "UHD" -> Quality.UHD
        else -> Quality.HD
    }

    fun qualityName(quality: Quality): String = when (quality) {
        Quality.SD -> "SD"
        Quality.HD -> "HD"
        Quality.FHD -> "FHD"
        Quality.UHD -> "UHD"
        else -> quality.toString()
    }

    fun supportedFrameRates(info: CameraInfo, quality: Quality): List<Int> {
        val recorder = Recorder.Builder().setQualitySelector(QualitySelector.from(quality)).build()
        val capture = VideoCapture.withOutput(recorder)
        val session = SessionConfig.Builder(listOf(capture)).build()
        return info.getSupportedFrameRateRanges(session)
            .filter { it.lower == it.upper && (it.lower == 30 || it.lower == 60) }
            .map { it.lower }
            .distinct()
            .sorted()
    }

    fun qualityLabel(quality: Quality): String = when (quality) {
        Quality.SD -> "480p"
        Quality.HD -> "720p"
        Quality.FHD -> "1080p"
        Quality.UHD -> "4K"
        else -> quality.toString()
    }
}
