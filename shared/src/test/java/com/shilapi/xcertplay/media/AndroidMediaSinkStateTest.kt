package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.AudioFormat
import com.shilapi.xcertplay.airplay.AudioStreamId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class AndroidMediaSinkStateTest {
    @Test fun mediaAudioStartsOnAndroid9() {
        val ready = CountDownLatch(1)
        val sink = AndroidMediaSink(onAudioDiagnostic = {
            if (it.startsWith("Audio: ready")) ready.countDown()
        })
        try {
            sink.onAudioStarted(AudioStreamId(100, "media"),
                AudioFormat(AudioCodecKind.LPCM, 48000, 2, 96), 0)
            assertTrue("Audio track did not start on Android 9", ready.await(5, TimeUnit.SECONDS))
        } finally {
            sink.close()
        }
    }

    @Test fun recreatingTheScreenRestoresItsActiveVideoState() {
        val sink = AndroidMediaSink()
        sink.onScreenStreamActive(110, true)
        sink.onScreenStreamActive(111, true)
        sink.onScreenStreamActive(111, false)
        val events = mutableListOf<Pair<Int, Boolean>>()
        sink.setScreenStreamActiveChangedListener { type, active -> events.add(type to active) }
        assertEquals(listOf(110 to true), events)
        sink.close()
        assertEquals(listOf(110 to true, 110 to false), events)
        val afterClose = mutableListOf<Pair<Int, Boolean>>()
        sink.setScreenStreamActiveChangedListener { type, active -> afterClose.add(type to active) }
        assertTrue(afterClose.isEmpty())
    }
}
