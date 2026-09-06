package com.whispertype.android.platform.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure host tests for [InsertionVerifier] (Phase 4 exactly-once verification). */
class InsertionVerifierTest {

    @Test
    fun `changed surrounding text containing the commit is confirmed`() {
        assertEquals(
            true,
            InsertionVerifier.confirmed(
                before = "hello ",
                after = "hello WhisperType test",
                committed = "WhisperType test",
            ),
        )
    }

    @Test
    fun `no change is a definite false`() {
        assertEquals(
            false,
            InsertionVerifier.confirmed(before = "unchanged", after = "unchanged", committed = "x"),
        )
    }

    @Test
    fun `change without the committed text is not confirmed`() {
        // The editor changed for another reason; we must not claim success.
        assertEquals(
            false,
            InsertionVerifier.confirmed(before = "a", after = "b", committed = "x"),
        )
    }

    @Test
    fun `null surrounding read is ambiguous not confirmed`() {
        assertNull(InsertionVerifier.confirmed(before = null, after = "x", committed = "x"))
        assertNull(InsertionVerifier.confirmed(before = "x", after = null, committed = "x"))
    }

    @Test
    fun `empty before and after with no commit is false`() {
        assertEquals(false, InsertionVerifier.confirmed(before = "", after = "", committed = "x"))
    }

    @Test
    fun `long committed text truncated by the IME read is confirmed via the tail`() {
        // The editor reflects the commit but the surrounding-text read is capped,
        // so the full committed string is absent; the trailing tail still matches.
        val committed = buildString {
            repeat(25) { append("the quick brown fox jumps over the lazy dog ") }
        } // ~700 chars, longer than the 400-char read window
        val before = "existing text "
        val after = (before + committed).takeLast(400)
        assertTrue(committed.length > 400)
        assertFalse(after.contains(committed))
        assertEquals(true, InsertionVerifier.confirmed(before = before, after = after, committed = committed))
    }

    @Test
    fun `a different change with a coincidental tail is not confirmed`() {
        // Change happened, tail happens to match, but the actual content differs
        // earlier in the window - must not be a false positive from an empty tail.
        assertEquals(
            false,
            InsertionVerifier.confirmed(before = "", after = "something else", committed = "x"),
        )
    }
}
