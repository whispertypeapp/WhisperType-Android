package com.whispertype.android.ui.home

import com.whispertype.android.data.history.HistoryRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Locale

class HomeHelpersTest {

    private fun fixedMondayMillis(): Long {
        val cal = Calendar.getInstance().apply {
            set(2026, Calendar.SEPTEMBER, 7, 14, 30, 0) // Monday, Sept 7, 2026
            set(Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis
    }

    private fun fixedWednesdayMillis(): Long {
        val cal = Calendar.getInstance().apply {
            set(2026, Calendar.SEPTEMBER, 9, 10, 0, 0) // Wednesday, Sept 9, 2026
            set(Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis
    }

    private fun makeEntry(
        text: String,
        timestampMillis: Long,
        outcome: String = "Success",
        durationMs: Long = 60_000L,
    ) = HistoryRepository.HistoryEntry(
        id = text.hashCode().toString(),
        timestampMillis = timestampMillis,
        text = text,
        language = "HINGLISH",
        charCount = text.length,
        outcome = outcome,
        durationMs = durationMs,
    )

    @Test
    fun testDayOfWeekMonday0() {
        val monday = fixedMondayMillis()
        assertEquals(0, HomeHelpers.dayOfWeekMonday0(monday))

        val wednesday = fixedWednesdayMillis()
        assertEquals(2, HomeHelpers.dayOfWeekMonday0(wednesday))
    }

    @Test
    fun testComputeWeeklyBarsState_emptyHistory() {
        val now = fixedMondayMillis()
        val state = HomeHelpers.computeWeeklyBarsState(emptyList(), now)

        assertEquals(0, state.weekWords)
        assertEquals(0, state.weekSessions)
        assertNull(state.weekWpm)
        assertEquals(7, state.days.size)
        assertTrue(state.days.all { it.words == 0 })
        assertEquals("No words recorded this week", state.semanticsDescription)
        assertTrue(state.days[0].isCurrentDay)
        assertFalse(state.days[1].isCurrentDay)
        assertFalse(state.days.any { it.isEarlierActive })
    }

    @Test
    fun testComputeWeeklyBarsState_singleDayEntry() {
        val now = fixedMondayMillis()
        val entry = makeEntry("one two three four five", now)
        val state = HomeHelpers.computeWeeklyBarsState(listOf(entry), now)

        assertEquals(5, state.weekWords)
        assertEquals(1, state.weekSessions)
        assertEquals(5, state.days[0].words)
        assertEquals("5 words this week, recorded on Monday", state.semanticsDescription)
    }

    @Test
    fun testComputeWeeklyBarsState_multiDayEntries() {
        val wednesdayNow = fixedWednesdayMillis()
        val monday = fixedMondayMillis()
        val e1 = makeEntry("one two three four five", monday)
        val e2 = makeEntry("six seven eight nine ten", wednesdayNow)
        val state = HomeHelpers.computeWeeklyBarsState(listOf(e1, e2), wednesdayNow)

        assertEquals(10, state.weekWords)
        assertEquals(2, state.weekSessions)
        assertTrue(state.days[0].isEarlierActive)
        assertEquals(5, state.days[0].words)
        assertTrue(state.days[2].isCurrentDay)
        assertEquals(5, state.days[2].words)
        assertEquals("10 words this week, recorded on Monday and Wednesday", state.semanticsDescription)
    }

    @Test
    fun testFormatCount() {
        assertEquals("0", HomeHelpers.formatCount(0))
        assertEquals("450", HomeHelpers.formatCount(450))
        assertEquals("999", HomeHelpers.formatCount(999))
        assertEquals("1.2k", HomeHelpers.formatCount(1200))
        assertEquals("15k", HomeHelpers.formatCount(15400))
    }

    @Test
    fun testComputeGlanceMetrics_emptyHistory() {
        val metrics = HomeHelpers.computeGlanceMetrics(emptyList())

        assertEquals("0", metrics.sessionsValue)
        assertEquals("All-time sessions", metrics.sessionsLabel)
        assertEquals("—", metrics.wpmValue)
        assertEquals("No timed sessions yet", metrics.wpmLabel)
        assertEquals("0", metrics.perSessionValue)
        assertEquals("Average words / session", metrics.perSessionLabel)
    }

    @Test
    fun testComputeGlanceMetrics_withSessionsAndDurations() {
        val now = fixedMondayMillis()
        val e1 = makeEntry("a ".repeat(100).trim(), now, durationMs = 60_000L) // 100 words, 1 min
        val e2 = makeEntry("b ".repeat(50).trim(), now, durationMs = 30_000L) // 50 words, 0.5 min
        val metrics = HomeHelpers.computeGlanceMetrics(listOf(e1, e2))

        assertEquals("2", metrics.sessionsValue)
        assertEquals("All-time sessions", metrics.sessionsLabel)
        assertEquals("100", metrics.wpmValue)
        assertEquals("Average words / min", metrics.wpmLabel)
        assertEquals("75", metrics.perSessionValue)
        assertEquals("Average words / session", metrics.perSessionLabel)
    }

    @Test
    fun testComputeGlanceMetrics_zeroDurationSessions() {
        val now = fixedMondayMillis()
        val e1 = makeEntry("test dictation text", now, durationMs = 0L)
        val metrics = HomeHelpers.computeGlanceMetrics(listOf(e1))

        assertEquals("1", metrics.sessionsValue)
        assertEquals("—", metrics.wpmValue)
        assertEquals("No timed sessions yet", metrics.wpmLabel)
        assertEquals("3", metrics.perSessionValue)
    }

    @Test
    fun testFormatRecentSnippetMeta() {
        val now = fixedMondayMillis()
        val todayEntry = makeEntry("hello world", now, outcome = "Success")
        val metaToday = HomeHelpers.formatRecentSnippetMeta(
            entry = todayEntry,
            nowMillis = now,
            todayLabel = "Today",
            insertedLabel = "Inserted",
            copiedLabel = "Copied",
            locale = Locale.US,
        )
        assertTrue(metaToday.startsWith("Today · "))
        assertTrue(metaToday.endsWith(" · Inserted"))

        val copiedEntry = makeEntry("hello world", now, outcome = "CopiedToClipboard")
        val metaCopied = HomeHelpers.formatRecentSnippetMeta(
            entry = copiedEntry,
            nowMillis = now,
            todayLabel = "Today",
            insertedLabel = "Inserted",
            copiedLabel = "Copied",
            locale = Locale.US,
        )
        assertTrue(metaCopied.endsWith(" · Copied"))

        val pastEntry = makeEntry("old text", now - 2 * 86_400_000L, outcome = "Success")
        val metaPast = HomeHelpers.formatRecentSnippetMeta(
            entry = pastEntry,
            nowMillis = now,
            todayLabel = "Today",
            insertedLabel = "Inserted",
            copiedLabel = "Copied",
            locale = Locale.US,
        )
        assertFalse(metaPast.startsWith("Today"))
        assertTrue(metaPast.contains("Sep 5"))
    }
}
