package com.whispertype.android.core.transcript

import com.whispertype.android.core.model.LanguageMode
import com.whispertype.android.core.model.ResultCandidate

/**
 * Pure, stateless transcript candidate selector/validator implementing PRD FR-7.
 *
 * Selection order (FR-7):
 *  1. The first candidate whose *cleaned* text passes validation.
 *  2. If none, the first candidate whose *raw* text passes validation.
 *  3. Otherwise [TranscriptSelection.None] so the caller can produce a Copy
 *     fallback or an actionable failure. No invalid text is ever returned.
 *
 * Determinism & ordering: a single left-to-right pass finds the first usable
 * cleaned candidate; only if no candidate has usable cleaned text do we search
 * for the first usable raw candidate. `cleaned` therefore wins over `raw` and,
 * within a tier, the earliest candidate wins.
 *
 * Immutability & purity: this class never mutates the input [ResultCandidate]s.
 * It only reads their `raw`/`cleaned`/`language` and returns a fresh sealed
 * value. It performs no I/O and has no state.
 *
 * Trust model: the dictation source is the server's ASR transcription of the
 * user's OWN speech (`inputTranscription`) — never model output — so this
 * selector trusts user speech and only rejects content that can never be usable
 * dictation:
 *  - blank / whitespace-only
 *  - punctuation-only (nothing letter-or-digit)
 *  - garbled / corrupted text (high ratio of replacement or control chars)
 *  - Devanagari script in English mode (Hinglish mode ACCEPTS Devanagari: the
 *    runtime instructs the Live model to emit Latin script, and when the model
 *    still returns Devanagari for Hinglish we prefer inserting it over erroring)
 * plus, for cleaned candidates only, an implausible-expansion guard (cleaned
 * word count far exceeding raw). The former model-preamble, greeting,
 * acknowledgment and pathological-repetition rejections were REMOVED because
 * the source is the user's own speech, where such phrasing is legitimate.
 *
 * False-positive protection: URLs, emails, identifiers, numbers, normal
 * sentence punctuation, short phrases and natural Latin-script code-switching
 * are preserved because validation only rejects the specific patterns above.
 *
 * [diagnose] mirrors [select] to report WHY a session produced no candidate; it
 * is for logging only and never includes transcript text.
 */
class TranscriptSelector {

    /** Returns the deterministic selection for [candidates] (first valid wins). */
    fun select(candidates: List<ResultCandidate>): TranscriptSelection {
        // Pass 1: first candidate whose cleaned text is usable.
        for (c in candidates) {
            val cleaned = c.cleaned
            if (cleaned != null && reject(cleaned, c.language) == null && !isImplausiblyExpanded(c)) {
                return TranscriptSelection.Cleaned(c, cleaned)
            }
        }
        // Pass 2: first candidate whose raw text is usable (cleaned was absent or invalid).
        for (c in candidates) {
            if (reject(c.raw, c.language) == null) {
                return TranscriptSelection.Raw(c, c.raw)
            }
        }
        return TranscriptSelection.None
    }

    /**
     * Returns the first rejection rule that would make [select] return
     * [TranscriptSelection.None], or null when [select] would succeed. Mirrors
     * [select]'s two-pass order (cleaned then raw): the first rejection
     * encountered in that order wins. For logging only; never includes
     * transcript text.
     */
    fun diagnose(candidates: List<ResultCandidate>): RejectionDiagnosis? {
        var firstFailure: RejectionDiagnosis? = null
        // Pass 1 (cleaned tier) — same order as [select].
        for (c in candidates) {
            val cleaned = c.cleaned
            if (cleaned != null) {
                val failure = reject(cleaned, c.language)
                if (failure == null && !isImplausiblyExpanded(c)) return null
                if (firstFailure == null) firstFailure = failure
            }
        }
        // Pass 2 (raw tier) — same order as [select].
        for (c in candidates) {
            val failure = reject(c.raw, c.language)
            if (failure == null) return null
            if (firstFailure == null) firstFailure = failure
        }
        return firstFailure
    }

    /**
     * The first rejection rule that fires on [text] for [language], or null when
     * [text] is usable dictation. Priority order: [RejectionRule.BLANK],
     * [RejectionRule.PUNCTUATION_ONLY], [RejectionRule.GARBLED],
     * [RejectionRule.DEVANAGARI].
     */
    private fun reject(text: String, language: LanguageMode): RejectionDiagnosis? {
        val wordCount = TranscriptCompleteness.contentWords(text).size
        val charCount = text.length
        val hasDevanagari = containsDevanagari(text)
        if (text.isBlank()) {
            return RejectionDiagnosis(RejectionRule.BLANK, wordCount, charCount, hasDevanagari)
        }
        if (!text.any { it.isLetterOrDigit() }) {
            return RejectionDiagnosis(RejectionRule.PUNCTUATION_ONLY, wordCount, charCount, hasDevanagari)
        }
        if (isGarbled(text)) {
            return RejectionDiagnosis(RejectionRule.GARBLED, wordCount, charCount, hasDevanagari)
        }
        if (language == LanguageMode.ENGLISH && hasDevanagari) {
            return RejectionDiagnosis(RejectionRule.DEVANAGARI, wordCount, charCount, hasDevanagari)
        }
        return null
    }

    /**
     * True when the candidate's cleaned text is implausibly longer than its raw
     * text (word-count ratio beyond [MAX_EXPANSION_RATIO]), a strong signal of a
     * fabricated expansion. Only meaningful when the raw text has real words;
     * otherwise there is nothing to expand from, so the check is skipped. This is
     * the one remaining model-output heuristic: real user speech is never ~10x
     * shorter than its usable text.
     */
    private fun isImplausiblyExpanded(c: ResultCandidate): Boolean {
        val cleaned = c.cleaned ?: return false
        val rawWords = TranscriptCompleteness.contentWords(c.raw).size
        if (rawWords == 0) return false
        val cleanedWords = TranscriptCompleteness.contentWords(cleaned).size
        return cleanedWords > rawWords &&
            cleanedWords > MAX_EXPANSION_RATIO * rawWords
    }

    /**
     * True when [text] is dominated by replacement ("\uFFFD") or control
     * characters, i.e. the transcription is corrupted/garbled.
     */
    private fun isGarbled(text: String): Boolean {
        if (text.isEmpty()) return false
        val problematic = text.count { it == REPLACEMENT_CHAR || it.isISOControl() }
        return problematic > 0 && problematic.toDouble() / text.length > GARBLED_CHAR_RATIO
    }

    private fun containsDevanagari(text: String): Boolean = text.any { isDevanagari(it) }

    private fun isDevanagari(ch: Char): Boolean =
        ch in DEVANAGARI_BLOCK ||
            ch in DEVANAGARI_EXTENDED_A_BLOCK ||
            ch in VEDIC_EXTENSIONS_BLOCK

    // ------------------------------------------------------------------
    // Documented thresholds (FR-7 heuristics).
    // ------------------------------------------------------------------

    private companion object {
        /** Cleaned/raw word-count ratio above which expansion is deemed implausible. */
        const val MAX_EXPANSION_RATIO: Double = 10.0

        /** Fraction of replacement/control characters that flags a garbled result. */
        const val GARBLED_CHAR_RATIO: Double = 0.2

        val REPLACEMENT_CHAR: Char = '\uFFFD'

        val DEVANAGARI_BLOCK: CharRange = '\u0900'..'\u097F'
        val DEVANAGARI_EXTENDED_A_BLOCK: CharRange = '\uA8E0'..'\uA8FF'
        val VEDIC_EXTENSIONS_BLOCK: CharRange = '\u1CD0'..'\u1CFF'
    }
}

/**
 * Typed, deterministic outcome of [TranscriptSelector.select].
 *
 *  - [Cleaned]: the selected text is the candidate's cleaned transcript.
 *  - [Raw]: the selected text is the candidate's raw transcript (no usable
 *    cleaned transcript existed).
 *  - [None]: no candidate passed validation; the caller must NOT insert or copy
 *    anything, and should instead offer a Copy fallback or an actionable failure.
 *
 * [text] is the exact, already-validated string to insert/copy — never invalid.
 */
sealed interface TranscriptSelection {
    /** The [ResultCandidate] that was selected (never mutated by the selector). */
    val candidate: ResultCandidate

    /** The exact validated text to insert/copy. */
    val text: String

    /** A candidate whose cleaned transcript passed validation. */
    data class Cleaned(override val candidate: ResultCandidate, override val text: String) : TranscriptSelection

    /** A candidate for which only the raw transcript passed validation. */
    data class Raw(override val candidate: ResultCandidate, override val text: String) : TranscriptSelection

    /**
     * No valid candidate was found; do not insert or copy. [candidate] and
     * [text] are intentionally undefined here and throw, so it is impossible to
     * ever obtain text from a [None] selection — the caller must instead branch
     * on it and produce a Copy fallback or an actionable failure.
     */
    data object None : TranscriptSelection {
        override val candidate: ResultCandidate
            get() = throw UnsupportedOperationException("TranscriptSelection.None carries no candidate")

        override val text: String
            get() = throw UnsupportedOperationException("TranscriptSelection.None carries no text")
    }
}

/**
 * Which rejection rule invalidated a transcript candidate. Used by
 * [TranscriptSelector.diagnose] to log WHY a session produced no selection.
 */
enum class RejectionRule { BLANK, PUNCTUATION_ONLY, GARBLED, DEVANAGARI }

/**
 * Diagnostics for a rejected candidate: the first [RejectionRule] that fired
 * plus basic metrics about the offending text. Contains no transcript text.
 */
data class RejectionDiagnosis(
    val rule: RejectionRule,
    val wordCount: Int,
    val charCount: Int,
    val hasDevanagari: Boolean,
)
