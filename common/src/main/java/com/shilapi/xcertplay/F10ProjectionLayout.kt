package com.shilapi.xcertplay

import android.content.Context
import android.view.View
import android.widget.LinearLayout

/** Gives the projection its own canvas and touch surface; lyrics never cover the CarPlay dock. */
internal class F10ProjectionLayout(context: Context, private val projection: View, private val lyrics: View) : LinearLayout(context) {
    var lyricsEnabled = false
        set(value) { field = value; requestLayout() }
    val lyricsVisible: Boolean get() = lyrics.visibility == View.VISIBLE
    val lyricsAvailable: Boolean get() = supportsLyrics(width, height)
    var lyricsOnLeft = false
        set(value) {
            field = value
            // Reorder without detaching the decoder surface or stopping the lyrics worker.
            if (value) bringChildToFront(projection) else bringChildToFront(lyrics)
            requestLayout()
        }

    init {
        orientation = HORIZONTAL
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        addView(projection, LayoutParams(0, -1, 1f))
        addView(lyrics, LayoutParams(0, -1))
        lyrics.visibility = View.GONE
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = View.MeasureSpec.getSize(widthMeasureSpec)
        val height = View.MeasureSpec.getSize(heightMeasureSpec)
        val density = resources.displayMetrics.density
        val show = lyricsEnabled && supportsLyrics(width, height)
        lyrics.visibility = if (show) View.VISIBLE else View.GONE
        lyrics.layoutParams.width = if (show) (width * .35f).toInt().coerceIn((260 * density).toInt(), (340 * density).toInt()) else 0
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun supportsLyrics(width: Int, height: Int): Boolean {
        val density = resources.displayMetrics.density
        return width >= 700 * density && height >= 260 * density && width > height
    }
}
