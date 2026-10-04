package com.shilapi.xcertplay

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.shilapi.xcertplay.host.R

/** Native, scrollable controls with two columns when landscape has enough room. */
internal class F10ControlsPanel(
    context: Context,
    status: String,
    onResume: () -> Unit,
    onAudio: () -> Unit,
    onReconnect: () -> Unit,
    onSettings: () -> Unit,
    onHealth: () -> Unit,
    onDisconnect: () -> Unit,
    onLyrics: (() -> Unit)? = null,
    lyricsEnabled: Boolean = false,
) : ScrollView(context) {
    val statusView = TextView(context).apply {
        text = status
        textSize = 16f
        setTextColor(context.getColor(R.color.drive_secondary))
        accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
    }

    init {
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
            setBackgroundColor(context.getColor(R.color.drive_surface))
        }
        addView(content)
        content.addView(TextView(context).apply {
            text = context.getString(R.string.f10_controls)
            textSize = 22f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(context.getColor(R.color.drive_text))
        })
        content.addView(statusView, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        val actions = listOf(
            R.string.f10_resume_carplay to onResume,
            R.string.f10_audio_title to onAudio,
            R.string.f10_reconnect to onReconnect,
            R.string.settings to onSettings,
        ) + if (onLyrics != null) listOf(
            (if (lyricsEnabled) R.string.f10_lyrics_hide_panel else R.string.f10_lyrics_show_panel) to onLyrics,
        ) else emptyList()
        val columns = if (resources.configuration.screenWidthDp >= 600) 2 else 1
        actions.chunked(columns).forEach { group ->
            val row = LinearLayout(context).apply { isBaselineAligned = false }
            content.addView(row)
            group.forEachIndexed { index, (label, action) ->
                row.addView(button(label, label == R.string.f10_resume_carplay, action),
                    LinearLayout.LayoutParams(0, -2, 1f).apply {
                        bottomMargin = dp(8)
                        if (index > 0) marginStart = dp(8)
                    })
            }
            if (group.size < columns) row.addView(android.view.View(context), LinearLayout.LayoutParams(0, 0, 1f))
        }
        val footer = LinearLayout(context).apply { isBaselineAligned = false }
        content.addView(footer, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        footer.addView(button(R.string.f10_health, false, onHealth),
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(16) })
        footer.addView(button(R.string.f10_disconnect_home, false, onDisconnect).apply {
            setTextColor(context.getColor(R.color.drive_warning))
        }, LinearLayout.LayoutParams(0, -2, 1f))

    }

    private fun button(label: Int, primary: Boolean, action: () -> Unit) = Button(context).apply {
        setText(label)
        isAllCaps = false
        textSize = 18f
        minHeight = dp(56)
        minimumHeight = dp(56)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        setTextColor(context.getColor(if (primary) R.color.drive_on_accent else R.color.drive_text))
        background = RippleDrawable(ColorStateList.valueOf(context.getColor(R.color.drive_border)),
            GradientDrawable().apply {
                setColor(context.getColor(if (primary) R.color.drive_accent else R.color.drive_selected))
                cornerRadius = dp(16).toFloat()
            }, null)
        setOnClickListener { action() }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
