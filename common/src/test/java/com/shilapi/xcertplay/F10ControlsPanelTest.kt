package com.shilapi.xcertplay

import android.view.View
import android.view.ViewGroup
import android.widget.Button
import com.shilapi.xcertplay.host.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 36])
class F10ControlsPanelTest {
    @Test @Config(qualifiers = "w853dp-h384dp-land")
    fun landscapeActionsFitAndDispatchIndependently() {
        checkPanel(640, 340)
    }

    @Test @Config(qualifiers = "w384dp-h853dp-port")
    fun portraitActionsRemainAccessibleWithLargeText() {
        checkPanel(352, 720, 1.5f)
    }

    private fun checkPanel(width: Int, height: Int, fontScale: Float = 1.15f) {
        val context = RuntimeEnvironment.getApplication()
        val config = android.content.res.Configuration(context.resources.configuration).apply { this.fontScale = fontScale }
        context.resources.updateConfiguration(config, context.resources.displayMetrics)
        val calls = mutableListOf<String>()
        val panel = F10ControlsPanel(context, "CarPlay connected", { calls += "resume" },
            { calls += "audio" }, { calls += "reconnect" }, { calls += "settings" }, { calls += "health" }, { calls += "disconnect" })
        val density = context.resources.displayMetrics.density
        panel.measure(View.MeasureSpec.makeMeasureSpec((width * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((height * density).toInt(), View.MeasureSpec.EXACTLY))
        panel.layout(0, 0, panel.measuredWidth, panel.measuredHeight)
        fun children(view: View): List<View> = listOf(view) + if (view is ViewGroup)
            (0 until view.childCount).flatMap { children(view.getChildAt(it)) } else emptyList()
        val buttons = children(panel).filterIsInstance<Button>()
        assertEquals(6, buttons.size)
        for (button in buttons) {
            assertTrue(button.height >= 56 * density)
            assertTrue(button.width > 0)
            button.performClick()
        }
        assertEquals(listOf("resume", "audio", "reconnect", "settings", "health", "disconnect"), calls)
        assertTrue("Actions fit without scrolling at the tested size", panel.getChildAt(0).height <= panel.height)
        assertEquals(context.getString(R.string.f10_resume_carplay), buttons.first().text)
    }
}
