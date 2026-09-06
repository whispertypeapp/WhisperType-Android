package com.whispertype.android.core.model

/**
 * Immutable, aggregate-only session diagnostics.
 *
 * The legacy [startedAtMillis] and [endedAtMillis] names are retained for source
 * compatibility. They are milliseconds from the process/device monotonic clock,
 * not Unix epoch timestamps. New code should prefer the explicitly named
 * monotonic-nanosecond fields.
 */
data class DictationMetrics(
    val sessionId: SessionId,
    val startedAtMillis: Long,
    val endedAtMillis: Long? = null,
    /** Legacy transport aggregate; the unit is audio chunks/frames. */
    val chunkCount: Int = 0,
    /** Number of encoded audio payload bytes accepted for sending. */
    val bytesSent: Long = 0,
    /** Legacy transport aggregate; the unit is audio chunks/frames. */
    val droppedChunks: Int = 0,
    /** Legacy transport aggregate; the unit is rejected send attempts. */
    val sendRejected: Int = 0,
    /** Legacy producer-supplied finalization duration, in milliseconds. */
    val finalizeLatencyMillis: Long? = null,
    val tapToCaptureMs: Long? = null,
    val tapToSetupCompleteMs: Long? = null,
    val tapToFirstAudioQueuedMs: Long? = null,
    val firstAudioToFirstTranscriptMs: Long? = null,
    val stopToCaptureQuiescedMs: Long? = null,
    val stopToActivityEndQueuedMs: Long? = null,
    val stopToTurnCompleteMs: Long? = null,
    val stopToSettledMs: Long? = null,
    val stopToInsertionResultMs: Long? = null,
    val insertionRequestedToResultMs: Long? = null,
    val acceptedFrames: Long = 0,
    val rejectedFrames: Long = 0,
    val inputTranscriptionCount: Long = 0,
    val outputTranscriptionCount: Long = 0,
    val maxWebSocketQueueSize: Int = 0,
    val turnCompleteArrived: Boolean = false,
    val usedHardDeadline: Boolean = false,
    val audioBufferOverflow: Boolean = false,
    val capturedFrames: Long = 0,
    /** Peak OkHttp WebSocket queue size in bytes (not messages or frames). */
    val maxWebSocketQueueBytes: Long = maxWebSocketQueueSize.toLong(),
    /** Null means the warm-pool resolution was not recorded. */
    val warmSessionHit: Boolean? = null,
    /** Typed warm-lease claim result; contains no profile or credential data. */
    val warmClaimResult: WarmClaimResult? = warmSessionHit?.let {
        if (it) WarmClaimResult.HIT else WarmClaimResult.MISS
    },
    /** Age of the claimed lease at claim time, in milliseconds. */
    val warmClaimAgeMs: Long? = null,
    val tapToWarmClaimResolvedMs: Long? = null,
    val stopToFirstInputRevisionMs: Long? = null,
    val stopToLastInputRevisionMs: Long? = null,
    val firstInputToLastInputRevisionMs: Long? = null,
    val stopToLastTranscriptRevisionMs: Long? = null,
    val stopToFirstEchoMs: Long? = null,
    val stopToLastEchoMs: Long? = null,
    val stopToGenerationCompleteMs: Long? = null,
    val firstEchoToLastEchoMs: Long? = null,
    val activityEndQueuedToGenerationCompleteMs: Long? = null,
    val generationCompleteToSettledMs: Long? = null,
    val generationCompleteArrived: Boolean = false,
    /** Peak number of audio frames waiting for the sender. */
    val maxFrameQueueDepth: Int = 0,
    /** Peak age of a queued audio frame, in milliseconds. */
    val maxFrameQueueAgeMs: Long? = null,
    val settlementReason: SettlementReason? = null,
    val stopToQuietBarrierMs: Long? = null,
    val lastTranscriptRevisionToQuietBarrierMs: Long? = null,
    val quietBarrierToSettledMs: Long? = null,
    val settlePath: SettlePath? = null,
    /** A typed rejection-rule code only; never rejected text. */
    val lastRejection: String? = null,
    val usedLenientFallback: Boolean = false,
    val repairDurationMs: Long? = null,
    val targetReservationDurationMs: Long? = null,
    /** Null means reservation was not attempted or not recorded. */
    val targetReserved: Boolean? = null,
    val insertionRequestedToIpcReceivedMs: Long? = null,
    val insertionIpcReceivedToCommitStartedMs: Long? = null,
    val insertionCommitDurationMs: Long? = null,
    val insertionCommitCompletedToReplySentMs: Long? = null,
    val insertionReplySentToReceivedMs: Long? = null,
    val insertionIpcRoundTripMs: Long? = null,
    val terminalOutcome: TerminalOutcome? = null,
    val tapToTerminalMs: Long? = null,
    val startedAtMonotonicNanos: Long? = null,
    val endedAtMonotonicNanos: Long? = null,
    val warmClaimResolvedAtMonotonicNanos: Long? = null,
    val firstInputRevisionAtMonotonicNanos: Long? = null,
    val lastInputRevisionAtMonotonicNanos: Long? = null,
    val firstEchoRevisionAtMonotonicNanos: Long? = null,
    val lastEchoRevisionAtMonotonicNanos: Long? = null,
    val lastTranscriptRevisionAtMonotonicNanos: Long? = null,
    val generationCompleteAtMonotonicNanos: Long? = null,
    val quietBarrierSatisfiedAtMonotonicNanos: Long? = null,
    val repairStartedAtMonotonicNanos: Long? = null,
    val repairEndedAtMonotonicNanos: Long? = null,
    val insertionIpcReceivedAtMonotonicNanos: Long? = null,
    val insertionCommitStartedAtMonotonicNanos: Long? = null,
    val insertionCommitCompletedAtMonotonicNanos: Long? = null,
    val insertionReplySentAtMonotonicNanos: Long? = null,
    val insertionReplyReceivedAtMonotonicNanos: Long? = null,
    val terminalAtMonotonicNanos: Long? = null,
) {
    /** Explicit phase-name aliases for legacy fields. */
    val tapToCaptureStartedMs: Long? get() = tapToCaptureMs
    val tapToGeminiSetupCompleteMs: Long? get() = tapToSetupCompleteMs
    val tapToFirstAudioFrameQueuedMs: Long? get() = tapToFirstAudioQueuedMs
    val firstAudioFrameQueuedToFirstInputTranscriptMs: Long?
        get() = firstAudioToFirstTranscriptMs
    val stopToTranscriptSettledMs: Long? get() = stopToSettledMs
    val insertionRequestToResultMs: Long? get() = insertionRequestedToResultMs

    /** Explicit counter-name aliases for legacy fields. */
    val inputTranscriptMessageCount: Long get() = inputTranscriptionCount
    val echoTranscriptMessageCount: Long get() = outputTranscriptionCount

    /** Explicit unit/name aliases for integration code. */
    val maxFrameQueueDepthFrames: Int get() = maxFrameQueueDepth
    val warmSessionAgeMs: Long? get() = warmClaimAgeMs
    val firstInputRevisionToLastInputRevisionMs: Long?
        get() = firstInputToLastInputRevisionMs
    val stopToFirstEchoRevisionMs: Long? get() = stopToFirstEchoMs
    val stopToLastEchoRevisionMs: Long? get() = stopToLastEchoMs
    val firstEchoRevisionToLastEchoRevisionMs: Long? get() = firstEchoToLastEchoMs
}