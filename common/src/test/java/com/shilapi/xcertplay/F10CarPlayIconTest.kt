package com.shilapi.xcertplay

import android.graphics.BitmapFactory
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 36])
class F10CarPlayIconTest {
    @Test fun legacyLabelMigratesButCustomLabelSurvives() {
        val context = RuntimeEnvironment.getApplication()
        AirPlayPersistence.saveOemLabel(context, "BYD")
        assertEquals("F10 Play", AirPlayPersistence.loadOemLabel(context))
        AirPlayPersistence.saveOemLabel(context, "My car")
        assertEquals("My car", AirPlayPersistence.loadOemLabel(context))
    }

    @Test fun defaultIconIsAnEncodedSquareImage() {
        val context = RuntimeEnvironment.getApplication()
        AirPlayPersistence.clearCustomAirPlayIcon(context)
        val icon = F10CarPlayIcon.load(context)
        val decoded = BitmapFactory.decodeByteArray(icon.data, 0, icon.data.size)
        assertNotNull(decoded)
        assertEquals(256, decoded.width)
        assertEquals(decoded.width, decoded.height)
        assertEquals(decoded.width, icon.widthPixels)
        decoded.recycle()
    }
}
