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
    fun show(context: Context, changed: () -> Unit = {}) {
        AlertDialog.Builder(context).setTitle(R.string.f10_audio_title)
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
