package com.shilapi.xcertplay.media

/** libopus for tablets without a platform Opus encoder. */
internal object SoftwareOpusEncoder {
    init { System.loadLibrary("diplay_opus") }
    external fun create(sampleRate: Int, bitrate: Int): Long
    external fun encode(handle: Long, pcm: ByteArray, samples: Int): ByteArray?
    external fun destroy(handle: Long)
}
