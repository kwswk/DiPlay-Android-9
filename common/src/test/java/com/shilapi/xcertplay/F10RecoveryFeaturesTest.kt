package com.shilapi.xcertplay

import android.content.Context
import android.os.Build
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 36])
class F10RecoveryFeaturesTest {
    @Test fun recommendedProfileMatchesDeviceApiAndSuccessfulSettingsCanBeRestored() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("f10_working_display", Context.MODE_PRIVATE).edit().clear().apply()
        assertNull(F10DisplayProfile.working(context))
        val profile = F10DisplayProfile.recommended(context)
        assertEquals(if (Build.VERSION.SDK_INT >= 31) 60 else 30, profile.fps)
        assertEquals(if (Build.VERSION.SDK_INT >= 31) 10 else 7, profile.scale)
        profile.apply(context)
        profile.markWorking(context)
        profile.copy(scale = 6, fps = 30).apply(context)
        assertEquals(6, F10DisplayProfile.current(context).scale)
        F10DisplayProfile.working(context)!!.apply(context)
        assertEquals(profile, F10DisplayProfile.current(context))
    }

    @Test fun staleHealthSamplesAreIgnoredAndFirstFrameIsMarkedOnlyOnce() {
        val old = ConnectionHealth.begin()
        val current = ConnectionHealth.begin()
        assertFalse(ConnectionHealth.record(old, "Video: first frame rendered"))
        assertTrue(ConnectionHealth.record(current, "Video: first frame rendered"))
        assertFalse(ConnectionHealth.record(current, "Video: first frame rendered"))
        ConnectionHealth.record(old, "audio stats dropped=99 underruns=+99")
        ConnectionHealth.record(current, "audio stats dropped=2 underruns=+3")
        val report = ConnectionHealth.report(RuntimeEnvironment.getApplication())
        assertTrue(report.contains("Audio packet drops: 2 · Buffer underruns: 3"))
        assertFalse(report.contains("dropped=99"))
    }
}
