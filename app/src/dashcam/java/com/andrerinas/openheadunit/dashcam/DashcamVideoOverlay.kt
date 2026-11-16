package com.andrerinas.openheadunit.dashcam

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.CameraEffect
import androidx.camera.effects.Frame
import androidx.camera.effects.OverlayEffect
import androidx.core.content.ContextCompat
import androidx.core.util.Consumer
import com.andrerinas.openheadunit.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DashcamVideoOverlay(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val thread = HandlerThread("DashcamVideoOverlay").apply { start() }
    private val handler = Handler(thread.looper)
    private val locationManager = appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(170, 0, 0, 0)
    }
    private val background = RectF()
    private val outputToBuffer = Matrix()
    private val transformValues = FloatArray(9).apply { this[8] = 1f }
    @Volatile private var location: Location? = null
    @Volatile private var coordinateText = ""
    @Volatile private var closed = false
    private val unavailableText = appContext.getString(R.string.dashcam_location_unavailable)
    private var timestamp = ""
    private var timestampSecond = Long.MIN_VALUE
    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            updateLocation(location)
        }

        @Suppress("DEPRECATION")
        override fun onStatusChanged(provider: String, status: Int, extras: Bundle) = Unit
        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = Unit
    }

    val effect = OverlayEffect(
        CameraEffect.VIDEO_CAPTURE,
        0,
        handler,
        Consumer { error -> Log.e(TAG, "Video overlay failed", error) }
    ).apply {
        setOnDrawListener { frame -> drawFrame(frame) }
    }

    @SuppressLint("MissingPermission")
    fun startLocationUpdates() {
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return

        handler.post {
            if (closed) return@post
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).forEach { provider ->
                try {
                    if (locationManager.isProviderEnabled(provider)) {
                        locationManager.getLastKnownLocation(provider)?.let(::updateLocation)
                        locationManager.requestLocationUpdates(provider, LOCATION_INTERVAL_MS, 0f, locationListener, handler.looper)
                    }
                } catch (_: Exception) {
                }
            }
        }
    }

    fun stopLocationUpdates() {
        try { locationManager.removeUpdates(locationListener) } catch (_: Exception) { }
        location = null
        coordinateText = ""
    }

    private fun drawFrame(frame: Frame): Boolean {
        val canvas = try { frame.overlayCanvas } catch (_: Exception) { return true }
        val saveCount = canvas.save()
        try {
            val rotation = frame.rotationDegrees
            val crop = frame.cropRect
            val cropWidth = crop.width().toFloat()
            val cropHeight = crop.height().toFloat()
            val width = if (rotation % 180 == 0) cropWidth else cropHeight
            val height = if (rotation % 180 == 0) cropHeight else cropWidth
            val mirrorScale = if (frame.isMirroring) -1f else 1f
            val mirrorOffset = if (frame.isMirroring) width else 0f
            when (rotation) {
                90 -> {
                    transformValues[0] = 0f; transformValues[1] = 1f; transformValues[2] = crop.left.toFloat()
                    transformValues[3] = -mirrorScale; transformValues[4] = 0f; transformValues[5] = crop.top + cropHeight - mirrorOffset
                }
                180 -> {
                    transformValues[0] = -mirrorScale; transformValues[1] = 0f; transformValues[2] = crop.left + cropWidth - mirrorOffset
                    transformValues[3] = 0f; transformValues[4] = -1f; transformValues[5] = crop.top + cropHeight
                }
                270 -> {
                    transformValues[0] = 0f; transformValues[1] = -1f; transformValues[2] = crop.left + cropWidth
                    transformValues[3] = mirrorScale; transformValues[4] = 0f; transformValues[5] = crop.top + mirrorOffset
                }
                else -> {
                    transformValues[0] = mirrorScale; transformValues[1] = 0f; transformValues[2] = crop.left + mirrorOffset
                    transformValues[3] = 0f; transformValues[4] = 1f; transformValues[5] = crop.top.toFloat()
                }
            }
            outputToBuffer.setValues(transformValues)
            canvas.concat(outputToBuffer)
            val now = System.currentTimeMillis()
            val second = now / 1000L
            if (second != timestampSecond) {
                timestamp = dateFormat.format(Date(now))
                timestampSecond = second
            }

            val textSize = height * 0.035f
            val margin = textSize * 0.9f
            val padding = textSize * 0.24f
            drawLabel(canvas, timestamp, margin, margin + textSize, textSize, padding, Paint.Align.LEFT)

            val latest = location
            val locationText = if (latest != null &&
                SystemClock.elapsedRealtimeNanos() - latest.elapsedRealtimeNanos <= LOCATION_MAX_AGE_NS) {
                coordinateText
            } else {
                unavailableText
            }
            drawLabel(canvas, locationText, width - margin, height - margin, textSize, padding, Paint.Align.RIGHT)
        } catch (_: Exception) {
        } finally {
            canvas.restoreToCount(saveCount)
        }
        return true
    }

    private fun drawLabel(
        canvas: Canvas,
        text: String,
        x: Float,
        baseline: Float,
        textSize: Float,
        padding: Float,
        align: Paint.Align
    ) {
        textPaint.textSize = textSize
        textPaint.textAlign = align
        val textWidth = textPaint.measureText(text)
        val left = if (align == Paint.Align.LEFT) x else x - textWidth
        background.set(left - padding, baseline - textSize - padding, left + textWidth + padding, baseline + padding)
        canvas.drawRoundRect(background, padding, padding, backgroundPaint)
        canvas.drawText(text, x, baseline, textPaint)
    }

    private fun updateLocation(location: Location) {
        this.location = Location(location)
        coordinateText = String.format(Locale.US, "%.6f, %.6f", location.latitude, location.longitude)
    }

    override fun close() {
        closed = true
        stopLocationUpdates()
        try {
            effect.clearOnDrawListener()
            effect.close()
        } finally {
            thread.quitSafely()
        }
    }

    private companion object {
        const val TAG = "DashcamVideoOverlay"
        const val LOCATION_INTERVAL_MS = 1_000L
        const val LOCATION_MAX_AGE_NS = 15_000_000_000L
    }
}
