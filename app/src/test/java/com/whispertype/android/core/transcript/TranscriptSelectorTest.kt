package com.whispertype.android.core.transcript

import com.whispertype.android.core.model.LanguageMode
import com.whispertype.android.core.model.ResultCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the transcript candidate selector/validator.
 */
class TranscriptSelectorTest {

    private val selector = TranscriptSelector()

    private fun candidate(raw: String, cleaned: String? = null, language: LanguageMode = LanguageMode.ENGLISH): ResultCandidate =
        ResultCandidate(raw = raw, cleaned = cleaned, language = language)

    // ------------------------------------------------------------------
    // Selection order
    // ------------------------------------------------------------------

    @Test
    fun `selects same candidate as Cleaned when cleaned is valid even if raw is valid`() {
        val c = candidate(raw = "the quick brown fox", cleaned = "The quick brown fox jumps over the lazy dog.")
        val result = selector.select(listOf(c))
        assertTrue(result is TranscriptSelection.Cleaned)
        assertEquals("The quick brown fox jumps over the lazy dog.", result.text)
        assertSame(c, result.candidate)
    }

    @Test
    fun `falls back to valid raw when cleaned is invalid`() {
        val c = candidate(raw = "hello world", cleaned = ".")
        val result = selector.select(listOf(c))
        assertTrue(result is TranscriptSelection.Raw)
        assertEquals("hello world", result.text)
        assertSame(c, result.candidate)
    }

    @Test
    fun `prefers a valid cleaned candidate later in the list over an earlier raw-only candidate`() {
        val rawOnly = candidate(raw = "earlier valid raw", cleaned = null)
        val cleaned = candidate(raw = "junk", cleaned = "later valid cleaned")
        val result = selector.select(listOf(rawOnly, cleaned))
        assertTrue(result is TranscriptSelection.Cleaned)
        assertEquals("later valid cleaned", result.text)
        assertSame(cleaned, result.candidate)
    }

    @Test
    fun `returns the first valid raw candidate in order when no cleaned is usable`() {
        val first = candidate(raw = "first raw", cleaned = ".")
        val second = candidate(raw = "second raw", cleaned = "..")
        val result = selector.select(listOf(first, second))
        assertTrue(result is TranscriptSelection.Raw)
        assertEquals("first raw", result.text)
        assertSame(first, result.candidate)
    }


    // ------------------------------------------------------------------
    // Rejections
    // ------------------------------------------------------------------

    @Test
    fun `rejects blank empty and whitespace-only text`() {
        assertEquals(TranscriptSelection.None, selector.select(listOf(candidate(raw = ""))))
        assertEquals(TranscriptSelection.None, selector.select(listOf(candidate(raw = "   "))))
        assertEquals(TranscriptSelection.None, selector.select(listOf(candidate(raw = "\t\n  "))))
        // Also reject via a cleaned field that is blank while raw is blank.
        assertEquals(TranscriptSelection.None, selector.select(listOf(candidate(raw = "   ", cleaned = "   "))))
    }

    @Test
    fun `rejects punctuation-only text`() {
        for (punc in listOf(".", "...", "?!", "—  :", "!?", ";;;", "---", "....")) {
            assertEquals("should reject '$punc'", TranscriptSelection.None, selector.select(listOf(candidate(raw = punc))))
        }
    }

    @Test
    fun `accepts phrases that were previously rejected as model preambles`() {
        // The source is the user's own ASR speech, so spoken leads such as
        // "Sure, ...", "Okay so ...", "Of course ..." are legitimate dictation.
        val phrases = listOf(
            "Sure, here is the report I promised",
            "Okay so we need to move the meeting",
            "Of course it works",
            "Certainly we can do that",
            "Yes I think so",
            "Alright let's go",
        )
        for (p in phrases) {
            val result = selector.select(listOf(candidate(raw = p)))
            assertTrue("should accept user speech '$p'", result is TranscriptSelection.Raw)
            assertEquals(p, result.text)
        }
    }

    @Test
    fun `accepts greetings that were previously rejected as model output`() {
        val greetings = listOf(
            "Hello, I'm going to the store",
            "Hi there, how is your day going?",
            "Hey there, let's grab lunch later",
        )
        for (g in greetings) {
            val result = selector.select(listOf(candidate(raw = g)))
            assertTrue("should accept greeting '$g'", result is TranscriptSelection.Raw)
        }
    }

    @Test
    fun `accepts acknowledgments that were previously rejected as model output`() {
        val acks = listOf(
            "Got it thanks",
            "Understood, I will meet you at three",
            "I see what you mean",
            "No problem at all",
        )
        for (a in acks) {
            val result = selector.select(listOf(candidate(raw = a)))
            assertTrue("should accept acknowledgment '$a'", result is TranscriptSelection.Raw)
        }
    }

    @Test
    fun `preserves legitimate sentences that start like an acknowledgment`() {
        val results = listOf(
            "I understand the instructions clearly.",
            "I see the difference between the two options.",
            "Got it from the store yesterday.",
        )
        for (r in results) {
            assertNotEquals("should accept sentence '$r'", TranscriptSelection.None, selector.select(listOf(candidate(raw = r))))
        }
    }

    @Test
    fun `does not treat short generic speech as a preamble`() {
        val result = selector.select(listOf(candidate(raw = "Sure!")))
        assertTrue(result is TranscriptSelection.Raw)
        val ok = selector.select(listOf(candidate(raw = "Okay")))
        assertTrue(ok is TranscriptSelection.Raw)
    }

    @Test
    fun `rejects implausibly expanded cleaned text`() {
        // The cleaned transcript is a fabricated ~17-word blow up of a 1-word
        // raw fragment, so it must be rejected and not selected as Cleaned; the
        // valid short raw "go" is then the fallback.
        val expanded = candidate(
            raw = "go",
            cleaned = "please go to the store and buy some milk and some bread and some eggs and more",
        )
        val result = selector.select(listOf(expanded))
        assertTrue("cleaned must be rejected as implausibly expanded", result is TranscriptSelection.Raw)
        assertEquals("go", result.text)
    }

    @Test
    fun `permits plausible cleanup expansion within the ratio`() {
        // cleaned (11 words) is longer than raw (2 words) but well within the
        // 10x hallucination ratio, so it is a legitimate Cleaned selection.
        val c = candidate(
            raw = "go now",
            cleaned = "Go to the store and buy some milk and eggs please",
        )
        val result = selector.select(listOf(c))
        assertTrue(result is TranscriptSelection.Cleaned)
        assertEquals("Go to the store and buy some milk and eggs please", result.text)
    }

    @Test
    fun `accepts repetitive but legitimate user speech`() {
        // Repetition is no longer rejected: it is the user's own speech.
        for (rep in listOf("la la la la la", "okay okay okay", "abc abc abc", "hi there hi there hi there")) {
            val result = selector.select(listOf(candidate(raw = rep)))
            assertTrue("should accept repetitive speech '$rep'", result is TranscriptSelection.Raw)
        }
    }

    @Test
    fun `rejects Devanagari text in English mode but accepts it in Hinglish mode`() {
        val devanagari = "\u0939\u093F\u0928\u094D\u0926\u0940 \u0938\u0902\u0938\u094D\u0915\u0930\u0923"
        assertEquals(
            TranscriptSelection.None,
            selector.select(listOf(candidate(raw = devanagari, language = LanguageMode.ENGLISH))),
        )
        val hinglish = selector.select(listOf(candidate(raw = devanagari, language = LanguageMode.HINGLISH)))
        assertTrue(hinglish is TranscriptSelection.Raw)
        assertEquals(devanagari, hinglish.text)
    }

    @Test
    fun `rejects garbled text full of replacement characters`() {
        val garbled = "hello\uFFFD\uFFFD\uFFFDworld\uFFFD\uFFFD"
        assertEquals(TranscriptSelection.None, selector.select(listOf(candidate(raw = garbled))))
    }

    @Test
    fun `rejects control character dominated text`() {
        val controls = "a\u0000\u0001\u0002\u0003b"
        assertEquals(TranscriptSelection.None, selector.select(listOf(candidate(raw = controls))))
    }

    @Test
    fun `skips invalid candidates and selects the first usable one`() {
        val invalid = candidate(raw = ".")
        val result = selector.select(listOf(invalid, candidate(raw = "the real text")))
        assertTrue(result is TranscriptSelection.Raw)
        assertEquals("the real text", result.text)
    }

    // ------------------------------------------------------------------
    // Preservation (must NOT be falsely rejected)
    // ------------------------------------------------------------------

    @Test
    fun `preserves URLs`() {
        val urls = listOf(
            "Check https://example.com/path?q=1 for details.",
            "See www.whispertype.dev today.",
            "Visit domain com docs.",
        )
        for (u in urls) {
            val result = selector.select(listOf(candidate(raw = u)))
            assertTrue("should preserve URL text '$u'", result is TranscriptSelection.Raw)
            assertEquals(u, result.text)
        }
    }

    @Test
    fun `preserves emails`() {
        val email = "Please email me at user.name@example-host.co.uk today."
        val result = selector.select(listOf(candidate(raw = email)))
        assertTrue(result is TranscriptSelection.Raw)
        assertEquals(email, result.text)
    }

    @Test
    fun `preserves identifiers`() {
        val identifiers = listOf("AB-123", "product_id_7", "order ref 0x1F4A")
        for (id in identifiers) {
            val result = selector.select(listOf(candidate(raw = id)))
            assertTrue("should preserve identifier '$id'", result is TranscriptSelection.Raw)
        }
    }

    @Test
    fun `preserves numbers`() {
        val result = selector.select(listOf(candidate(raw = "42")))
        assertTrue(result is TranscriptSelection.Raw)
        assertEquals("42", result.text)
    }

    @Test
    fun `preserves short legitimate phrases`() {
        for (phrase in listOf("go", "hi", "ok", "yes", "done")) {
            val result = selector.select(listOf(candidate(raw = phrase)))
            assertTrue("should preserve short phrase '$phrase'", result is TranscriptSelection.Raw)
        }
    }

    @Test
    fun `preserves natural Latin-script Hinglish code-switching`() {
        val hinglish = "main kal aunga aur phir baat karenge"
        val result = selector.select(listOf(candidate(raw = hinglish, language = LanguageMode.HINGLISH)))
        assertTrue(result is TranscriptSelection.Raw)
        assertEquals(hinglish, result.text)
    }

    @Test
    fun `preserves sentences with normal punctuation`() {
        val sentence = "Hello, world! How are you doing today?"
        val result = selector.select(listOf(candidate(raw = sentence)))
        assertTrue(result is TranscriptSelection.Raw)
        assertEquals(sentence, result.text)
    }

    // ------------------------------------------------------------------
    // Regression: natural user speech openers must not be rejected
    // ------------------------------------------------------------------

    @Test
    fun `accepts a long natural English sentence starting with an opener`() {
        val sentence = "Okay so the quarterly review is scheduled for Thursday morning and I need everyone to bring their reports"
        val result = selector.select(listOf(candidate(raw = sentence)))
        assertTrue(result is TranscriptSelection.Raw)
        assertEquals(sentence, result.text)
    }

    @Test
    fun `accepts a small sentence starting with an opener`() {
        val result = selector.select(listOf(candidate(raw = "Yes I think so")))
        assertTrue(result is TranscriptSelection.Raw)
        assertEquals("Yes I think so", result.text)
    }

    // ------------------------------------------------------------------
    // Diagnose
    // ------------------------------------------------------------------

    @Test
    fun `diagnose returns null when a candidate is valid`() {
        assertNull(selector.diagnose(listOf(candidate(raw = "the quick brown fox"))))
    }

    @Test
    fun `diagnose returns BLANK for whitespace`() {
        val result = selector.diagnose(listOf(candidate(raw = "   ")))
        assertEquals(RejectionDiagnosis(RejectionRule.BLANK, 0, 3, false), result)
    }

    @Test
    fun `diagnose returns PUNCTUATION_ONLY for punctuation`() {
        val result = selector.diagnose(listOf(candidate(raw = "!!!")))
        assertEquals(RejectionDiagnosis(RejectionRule.PUNCTUATION_ONLY, 0, 3, false), result)
    }

    @Test
    fun `diagnose returns GARBLED for a replacement-character dominated string`() {
        val garbled = "hello\uFFFD\uFFFD\uFFFDworld\uFFFD\uFFFD"
        val result = selector.diagnose(listOf(candidate(raw = garbled)))
        assertEquals(RejectionDiagnosis(RejectionRule.GARBLED, 2, garbled.length, false), result)
    }

    @Test
    fun `diagnose returns DEVANAGARI for Devanagari text in English mode`() {
        val devanagari = "\u0939\u093F\u0928\u094D\u0926\u0940 \u0938\u0902\u0938\u094D\u0915\u0930\u0923"
        val result = selector.diagnose(listOf(candidate(raw = devanagari, language = LanguageMode.ENGLISH)))
        // wordCount is the number of letter/digit runs; Devanagari combining marks
        // (matras/virama) split runs, so the 14-char string yields 6 runs.
        assertEquals(RejectionDiagnosis(RejectionRule.DEVANAGARI, 6, devanagari.length, true), result)
    }

    @Test
    fun `diagnose returns null for Devanagari text in Hinglish mode`() {
        val devanagari = "\u0939\u093F\u0928\u094D\u0926\u0940 \u0938\u0902\u0938\u094D\u0915\u0930\u0923"
        assertNull(selector.diagnose(listOf(candidate(raw = devanagari, language = LanguageMode.HINGLISH))))
    }

    @Test
    fun `diagnose populates wordCount charCount and hasDevanagari`() {
        // Garbled text that also contains Devanagari: GARBLED outranks DEVANAGARI
        // in reporting priority, and the Devanagari flag still reflects the text.
        val text = "\u0939\u093F\uFFFD\uFFFD\uFFFD\u0938\u094D\uFFFD"
        val result = selector.diagnose(listOf(candidate(raw = text, language = LanguageMode.ENGLISH)))
        assertEquals(RejectionRule.GARBLED, result?.rule)
        assertEquals(2, result?.wordCount)
        assertEquals(text.length, result?.charCount)
        assertEquals(true, result?.hasDevanagari)
    }

    @Test
    fun `diagnose follows the same cleaned then raw order as select`() {
        // Cleaned fails but raw is usable: select returns Raw, diagnose null.
        val cleanedInvalid = candidate(raw = "hello world", cleaned = "!!!")
        assertTrue(selector.select(listOf(cleanedInvalid)) is TranscriptSelection.Raw)
        assertNull(selector.diagnose(listOf(cleanedInvalid)))

        // First cleaned fails but a later cleaned is usable: select returns
        // Cleaned, diagnose null.
        val first = candidate(raw = "!!!")
        val second = candidate(raw = "go", cleaned = "Go to the store and buy some milk please")
        assertTrue(selector.select(listOf(first, second)) is TranscriptSelection.Cleaned)
        assertNull(selector.diagnose(listOf(first, second)))

        // Nothing usable anywhere: diagnose reports the first pass-1 rejection.
        val a = candidate(raw = "!!", cleaned = "!!!")
        val b = candidate(raw = "")
        assertEquals(TranscriptSelection.None, selector.select(listOf(a, b)))
        assertEquals(RejectionRule.PUNCTUATION_ONLY, selector.diagnose(listOf(a, b))?.rule)
    }

    // ------------------------------------------------------------------
    // No-valid-candidate / immutability
    // ------------------------------------------------------------------

    @Test
    fun `returns None when there are no candidates`() {
        assertEquals(TranscriptSelection.None, selector.select(emptyList()))
    }

    @Test
    fun `returns None when every candidate is invalid`() {
        val allInvalid = listOf(
            candidate(raw = "."),
            candidate(raw = ""),
            candidate(raw = "!!!"),
            candidate(raw = "hello\uFFFD\uFFFD\uFFFDworld\uFFFD\uFFFD"),
        )
        assertEquals(TranscriptSelection.None, selector.select(allInvalid))
    }

    @Test
    fun `selection does not mutate the original candidate objects`() {
        val original = candidate(raw = "Hello, World!", cleaned = "Hello, World! This is final.")
        val rawBefore = original.raw
        val cleanedBefore = original.cleaned
        val languageBefore = original.language

        val result = selector.select(listOf(original))

        assertEquals(rawBefore, original.raw)
        assertEquals(cleanedBefore, original.cleaned)
        assertEquals(languageBefore, original.language)
        // The returned text is exactly the candidate's unchanged cleaned value.
        assertEquals(original.cleaned, result.text)
    }
}
