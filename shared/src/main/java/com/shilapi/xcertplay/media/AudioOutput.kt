package com.shilapi.xcertplay.media

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager

/** A saved route preference; device IDs change when Bluetooth reconnects. */
object AudioOutput {
    const val SYSTEM = "system"
    const val SPEAKER = "speaker"
    const val BLUETOOTH = "bluetooth"
    fun load(context: Context): String = context.getSharedPreferences("diplay", Context.MODE_PRIVATE)
        .getString("audio_output", SYSTEM) ?: SYSTEM
    fun save(context: Context, output: String) {
        require(output in listOf(SYSTEM, SPEAKER, BLUETOOTH))
        context.getSharedPreferences("diplay", Context.MODE_PRIVATE).edit().putString("audio_output", output).apply()
    }
    fun matches(output: String, type: Int): Boolean = when (output) {
        SPEAKER -> type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        BLUETOOTH -> type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
            type == AudioDeviceInfo.TYPE_BLE_HEADSET || type == AudioDeviceInfo.TYPE_BLE_SPEAKER
        else -> false
    }
    fun device(manager: AudioManager?, output: String): AudioDeviceInfo? =
        manager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)?.firstOrNull { matches(output, it.type) }
}
