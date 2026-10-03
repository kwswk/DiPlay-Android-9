package com.shilapi.xcertplay

import android.content.Context
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.Button
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.math.abs
import kotlin.math.roundToInt

/** A normal, accessible button that can also be dragged away from projection controls. */
internal class FloatingControlsButton(context: Context) : Button(context) {
    private val positions = context.getSharedPreferences("floating_controls", Context.MODE_PRIVATE)
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var rightDrive = false
    private var downX = 0f
    private var downY = 0f
    private var startLeft = 0
    private var startTop = 0
    private var dragging = false
    private val sideKey get() = if (rightDrive) "right" else "left"

    fun place(rightHandDrive: Boolean, insets: WindowInsetsCompat? = null) {
        rightDrive = rightHandDrive
        val params = layoutParams as? FrameLayout.LayoutParams ?: return
        val safe = (insets ?: ViewCompat.getRootWindowInsets(this))
            ?.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        val gap = (8 * resources.displayMetrics.density).roundToInt()
        val host = parent as? View
        val minX = gap + (safe?.left ?: 0)
        val minY = gap + (safe?.top ?: 0)
        val maxX = ((host?.width ?: 0) - (safe?.right ?: 0) - gap - params.width).coerceAtLeast(minX)
        val maxY = ((host?.height ?: 0) - (safe?.bottom ?: 0) - gap - params.height).coerceAtLeast(minY)
        val saved = positions.contains("${sideKey}_x") && (host?.width ?: 0) > 0
        // The initial corner is physical, independent of the app's reading direction.
        val gravity = Gravity.TOP or if (saved || rightDrive) Gravity.LEFT else Gravity.RIGHT
        val x = if (saved) minX + ((maxX - minX) * positions.getFloat("${sideKey}_x", 0f)).roundToInt() else minX
        val y = if (saved) minY + ((maxY - minY) * positions.getFloat("${sideKey}_y", 0f)).roundToInt() else minY
        val right = if (saved) 0 else gap + (safe?.right ?: 0)
        if (params.gravity != gravity || params.leftMargin != x || params.rightMargin != right || params.topMargin != y) {
            params.gravity = gravity
            params.leftMargin = x.coerceIn(minX, maxX)
            params.rightMargin = right
            params.topMargin = y.coerceIn(minY, maxY)
            layoutParams = params
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX; downY = event.rawY
                startLeft = left; startTop = top
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount != 1) return true
                val dx = event.rawX - downX
                val dy = event.rawY - downY
                if (dragging || abs(dx) > touchSlop || abs(dy) > touchSlop) {
                    dragging = true
                    isPressed = false
                    parent.requestDisallowInterceptTouchEvent(true)
                    savePosition(startLeft + dx.roundToInt(), startTop + dy.roundToInt())
                    place(rightDrive)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) {
                isPressed = false
                dragging = false
                parent.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun savePosition(x: Int, y: Int) {
        val host = parent as? View ?: return
        val safe = ViewCompat.getRootWindowInsets(this)
            ?.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        val gap = (8 * resources.displayMetrics.density).roundToInt()
        val minX = gap + (safe?.left ?: 0)
        val minY = gap + (safe?.top ?: 0)
        val spanX = (host.width - (safe?.right ?: 0) - gap - width - minX).coerceAtLeast(1)
        val spanY = (host.height - (safe?.bottom ?: 0) - gap - height - minY).coerceAtLeast(1)
        positions.edit()
            .putFloat("${sideKey}_x", ((x - minX).toFloat() / spanX).coerceIn(0f, 1f))
            .putFloat("${sideKey}_y", ((y - minY).toFloat() / spanY).coerceIn(0f, 1f))
            .apply()
    }
}
