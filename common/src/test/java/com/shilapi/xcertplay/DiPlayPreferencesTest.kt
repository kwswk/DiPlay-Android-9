package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.media.AudioOutput
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class DiPlayPreferencesTest {
    @Test fun removingPhonesClearsOnlyTheSelectedPhoneAndAddingItRestoresVisibility() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("diplay", Context.MODE_PRIVATE).edit().clear().commit()
        val selected = "AA:BB:CC:DD:EE:FF"
        val other = "00:11:22:33:44:55"
        DiPlayPreferences.savePhone(context, selected, "Test iPhone")
        DiPlayPreferences.removePhone(context, other)
        assertEquals(selected, DiPlayPreferences.phoneAddress(context))
        assertTrue(DiPlayPreferences.isPhoneRemoved(context, other))
        DiPlayPreferences.removePhone(context, selected.lowercase())
        assertNull(DiPlayPreferences.phoneAddress(context))
        assertTrue(DiPlayPreferences.isPhoneRemoved(context, selected))
        DiPlayPreferences.savePhone(context, selected, "Restored iPhone")
        assertFalse(DiPlayPreferences.isPhoneRemoved(context, selected))
        assertTrue(DiPlayPreferences.isPhoneRemoved(context, other))
        assertEquals("Restored iPhone", DiPlayPreferences.phoneName(context))
    }
    @Test fun launchAndAudioPreferencesSurviveAndRespectOptOut() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("diplay", Context.MODE_PRIVATE).edit().clear().commit()
        assertTrue(DiPlayPreferences.autoConnect(context))
        DiPlayPreferences.saveAutoConnect(context, false)
        assertFalse(DiPlayPreferences.autoConnect(context))
        assertEquals(AudioOutput.SYSTEM, AudioOutput.load(context))
        AudioOutput.save(context, AudioOutput.SPEAKER)
        assertEquals(AudioOutput.SPEAKER, AudioOutput.load(context))
        DiPlayPreferences.savePhone(context, "00:11:22:33:44:55", "Test iPhone")
        assertEquals("Test iPhone", DiPlayPreferences.phoneName(context))
        assertEquals("00:11:22:33:44:55", DiPlayPreferences.phoneAddress(context))
    }
}
