package com.shilapi.xcertplay.media

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class AudioOutputLifecycleTest {
    private class RecordingTrack : AudioTrack(AudioManager.STREAM_MUSIC, 48000,
        AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT, 16384, MODE_STREAM) {
        var volume = 1f
        var routeRequests = 0
        override fun setVolume(gain: Float): Int { volume = gain; return SUCCESS }
        override fun setPreferredDevice(device: AudioDeviceInfo?): Boolean { routeRequests++; return true }
    }

    @Test fun disconnectSilencesCurrentAndLateTracksEvenWithFocusDisabled() {
        val output = AudioFocusCoordinator(null, false)
        val music = RecordingTrack()
        val guidance = RecordingTrack()
        val late = RecordingTrack()
        val attributes = AudioAttributes.Builder().build()
        try {
            output.acquire(music, AudioChannel.MEDIA, attributes)
            output.acquire(guidance, AudioChannel.NAVIGATION, attributes)
            output.setOutput(AudioOutput.SPEAKER)
            assertEquals(2, music.routeRequests)
            assertEquals(2, guidance.routeRequests)
            output.silence()
            assertEquals(0f, music.volume, 0f)
            assertEquals(0f, guidance.volume, 0f)
            output.close()
            output.acquire(late, AudioChannel.ASSISTANT, attributes)
            assertEquals(0f, late.volume, 0f)
            assertEquals(0, late.routeRequests)
        } finally {
            output.close()
            music.release(); guidance.release(); late.release()
        }
    }
}
