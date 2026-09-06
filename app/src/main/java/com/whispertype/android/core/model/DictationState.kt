package com.whispertype.android.core.model

/**
 * Session state machine: Unavailable -> Idle -> Starting -> Listening
 * -> Finalizing -> Inserting -> Success, with Cancelled / Error / CopyAvailable
 * branches. All id-bearing states carry the canonical [SessionId].
 *
 * DictationState is intentionally immutable and small; it is rendered by the
 * overlay and driven by the core reducer.
 */
sealed interface DictationState {

    /** No dictation possible; overlay hidden. */
    data object Unavailable : DictationState

    /** Eligible and ready; the idle bubble may show. */
    data object Idle : DictationState

    data class Starting(val sessionId: SessionId, val target: TargetSnapshot) : DictationState

    data class Listening(
        val sessionId: SessionId,
        val amplitude: Float? = null,
        val elapsedMillis: Long = 0L,
        /** True while recording starts immediately but the Live session is still
         *  connecting (cold path with a bounded pre-ready buffer). */
        val connecting: Boolean = false,
    ) : DictationState

    data class Finalizing(val sessionId: SessionId) : DictationState

    data class Inserting(val sessionId: SessionId) : DictationState

    data class Success(val sessionId: SessionId) : DictationState

    data class Cancelled(val sessionId: SessionId, val reason: CancelReason) : DictationState

    data class CopyAvailable(val sessionId: SessionId, val candidate: ResultCandidate) : DictationState

    /** 0.5.8: the transcript was copied to the clipboard because it could not be
     *  committed to a focused field. */
    data class CopiedToClipboard(val sessionId: SessionId) : DictationState

    data class Error(val sessionId: SessionId, val failure: DictationFailure) : DictationState
}