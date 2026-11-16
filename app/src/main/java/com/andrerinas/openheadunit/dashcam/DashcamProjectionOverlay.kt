package com.andrerinas.openheadunit.dashcam

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.andrerinas.openheadunit.BuildConfig
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.aap.AapProjectionActivity

class DashcamProjectionOverlay(
    private val activity: AapProjectionActivity,
    private val container: FrameLayout
) {
    private val density = activity.resources.displayMetrics.density
    private val preferences = activity.getSharedPreferences(DashcamSettingsKeys.PREFS, Context.MODE_PRIVATE)
    private val card = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8), dp(4), dp(6), dp(4))
        background = GradientDrawable().apply {
            setColor(0xD91B1E24.toInt())
            cornerRadius = dp(14).toFloat()
        }
        visibility = View.GONE
    }
    private val status = TextView(activity).apply {
        setTextColor(Color.WHITE)
        textSize = 12f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        maxWidth = dp(190)
    }
    private val eventButton = Button(activity).apply {
        setText(R.string.dashcam_save_event)
        textSize = 11f
        minHeight = 0
        minimumHeight = 0
        setPadding(dp(8), 0, dp(8), 0)
        setOnClickListener { DashcamIntegration.saveEvent(activity) }
    }
    private var registered = false
    private var preferencesRegistered = false
    private var lastState = ""
    private var lastMessage = ""

    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == DashcamSettingsKeys.OVERLAY_MODE) render(lastState, lastMessage)
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            render(
                intent.getStringExtra(DashcamActions.EXTRA_STATUS).orEmpty(),
                intent.getStringExtra(DashcamActions.EXTRA_MESSAGE).orEmpty()
            )
        }
    }

    fun attach() {
        if (BuildConfig.FLAVOR != "dashcam") return
        card.addView(status, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        card.addView(eventButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(34)).apply {
            marginStart = dp(8)
        })
        val params = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.END).apply {
            setMargins(dp(8), dp(12), dp(12), dp(8))
        }
        container.addView(card, params)
        ContextCompat.registerReceiver(
            activity, receiver, IntentFilter(DashcamActions.ACTION_STATUS), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        registered = true
        preferences.registerOnSharedPreferenceChangeListener(preferenceListener)
        preferencesRegistered = true
        DashcamIntegration.requestStatus(activity)
    }

    fun bringToFront() {
        if (card.parent === container) card.bringToFront()
    }

    fun release() {
        if (registered) {
            activity.unregisterReceiver(receiver)
            registered = false
        }
        if (preferencesRegistered) {
            preferences.unregisterOnSharedPreferenceChangeListener(preferenceListener)
            preferencesRegistered = false
        }
        container.removeView(card)
    }

    private fun render(state: String, message: String) {
        lastState = state
        lastMessage = message
        val recording = state == "recording"
        val mode = preferences.getString(DashcamSettingsKeys.OVERLAY_MODE, DashcamSettingsKeys.DEFAULT_OVERLAY_MODE)
        val compact = mode == DashcamSettingsKeys.OVERLAY_COMPACT
        val full = mode == DashcamSettingsKeys.OVERLAY_FULL
        val visible = if (full) recording || state == "permission" || state == "error" || state == "event" else compact && recording
        card.visibility = if (visible) View.VISIBLE else View.GONE
        eventButton.visibility = if (recording && full) View.VISIBLE else View.GONE
        card.setPadding(dp(if (compact) 6 else 8), dp(4), dp(if (compact) 6 else 6), dp(4))
        status.maxWidth = dp(if (compact) 72 else 190)
        status.textSize = if (compact) 11f else 12f
        status.setTextColor(if (recording) 0xFFFF6666.toInt() else 0xFFFFD166.toInt())
        status.text = when {
            compact && recording -> "● ${getString(R.string.dashcam_rec)}"
            recording && message.isNotBlank() -> "● ${getString(R.string.dashcam_rec)} · $message"
            recording -> "● ${getString(R.string.dashcam_rec)}"
            else -> message
        }
    }

    private fun getString(id: Int): String = activity.getString(id)
    private fun dp(value: Int): Int = (value * density + 0.5f).toInt()
}
