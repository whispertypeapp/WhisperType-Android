package com.whispertype.android.platform.accessibility

/**
 * Pure surrounding-text verification for a single-shot, cursor-aware insertion
 * (Phase 4, §4.5 step 6-7). Kept framework-free so the exactly-once / ambiguous
 * contract is host-testable without an input connection.
 *
 * Returns:
 *  - `true`  — the editor's surrounding text changed AND now contains [committed];
 *  - `false` — a definite no-change;
 *  - `null`  — could not be read / verified (treat as [Ambiguous], never retry).
 */
object InsertionVerifier {

    fun confirmed(
        before: String?,
        after: String?,
        committed: String,
    ): Boolean? {
        if (before == null || after == null) return null
        if (after == before) return false
        // A full `contains` may fail when the IME truncates the surrounding-text
        // read for long commits; accept a confirmed change when the read ends with
        // the committed text's tail (robust to truncation and trailing whitespace).
        return after.contains(committed) ||
            after.trimEnd().endsWith(committed.trimEnd().takeLast(TAIL_MATCH_CHARS))
    }

    private const val TAIL_MATCH_CHARS = 64
}
