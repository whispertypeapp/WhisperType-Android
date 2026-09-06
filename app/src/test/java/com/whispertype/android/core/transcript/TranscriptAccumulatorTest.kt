package com.whispertype.android.core.transcript

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for the session-local [TranscriptAccumulator] merge rules
 * (remediation plan Release E1/E2).
 */
class TranscriptAccumulatorTest {

    @Test
    fun `empty and blank messages are ignored without change or revision bump`() {
        val accumulator = TranscriptAccumulator()
        assertNull(accumulator.accept(""))
        assertNull(accumulator.accept("   "))
        assertNull(accumulator.accept("\t\n "))
        assertNull(accumulator.current)
        assertEquals(0, accumulator.revisionCount)
    }

    @Test
    fun `first message is accepted as current`() {
        val accumulator = TranscriptAccumulator()
        assertEquals("hello world", accumulator.accept("hello world"))
        assertEquals("hello world", accumulator.current)
        assertEquals(1, accumulator.revisionCount)
    }

    @Test
    fun `exact duplicate of current is ignored`() {
        val accumulator = TranscriptAccumulator()
        accumulator.accept("schedule the meeting")
        assertEquals("schedule the meeting", accumulator.accept("schedule the meeting"))
        assertEquals("schedule the meeting", accumulator.current)
        assertEquals(1, accumulator.revisionCount)
    }

    @Test
    fun `cumulative extension ends as the longest value`() {
        val accumulator = TranscriptAccumulator()
        accumulator.accept("schedule")
        accumulator.accept("schedule the")
        assertEquals("schedule the meeting", accumulator.accept("schedule the meeting"))
        assertEquals("schedule the meeting", accumulator.current)
        assertEquals(3, accumulator.revisionCount)
    }

    @Test
    fun `extension preserves inner spaces and does not trim`() {
        val accumulator = TranscriptAccumulator()
        accumulator.accept("word ")
        assertEquals("word count", accumulator.accept("word count"))
        assertEquals("word count", accumulator.current)
        assertEquals(2, accumulator.revisionCount)
    }

    @Test
    fun `reverse prefix partial message does not shrink current`() {
        val accumulator = TranscriptAccumulator()
        accumulator.accept("schedule the meeting")
        assertEquals("schedule the meeting", accumulator.accept("schedule the"))
        assertEquals("schedule the meeting", accumulator.current)
        assertEquals(1, accumulator.revisionCount)
    }

    @Test
    fun `message that merely extends a word is a correction not a merge`() {
        val accumulator = TranscriptAccumulator()
        accumulator.accept("schedule")
        assertEquals("scheduled", accumulator.accept("scheduled"))
        assertEquals("scheduled", accumulator.current)
        assertEquals(2, accumulator.revisionCount)
    }

    @Test
    fun `correction replaces the prior provisional value`() {
        val accumulator = TranscriptAccumulator()
        accumulator.accept("schedule the meeting")
        assertEquals("schedule the demo", accumulator.accept("schedule the demo"))
        assertEquals("schedule the demo", accumulator.current)
        assertEquals(2, accumulator.revisionCount)
    }

    @Test
    fun `revision count increments only on actual changes`() {
        val accumulator = TranscriptAccumulator()
        accumulator.accept("hello")
        accumulator.accept("hello")
        accumulator.accept("   ")
        accumulator.accept("hello world")
        accumulator.accept("hello wo")
        accumulator.accept("goodbye world")
        assertEquals(3, accumulator.revisionCount)
        assertEquals("goodbye world", accumulator.current)
    }

    @Test
    fun `settledText returns null before any accept and current afterwards`() {
        val accumulator = TranscriptAccumulator()
        assertNull(accumulator.settledText())
        accumulator.accept("hello")
        assertEquals("hello", accumulator.settledText())
    }

    @Test
    fun `reset clears current and revision count`() {
        val accumulator = TranscriptAccumulator()
        accumulator.accept("hello")
        accumulator.accept("hello world")
        accumulator.reset()
        assertNull(accumulator.current)
        assertNull(accumulator.settledText())
        assertEquals(0, accumulator.revisionCount)
        assertEquals("again", accumulator.accept("again"))
        assertEquals(1, accumulator.revisionCount)
    }

    // ------------------------------------------------------------------
    // 0.4.2: anti-shrink hardening
    // ------------------------------------------------------------------

    @Test
    fun `strictly shorter non-prefix revision never shrinks the current value`() {
        val accumulator = TranscriptAccumulator()
        accumulator.accept("I want to order a pizza for delivery")
        assertEquals("I want to order a pizza for delivery", accumulator.accept("order pizza"))
        assertEquals("I want to order a pizza for delivery", accumulator.current)
        assertEquals(1, accumulator.revisionCount)
    }

    @Test
    fun `authoritative final replaces a longer interim even without a shared edge`() {
        val accumulator = TranscriptAccumulator()
        accumulator.accept("so um we should uh meet on thursday for the like project review at ten")
        val result = accumulator.acceptAuthoritative(
            "we should meet on thursday for the project review at 10",
        )
        assertTrue(result.changed)
        assertEquals("we should meet on thursday for the project review at 10", accumulator.current)
        assertEquals(2, accumulator.revisionCount)
    }

    @Test
    fun `authoritative final equal to current is unchanged`() {
        val accumulator = TranscriptAccumulator()
        accumulator.accept("the quick brown fox")
        val result = accumulator.acceptAuthoritative("the quick brown fox")
        assertFalse(result.changed)
        assertEquals(1, accumulator.revisionCount)
    }

    @Test
    fun `same length correction still replaces the prior value`() {
        val accumulator = TranscriptAccumulator()
        accumulator.accept("schedule the meeting")
        assertEquals("schedule the demo", accumulator.accept("schedule the demo"))
        assertEquals("schedule the demo", accumulator.current)
        assertEquals(2, accumulator.revisionCount)
    }

    @Test
    fun `merge only accumulator replaces delta style messages as before`() {
        // Default replace semantics stay for the ASR source.
        val accumulator = TranscriptAccumulator()
        accumulator.accept("hello")
        assertEquals("world", accumulator.accept("world"))
        assertEquals("world", accumulator.current)
    }

    @Test
    fun `acceptWithResult distinguishes state changes from ignored messages`() {
        val accumulator = TranscriptAccumulator()

        val first = accumulator.acceptWithResult("hello")
        assertTrue(first.changed)
        assertEquals("hello", first.text)

        assertFalse(accumulator.acceptWithResult("hello").changed)
        assertFalse(accumulator.acceptWithResult("   ").changed)

        val extension = accumulator.acceptWithResult("hello world")
        assertTrue(extension.changed)
        assertEquals("hello world", extension.text)
        assertEquals(2, accumulator.revisionCount)
    }

    @Test
    fun `punctuation-only revision reports a real text change`() {
        val accumulator = TranscriptAccumulator()
        accumulator.accept("hello world!")

        val revision = accumulator.acceptWithResult("hello world")

        assertTrue(revision.changed)
        assertEquals("hello world", revision.text)
        assertEquals(2, accumulator.revisionCount)
    }
}
