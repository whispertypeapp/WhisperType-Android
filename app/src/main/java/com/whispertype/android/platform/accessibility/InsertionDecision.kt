package com.whispertype.android.platform.accessibility

import com.whispertype.android.core.model.DictationFailure
import com.whispertype.android.core.model.InsertionResult

/**
 * Pure decision logic for an insertion transaction. Extracting the mapping
 * keeps the fail-closed, exactly-once insertion contract host-testable without
 * an [android.view.inputmethod.InputConnection] (see [InsertionDecisionTest]).
 */
object InsertionDecision {

    /** No live input connection could be reached for the target window. */
    fun connectionUnavailable(): DictationFailure = DictationFailure(
        code = "insert_connection_unavailable",
        message = "Could not reach the focused text field. Tap the field and try again.",
        recoverable = true,
    )

    /** The target window/editor changed between capture and insertion. */
    fun targetStale(): DictationFailure = DictationFailure(
        code = "insert_target_stale",
        message = "The focused field changed before insertion. Tap the field and try again.",
        recoverable = true,
    )

    /** The target turned out to be a protected / uncertain field. */
    fun targetNotSafe(): DictationFailure = DictationFailure(
        code = "insert_target_not_safe",
        message = "A protected field was focused; text was not inserted.",
        recoverable = true,
    )

    /** Commit status could not be confirmed; surface a Copy fallback, never retry. */
    fun ambiguous(): DictationFailure = DictationFailure(
        code = "insert_ambiguous",
        message = "Could not confirm the text was inserted. Use Copy to grab it.",
        recoverable = true,
        retryAllowed = false,
    )

    /**
     * Evaluates an insertion attempt into a typed [InsertionResult].
     *
     * @param connectionPresent whether a live input connection was reachable.
     * @param targetCurrent whether the live connection still matches the
     *                  captured target (package + window + generation).
     * @param targetSecureOrUncertain whether the captured target was a
     *                  protected / uncertain field.
     * @param commitAccepted the single-shot insertion action result (`true` when
     *                  accepted), or null when the commit was never attempted.
     */
    fun evaluate(
        connectionPresent: Boolean,
        targetCurrent: Boolean,
        targetSecureOrUncertain: Boolean,
        commitAccepted: Boolean?,
    ): InsertionResult {
        // Security wins over transport/currentness diagnostics so a protected
        // live field can never route dictated text to the clipboard fallback.
        if (targetSecureOrUncertain) return InsertionResult.Failed(targetNotSafe())
        if (!connectionPresent) return InsertionResult.Failed(connectionUnavailable())
        if (!targetCurrent) return InsertionResult.Failed(targetStale())

        return if (commitAccepted == true) {
            InsertionResult.Inserted
        } else {
            // false or null -> status unconfirmed; surface Copy fallback, never retry.
            InsertionResult.Ambiguous
        }
    }
}
