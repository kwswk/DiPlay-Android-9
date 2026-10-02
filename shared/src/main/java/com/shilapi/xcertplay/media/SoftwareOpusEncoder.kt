package com.shilapi.xcertplay.media

/** libopus for tablets without a platform Opus encoder. */
internal object SoftwareOpusEncoder {
    init { System.loadLibrary("diplay_opus") }
    external fun create(bitrate: Int): Long
    external fun encode(handle: Long, pcm: ByteArray): ByteArray?
    external fun destroy(handle: Long)
}
