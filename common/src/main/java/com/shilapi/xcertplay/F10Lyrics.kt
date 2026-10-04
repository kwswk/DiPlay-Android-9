package com.shilapi.xcertplay

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONException
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.Normalizer
import java.util.Locale

internal data class LyricsQuery(val title: String, val artist: String, val album: String, val durationMillis: Long?)
internal data class LyricLine(val millis: Long, val text: String)
internal data class LyricsDocument(
    val title: String, val artist: String, val durationMillis: Long?, val provider: String,
    val lines: List<LyricLine>, val plain: String,
    val instrumental: Boolean = false,
) {
    fun lineAt(positionMillis: Long): Int {
        // Upper bound: duplicate timestamps select the final line at that timestamp.
        var low = 0
        var high = lines.size
        while (low < high) {
            val mid = (low + high) / 2
            if (lines[mid].millis <= positionMillis) low = mid + 1 else high = mid
        }
        return low - 1
    }
}

internal object LrcParser {
    private val timestamp = Regex("\\[(\\d{1,3}):(\\d{2})(?:[.,:](\\d{1,3}))?]")
    private val offset = Regex("\\[offset:([+-]?\\d+)]", RegexOption.IGNORE_CASE)
    private val tag = Regex("^\\[[a-z]+:.*]$", RegexOption.IGNORE_CASE)

    fun parse(text: String): List<LyricLine> {
        val adjustment = offset.find(text)?.groupValues?.get(1)?.toLongOrNull()?.coerceIn(-60_000, 60_000) ?: 0
        return text.lineSequence().take(5_000).flatMap { raw ->
            val line = raw.take(4_096).trim().removePrefix("\uFEFF")
            val times = timestamp.findAll(line).take(100).toList()
            val words = line.substring(times.lastOrNull()?.range?.last?.plus(1) ?: line.length).trim()
            times.mapNotNull { match ->
                val seconds = match.groupValues[2].toInt()
                if (seconds >= 60) return@mapNotNull null
                val fraction = match.groupValues[3].padEnd(3, '0').take(3).toInt()
                // Positive LRC offsets advance the lyrics relative to audio.
                val millis = match.groupValues[1].toLong() * 60_000 + seconds * 1_000 + fraction - adjustment
                LyricLine(millis.coerceAtLeast(0), words)
            }.asSequence()
        }.take(5_000).sortedBy { it.millis }.distinct().toList()
    }

    fun plain(text: String): String = text.lineSequence().take(5_000).map { it.trim().removePrefix("\uFEFF") }
        .filterNot { tag.matches(it) }.joinToString("\n").trim().take(120_000)
}

/** No credentials or new dependencies. HTTP is bounded, cancellable, and never runs on the UI thread. */
internal class F10LyricsClient(private val get: (String) -> String? = ::readLyricsUrl) {
    fun find(query: LyricsQuery, includeAlternatives: Boolean = false): List<LyricsDocument> {
        var failure: IOException? = null
        val primary = try {
            val body = get(endpoint("https://lrclib.net/api/get", query, includeDuration = true))
                ?: if (query.album.isNotBlank()) get(endpoint("https://lrclib.net/api/get", query.copy(album = ""), includeDuration = true)) else null
            body?.let {
                val record = JSONObject(it)
                listOf(document(record, "LRCLIB"))
            }.orEmpty()
        } catch (error: IOException) { failure = error; emptyList() }
        catch (_: JSONException) { failure = IOException("Invalid lyrics response"); emptyList() }
        if (!includeAlternatives && primary.any { (it.instrumental || it.lines.isNotEmpty()) && matches(query, it) }) return primary
        val fallback = try {
            val search = if (includeAlternatives) query.copy(artist = "", album = "") else query
            fun records(search: LyricsQuery) = get(endpoint("https://api.lrc.cx/jsonapi", search, includeDuration = false))?.let(::JSONArray)
            val result = records(search)
            val available = if (result != null && result.length() > 0) result else if (search.artist.isNotBlank())
                records(search.copy(artist = "", album = "")) else result
            available?.let { records ->
                (0 until minOf(records.length(), 20)).mapNotNull { index ->
                    records.optJSONObject(index)?.let { document(it, "LrcAPI") }
                }
            }.orEmpty()
        } catch (error: IOException) { failure = error; emptyList() }
        catch (_: JSONException) { failure = IOException("Invalid lyrics response"); emptyList() }
        val result = (primary + fallback).filter { it.instrumental || it.lines.isNotEmpty() || it.plain.isNotBlank() }.distinct()
        if (result.isEmpty() && failure != null) throw failure
        return result
    }

    companion object {
        fun matches(query: LyricsQuery, document: LyricsDocument): Boolean {
            if (normalize(query.title) != normalize(document.title) || normalize(query.artist) != normalize(document.artist)) return false
            val duration = query.durationMillis
            return duration == null || document.durationMillis?.let { kotlin.math.abs(it - duration) <= 2_000 } == true
        }

        private fun normalize(value: String) = Normalizer.normalize(value, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

        private fun document(record: JSONObject, provider: String): LyricsDocument {
            fun string(key: String) = if (record.isNull(key)) "" else record.optString(key, "")
            val lrc = if (provider == "LRCLIB") string("syncedLyrics") else string("lrc").ifBlank { string("lyrics") }
            return LyricsDocument(
                string(if (provider == "LRCLIB") "trackName" else "title"),
                string(if (provider == "LRCLIB") "artistName" else "artist"),
                record.optDouble("duration", 0.0).takeIf { it.isFinite() && it > 0 }?.let { (it * 1_000).toLong() },
                provider, LrcParser.parse(lrc),
                if (record.optBoolean("instrumental")) "" else LrcParser.plain(string("plainLyrics").ifBlank { lrc }),
                record.optBoolean("instrumental"),
            )
        }

        private fun endpoint(base: String, query: LyricsQuery, includeDuration: Boolean): String {
            val fields = linkedMapOf(
                (if (includeDuration) "track_name" else "title") to query.title,
                (if (includeDuration) "artist_name" else "artist") to query.artist,
            )
            if (query.album.isNotBlank()) fields[if (includeDuration) "album_name" else "album"] = query.album
            if (includeDuration) query.durationMillis?.let { fields["duration"] = (it / 1_000.0).toString() }
            return base + "?" + fields.entries.joinToString("&") { (name, value) -> "$name=${URLEncoder.encode(value, "UTF-8")}" }
        }
    }
}

private val lyricsCooldowns = java.util.concurrent.ConcurrentHashMap<String, Long>()

private fun readLyricsUrl(url: String): String? {
    if (Thread.currentThread().isInterrupted) throw IOException("Cancelled")
    val endpoint = URL(url)
    if ((lyricsCooldowns[endpoint.host] ?: 0) > System.currentTimeMillis()) throw IOException("Rate limited")
    val connection = endpoint.openConnection() as HttpURLConnection
    try {
        connection.connectTimeout = 6_000
        connection.readTimeout = 6_000
        connection.setRequestProperty("User-Agent", "F10Play/0.2.10 (https://github.com/kwswk/DiPlay-Android-9)")
        val status = connection.responseCode
        if (status == 404) return null
        if (status == 429) {
            val header = connection.getHeaderField("Retry-After")
            val delay = header?.toLongOrNull()?.coerceIn(0, 86_400)?.times(1_000)
                ?: runCatching { java.time.ZonedDateTime.parse(header, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
                    .toInstant().toEpochMilli() - System.currentTimeMillis() }.getOrDefault(60_000)
            lyricsCooldowns[endpoint.host] = System.currentTimeMillis() + delay.coerceAtLeast(60_000)
            throw IOException("Rate limited")
        }
        if (status !in 200..299) throw IOException("Lyrics service unavailable")
        return connection.inputStream.use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8_192)
            while (true) {
                if (Thread.currentThread().isInterrupted) throw IOException("Cancelled")
                val count = input.read(buffer)
                if (count < 0) break
                if (output.size() + count > 2_000_000) throw IOException("Lyrics response too large")
                output.write(buffer, 0, count)
            }
            output.toString("UTF-8")
        }
    } finally { connection.disconnect() }
}
