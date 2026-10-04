package com.shilapi.xcertplay

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.provider.Settings
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.media.AudioOutput

internal object AudioOutputPicker {
    private val outputs = listOf(AudioOutput.SYSTEM, AudioOutput.SPEAKER, AudioOutput.BLUETOOTH)
    private val labels = listOf(R.string.f10_audio_system, R.string.f10_audio_speaker, R.string.f10_audio_bluetooth)
    fun label(context: Context): String = context.getString(labels[outputs.indexOf(AudioOutput.load(context)).coerceAtLeast(0)])
    fun actualRoute(context: Context): String {
        val routes = CarPlayBackgroundSession.snapshot()?.sink?.activeAudioDeviceTypes().orEmpty()
        if (routes.isEmpty()) return context.getString(R.string.f10_audio_idle)
        return routes.joinToString(" · ") { type -> context.getString(when (type) {
            android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> R.string.f10_audio_speaker
            android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            android.media.AudioDeviceInfo.TYPE_BLE_HEADSET, android.media.AudioDeviceInfo.TYPE_BLE_SPEAKER -> R.string.f10_audio_bluetooth
            android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES, android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET,
            android.media.AudioDeviceInfo.TYPE_USB_DEVICE, android.media.AudioDeviceInfo.TYPE_USB_HEADSET,
            android.media.AudioDeviceInfo.TYPE_USB_ACCESSORY -> R.string.f10_audio_wired
            android.media.AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> R.string.f10_audio_earpiece
            else -> R.string.f10_audio_other
        }) }
    }

    fun status(context: Context): String {
        val selected = AudioOutput.load(context)
        val missing = selected != AudioOutput.SYSTEM &&
            AudioOutput.device(context.getSystemService(AudioManager::class.java), selected) == null
        return context.getString(R.string.f10_audio_status, label(context), actualRoute(context)) +
            if (missing) "\n" + context.getString(R.string.f10_audio_unavailable) else ""
    }

    fun show(context: Context, changed: () -> Unit = {}) {
        AlertDialog.Builder(context).setCustomTitle(android.widget.TextView(context).apply {
                text = status(context)
                textSize = 18f
                val pad = (24 * resources.displayMetrics.density).toInt()
                setPadding(pad, pad, pad, pad / 2)
            })
            .setSingleChoiceItems(labels.map(context::getString).toTypedArray(), outputs.indexOf(AudioOutput.load(context))) { dialog, index ->
                val output = outputs[index]
                val manager = context.getSystemService(AudioManager::class.java)
                if (output != AudioOutput.SYSTEM && AudioOutput.device(manager, output) == null) {
                    dialog.dismiss()
                    AlertDialog.Builder(context).setMessage(R.string.f10_audio_missing)
                        .setPositiveButton(R.string.open_bluetooth) { _, _ ->
                            runCatching { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
                        }.setNegativeButton(R.string.cancel, null).show()
                } else {
                    AudioOutput.save(context, output)
                    CarPlayBackgroundSession.snapshot()?.sink?.setAudioOutput(output)
                    dialog.dismiss()
                    changed()
                }
            }.setNegativeButton(R.string.cancel, null).show()
    }
}
