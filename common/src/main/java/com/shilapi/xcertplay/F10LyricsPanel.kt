package com.shilapi.xcertplay

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** Fetch only on track changes; highlight locally. Hidden/stopped panels do no polling or requests. */
internal class F10LyricsPanel(context: Context, onHide: () -> Unit) : LinearLayout(context) {
    var outerEdgeOnLeft = false
        set(value) { field = value; ViewCompat.requestApplyInsets(this) }
    private val main = Handler(Looper.getMainLooper())
    private val client = F10LyricsClient()
    private var worker = Executors.newSingleThreadExecutor { Thread(it, "f10-lyrics").apply { isDaemon = true } }
    private var pending: Future<*>? = null
    private var generation = 0
    private var foreground = false
    private var visible = false
    private var query: LyricsQuery? = null
    private var document: LyricsDocument? = null
    private var candidates = emptyList<LyricsDocument>()
    private var dialog: AlertDialog? = null
    private var currentLine = Int.MIN_VALUE
    // ponytail: eight songs kept in memory; add disk caching when offline use is required.
    private val cache = object : LinkedHashMap<LyricsQuery, LyricsDocument>(8, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<LyricsQuery, LyricsDocument>?) = size > 8
    }
    private fun text(size: Float, secondary: Boolean = false) = TextView(context).apply {
        textSize = size
        setTextColor(context.getColor(if (secondary) R.color.lyrics_secondary else R.color.lyrics_text))
    }
    private val title = text(20f).apply {
        maxLines = 2; ellipsize = TextUtils.TruncateAt.END
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        ViewCompat.setAccessibilityHeading(this, true)
    }
    private val artist = text(16f, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
    private val previous = text(18f, true)
    private val current = text(24f).apply { typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
    private val next = text(18f, true)
    private val words = LinearLayout(context).apply {
        orientation = VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        gravity = Gravity.CENTER_VERTICAL
        listOf(previous, current, next).forEach { view ->
            view.setLineSpacing(dp(3).toFloat(), 1f)
            addView(view, LayoutParams(-1, -2).apply { topMargin = dp(8); bottomMargin = dp(8) })
        }
    }
    private val scroll = ScrollView(context).apply { isFillViewport = true; addView(words) }
    private val find = button(R.string.f10_lyrics_find) { showSearch() }
    private val retry = button(R.string.f10_lyrics_retry) { query?.let { fetch(it) } }.apply {
        text = "↻"; textSize = 24f; contentDescription = context.getString(R.string.f10_lyrics_retry)
        setPadding(0, 0, 0, 0)
    }
    private val hide = button(R.string.f10_lyrics_hide, onHide)
    private val songCard = card(14).apply {
        addView(title, LayoutParams(-1, -2))
        addView(artist, LayoutParams(-1, -2).apply { topMargin = dp(4) })
        visibility = View.GONE
    }
    private val lyricsCard = card(14).apply { addView(scroll, LayoutParams(-1, -1)) }
    private val controlsCard = card(8).apply {
        orientation = HORIZONTAL
        isBaselineAligned = false
        gravity = Gravity.CENTER_VERTICAL
        addView(find, LayoutParams(0, -2, 1f))
        addView(retry, LayoutParams(dp(48), -2).apply { marginStart = dp(6) })
        addView(hide, LayoutParams(-2, -2).apply { marginStart = dp(6) })
    }
    private val tick = object : Runnable {
        override fun run() {
            if (!running()) return
            updatePlayback(CarPlayMediaKeys.snapshot())
            main.postDelayed(this, 250)
        }
    }

    init {
        orientation = VERTICAL
        setPadding(dp(12), dp(12), dp(12), dp(12))
        addView(songCard, LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        addView(lyricsCard, LayoutParams(-1, 0, 1f))
        addView(controlsCard, LayoutParams(-1, -2).apply { topMargin = dp(10) })
        setMessage(R.string.f10_lyrics_waiting)
        refreshAppearance()
        ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(dp(12) + if (outerEdgeOnLeft) safe.left else 0, dp(12) + safe.top,
                dp(12) + if (outerEdgeOnLeft) 0 else safe.right, dp(12) + safe.bottom)
            insets
        }
    }

    fun setForeground(value: Boolean) { foreground = value; schedule() }
    private fun running() = foreground && visible && isAttachedToWindow
    private fun schedule() {
        main.removeCallbacks(tick)
        if (running()) main.post(tick) else {
            generation++
            pending?.cancel(true)
            if (pending != null) query = null // fetch again on return if a request was cancelled
            pending = null
            dialog?.dismiss()
            dialog = null
        }
    }
    override fun onVisibilityAggregated(isVisible: Boolean) { super.onVisibilityAggregated(isVisible); visible = isVisible; schedule() }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (worker.isShutdown) worker = Executors.newSingleThreadExecutor { Thread(it, "f10-lyrics").apply { isDaemon = true } }
        schedule()
    }
    override fun onDetachedFromWindow() {
        visible = false
        schedule()
        worker.shutdownNow()
        super.onDetachedFromWindow()
    }

    internal fun updatePlayback(info: CarPlayNowPlaying) {
        val updated = info.title?.takeIf { it.isNotBlank() }?.let {
            LyricsQuery(it, info.artist.orEmpty(), info.album.orEmpty(), info.durationMillis?.takeIf { duration -> duration > 0 })
        }
        if (updated != query) {
            generation++
            pending?.cancel(true)
            pending = null
            dialog?.dismiss()
            query = updated
            document = null
            candidates = emptyList()
            currentLine = Int.MIN_VALUE
            title.text = info.title.orEmpty()
            artist.text = info.artist.orEmpty()
            songCard.visibility = if (updated == null) View.GONE else View.VISIBLE
            artist.visibility = if (info.artist.isNullOrBlank()) View.GONE else View.VISIBLE
            if (updated == null) setMessage(R.string.f10_lyrics_waiting)
            else cache[updated]?.let { showDocument(it) } ?: fetch(updated)
        }
        val active = document ?: return
        if (active.lines.isEmpty()) return
        val index = info.elapsedMillis?.let(active::lineAt) ?: -1
        if (index == currentLine) return
        currentLine = index
        previous.text = active.lines.getOrNull(index - 1)?.text.orEmpty()
        current.text = if (index < 0) context.getString(R.string.f10_lyrics_intro) else
            active.lines.getOrNull(index)?.text?.ifBlank { "♪" }.orEmpty()
        next.text = active.lines.getOrNull(index + 1)?.text.orEmpty()
        scroll.post {
            scroll.scrollTo(0, (current.top - (scroll.height - current.height).coerceAtLeast(0) / 2).coerceAtLeast(0))
        }
    }

    private fun fetch(search: LyricsQuery, showChoices: Boolean = false) {
        if (!running()) return
        val owner = query ?: return
        pending?.cancel(true)
        val token = ++generation
        document = null
        candidates = emptyList()
        setMessage(R.string.f10_lyrics_loading)
        find.isEnabled = false
        retry.isEnabled = false
        pending = worker.submit {
            val result = runCatching { client.find(search, includeAlternatives = showChoices) }
            main.post {
                if (token != generation || query != owner || !running()) return@post
                pending = null
                find.isEnabled = true
                retry.isEnabled = true
                candidates = result.getOrDefault(emptyList())
                val match = candidates.firstOrNull()
                when {
                    result.isFailure -> setMessage(R.string.f10_lyrics_error)
                    match != null -> {
                        cache[owner] = match
                        showDocument(match)
                        if (showChoices) chooseCandidate()
                    }
                    else -> setMessage(R.string.f10_lyrics_missing)
                }
            }
        }
    }

    private fun showDocument(value: LyricsDocument) {
        document = value
        if (candidates.isEmpty()) candidates = listOf(value)
        find.setText(R.string.f10_lyrics_choose)
        currentLine = Int.MIN_VALUE
        current.setTextColor(context.getColor(if (value.lines.isEmpty()) R.color.lyrics_text else R.color.lyrics_active))
        current.textSize = if (value.lines.isEmpty()) 18f else 24f
        current.typeface = Typeface.create("sans-serif", if (value.lines.isEmpty()) Typeface.NORMAL else Typeface.BOLD)
        if (value.lines.isEmpty()) {
            previous.text = ""
            next.text = ""
            current.text = value.plain.ifBlank { context.getString(R.string.f10_lyrics_instrumental) }
        } else {
            previous.text = ""
            current.setText(R.string.f10_lyrics_intro)
            next.text = value.lines.firstOrNull()?.text.orEmpty()
        }
    }

    private fun setMessage(message: Int) {
        previous.text = ""
        next.text = ""
        current.setTextColor(context.getColor(R.color.lyrics_text))
        current.textSize = 18f
        current.typeface = Typeface.DEFAULT
        current.setText(message)
        currentLine = Int.MIN_VALUE
        find.setText(R.string.f10_lyrics_find)
        find.isEnabled = query != null
        retry.isEnabled = query != null
        scroll.scrollTo(0, 0)
    }

    private fun showSearch() {
        val owner = query ?: return
        if (candidates.isNotEmpty()) {
            if (candidates.size == 1) fetch(owner, showChoices = true) else chooseCandidate()
            return
        }
        val name = EditText(context).apply { setText(owner.title); setSingleLine(); hint = context.getString(R.string.f10_lyrics_song) }
        val singer = EditText(context).apply { setText(owner.artist); setSingleLine(); hint = context.getString(R.string.f10_lyrics_artist) }
        val form = LinearLayout(context).apply {
            orientation = VERTICAL; setPadding(dp(24), dp(12), dp(24), 0)
            addView(text(14f, true).apply { setText(R.string.f10_lyrics_search_hint) })
            addView(text(14f).apply { setText(R.string.f10_lyrics_song) }); addView(name)
            addView(text(14f).apply { setText(R.string.f10_lyrics_artist) }); addView(singer)
        }
        dialog = AlertDialog.Builder(context).setTitle(R.string.f10_lyrics_find).setView(form)
            .setNegativeButton(android.R.string.cancel, null).setPositiveButton(R.string.f10_lyrics_search, null).create().apply {
                show()
                getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    if (name.text.isBlank()) { name.error = context.getString(R.string.f10_lyrics_song); return@setOnClickListener }
                    dismiss()
                    if (query == owner) fetch(owner.copy(title = name.text.toString().trim(), artist = singer.text.toString().trim(), album = ""))
                }
            }
    }

    private fun chooseCandidate() {
        val owner = query ?: return
        val choices = candidates
        dialog = AlertDialog.Builder(context).setTitle(R.string.f10_lyrics_choose)
            .setSingleChoiceItems(choices.map { "${it.title} · ${it.artist}\n${it.provider} · ${it.durationMillis?.let { ms -> "%d:%02d".format(ms / 60_000, ms / 1_000 % 60) } ?: "—"}" }.toTypedArray(), choices.indexOf(document)) { selected, index ->
                if (query == owner) { cache[owner] = choices[index]; showDocument(choices[index]) }
                selected.dismiss()
            }.setNeutralButton(R.string.f10_lyrics_search_again) { _, _ -> candidates = emptyList(); showSearch() }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun button(label: Int, action: () -> Unit) = Button(context).apply {
        setText(label); isAllCaps = false; textSize = 14f
        minWidth = dp(48); minimumWidth = dp(48); minHeight = dp(48); minimumHeight = dp(48)
        setPadding(dp(12), dp(8), dp(12), dp(8))
        maxLines = 2
        setTextColor(context.getColor(R.color.lyrics_text))
        background = controlBackground()
        setOnClickListener { action() }
    }
    private fun card(padding: Int) = LinearLayout(context).apply {
        orientation = VERTICAL
        setPadding(dp(padding), dp(padding), dp(padding), dp(padding))
        background = cardBackground()
    }
    private fun cardBackground() = GradientDrawable().apply {
        setColor(context.getColor(R.color.lyrics_surface)); cornerRadius = dp(20).toFloat()
    }
    private fun controlBackground() = RippleDrawable(ColorStateList.valueOf(context.getColor(R.color.lyrics_ripple)),
        GradientDrawable().apply { setColor(context.getColor(R.color.lyrics_control)); cornerRadius = dp(14).toFloat() }, null)
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        refreshAppearance()
    }
    private fun refreshAppearance() {
        setBackgroundColor(context.getColor(R.color.lyrics_background))
        listOf(songCard, lyricsCard, controlsCard).forEach { it.background = cardBackground() }
        title.setTextColor(context.getColor(R.color.lyrics_text))
        listOf(artist, previous, next).forEach { it.setTextColor(context.getColor(R.color.lyrics_secondary)) }
        current.setTextColor(context.getColor(if (document?.lines?.isNotEmpty() == true) R.color.lyrics_active else R.color.lyrics_text))
        listOf(find, retry, hide).forEach { it.setTextColor(context.getColor(R.color.lyrics_text)); it.background = controlBackground() }
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
