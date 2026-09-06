package com.whispertype.android.core.model

/**
 * Typed result of an insertion transaction. Never a bare Boolean: [Ambiguous]
 * means the commit status could not be confirmed and must surface an explicit
 * Copy fallback rather than a blind retry.
 */
sealed interface InsertionResult {
    data object Inserted : InsertionResult
    data class Failed(val failure: DictationFailure) : InsertionResult
    data object Ambiguous : InsertionResult
}

/**
 * 0.5.8: failure codes for which the settled transcript should be copied to the
 * clipboard instead of surfaced as an error. All three mean "the transcript
 * could not be committed to a focused field" — no field was focused, no live
 * input connection could be reached, or the field changed before insertion.
 *
 * Protected-field failures (`insert_target_not_safe`) are deliberately excluded
 * so a secure / uncertain field never leaks the transcript to the clipboard.
 */
val CLIPBOARD_FALLBACK_CODES: Set<String> = setOf(
    "insert_target_ineligible",
    "insert_connection_unavailable",
    "insert_target_stale",
)

fun DictationFailure.isClipboardFallback(): Boolean = code in CLIPBOARD_FALLBACK_CODES