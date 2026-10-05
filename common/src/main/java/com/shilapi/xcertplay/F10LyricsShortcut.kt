package com.shilapi.xcertplay

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.media.CarPlayNowPlaying

/** A passenger-side action, shown only for active Spotify playback with room for lyrics. */
internal class F10LyricsShortcut(context: Context, onOpen: () -> Unit) : Button(context) {
    private var onLeft = false

    init {
        setText(R.string.f10_lyrics_title)
        contentDescription = context.getString(R.string.f10_lyrics_show_panel)
        isAllCaps = false
        textSize = 16f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        minHeight = dp(48); minimumHeight = dp(48)
        setPadding(dp(18), dp(10), dp(18), dp(10))
        layoutParams = FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.RIGHT)
        visibility = View.GONE
        refreshAppearance()
        setOnClickListener { onOpen() }
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets -> position(insets); insets }
    }

    fun update(info: CarPlayNowPlaying, available: Boolean, passengerOnLeft: Boolean) {
        val show = available && info.playing && !info.title.isNullOrBlank() && info.sourceApp?.contains("spotify", true) == true
        visibility = if (show) View.VISIBLE else View.GONE
        if (onLeft != passengerOnLeft || show) {
            onLeft = passengerOnLeft
            position(ViewCompat.getRootWindowInsets(this))
        }
    }

    private fun position(insets: WindowInsetsCompat?) {
        val safe = insets?.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        val params = layoutParams as FrameLayout.LayoutParams
        val gravity = Gravity.BOTTOM or if (onLeft) Gravity.LEFT else Gravity.RIGHT
        val left = dp(16) + (safe?.left ?: 0)
        val right = dp(16) + (safe?.right ?: 0)
        val bottom = dp(16) + (safe?.bottom ?: 0)
        if (params.gravity == gravity && params.leftMargin == left && params.rightMargin == right && params.bottomMargin == bottom) return
        params.gravity = gravity
        params.setMargins(left, dp(16), right, bottom)
        layoutParams = params
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        refreshAppearance()
    }

    private fun refreshAppearance() {
        setTextColor(context.getColor(R.color.lyrics_text))
        background = RippleDrawable(ColorStateList.valueOf(context.getColor(R.color.lyrics_ripple)),
            GradientDrawable().apply { setColor(context.getColor(R.color.lyrics_surface)); cornerRadius = dp(24).toFloat() }, null)
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
