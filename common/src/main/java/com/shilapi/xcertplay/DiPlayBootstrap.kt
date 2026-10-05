package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.mfi.LocalMfiAuthenticationClient
import com.shilapi.xcertplay.orchestration.MfiTarget
import java.io.File
import java.security.MessageDigest

/** Installs the private beta's experimental identity. It has no remote fallback. */
internal object DiPlayBootstrap {
    @Volatile private var ready = false

    @Synchronized fun ensure(context: Context) {
        if (ready) return
        val target = File(context.noBackupFilesDir, LocalMfiAuthenticationClient.DIRECTORY)
        if (!target.exists()) {
            val staging = File(context.noBackupFilesDir, "offline-mfi-staging")
            staging.deleteRecursively()
            check(staging.mkdirs()) { "Could not prepare local authentication" }
            staging.setReadable(false, false); staging.setReadable(true, true)
            staging.setExecutable(false, false); staging.setExecutable(true, true)
            try {
                for (name in listOf("identity.pk8", "certificate.p7b")) {
                    val file = File(staging, name)
                    context.assets.open("offline-mfi/$name").use { input ->
                        file.outputStream().use { output -> input.copyTo(output) }
                    }
                    file.setReadable(false, false); file.setReadable(true, true)
                    file.setWritable(false, false); file.setWritable(true, true)
                }
                LocalMfiAuthenticationClient.load(staging)
                check(staging.renameTo(target)) { "Could not install local authentication" }
            } finally {
                staging.deleteRecursively()
            }
        }
        LocalMfiAuthenticationClient.load(target)
        AirPlayPersistence.saveMfiTarget(context, MfiTarget.LOCAL)
        AirPlayPersistence.saveDebugLogsEnabled(context, false)
        ready = true
    }

    fun deviceId(identity: AirPlayIdentity): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(identity.publicKey).take(6).toByteArray()
        bytes[0] = ((bytes[0].toInt() and 0xfc) or 0x02).toByte()
        return bytes.joinToString(":") { "%02X".format(it.toInt() and 0xff) }
    }
}

internal object DiPlayPreferences {
    private fun prefs(context: Context) = context.getSharedPreferences("diplay", Context.MODE_PRIVATE)
    fun phoneAddress(context: Context): String? = prefs(context).getString("phone_address", null)
    fun phoneName(context: Context): String = prefs(context).getString("phone_name", null) ?: "Your iPhone"
    fun savePhone(context: Context, address: String, name: String) {
        val saved = prefs(context)
        saved.edit().putString("phone_address", address).putString("phone_name", name)
            .putStringSet("removed_phones", saved.getStringSet("removed_phones", emptySet()).orEmpty() - address.uppercase(java.util.Locale.ROOT)).apply()
    }
    fun isPhoneRemoved(context: Context, address: String) =
        address.uppercase(java.util.Locale.ROOT) in prefs(context).getStringSet("removed_phones", emptySet()).orEmpty()
    fun removePhone(context: Context, address: String) {
        val saved = prefs(context)
        val edit = saved.edit().putStringSet("removed_phones",
            saved.getStringSet("removed_phones", emptySet()).orEmpty() + address.uppercase(java.util.Locale.ROOT))
        if (address.equals(phoneAddress(context), true)) edit.remove("phone_address").remove("phone_name")
        edit.apply()
    }
    fun autoConnect(context: Context) = prefs(context).getBoolean("auto_connect", true)
    fun saveAutoConnect(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean("auto_connect", value).apply()
    }
    fun lyricsEnabled(context: Context) = prefs(context).getBoolean("lyrics_panel", false)
    fun saveLyricsEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean("lyrics_panel", value).apply()
    }
}
