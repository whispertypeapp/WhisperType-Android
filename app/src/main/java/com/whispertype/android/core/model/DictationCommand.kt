package com.whispertype.android.core.model

/**
 * Commands fed to the core reducer. Every session-scoped command carries the
 * canonical [SessionId]; the reducer rejects commands from any other session.
 * A terminal result ([InsertOutcome]) is consumed at most once.
 */
sealed interface DictationCommand {
    data class Start(val target: TargetSnapshot) : DictationCommand
    data class Stop(val sessionId: SessionId) : DictationCommand
    data class Cancel(val sessionId: SessionId, val reason: CancelReason) : DictationCommand
    data class AmplitudeTick(val sessionId: SessionId, val amplitude: Float) : DictationCommand
    data class TranscriptCandidates(
        val sessionId: SessionId,
        val candidates: List<ResultCandidate>,
    ) : DictationCommand

    data class InsertOutcome(
        val sessionId: SessionId,
        val outcome: InsertionResult,
    ) : DictationCommand

    data object DismissCopy : DictationCommand
    data object DismissError : DictationCommand
}