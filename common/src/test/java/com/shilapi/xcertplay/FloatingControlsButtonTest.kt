package com.shilapi.xcertplay

import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 36])
class FloatingControlsButtonTest {
    @Test fun draggingDoesNotOpenTheMenuAndPositionSurvivesRotationAndRecreation() {
        val context = RuntimeEnvironment.getApplication()
        val root = FrameLayout(context)
        val button = FloatingControlsButton(context)
        root.addView(button, FrameLayout.LayoutParams(48, 48))
        var clicks = 0
        button.setOnClickListener { clicks++ }
        fun layout(width: Int, height: Int) {
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, width, height)
        }
        fun touch(action: Int, x: Float, y: Float) {
            val event = MotionEvent.obtain(0, 1, action, x, y, 0)
            try { button.onTouchEvent(event) } finally { event.recycle() }
        }
        layout(800, 320)
        button.place(true)
        layout(800, 320)
        touch(MotionEvent.ACTION_DOWN, 20f, 20f)
        touch(MotionEvent.ACTION_MOVE, 320f, 120f)
        touch(MotionEvent.ACTION_UP, 320f, 120f)
        layout(800, 320)
        assertEquals(0, clicks)
        assertTrue(button.left > 200 && button.top > 80)
        button.performClick()
        assertEquals(1, clicks)
        val oldLeft = button.left
        val oldTop = button.top
        root.removeView(button)
        val restored = FloatingControlsButton(context)
        root.addView(restored, FrameLayout.LayoutParams(48, 48))
        restored.place(true)
        layout(800, 320)
        assertEquals(oldLeft, restored.left)
        assertEquals(oldTop, restored.top)
        layout(320, 800)
        restored.place(true)
        layout(320, 800)
        assertTrue(restored.left >= 8 && restored.right <= 312)
        assertTrue(restored.top >= 8 && restored.bottom <= 792)
    }
}
