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
