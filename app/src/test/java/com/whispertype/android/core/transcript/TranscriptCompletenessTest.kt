package com.whispertype.android.core.transcript

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for the 0.4.2 completeness gate that decides whether a polished
 * echo may be inserted over the raw ASR baseline.
 */
class TranscriptCompletenessTest {

    @Test
    fun `verbatim echo fully covering the raw is complete`() {
        assertTrue(
            TranscriptCompleteness.covers(
                echo = "The quick brown fox jumps over the lazy dog",
                raw = "The quick brown fox jumps over the lazy dog",
            ),
        )
    }

    @Test
    fun `echo that only drops fillers is complete`() {
        assertTrue(
            TranscriptCompleteness.covers(
                echo = "We should meet on Thursday",
                raw = "um we should like meet on thursday",
            ),
        )
    }

    @Test
    fun `first-few-words echo is incomplete`() {
        assertFalse(
            TranscriptCompleteness.covers(
                echo = "The quick brown",
                raw = "The quick brown fox jumps over the lazy dog",
            ),
        )
    }

    @Test
    fun `one word summary echo is incomplete`() {
        assertFalse(
            TranscriptCompleteness.covers(
                echo = "Meeting",
                raw = "The team meeting is scheduled for nine in the morning",
            ),
        )
    }

    @Test
    fun `last word only echo is incomplete`() {
        assertFalse(
            TranscriptCompleteness.covers(
                echo = "dog",
                raw = "The quick brown fox jumps over the lazy dog",
            ),
        )
    }

    @Test
    fun `echo at the completeness boundary is accepted`() {
        // 4 of 6 words: ratio 0.667 >= 0.6 (polish may drop ~a third of fillers).
        assertTrue(
            TranscriptCompleteness.covers(
                echo = "one two three four",
                raw = "one two three four five six",
            ),
        )
        assertFalse(
            TranscriptCompleteness.covers(
                echo = "one two three",
                raw = "one two three four five six",
            ),
        )
    }

    @Test
    fun `echo may be longer when it preserves the ordered raw content`() {
        assertTrue(
            TranscriptCompleteness.covers(
                echo = "We should meet on Thursday then",
                raw = "We should meet on Thursday",
            ),
        )
    }

    @Test
    fun `blank raw cannot be verified`() {
        assertFalse(TranscriptCompleteness.covers(echo = "anything", raw = ""))
        assertFalse(TranscriptCompleteness.covers(echo = "anything", raw = "   "))
    }

    @Test
    fun `blank echo never covers a real raw`() {
        assertFalse(
            TranscriptCompleteness.covers(
                echo = "",
                raw = "The quick brown fox",
            ),
        )
    }

    @Test
    fun `custom min ratio adjusts the acceptance band`() {
        assertFalse(TranscriptCompleteness.covers(echo = "a b c", raw = "a b c d e f", minRatio = 0.6))
        assertTrue(TranscriptCompleteness.covers(echo = "a b c d", raw = "a b c d e f", minRatio = 0.6))
    }

    @Test
    fun `expected words scales with duration and speaking rate`() {
        assertEquals(220.0, TranscriptCompleteness.expectedWords(100_000), 0.001)
        assertEquals(2.2, TranscriptCompleteness.expectedWords(1_000), 0.001)
    }

    @Test
    fun `content words tokenizes letter and digit runs only`() {
        assertEquals(
            listOf("the", "quick", "brown", "fox123"),
            TranscriptCompleteness.contentWords("The quick, brown 'fox123'!"),
        )
    }

    @Test
    fun `unrelated equal length text fails ordered coverage`() {
        val assessment = TranscriptCompleteness.assess(
            echo = "orange violet silver copper golden bronze",
            raw = "alpha beta gamma delta epsilon zeta",
        )

        assertFalse(assessment.isComplete)
        assertEquals(
            TranscriptCompleteness.Diagnosis.LOW_ORDERED_COVERAGE,
            assessment.diagnosis,
        )
        assertEquals(1.0, assessment.lengthRatio, 0.001)
        assertEquals(0.0, assessment.orderedCoverage, 0.001)
    }

    @Test
    fun `matching length cannot substitute unrelated tokens for missing content`() {
        val assessment = TranscriptCompleteness.assess(
            echo = "alpha beta gamma orange violet silver",
            raw = "alpha beta gamma delta epsilon zeta",
            minOrderedCoverage = 0.5,
        )

        assertFalse(assessment.isComplete)
        assertEquals(
            TranscriptCompleteness.Diagnosis.LOW_TOKEN_COVERAGE,
            assessment.diagnosis,
        )
        assertEquals(1.0, assessment.lengthRatio, 0.001)
        assertEquals(0.5, assessment.tokenCoverage, 0.001)
        assertEquals(0.5, assessment.orderedCoverage, 0.001)
    }

    @Test
    fun `filler removal and moderate reordering remain complete`() {
        assertTrue(
            TranscriptCompleteness.covers(
                echo = "Please send Elena the revised report",
                raw = "um uh please send the revised report to Elena",
            ),
        )
    }

    @Test
    fun `reversing a short phrase is not ordered evidence`() {
        val assessment = TranscriptCompleteness.assess(
            echo = "beta alpha",
            raw = "alpha beta",
        )

        assertFalse(assessment.isComplete)
        assertEquals(
            TranscriptCompleteness.Diagnosis.LOW_ORDERED_COVERAGE,
            assessment.diagnosis,
        )
    }

    @Test
    fun `style rephrasing may change wording while preserving ordered content`() {
        val assessment = TranscriptCompleteness.assess(
            echo = "Please reserve a window table for four people tonight",
            raw = "Please book a table for four people near the window tonight",
        )

        assertTrue(assessment.isComplete)
        assertTrue(assessment.orderedCoverage >= TranscriptCompleteness.DEFAULT_MIN_ORDERED_COVERAGE)
    }

    @Test
    fun `every high signal anchor class is mandatory`() {
        val examples = listOf(
            "send 42 blue boxes to the north warehouse" to
                "send many blue boxes to the north warehouse",
            "meet the design team on Thursday in the main office" to
                "meet the design team sometime in the main office",
            "review https://example.com/orders before sending the final report" to
                "review the customer portal before sending the final report now",
            "send the report to user.name@example.com before lunch today" to
                "send the report to the account owner before lunch today",
            "deploy build AB-123 to the staging cluster after lunch" to
                "deploy the approved build to the staging cluster after lunch",
        )

        for ((raw, echo) in examples) {
            val assessment = TranscriptCompleteness.assess(echo = echo, raw = raw)
            assertFalse(assessment.isComplete, raw)
            assertEquals(
                TranscriptCompleteness.Diagnosis.MISSING_ANCHOR,
                assessment.diagnosis,
                raw,
            )
            assertTrue(assessment.preservedAnchorCount < assessment.requiredAnchorCount, raw)
        }
    }

    @Test
    fun `anchors are compared case insensitively across rephrasing`() {
        assertTrue(
            TranscriptCompleteness.covers(
                echo = "Please email user.name@example.com regarding ab-123 this Thursday at 42",
                raw = "Email User.Name@Example.com about AB-123 on Thursday at 42",
            ),
        )
    }

    @Test
    fun `numeric date separators may be polished without losing date evidence`() {
        assertTrue(
            TranscriptCompleteness.covers(
                echo = "Meet on 2026/08/12 at 09:30",
                raw = "meet on 2026-08-12 at 09:30",
            ),
        )
    }

    @Test
    fun `ordinal day numbers survive polish that drops the suffix`() {
        // "15th" and "15" are the same NUMBER anchor and the same content
        // token; the polish must not fail MISSING_ANCHOR or ordered coverage.
        assertTrue(
            TranscriptCompleteness.covers(
                echo = "May 15",
                raw = "May 15th",
            ),
        )
    }

    @Test
    fun `repeated anchors must be preserved with multiplicity`() {
        val assessment = TranscriptCompleteness.assess(
            echo = "use code 7 and then continue later",
            raw = "use code 7 and then code 7 later",
        )

        assertFalse(assessment.isComplete)
        assertEquals(TranscriptCompleteness.Diagnosis.MISSING_ANCHOR, assessment.diagnosis)
    }

    @Test
    fun `high signal anchors must retain their order`() {
        val examples = listOf(
            "move 21 before 84" to "move 84 before 21",
            "meet Thursday at 42" to "meet 42 on Thursday",
        )

        for ((raw, echo) in examples) {
            val assessment = TranscriptCompleteness.assess(echo = echo, raw = raw)
            assertFalse(assessment.isComplete, raw)
            assertEquals(
                TranscriptCompleteness.Diagnosis.MISSING_ANCHOR,
                assessment.diagnosis,
                raw,
            )
        }
    }

    @Test
    fun `optional vocal fillers do not reduce ordered coverage`() {
        val base = "please send the final report tomorrow"
        for (fillers in listOf("um", "uh um", "hmm er uh")) {
            assertTrue(
                TranscriptCompleteness.covers(
                    echo = base,
                    raw = "$fillers $base",
                ),
                fillers,
            )
        }
    }
}
