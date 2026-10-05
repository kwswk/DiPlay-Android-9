package com.shilapi.xcertplay.airplay

import java.io.Closeable
import java.net.Socket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.SecureRandom
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class CarPlayAudioStreamIsolationTest {
    @Test fun opusMicrophoneUsesNegotiatedInputRateAndClockWhileSpeakerDecodeRemains48k() {
        val configs = mutableListOf<MicrophoneConfig>()
        val engine = CarPlayMediaEngine(object : MediaSink {
            override fun onMicrophoneStarted(id: AudioStreamId, config: MicrophoneConfig) { configs.add(config) }
        }, microphoneEnabled = true)
        val session = session()
        try {
            for ((bits, rate) in listOf(0x10000000L to 16_000, 0x20000000L to 24_000, 0x40000000L to 48_000)) {
                engine.onAudio(session, 100, setup("speechRecognition") +
                    mapOf("dataPort" to 9000, "audioFormat" to bits, "framesPerPacket" to rate / 50))
                engine.onSetupResponseSent(session)
                val input = configs.last()
                assertEquals(rate, input.sampleRate)
                assertEquals(20, input.frameMillis)
                assertEquals(rate / 50, input.samplesPerPacket)
                assertEquals(rate / 50 * 2, input.frameBytes)
                assertEquals(48_000, AudioStreamCodec.fromFormatBits(bits, 100).sampleRate)
                val counters = MicrophoneCounters()
                MicrophonePacketizer.sealPacket(input.key, 100, counters, byteArrayOf(1), input.samplesPerPacket)
                assertEquals(rate / 50, counters.timestamp)
            }
            engine.onAudio(session, 100, setup("speechRecognition") +
                mapOf("dataPort" to 9000, "audioFormat" to 0x20000000L, "framesPerPacket" to 960))
            engine.onSetupResponseSent(session)
            assertEquals(40, configs.last().frameMillis)
            assertEquals(960, configs.last().samplesPerPacket)
        } finally {
            engine.onSessionClosed(session)
            session.close()
        }
    }

    @Test fun speechInputStartsAfterSetupResponseWithoutAnySpeakerPacketsAndStopsOnDisconnect() {
        val started = mutableListOf<AudioStreamId>()
        val stopped = mutableListOf<AudioStreamId>()
        val engine = CarPlayMediaEngine(object : MediaSink {
            override fun onMicrophoneStarted(id: AudioStreamId, config: MicrophoneConfig) { started.add(id) }
            override fun onMicrophoneStopped(id: AudioStreamId) { stopped.add(id) }
        }, microphoneEnabled = true)
        val session = session()
        val id = AudioStreamId(100, "speechrecognition")
        try {
            assertNotNull(engine.onAudio(session, 100, setup("speechRecognition") +
                mapOf("dataPort" to 9000, "audioFormat" to 0x10L)))
            assertTrue(started.isEmpty())
            engine.onSetupResponseSent(session)
            assertEquals(listOf(id), started)
            engine.onSetupResponseSent(session)
            assertEquals("Repeated response callbacks must not restart capture", listOf(id), started)
            engine.onSessionClosed(session)
            assertEquals(listOf(id), stopped)
        } finally {
            engine.onSessionClosed(session)
            session.close()
        }
    }

    @Test fun tornDownInputCannotStartFromALateSetupCallback() {
        val started = mutableListOf<AudioStreamId>()
        val engine = CarPlayMediaEngine(object : MediaSink {
            override fun onMicrophoneStarted(id: AudioStreamId, config: MicrophoneConfig) { started.add(id) }
        }, microphoneEnabled = true)
        val session = session()
        try {
            engine.onAudio(session, 100, setup("speechRecognition") +
                mapOf("dataPort" to 9000, "audioFormat" to 0x10L))
            engine.onTeardown(session, 100)
            engine.onSetupResponseSent(session)
            assertTrue(started.isEmpty())
        } finally {
            engine.onSessionClosed(session)
            session.close()
        }
    }

    @Test fun advertisedDefaultAndCompatibilityInputsWorkButOutputOnlyStreamsNeverCapture() {
        val started = mutableListOf<AudioStreamId>()
        val engine = CarPlayMediaEngine(object : MediaSink {
            override fun onMicrophoneStarted(id: AudioStreamId, config: MicrophoneConfig) { started.add(id) }
        }, microphoneEnabled = true)
        val session = session()
        try {
            for (type in listOf("default", "compatibility", "media", "alert")) {
                engine.onAudio(session, 100, setup(type) + mapOf("dataPort" to 9000, "audioFormat" to 0x10L))
                engine.onSetupResponseSent(session)
            }
            assertEquals(listOf(AudioStreamId(100, "default"), AudioStreamId(100, "compatibility")), started)
            engine.onAudio(session, 100, setup("speechRecognition") + mapOf("dataPort" to 0))
            engine.onSetupResponseSent(session)
            assertEquals(2, started.size)
        } finally {
            engine.onSessionClosed(session)
            session.close()
        }
    }

    @Test fun guidanceSetupAndMediaReplacementKeepTheOtherAudioStreamAlive() {
        val session = session()
        val engine = CarPlayMediaEngine(object : MediaSink {})
        try {
            assertNotNull(engine.onAudio(session, 100, setup("media")))
            val streams = streams(engine)
            val mediaKey = CarPlayMediaEngine.StreamKey(session, 100, "media")
            val firstMedia = streams[mediaKey]
            assertNotNull(engine.onAudio(session, 100, setup("default")))
            val guidanceKey = CarPlayMediaEngine.StreamKey(session, 100, "default")
            val guidance = streams[guidanceKey]
            assertSame(firstMedia, streams[mediaKey])
            assertEquals(2, streams.size)
            assertNotNull(engine.onAudio(session, 100, setup("MEDIA")))
            assertNotSame(firstMedia, streams[mediaKey])
            assertSame(guidance, streams[guidanceKey])
            assertEquals(2, streams.size)
        } finally {
            engine.onSessionClosed(session)
            session.close()
        }
    }

    @Test fun typeTeardownClosesEveryVariantButKeepsOtherTypes() {
        val stopped = mutableListOf<AudioStreamId>()
        val engine = CarPlayMediaEngine(object : MediaSink {
            override fun onAudioStopped(id: AudioStreamId) { stopped.add(id) }
        })
        val session = session()
        try {
            assertNotNull(engine.onAudio(session, 100, setup("media")))
            assertNotNull(engine.onAudio(session, 100, setup("default")))
            assertNotNull(engine.onAudio(session, 102, setup("media")))
            stopped.clear()
            engine.onTeardown(session, 100)
            assertEquals(setOf(AudioStreamId(100, "media"), AudioStreamId(100, "default")), stopped.toSet())
            assertEquals(setOf(CarPlayMediaEngine.StreamKey(session, 102, "media")), streams(engine).keys)
        } finally {
            engine.onSessionClosed(session)
            session.close()
        }
    }

    private fun setup(audioType: String): Map<String, Any?> = mapOf(
        "audioType" to audioType,
        "audioFormat" to 0x8000L,
        "streamConnectionID" to 42L,
    )

    @Suppress("UNCHECKED_CAST")
    private fun streams(engine: CarPlayMediaEngine): MutableMap<CarPlayMediaEngine.StreamKey, Closeable> =
        CarPlayMediaEngine::class.java.getDeclaredField("streams").apply { isAccessible = true }
            .get(engine) as MutableMap<CarPlayMediaEngine.StreamKey, Closeable>

    private fun session(): AirPlaySession {
        val session = AirPlaySession(
            socket = object : Socket() {
                override fun getRemoteSocketAddress() = InetSocketAddress(InetAddress.getLoopbackAddress(), 9000)
            },
            config = AirPlayConfig(
                deviceName = "test", deviceId = "02:00:00:00:00:02", btMac = "02:00:00:00:00:01",
                sourceVersion = "1.0", main = AirPlayDisplayConfig(widthPixels = 800, heightPixels = 480),
            ),
            identity = AirPlayIdentity.generate(), pairings = PairingStore(), mfi = null,
            listener = object : AirPlaySessionListener {}, media = object : AirPlayMediaHandler {},
        )
        val secret = ByteArray(32).also(SecureRandom()::nextBytes)
        session.pairVerify.javaClass.getDeclaredField("sharedSecret").apply { isAccessible = true }
            .set(session.pairVerify, secret)
        return session
    }
}
