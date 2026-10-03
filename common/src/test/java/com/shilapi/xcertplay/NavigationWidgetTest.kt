package com.shilapi.xcertplay

import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import com.shilapi.xcertplay.glance.CarPlayGlance
import com.shilapi.xcertplay.host.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class NavigationWidgetTest {
    @Test fun android9WidgetShowsGuidanceAndClearsItAfterDisconnect() {
        val context = RuntimeEnvironment.getApplication()
        val parent = FrameLayout(context)
        val connected = NavigationWidgetUpdater.views(context, CarPlayGlance.Snapshot(
            connected = true, maneuverType = 1, distanceMeters = 250, road = "Test road",
            remainingSeconds = 600, song = "Test song", playing = true,
        )).apply(context, parent)
        assertEquals("250 m", connected.findViewById<TextView>(R.id.widget_distance).text.toString())
        assertEquals("Test road", connected.findViewById<TextView>(R.id.widget_road).text.toString())
        assertEquals(View.VISIBLE, connected.findViewById<View>(R.id.widget_song).visibility)
        NavigationWidgetUpdater.views(context, CarPlayGlance.Snapshot()).reapply(context, connected)
        assertEquals(context.getString(R.string.widget_not_connected), connected.findViewById<TextView>(R.id.widget_road).text.toString())
        assertEquals(View.GONE, connected.findViewById<View>(R.id.widget_eta).visibility)
        assertEquals(View.GONE, connected.findViewById<View>(R.id.widget_song).visibility)
    }
}
