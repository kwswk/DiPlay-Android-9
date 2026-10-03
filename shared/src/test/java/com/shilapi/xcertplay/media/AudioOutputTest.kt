package com.shilapi.xcertplay.media

import android.media.AudioDeviceInfo
import org.junit.Assert.*
import org.junit.Test

class AudioOutputTest {
    @Test fun mediaRoutesExcludeBluetoothCallAndInputDevices() {
        assertTrue(AudioOutput.matches(AudioOutput.BLUETOOTH, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP))
        assertTrue(AudioOutput.matches(AudioOutput.BLUETOOTH, AudioDeviceInfo.TYPE_BLE_HEADSET))
        assertTrue(AudioOutput.matches(AudioOutput.BLUETOOTH, AudioDeviceInfo.TYPE_BLE_SPEAKER))
        assertFalse(AudioOutput.matches(AudioOutput.BLUETOOTH, AudioDeviceInfo.TYPE_BLUETOOTH_SCO))
        assertFalse(AudioOutput.matches(AudioOutput.BLUETOOTH, AudioDeviceInfo.TYPE_BUILTIN_MIC))
        assertTrue(AudioOutput.matches(AudioOutput.SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))
        assertFalse(AudioOutput.matches(AudioOutput.SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE))
        assertFalse(AudioOutput.matches(AudioOutput.SYSTEM, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))
    }
}
