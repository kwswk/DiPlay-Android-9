package com.shilapi.xcertplay

import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import android.view.ViewGroup
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 36])
class F10LyricsTest {
    @Test fun lrcHandlesFractionsOffsetsRepeatedTimestampsAndSeek() {
        val lines = LrcParser.parse("\uFEFF[ar:Example]\n[offset:500]\n[00:12.5][00:20.50]Again\n[00:00.100]Start\n[00:10]First\n[00:72.00]Invalid\n[00:12.500]Again")
        assertEquals(listOf(0L, 9_500L, 12_000L, 20_000L), lines.map { it.millis })
        val document = LyricsDocument("Song", "Artist", null, "Test", lines, "")
        assertEquals(-1, document.lineAt(-1))
        assertEquals(2, document.lineAt(12_000))
        assertEquals(0, document.lineAt(1_000)) // backwards seek
        assertEquals(3, document.lineAt(99_000))
        assertEquals("Words", LrcParser.plain("[ar:Artist]\n[ti:Song]\nWords"))
    }

    @Test fun exactLrclibMatchSkipsFallbackAndEncodesMetadata() {
        val calls = mutableListOf<String>()
        val client = F10LyricsClient { url -> calls += url; lrclib("Song & Dance", "Artist", 255, "[00:10.00]First") }
        val query = LyricsQuery("Song & Dance", "Artist", "Album", 255_000)
        val result = client.find(query).single()
        assertTrue(F10LyricsClient.matches(query, result))
        assertEquals("LRCLIB", result.provider)
        assertEquals(1, calls.size)
        assertTrue(calls.single().contains("track_name=Song+%26+Dance"))
        assertTrue(calls.single().contains("duration=255.0"))
    }

    @Test fun fallbackAcceptsBothDocumentedAndLiveJsonFieldsWithoutSelectingWrongVersions() {
        val query = LyricsQuery("Song", "Artist", "", 255_000)
        val client = F10LyricsClient { url ->
            if (url.contains("lrclib")) null else """[
                {"title":"Song (Live)","artist":"Artist","duration":255,"lrc":"[00:01.0]Live"},
                {"title":"Song","artist":"Artist","duration":294,"lrc":"[00:01.0]Long version"},
                {"title":"Song","artist":"Artist","duration":255,"lyrics":"[00:01.0]Correct"}
            ]"""
        }
        val result = client.find(query)
        assertEquals(3, result.size)
        assertEquals(listOf(false, false, true), result.map { F10LyricsClient.matches(query, it) })
        assertEquals("Correct", result.last().lines.single().text)
    }

    @Test fun malformedPrimaryStillTriesFallbackAndNetworkFailureDoesNotMasqueradeAsMissingLyrics() {
        val client = F10LyricsClient { url ->
            if (url.contains("lrclib")) "not json" else """[{"title":"Song","artist":"Artist","duration":255,"lrc":"[00:01]Line"}]"""
        }
        assertEquals("LrcAPI", client.find(LyricsQuery("Song", "Artist", "", 255_000)).single().provider)
        val offline = F10LyricsClient { throw IOException("offline") }
        assertThrows(IOException::class.java) { offline.find(LyricsQuery("Song", "Artist", "", null)) }
    }

    @Test fun choosingLyricsLoadsAlternativesWhileKeepingPrimaryFirst() {
        val calls = mutableListOf<String>()
        val client = F10LyricsClient { url ->
            calls += url
            if (url.contains("lrclib")) lrclib("Song", "Artist", 255, "[00:01]Primary")
            else """[{"title":"Song (Live)","artist":"Artist","duration":260,"lrc":"[00:01]Alternative"}]"""
        }
        val result = client.find(LyricsQuery("Song", "Artist", "Album", 255_000), includeAlternatives = true)
        assertEquals(listOf("LRCLIB", "LrcAPI"), result.map { it.provider })
        assertEquals(2, calls.size)
        assertTrue(calls.last().endsWith("artist="))
        assertFalse(calls.last().contains("album="))
    }

    @Test fun instrumentalIsDistinctFromMissingAndUntimedLyricsAreRetained() {
        val query = LyricsQuery("Song", "Artist", "", 255_000)
        val instrumental = F10LyricsClient { lrclib("Song", "Artist", 255, null, instrumental = true) }.find(query).single()
        assertTrue(instrumental.instrumental)
        assertTrue(instrumental.lines.isEmpty())
        val plain = F10LyricsClient { url -> if (url.contains("lrclib")) lrclib("Song", "Artist", 255, null, "Plain words") else "[]" }
            .find(query).single()
        assertEquals("Plain words", plain.plain)
        assertTrue(plain.lines.isEmpty())
        val missing = F10LyricsClient { null }.find(query)
        assertTrue(missing.isEmpty())
    }

    @Test fun landscapePartitionsTouchSurfaceAndPortraitRestoresFullProjection() {
        val context = RuntimeEnvironment.getApplication()
        val projection = FrameLayout(context)
        val lyrics = FrameLayout(context)
        val layout = F10ProjectionLayout(context, projection, lyrics)
        val density = context.resources.displayMetrics.density
        fun measure(width: Int, height: Int) {
            val w = (width * density).toInt(); val h = (height * density).toInt()
            layout.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            layout.layout(0, 0, w, h)
        }
        layout.lyricsEnabled = true
        measure(853, 384)
        assertTrue(layout.lyricsVisible)
        assertTrue(projection.width >= 500 * density)
        assertEquals(projection.right, lyrics.left)
        assertEquals(layout.width, lyrics.right)
        layout.lyricsOnLeft = true
        measure(853, 384)
        assertEquals(0, lyrics.left)
        assertEquals(lyrics.right, projection.left)
        assertEquals(layout.width, projection.right)
        layout.lyricsOnLeft = false
        measure(853, 384)
        assertEquals(projection.right, lyrics.left)
        layout.lyricsEnabled = false
        measure(853, 384)
        assertFalse(layout.lyricsVisible)
        assertEquals(layout.width, projection.width)
        layout.lyricsEnabled = true
        measure(384, 853)
        assertFalse(layout.lyricsVisible)
        assertEquals(layout.width, projection.width)
        measure(600, 360)
        assertFalse(layout.lyricsVisible)
    }

    @Test fun lyricsPanelWrapsLargeTextAndHighlightsPauseAndBackwardSeekWithoutFetching() {
        val context = RuntimeEnvironment.getApplication()
        val config = android.content.res.Configuration(context.resources.configuration).apply { fontScale = 1.5f }
        context.resources.updateConfiguration(config, context.resources.displayMetrics)
        val panel = F10LyricsPanel(context) {}
        val query = LyricsQuery("Song", "Artist", "", 60_000)
        val document = LyricsDocument("Song", "Artist", 60_000, "Test", LrcParser.parse("[00:01]First line\n[00:10]A current lyric that wraps comfortably\n[00:20]Next line"), "")
        @Suppress("UNCHECKED_CAST")
        val cache = panel.javaClass.getDeclaredField("cache").apply { isAccessible = true }.get(panel) as MutableMap<LyricsQuery, LyricsDocument>
        cache[query] = document
        fun children(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { children(view.getChildAt(it)) } else emptyList()
        val info = CarPlayNowPlaying(title = "Song", artist = "Artist", durationMillis = 60_000, elapsedMillis = 12_000, playing = true)
        panel.updatePlayback(info)
        val density = context.resources.displayMetrics.density
        panel.measure(View.MeasureSpec.makeMeasureSpec((298 * density).toInt(), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec((340 * density).toInt(), View.MeasureSpec.EXACTLY))
        panel.layout(0, 0, panel.measuredWidth, panel.measuredHeight)
        val current = panel.javaClass.getDeclaredField("current").apply { isAccessible = true }.get(panel) as TextView
        assertEquals("A current lyric that wraps comfortably", current.text.toString())
        assertEquals(android.graphics.Typeface.BOLD, current.typeface.style and android.graphics.Typeface.BOLD)
        assertEquals(context.getColor(com.shilapi.xcertplay.host.R.color.lyrics_active), current.currentTextColor)
        panel.updatePlayback(info.copy(playing = false))
        assertEquals("A current lyric that wraps comfortably", current.text.toString())
        panel.updatePlayback(info.copy(elapsedMillis = 2_000))
        assertEquals("First line", current.text.toString())
        for (button in children(panel).filterIsInstance<android.widget.Button>()) {
            assertTrue(button.height >= 48 * density)
            assertTrue(button.width > 0)
            val bounds = android.graphics.Rect(0, 0, button.width, button.height)
            panel.offsetDescendantRectToMyCoords(button, bounds)
            assertTrue("Controls remain visible: $bounds", bounds.bottom <= panel.height && bounds.right <= panel.width)
        }
    }

    @Test fun manualRecordingChoiceSurvivesTheNextPlaybackUpdateAndIsCached() {
        val activity = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup()
        try {
            val panel = F10LyricsPanel(activity.get()) {}
            val query = LyricsQuery("Song", "Artist", "", 60_000)
            val first = LyricsDocument("Song", "Artist", 60_000, "Test", LrcParser.parse("[00:01]First recording"), "")
            val second = first.copy(lines = LrcParser.parse("[00:01]Chosen recording"))
            @Suppress("UNCHECKED_CAST")
            val cache = panel.javaClass.getDeclaredField("cache").apply { isAccessible = true }.get(panel) as MutableMap<LyricsQuery, LyricsDocument>
            cache[query] = first
            val info = CarPlayNowPlaying(title = "Song", artist = "Artist", durationMillis = 60_000, elapsedMillis = 2_000, playing = true)
            panel.updatePlayback(info)
            panel.javaClass.getDeclaredField("candidates").apply { isAccessible = true }.set(panel, listOf(first, second))
            panel.javaClass.getDeclaredMethod("chooseCandidate").apply { isAccessible = true }.invoke(panel)
            val chooser = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
            chooser.listView.performItemClick(null, 1, 1)
            panel.updatePlayback(info.copy(elapsedMillis = 3_000))
            val current = panel.javaClass.getDeclaredField("current").apply { isAccessible = true }.get(panel) as TextView
            assertEquals("Chosen recording", current.text.toString())
            assertEquals(second, cache[query])
            assertFalse(chooser.isShowing)
        } finally { activity.pause().stop().destroy() }
    }

    private fun lrclib(title: String, artist: String, duration: Int, synced: String?, plain: String? = null, instrumental: Boolean = false) = JSONObject()
        .put("trackName", title).put("artistName", artist).put("duration", duration)
        .put("syncedLyrics", synced ?: JSONObject.NULL).put("plainLyrics", plain ?: JSONObject.NULL)
        .put("instrumental", instrumental).toString()
}
