package com.shilapi.xcertplay

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.widget.ScrollView
import android.widget.TextView
import com.shilapi.xcertplay.host.R

/** Fixed-size, in-memory measurements for this app process; no per-frame work or retained identities. */
internal object ConnectionHealth {
    private var attempt = 0
    private var started = 0L
    private var firstFrameMs: Long? = null
    private var reconnects = 0
    private var video = ""
    private var videoAt = 0L
    private var audio = ""
    private var audioAt = 0L
    private var audioDrops = 0L
    private var underruns = 0L
    private val dropsPattern = Regex("(?:^| )dropped=(\\d+)")
    private val underrunsPattern = Regex("underruns=\\+(\\d+)")

    @Synchronized fun token() = attempt
    @Synchronized fun begin(): Int {
        attempt++
        started = SystemClock.elapsedRealtime()
        firstFrameMs = null
        video = ""; audio = ""; videoAt = 0; audioAt = 0
        audioDrops = 0; underruns = 0
        return attempt
    }
    @Synchronized fun reconnect() { reconnects++ }

    /** Returns true once, when this attempt first produces video. Ignores late teardown samples. */
    @Synchronized fun record(token: Int, message: String): Boolean {
        if (token != attempt || attempt == 0) return false
        val now = SystemClock.elapsedRealtime()
        if (message == "Video: first frame rendered" && firstFrameMs == null) {
            firstFrameMs = now - started
            return true
        }
        if (message.contains("video stats") && !message.contains("stream=")) {
            video = message.substringAfter("video stats").trim().take(300)
            videoAt = now
        }
        if (message.startsWith("audio stats ")) {
            // Only cumulative counters are aggregated; the detailed line is the latest audio stream sample.
            audioDrops += dropsPattern.find(message)?.groupValues?.get(1)?.toLongOrNull() ?: 0
            underruns += underrunsPattern.find(message)?.groupValues?.get(1)?.toLongOrNull() ?: 0
            audio = message.take(1200)
            audioAt = now
        }
        return false
    }

    @Synchronized fun report(context: Context): String = buildString {
        val now = SystemClock.elapsedRealtime()
        appendLine("${Build.MODEL} · Android ${Build.VERSION.RELEASE}")
        appendLine(context.getString(R.string.f10_health_process))
        appendLine(context.getString(R.string.f10_health_attempts, attempt, reconnects))
        appendLine(context.getString(R.string.f10_health_first_frame,
            firstFrameMs?.let { "${it} ms" } ?: context.getString(R.string.f10_health_waiting)))
        appendLine(AudioOutputPicker.status(context))
        appendLine(if (audio.isEmpty()) context.getString(R.string.f10_health_no_audio)
            else context.getString(R.string.f10_health_audio_totals, audioDrops, underruns))
        appendLine()
        appendLine(context.getString(R.string.f10_health_video))
        appendLine(if (video.isEmpty()) context.getString(R.string.f10_health_no_samples)
            else context.getString(R.string.f10_health_sample, (now - videoAt) / 1000, video))
        appendLine(context.getString(R.string.f10_health_static_map))
        appendLine()
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val temp = battery?.getIntExtra("temperature", Int.MIN_VALUE)
        if (temp != null && temp != Int.MIN_VALUE) appendLine(context.getString(R.string.f10_health_temperature, temp / 10.0))
        if (Build.VERSION.SDK_INT >= 29) {
            val status = context.getSystemService(PowerManager::class.java)?.currentThermalStatus
            if (status != null && status >= PowerManager.THERMAL_STATUS_SEVERE) appendLine(context.getString(R.string.f10_health_hot))
        }
        if (audio.isNotEmpty()) {
            appendLine()
            appendLine(context.getString(R.string.f10_health_audio_sample))
            appendLine(context.getString(R.string.f10_health_sample, (now - audioAt) / 1000, audio))
        }
    }

    fun show(context: Context) {
        val handler = Handler(Looper.getMainLooper())
        val text = TextView(context).apply {
            textSize = 16f
            setTextColor(context.getColor(R.color.drive_text))
            val padding = (20 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
            setTextIsSelectable(true)
        }
        val dialog = AlertDialog.Builder(context).setTitle(R.string.f10_health)
            .setView(ScrollView(context).apply { addView(text) })
            .setPositiveButton(android.R.string.ok, null).create()
        val update = object : Runnable {
            override fun run() {
                if (!dialog.isShowing) return
                text.text = report(context)
                handler.postDelayed(this, 2000)
            }
        }
        val owner = context as? androidx.lifecycle.LifecycleOwner
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_DESTROY) dialog.dismiss()
        }
        owner?.lifecycle?.addObserver(observer)
        dialog.setOnDismissListener {
            handler.removeCallbacks(update)
            owner?.lifecycle?.removeObserver(observer)
        }
        dialog.show()
        update.run()
    }
}
