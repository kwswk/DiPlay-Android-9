package com.shilapi.xcertplay

import android.content.Intent
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DiPlaySettingsNavigationTest {
    @Test fun settingsCategoriesKeepControlsSeparateAndBackReturnsToTheMenu() {
        val context = RuntimeEnvironment.getApplication()
        DiPlayPreferences.saveAutoConnect(context, false)
        AirPlayPersistence.saveDisplayScaleTenths(context, 10)
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java,
            Intent(context, DiPlayActivity::class.java).putExtra("page", "settings")).setup()
        val activity = controller.get()
        fun texts(): List<TextView> = descendants(activity.window.decorView).filterIsInstance<TextView>().toList()
        fun contains(resource: Int) = texts().any { it.text.toString().contains(activity.getString(resource)) }
        fun open(resource: Int) {
            val title = texts().first { it.text.toString() == activity.getString(resource) }
            if (title.isClickable) title.performClick() else (title.parent.parent as View).performClick()
        }
        try {
            assertTrue(contains(R.string.f10_widgets_language))
            assertFalse(contains(R.string.music_buffer))
            open(R.string.display_and_performance)
            assertTrue(contains(R.string.frame_rate))
            assertFalse(contains(R.string.music_buffer))
            open(R.string.resolution)
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            dialog.listView.performItemClick(null, 1, 1)
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(8, AirPlayPersistence.loadDisplayScaleTenths(context))
            activity.onBackPressedDispatcher.onBackPressed()
            assertTrue(contains(R.string.f10_widgets_language))
            open(R.string.audio_routing)
            assertTrue(contains(R.string.music_buffer))
            assertFalse(contains(R.string.frame_rate))
            activity.onBackPressedDispatcher.onBackPressed()
            open(R.string.f10_widgets_language)
            assertTrue(contains(R.string.f10_add_navigation_widget))
            activity.onBackPressedDispatcher.onBackPressed()
            open(R.string.connection_setup)
            assertTrue(contains(R.string.f10_auto_wireless))
            open(R.string.open_connection_setup)
            activity.onBackPressedDispatcher.onBackPressed()
            assertTrue(contains(R.string.f10_auto_wireless))
            activity.onBackPressedDispatcher.onBackPressed()
            open(R.string.f10_privacy)
            assertTrue(contains(R.string.report_location_to_iphone))
            activity.onBackPressedDispatcher.onBackPressed()
            open(R.string.f10_support)
            assertTrue(contains(R.string.save_diagnostic_report))
            open(R.string.about_diplay)
            activity.onBackPressedDispatcher.onBackPressed()
            assertTrue(contains(R.string.save_diagnostic_report))
            activity.onBackPressedDispatcher.onBackPressed()
            activity.onBackPressedDispatcher.onBackPressed()
            assertTrue(contains(R.string.f10_your_phones))
        } finally { controller.pause().stop().destroy() }
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
}
