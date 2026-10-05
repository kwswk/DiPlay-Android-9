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
@Config(sdk = [28, 36])
class DiPlaySettingsNavigationTest {
    @Test fun removingAHomePhonePersistsAcrossActivityRestartAndCanBeAddedBack() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("diplay", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        DiPlayPreferences.saveAutoConnect(context, false)
        val adapter = context.getSystemService(android.bluetooth.BluetoothManager::class.java).adapter
        shadowOf(adapter).setEnabled(true)
        val phone = org.robolectric.shadows.ShadowBluetoothDevice.newInstance("AA:BB:CC:DD:EE:FF")
        shadowOf(phone).setName("Test iPhone")
        shadowOf(adapter).setBondedDevices(setOf(phone))
        if (android.os.Build.VERSION.SDK_INT >= 31) shadowOf(context).grantPermissions(android.Manifest.permission.BLUETOOTH_CONNECT)
        DiPlayPreferences.savePhone(context, phone.address, "Test iPhone")
        fun launch() = Robolectric.buildActivity(DiPlayActivity::class.java,
            Intent(context, DiPlayActivity::class.java).putExtra("page", "home")).setup()
        val first = launch()
        try {
            descendants(first.get().window.decorView).filterIsInstance<android.widget.Button>()
                .single { it.text.toString() == first.get().getString(R.string.f10_remove_phone) }.performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertNull(DiPlayPreferences.phoneAddress(context))
            assertTrue(DiPlayPreferences.isPhoneRemoved(context, phone.address))
            assertEquals(1, adapter.bondedDevices.size)
        } finally { first.pause().stop().destroy() }
        val second = launch()
        try {
            val texts = descendants(second.get().window.decorView).filterIsInstance<TextView>().toList()
            assertFalse(texts.any { it.text.toString() == "Test iPhone" })
            texts.single { it.text.toString() == second.get().getString(R.string.f10_add_phone) }.performClick()
            ShadowAlertDialog.getLatestAlertDialog().listView.performItemClick(null, 0, 0)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(phone.address, DiPlayPreferences.phoneAddress(context))
            assertFalse(DiPlayPreferences.isPhoneRemoved(context, phone.address))
            assertTrue(descendants(second.get().window.decorView).filterIsInstance<TextView>().any { it.text.toString() == "Test iPhone" })
        } finally { second.pause().stop().destroy() }
    }
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

    @Test @Config(sdk = [28, 36], qualifiers = "w384dp-h853dp-port")
    fun phoneHomeKeepsEverydayControlsInsideTheScreenAtNormalTextSize() = checkHomeControls(384, 853)

    @Test @Config(sdk = [28, 36], qualifiers = "w853dp-h384dp-land")
    fun landscapeHomeKeepsEverydayControlsInsideTheScreenAtNormalTextSize() = checkHomeControls(853, 384)

    private fun checkHomeControls(widthDp: Int, heightDp: Int) {
        val context = RuntimeEnvironment.getApplication()
        val config = android.content.res.Configuration(context.resources.configuration).apply { fontScale = 1.15f }
        context.resources.updateConfiguration(config, context.resources.displayMetrics)
        DiPlayPreferences.saveAutoConnect(context, false)
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java,
            Intent(context, DiPlayActivity::class.java).putExtra("page", "home")).setup().visible()
        val activity = controller.get()
        try {
            val root = activity.findViewById<ViewGroup>(android.R.id.content)
            val density = activity.resources.displayMetrics.density
            val width = (widthDp * density).toInt()
            val height = (heightDp * density).toInt()
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, width, height)
            val controls = descendants(root).filterIsInstance<android.widget.Button>().toList()
            for (resource in listOf(R.string.connect_phone, R.string.settings, R.string.disconnect)) {
                val control = controls.single { it.text.toString() == activity.getString(resource) }
                val offset = android.graphics.Rect(0, 0, control.width, control.height)
                root.offsetDescendantRectToMyCoords(control, offset)
                assertTrue("${control.text} must be within the phone width: $offset", offset.left >= 0 && offset.right <= width)
                assertTrue("${control.text} must be above the screen bottom: $offset", offset.top >= 0 && offset.bottom <= height)
                assertTrue(control.height >= (48 * density).toInt())
            }
            if (widthDp > heightDp) {
                controls.single { it.text.toString() == activity.getString(R.string.settings) }.performClick()
                val settingsRoot = activity.findViewById<ViewGroup>(android.R.id.content)
                settingsRoot.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                settingsRoot.layout(0, 0, width, height)
                val labels = descendants(settingsRoot).filterIsInstance<TextView>().toList()
                for (resource in listOf(R.string.connection_setup, R.string.display_and_performance,
                    R.string.audio_routing, R.string.f10_widgets_language, R.string.f10_privacy, R.string.f10_support)) {
                    val title = labels.single { it.text.toString() == activity.getString(resource) }
                    val entry = title.parent.parent as View
                    val bounds = android.graphics.Rect(0, 0, entry.width, entry.height)
                    settingsRoot.offsetDescendantRectToMyCoords(entry, bounds)
                    assertTrue("${title.text} must fit in landscape: $bounds", bounds.left >= 0 && bounds.right <= width && bounds.top >= 0 && bounds.bottom <= height)
                }
            }
        } finally { controller.pause().stop().destroy() }
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
}
