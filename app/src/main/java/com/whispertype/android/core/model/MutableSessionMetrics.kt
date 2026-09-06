package com.whispertype.android.core.model

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicReferenceArray

/**
 * Session-local monotonic timing and aggregate counters for dictation diagnostics.
 *
 * Every `*At` property is a monotonic timestamp in nanoseconds, never a wall-clock
 * or epoch value. The default uses the framework-free JVM monotonic clock; tests
 * can inject a deterministic clock. First marks, counter methods, and high-water
 * recorders are safe when transport, audio, runtime, and IPC callbacks race.
 *
 * Legacy mutable properties remain available for source compatibility. Concurrent
 * producers must use the atomic `increment*` / `record*` methods rather than a
 * read-modify-write expression such as `capturedFrames++`.
 */
class MutableSessionMetrics(
    val sessionId: SessionId,
    private val nowNanos: () -> Long = { System.nanoTime() },
) {

    enum class Event {
        Tap,
        KeyLoadStarted,
        KeyLoaded,
        SettingsReady,
        SocketCreated,
        SocketOpen,
        SetupComplete,
        CaptureStartRequested,
        CaptureStarted,
        ActivityStartQueued,
        FirstAudioQueued,
        FirstInputTranscript,
        LastInputTranscript,
        Stop,
        CaptureQuiesced,
        LastAudioQueued,
        ActivityEndQueued,
        TurnComplete,
        TranscriptSettled,
        InsertionRequested,
        InsertionResult,
        CopiedToClipboard,
        WarmSessionResolved,
        FirstEcho,
        LastEcho,
        GenerationComplete,
        RepairStarted,
        RepairCompleted,
        TargetReservationStarted,
        TargetReservationCompleted,
        InsertionIpcReceived,
        InsertionCommitStarted,
        InsertionCommitCompleted,
        InsertionReplySent,
        InsertionReplyReceived,
        QuietBarrierSatisfied,
        Terminal,
    }

    /**
     * A reference wrapper keeps CAS identity stable. Using boxed [Long] values
     * directly is incorrect because unboxing/reboxing can change the expected
     * reference even when the numeric timestamp is unchanged.
     */
    private class Timestamp(val nanos: Long)

    private val timestamps = AtomicReferenceArray<Timestamp?>(Event.entries.size)

    var tapAt: Long?
        get() = timestamp(Event.Tap)
        set(value) = setTimestamp(Event.Tap, value)
    var keyLoadStartedAt: Long?
        get() = timestamp(Event.KeyLoadStarted)
        set(value) = setTimestamp(Event.KeyLoadStarted, value)
    var keyLoadedAt: Long?
        get() = timestamp(Event.KeyLoaded)
        set(value) = setTimestamp(Event.KeyLoaded, value)
    var settingsReadyAt: Long?
        get() = timestamp(Event.SettingsReady)
        set(value) = setTimestamp(Event.SettingsReady, value)
    var socketCreatedAt: Long?
        get() = timestamp(Event.SocketCreated)
        set(value) = setTimestamp(Event.SocketCreated, value)
    var socketOpenAt: Long?
        get() = timestamp(Event.SocketOpen)
        set(value) = setTimestamp(Event.SocketOpen, value)
    var setupCompleteAt: Long?
        get() = timestamp(Event.SetupComplete)
        set(value) = setTimestamp(Event.SetupComplete, value)
    var captureStartRequestedAt: Long?
        get() = timestamp(Event.CaptureStartRequested)
        set(value) = setTimestamp(Event.CaptureStartRequested, value)
    var captureStartedAt: Long?
        get() = timestamp(Event.CaptureStarted)
        set(value) = setTimestamp(Event.CaptureStarted, value)
    var activityStartQueuedAt: Long?
        get() = timestamp(Event.ActivityStartQueued)
        set(value) = setTimestamp(Event.ActivityStartQueued, value)
    var firstAudioQueuedAt: Long?
        get() = timestamp(Event.FirstAudioQueued)
        set(value) = setTimestamp(Event.FirstAudioQueued, value)
    var firstInputTranscriptAt: Long?
        get() = timestamp(Event.FirstInputTranscript)
        set(value) = setTimestamp(Event.FirstInputTranscript, value)
    var lastInputTranscriptAt: Long?
        get() = timestamp(Event.LastInputTranscript)
        set(value) = setTimestamp(Event.LastInputTranscript, value)
    var stopAt: Long?
        get() = timestamp(Event.Stop)
        set(value) = setTimestamp(Event.Stop, value)
    var captureQuiescedAt: Long?
        get() = timestamp(Event.CaptureQuiesced)
        set(value) = setTimestamp(Event.CaptureQuiesced, value)
    var lastAudioQueuedAt: Long?
        get() = timestamp(Event.LastAudioQueued)
        set(value) = setTimestamp(Event.LastAudioQueued, value)
    var activityEndQueuedAt: Long?
        get() = timestamp(Event.ActivityEndQueued)
        set(value) = setTimestamp(Event.ActivityEndQueued, value)
    var turnCompleteAt: Long?
        get() = timestamp(Event.TurnComplete)
        set(value) = setTimestamp(Event.TurnComplete, value)
    var transcriptSettledAt: Long?
        get() = timestamp(Event.TranscriptSettled)
        set(value) = setTimestamp(Event.TranscriptSettled, value)
    var insertionRequestedAt: Long?
        get() = timestamp(Event.InsertionRequested)
        set(value) = setTimestamp(Event.InsertionRequested, value)
    var insertionResultAt: Long?
        get() = timestamp(Event.InsertionResult)
        set(value) = setTimestamp(Event.InsertionResult, value)
    var copiedToClipboardAt: Long?
        get() = timestamp(Event.CopiedToClipboard)
        set(value) = setTimestamp(Event.CopiedToClipboard, value)
    var warmSessionResolvedAt: Long?
        get() = timestamp(Event.WarmSessionResolved)
        set(value) = setTimestamp(Event.WarmSessionResolved, value)
    var firstEchoAt: Long?
        get() = timestamp(Event.FirstEcho)
        set(value) = setTimestamp(Event.FirstEcho, value)
    var lastEchoAt: Long?
        get() = timestamp(Event.LastEcho)
        set(value) = setTimestamp(Event.LastEcho, value)
    var generationCompleteAt: Long?
        get() = timestamp(Event.GenerationComplete)
        set(value) = setTimestamp(Event.GenerationComplete, value)
    var repairStartedAt: Long?
        get() = timestamp(Event.RepairStarted)
        set(value) = setTimestamp(Event.RepairStarted, value)
    var repairCompletedAt: Long?
        get() = timestamp(Event.RepairCompleted)
        set(value) = setTimestamp(Event.RepairCompleted, value)
    var targetReservationStartedAt: Long?
        get() = timestamp(Event.TargetReservationStarted)
        set(value) = setTimestamp(Event.TargetReservationStarted, value)
    var targetReservationCompletedAt: Long?
        get() = timestamp(Event.TargetReservationCompleted)
        set(value) = setTimestamp(Event.TargetReservationCompleted, value)
    var insertionIpcReceivedAt: Long?
        get() = timestamp(Event.InsertionIpcReceived)
        set(value) = setTimestamp(Event.InsertionIpcReceived, value)
    var insertionCommitStartedAt: Long?
        get() = timestamp(Event.InsertionCommitStarted)
        set(value) = setTimestamp(Event.InsertionCommitStarted, value)
    var insertionCommitCompletedAt: Long?
        get() = timestamp(Event.InsertionCommitCompleted)
        set(value) = setTimestamp(Event.InsertionCommitCompleted, value)
    var insertionReplySentAt: Long?
        get() = timestamp(Event.InsertionReplySent)
        set(value) = setTimestamp(Event.InsertionReplySent, value)
    var insertionReplyReceivedAt: Long?
        get() = timestamp(Event.InsertionReplyReceived)
        set(value) = setTimestamp(Event.InsertionReplyReceived, value)
    var quietBarrierSatisfiedAt: Long?
        get() = timestamp(Event.QuietBarrierSatisfied)
        set(value) = setTimestamp(Event.QuietBarrierSatisfied, value)
    var terminalAt: Long?
        get() = timestamp(Event.Terminal)
        set(value) = setTimestamp(Event.Terminal, value)

    /** Explicit revision/phase aliases retained alongside legacy names. */
    var firstInputRevisionAt: Long?
        get() = firstInputTranscriptAt
        set(value) {
            firstInputTranscriptAt = value
        }
    var lastInputRevisionAt: Long?
        get() = lastInputTranscriptAt
        set(value) {
            lastInputTranscriptAt = value
        }
    var firstEchoRevisionAt: Long?
        get() = firstEchoAt
        set(value) {
            firstEchoAt = value
        }
    var lastEchoRevisionAt: Long?
        get() = lastEchoAt
        set(value) {
            lastEchoAt = value
        }
    var warmClaimResolvedAt: Long?
        get() = warmSessionResolvedAt
        set(value) {
            warmSessionResolvedAt = value
        }
    var repairEndedAt: Long?
        get() = repairCompletedAt
        set(value) {
            repairCompletedAt = value
        }

    private val capturedFramesValue = AtomicLong()
    private val acceptedFramesValue = AtomicLong()
    private val rejectedFramesValue = AtomicLong()
    private val inputTranscriptionCountValue = AtomicLong()
    private val outputTranscriptionCountValue = AtomicLong()
    private val maxWebSocketQueueBytesValue = AtomicLong()
    private val maxFrameQueueDepthValue = AtomicInteger()
    private val maxFrameQueueAgeNanosValue = AtomicLong(UNRECORDED_AGE_NANOS)

    var capturedFrames: Long
        get() = capturedFramesValue.get()
        set(value) = capturedFramesValue.set(value)
    var acceptedFrames: Long
        get() = acceptedFramesValue.get()
        set(value) = acceptedFramesValue.set(value)
    var rejectedFrames: Long
        get() = rejectedFramesValue.get()
        set(value) = rejectedFramesValue.set(value)
    var inputTranscriptionCount: Long
        get() = inputTranscriptionCountValue.get()
        set(value) = inputTranscriptionCountValue.set(value)
    var outputTranscriptionCount: Long
        get() = outputTranscriptionCountValue.get()
        set(value) = outputTranscriptionCountValue.set(value)

    /**
     * Legacy alias for the WebSocket byte high-water mark. Values above [Int.MAX_VALUE]
     * saturate through this getter; new code should use [maxWebSocketQueueBytes].
     */
    var maxWebSocketQueueSize: Int
        get() = maxWebSocketQueueBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        set(value) {
            require(value >= 0) { "WebSocket queue bytes must be non-negative" }
            maxWebSocketQueueBytesValue.set(value.toLong())
        }

    /** Peak OkHttp WebSocket queue size in bytes, not messages or frames. */
    var maxWebSocketQueueBytes: Long
        get() = maxWebSocketQueueBytesValue.get()
        set(value) {
            require(value >= 0L) { "WebSocket queue bytes must be non-negative" }
            maxWebSocketQueueBytesValue.set(value)
        }

    /** Peak number of audio frames waiting for the sender. */
    var maxFrameQueueDepth: Int
        get() = maxFrameQueueDepthValue.get()
        set(value) {
            require(value >= 0) { "Frame queue depth must be non-negative" }
            maxFrameQueueDepthValue.set(value)
        }

    /** Peak queued-frame age in monotonic nanoseconds; null until sampled. */
    var maxFrameQueueAgeNanos: Long?
        get() = maxFrameQueueAgeNanosValue.get().takeIf { it != UNRECORDED_AGE_NANOS }
        set(value) {
            require(value == null || value >= 0L) { "Frame queue age must be non-negative" }
            maxFrameQueueAgeNanosValue.set(value ?: UNRECORDED_AGE_NANOS)
        }

    private val turnCompleteArrivedValue = AtomicBoolean()
    private val generationCompleteArrivedValue = AtomicBoolean()
    private val usedHardDeadlineValue = AtomicBoolean()
    private val audioBufferOverflowValue = AtomicBoolean()
    private val usedLenientFallbackValue = AtomicBoolean()

    var turnCompleteArrived: Boolean
        get() = turnCompleteArrivedValue.get()
        set(value) = turnCompleteArrivedValue.set(value)
    var generationCompleteArrived: Boolean
        get() = generationCompleteArrivedValue.get()
        set(value) = generationCompleteArrivedValue.set(value)
    var usedHardDeadline: Boolean
        get() = usedHardDeadlineValue.get()
        set(value) = usedHardDeadlineValue.set(value)
    var audioBufferOverflow: Boolean
        get() = audioBufferOverflowValue.get()
        set(value) = audioBufferOverflowValue.set(value)

    /** First selector rejection rule that blocked a candidate, when selection failed. */
    private val lastRejectionValue = AtomicReference<String?>(null)
    var lastRejection: String?
        get() = lastRejectionValue.get()
        set(value) = lastRejectionValue.set(value?.let(::safeDiagnosticCode))

    /** True when a rejected candidate was still inserted via the lenient fallback. */
    var usedLenientFallback: Boolean
        get() = usedLenientFallbackValue.get()
        set(value) = usedLenientFallbackValue.set(value)

    /** 0.4.2 reliability: which source the settled transcript came from. */
    private val settlePathValue = AtomicReference<SettlePath?>(null)
    var settlePath: SettlePath?
        get() = settlePathValue.get()
        set(value) = settlePathValue.set(value)

    private val warmClaimResultValue = AtomicReference<WarmClaimResult?>(null)
    private val warmClaimAgeNanosValue = AtomicLong(UNRECORDED_AGE_NANOS)
    /** Tri-state integer avoids identity-sensitive boxed-Boolean atomics. */
    private val targetReservedValue = AtomicInteger(BOOLEAN_UNRECORDED)
    private val settlementReasonValue = AtomicReference<SettlementReason?>(null)
    private val terminalOutcomeValue = AtomicReference<TerminalOutcome?>(null)

    /** Null until warm-pool resolution is recorded; true is a hit, false a miss. */
    var warmSessionHit: Boolean?
        get() = warmClaimResultValue.get()?.isHit
        set(value) {
            warmClaimResultValue.set(
                value?.let { if (it) WarmClaimResult.HIT else WarmClaimResult.MISS },
            )
        }

    /** Typed claim result; first-wins recorders should use [recordWarmClaim]. */
    var warmClaimResult: WarmClaimResult?
        get() = warmClaimResultValue.get()
        set(value) = warmClaimResultValue.set(value)

    /** Age of a warm lease at claim time, in monotonic nanoseconds. */
    var warmClaimAgeNanos: Long?
        get() = warmClaimAgeNanosValue.get().takeIf { it != UNRECORDED_AGE_NANOS }
        set(value) {
            require(value == null || value >= 0L) { "Warm claim age must be non-negative" }
            warmClaimAgeNanosValue.set(value ?: UNRECORDED_AGE_NANOS)
        }

    /** Null until target reservation completes. */
    var targetReserved: Boolean?
        get() = targetReservedValue.get().toNullableBoolean()
        set(value) = targetReservedValue.set(value.toAtomicBoolean())

    var settlementReason: SettlementReason?
        get() = settlementReasonValue.get()
        set(value) = settlementReasonValue.set(value)

    var terminalOutcome: TerminalOutcome?
        get() = terminalOutcomeValue.get()
        set(value) = terminalOutcomeValue.set(value)

    /** Records the first timestamp observed for [event]; later marks are ignored. */
    fun mark(event: Event) {
        mark(event, nowNanos())
    }

    /**
     * Imports a timestamp from another callback or process. The caller must use
     * the same monotonic clock domain as this instance (the default is [System.nanoTime]).
     */
    fun mark(event: Event, atNanos: Long) {
        markIfFirst(event, atNanos)
    }

    /** Atomically records [event], returning true only for the winning first mark. */
    fun markIfFirst(event: Event, atNanos: Long): Boolean {
        val recorded = timestamps.compareAndSet(event.ordinal, null, Timestamp(atNanos))
        when (event) {
            Event.FirstInputTranscript -> recordLatest(Event.LastInputTranscript, atNanos)
            Event.FirstEcho -> recordLatest(Event.LastEcho, atNanos)
            else -> Unit
        }
        when (event) {
            Event.TurnComplete -> turnCompleteArrivedValue.set(true)
            Event.GenerationComplete -> generationCompleteArrivedValue.set(true)
            else -> Unit
        }
        return recorded
    }

    fun incrementCapturedFrames(): Long = capturedFramesValue.incrementAndGet()

    fun addCapturedFrames(count: Long): Long = addCounter(capturedFramesValue, count)

    fun incrementAcceptedFrames(): Long = acceptedFramesValue.incrementAndGet()

    fun addAcceptedFrames(count: Long): Long = addCounter(acceptedFramesValue, count)

    fun incrementRejectedFrames(): Long = rejectedFramesValue.incrementAndGet()

    fun addRejectedFrames(count: Long): Long = addCounter(rejectedFramesValue, count)

    fun incrementInputTranscriptionCount(): Long = inputTranscriptionCountValue.incrementAndGet()

    fun incrementOutputTranscriptionCount(): Long = outputTranscriptionCountValue.incrementAndGet()

    /** Counts one input revision and retains its chronological first/last times. */
    fun recordInputTranscription(): Long = recordInputTranscription(nowNanos())

    fun recordInputTranscription(atNanos: Long): Long {
        val count = inputTranscriptionCountValue.incrementAndGet()
        recordEarliest(Event.FirstInputTranscript, atNanos)
        recordLatest(Event.LastInputTranscript, atNanos)
        return count
    }

    fun recordInputRevision(): Long = recordInputTranscription()

    fun recordInputRevision(atNanos: Long): Long = recordInputTranscription(atNanos)

    /**
     * Counts one non-empty echo message and updates the chronological first/last
     * echo marks. Min/max timestamp updates remain correct if callbacks race.
     */
    fun recordEcho(): Long = recordEcho(nowNanos())

    fun recordEcho(atNanos: Long): Long {
        val count = outputTranscriptionCountValue.incrementAndGet()
        recordEarliest(Event.FirstEcho, atNanos)
        recordLatest(Event.LastEcho, atNanos)
        return count
    }

    fun recordEchoRevision(): Long = recordEcho()

    fun recordEchoRevision(atNanos: Long): Long = recordEcho(atNanos)

    fun recordTurnComplete() {
        recordTurnComplete(nowNanos())
    }

    fun recordTurnComplete(atNanos: Long) {
        turnCompleteArrivedValue.set(true)
        recordEarliest(Event.TurnComplete, atNanos)
    }

    fun recordGenerationComplete() {
        recordGenerationComplete(nowNanos())
    }

    fun recordGenerationComplete(atNanos: Long) {
        generationCompleteArrivedValue.set(true)
        recordEarliest(Event.GenerationComplete, atNanos)
    }

    /**
     * Records a typed warm-lease claim result and optional lease age. The first
     * decision wins, preventing racing hit/miss reports.
     */
    fun recordWarmClaim(
        result: WarmClaimResult,
        ageNanos: Long? = null,
    ): Boolean = recordWarmClaim(result, ageNanos, nowNanos())

    fun recordWarmClaim(
        result: WarmClaimResult,
        ageNanos: Long?,
        atNanos: Long,
    ): Boolean {
        require(ageNanos == null || ageNanos >= 0L) { "Warm claim age must be non-negative" }
        if (!warmClaimResultValue.compareAndSet(null, result)) return false
        warmClaimAgeNanosValue.set(ageNanos ?: UNRECORDED_AGE_NANOS)
        markIfFirst(Event.WarmSessionResolved, atNanos)
        return true
    }

    /** First warm-pool decision wins, preventing conflicting hit/miss reports. */
    fun recordWarmSessionResult(hit: Boolean): Boolean =
        recordWarmSessionResult(hit, nowNanos())

    fun recordWarmSessionResult(hit: Boolean, atNanos: Long): Boolean =
        recordWarmClaim(
            result = if (hit) WarmClaimResult.HIT else WarmClaimResult.MISS,
            ageNanos = null,
            atNanos = atNanos,
        )

    /** Readable alias for callers that naturally model the result as a hit flag. */
    fun recordWarmSessionHit(hit: Boolean): Boolean = recordWarmSessionResult(hit)

    fun recordWarmSessionHit(hit: Boolean, atNanos: Long): Boolean =
        recordWarmSessionResult(hit, atNanos)

    fun recordTargetReservationResult(reserved: Boolean): Boolean =
        recordTargetReservationResult(reserved, nowNanos())

    fun recordTargetReservationResult(reserved: Boolean, atNanos: Long): Boolean {
        if (!targetReservedValue.compareAndSet(BOOLEAN_UNRECORDED, reserved.toAtomicBoolean())) {
            return false
        }
        markIfFirst(Event.TargetReservationCompleted, atNanos)
        return true
    }

    /** First settlement trigger wins; the source selection remains [settlePath]. */
    fun recordSettlementReason(reason: SettlementReason): Boolean {
        val recorded = settlementReasonValue.compareAndSet(null, reason)
        if (recorded && reason == SettlementReason.HARD_DEADLINE) {
            usedHardDeadlineValue.set(true)
        }
        return recorded
    }

    fun recordSettlement(reason: SettlementReason) {
        recordSettlement(reason, nowNanos())
    }

    fun recordSettlement(reason: SettlementReason, atNanos: Long) {
        recordSettlementReason(reason)
        markIfFirst(Event.TranscriptSettled, atNanos)
    }

    fun recordQuietBarrierSatisfied() {
        recordQuietBarrierSatisfied(nowNanos())
    }

    fun recordQuietBarrierSatisfied(atNanos: Long) {
        recordEarliest(Event.QuietBarrierSatisfied, atNanos)
    }

    fun recordRepairStarted() {
        mark(Event.RepairStarted)
    }

    fun recordRepairEnded() {
        mark(Event.RepairCompleted)
    }

    /** First terminal outcome wins and shares one timestamp with the terminal mark. */
    fun recordTerminalOutcome(outcome: TerminalOutcome): Boolean =
        recordTerminalOutcome(outcome, nowNanos())

    fun recordTerminalOutcome(outcome: TerminalOutcome, atNanos: Long): Boolean {
        if (!terminalOutcomeValue.compareAndSet(null, outcome)) return false
        markIfFirst(Event.Terminal, atNanos)
        return true
    }

    /** Compatibility entry point; [size] is bytes despite the legacy name. */
    fun recordWebSocketQueue(size: Int) {
        require(size >= 0) { "WebSocket queue bytes must be non-negative" }
        recordWebSocketQueueBytes(size.toLong())
    }

    /** Atomically records the peak OkHttp WebSocket queue size in bytes. */
    fun recordWebSocketQueueBytes(sizeBytes: Long) {
        require(sizeBytes >= 0L) { "WebSocket queue bytes must be non-negative" }
        updateMax(maxWebSocketQueueBytesValue, sizeBytes)
    }

    /** Atomically records a frame-queue depth high-water mark. */
    fun recordFrameQueueDepth(depthFrames: Int) {
        require(depthFrames >= 0) { "Frame queue depth must be non-negative" }
        updateMax(maxFrameQueueDepthValue, depthFrames)
    }

    /** Atomically records a queued-frame age high-water mark in nanoseconds. */
    fun recordFrameQueueAgeNanos(ageNanos: Long) {
        require(ageNanos >= 0L) { "Frame queue age must be non-negative" }
        updateMax(maxFrameQueueAgeNanosValue, ageNanos)
    }

    fun recordFrameQueue(depthFrames: Int, ageNanos: Long) {
        recordFrameQueueDepth(depthFrames)
        recordFrameQueueAgeNanos(ageNanos)
    }

    /** Records queue depth and derives age from timestamps in the same clock domain. */
    fun recordFrameQueueFromTimestamp(
        depthFrames: Int,
        enqueuedAtNanos: Long,
        observedAtNanos: Long = nowNanos(),
    ) {
        recordFrameQueueDepth(depthFrames)
        elapsedNanos(enqueuedAtNanos, observedAtNanos)?.let(::recordFrameQueueAgeNanos)
    }

    /**
     * Convenience for the audio sender: records the remaining depth and age of
     * a dequeued frame. Null legacy capture timestamps omit only the age sample.
     */
    fun recordFrameDequeued(
        depthFrames: Int,
        capturedAtMonotonicNanos: Long?,
    ) {
        recordFrameQueueDepth(depthFrames)
        capturedAtMonotonicNanos ?: return
        elapsedNanos(capturedAtMonotonicNanos, nowNanos())?.let(::recordFrameQueueAgeNanos)
    }

    fun maxFrameQueueAgeMs(): Long? =
        maxFrameQueueAgeNanos?.div(NANOS_PER_MILLISECOND)

    fun warmClaimAgeMs(): Long? =
        warmClaimAgeNanos?.div(NANOS_PER_MILLISECOND)

    private fun durationMs(from: Long?, to: Long?): Long? {
        if (from == null || to == null) return null
        // Completion signals can legally arrive before STOP. At STOP there is
        // therefore zero remaining latency, never a negative duration.
        if (to <= from) return 0L
        return elapsedNanos(from, to)?.div(NANOS_PER_MILLISECOND)
    }

    private fun elapsedNanos(from: Long, to: Long): Long? {
        if (to < from) return null
        val elapsed = to - from
        return elapsed.takeIf { it >= 0L }
    }

    /** Explicit phase name for the legacy [tapToCaptureMs] API. */
    fun tapToCaptureStartedMs(): Long? = durationMs(tapAt, captureStartedAt)

    fun tapToCaptureMs(): Long? = tapToCaptureStartedMs()

    fun tapToSetupCompleteMs(): Long? = durationMs(tapAt, setupCompleteAt)

    fun tapToFirstAudioFrameQueuedMs(): Long? = durationMs(tapAt, firstAudioQueuedAt)

    fun tapToFirstAudioQueuedMs(): Long? = tapToFirstAudioFrameQueuedMs()

    fun firstAudioFrameQueuedToFirstInputTranscriptMs(): Long? =
        durationMs(firstAudioQueuedAt, firstInputTranscriptAt)

    fun firstAudioToFirstTranscriptMs(): Long? =
        firstAudioFrameQueuedToFirstInputTranscriptMs()

    fun tapToWarmClaimResolvedMs(): Long? = durationMs(tapAt, warmClaimResolvedAt)

    fun stopToFirstInputRevisionMs(): Long? = durationMs(stopAt, firstInputRevisionAt)

    fun stopToLastInputRevisionMs(): Long? = durationMs(stopAt, lastInputRevisionAt)

    fun firstInputToLastInputRevisionMs(): Long? =
        durationMs(firstInputRevisionAt, lastInputRevisionAt)

    fun latestTranscriptRevisionAtNanos(): Long? = latestTranscriptRevisionAt()

    fun stopToLastTranscriptRevisionMs(): Long? =
        durationMs(stopAt, latestTranscriptRevisionAt())

    fun stopToCaptureQuiescedMs(): Long? = durationMs(stopAt, captureQuiescedAt)

    fun stopToActivityEndQueuedMs(): Long? = durationMs(stopAt, activityEndQueuedAt)

    fun stopToTurnCompleteMs(): Long? = durationMs(stopAt, turnCompleteAt)

    fun stopToFirstEchoMs(): Long? = durationMs(stopAt, firstEchoAt)

    fun stopToLastEchoMs(): Long? = durationMs(stopAt, lastEchoAt)

    fun stopToFirstEchoRevisionMs(): Long? = stopToFirstEchoMs()

    fun stopToLastEchoRevisionMs(): Long? = stopToLastEchoMs()

    fun stopToGenerationCompleteMs(): Long? = durationMs(stopAt, generationCompleteAt)

    fun firstEchoToLastEchoMs(): Long? = durationMs(firstEchoAt, lastEchoAt)

    fun firstEchoRevisionToLastEchoRevisionMs(): Long? = firstEchoToLastEchoMs()

    fun activityEndQueuedToGenerationCompleteMs(): Long? =
        durationMs(activityEndQueuedAt, generationCompleteAt)

    fun generationCompleteToSettledMs(): Long? =
        durationMs(generationCompleteAt, transcriptSettledAt)

    fun stopToQuietBarrierMs(): Long? = durationMs(stopAt, quietBarrierSatisfiedAt)

    fun lastTranscriptRevisionToQuietBarrierMs(): Long? =
        durationMs(latestTranscriptRevisionAt(), quietBarrierSatisfiedAt)

    fun quietBarrierToSettledMs(): Long? =
        durationMs(quietBarrierSatisfiedAt, transcriptSettledAt)

    fun stopToTranscriptSettledMs(): Long? = durationMs(stopAt, transcriptSettledAt)

    fun stopToSettledMs(): Long? = stopToTranscriptSettledMs()

    fun stopToInsertionResultMs(): Long? = durationMs(stopAt, insertionResultAt)

    fun insertionRequestToResultMs(): Long? =
        durationMs(insertionRequestedAt, insertionResultAt)

    fun insertionRequestedToResultMs(): Long? = insertionRequestToResultMs()

    fun repairDurationMs(): Long? = durationMs(repairStartedAt, repairCompletedAt)

    fun targetReservationDurationMs(): Long? =
        durationMs(targetReservationStartedAt, targetReservationCompletedAt)

    fun insertionRequestedToIpcReceivedMs(): Long? =
        durationMs(insertionRequestedAt, insertionIpcReceivedAt)

    fun insertionIpcReceivedToCommitStartedMs(): Long? =
        durationMs(insertionIpcReceivedAt, insertionCommitStartedAt)

    fun insertionCommitDurationMs(): Long? =
        durationMs(insertionCommitStartedAt, insertionCommitCompletedAt)

    fun insertionCommitCompletedToReplySentMs(): Long? =
        durationMs(insertionCommitCompletedAt, insertionReplySentAt)

    fun insertionReplySentToReceivedMs(): Long? =
        durationMs(insertionReplySentAt, insertionReplyReceivedAt)

    fun insertionIpcRoundTripMs(): Long? =
        durationMs(insertionRequestedAt, insertionReplyReceivedAt)

    fun tapToTerminalMs(): Long? = durationMs(tapAt, terminalAt)

    fun snapshot(): DictationMetrics {
        val startedAtNanos = tapAt
        val endedAtNanos = terminalAt
            ?: insertionReplyReceivedAt
            ?: insertionResultAt
            ?: transcriptSettledAt
        return DictationMetrics(
            sessionId = sessionId,
            startedAtMillis = (startedAtNanos ?: 0L) / NANOS_PER_MILLISECOND,
            endedAtMillis = endedAtNanos?.div(NANOS_PER_MILLISECOND),
            tapToCaptureMs = tapToCaptureMs(),
            tapToSetupCompleteMs = tapToSetupCompleteMs(),
            tapToFirstAudioQueuedMs = tapToFirstAudioQueuedMs(),
            firstAudioToFirstTranscriptMs = firstAudioToFirstTranscriptMs(),
            stopToCaptureQuiescedMs = stopToCaptureQuiescedMs(),
            stopToActivityEndQueuedMs = stopToActivityEndQueuedMs(),
            stopToTurnCompleteMs = stopToTurnCompleteMs(),
            stopToSettledMs = stopToSettledMs(),
            stopToInsertionResultMs = stopToInsertionResultMs(),
            insertionRequestedToResultMs = insertionRequestedToResultMs(),
            capturedFrames = capturedFrames,
            acceptedFrames = acceptedFrames,
            rejectedFrames = rejectedFrames,
            inputTranscriptionCount = inputTranscriptionCount,
            outputTranscriptionCount = outputTranscriptionCount,
            maxWebSocketQueueSize = maxWebSocketQueueSize,
            maxWebSocketQueueBytes = maxWebSocketQueueBytes,
            turnCompleteArrived = turnCompleteArrived,
            generationCompleteArrived = generationCompleteArrived,
            usedHardDeadline = usedHardDeadline,
            audioBufferOverflow = audioBufferOverflow,
            warmSessionHit = warmSessionHit,
            warmClaimResult = warmClaimResult,
            warmClaimAgeMs = warmClaimAgeMs(),
            tapToWarmClaimResolvedMs = tapToWarmClaimResolvedMs(),
            stopToFirstInputRevisionMs = stopToFirstInputRevisionMs(),
            stopToLastInputRevisionMs = stopToLastInputRevisionMs(),
            firstInputToLastInputRevisionMs = firstInputToLastInputRevisionMs(),
            stopToLastTranscriptRevisionMs = stopToLastTranscriptRevisionMs(),
            stopToFirstEchoMs = stopToFirstEchoMs(),
            stopToLastEchoMs = stopToLastEchoMs(),
            stopToGenerationCompleteMs = stopToGenerationCompleteMs(),
            firstEchoToLastEchoMs = firstEchoToLastEchoMs(),
            activityEndQueuedToGenerationCompleteMs = activityEndQueuedToGenerationCompleteMs(),
            generationCompleteToSettledMs = generationCompleteToSettledMs(),
            maxFrameQueueDepth = maxFrameQueueDepth,
            maxFrameQueueAgeMs = maxFrameQueueAgeMs(),
            settlementReason = settlementReason,
            stopToQuietBarrierMs = stopToQuietBarrierMs(),
            lastTranscriptRevisionToQuietBarrierMs = lastTranscriptRevisionToQuietBarrierMs(),
            quietBarrierToSettledMs = quietBarrierToSettledMs(),
            settlePath = settlePath,
            lastRejection = lastRejection?.let(::safeDiagnosticCode),
            usedLenientFallback = usedLenientFallback,
            repairDurationMs = repairDurationMs(),
            targetReservationDurationMs = targetReservationDurationMs(),
            targetReserved = targetReserved,
            insertionRequestedToIpcReceivedMs = insertionRequestedToIpcReceivedMs(),
            insertionIpcReceivedToCommitStartedMs = insertionIpcReceivedToCommitStartedMs(),
            insertionCommitDurationMs = insertionCommitDurationMs(),
            insertionCommitCompletedToReplySentMs = insertionCommitCompletedToReplySentMs(),
            insertionReplySentToReceivedMs = insertionReplySentToReceivedMs(),
            insertionIpcRoundTripMs = insertionIpcRoundTripMs(),
            terminalOutcome = terminalOutcome,
            tapToTerminalMs = tapToTerminalMs(),
            startedAtMonotonicNanos = startedAtNanos,
            endedAtMonotonicNanos = endedAtNanos,
            warmClaimResolvedAtMonotonicNanos = warmClaimResolvedAt,
            firstInputRevisionAtMonotonicNanos = firstInputRevisionAt,
            lastInputRevisionAtMonotonicNanos = lastInputRevisionAt,
            firstEchoRevisionAtMonotonicNanos = firstEchoRevisionAt,
            lastEchoRevisionAtMonotonicNanos = lastEchoRevisionAt,
            lastTranscriptRevisionAtMonotonicNanos = latestTranscriptRevisionAt(),
            generationCompleteAtMonotonicNanos = generationCompleteAt,
            quietBarrierSatisfiedAtMonotonicNanos = quietBarrierSatisfiedAt,
            repairStartedAtMonotonicNanos = repairStartedAt,
            repairEndedAtMonotonicNanos = repairEndedAt,
            insertionIpcReceivedAtMonotonicNanos = insertionIpcReceivedAt,
            insertionCommitStartedAtMonotonicNanos = insertionCommitStartedAt,
            insertionCommitCompletedAtMonotonicNanos = insertionCommitCompletedAt,
            insertionReplySentAtMonotonicNanos = insertionReplySentAt,
            insertionReplyReceivedAtMonotonicNanos = insertionReplyReceivedAt,
            terminalAtMonotonicNanos = terminalAt,
        )
    }

    /** Single non-sensitive log line: only derived durations, flags, and counts. */
    fun summary(): String = buildList {
        warmClaimResult?.let { add("warmClaimResult=$it") }
            ?: warmSessionHit?.let { add("warmClaimResult=${if (it) "HIT" else "MISS"}") }
        durationToken("warmClaimAge", warmClaimAgeMs())?.let(::add)
        durationToken("tapToWarmClaimResolved", tapToWarmClaimResolvedMs())?.let(::add)
        durationToken("tapToCaptureStarted", tapToCaptureStartedMs())?.let(::add)
        durationToken("tapToSetupComplete", tapToSetupCompleteMs())?.let(::add)
        durationToken("tapToFirstAudioQueued", tapToFirstAudioQueuedMs())?.let(::add)
        durationToken(
            "firstAudioQueuedToFirstInputTranscript",
            firstAudioFrameQueuedToFirstInputTranscriptMs(),
        )?.let(::add)
        durationToken("stopToCaptureQuiesced", stopToCaptureQuiescedMs())?.let(::add)
        durationToken("stopToActivityEndQueued", stopToActivityEndQueuedMs())?.let(::add)
        durationToken("stopToTurnComplete", stopToTurnCompleteMs())?.let(::add)
        durationToken("stopToFirstInputRevision", stopToFirstInputRevisionMs())?.let(::add)
        durationToken("stopToLastInputRevision", stopToLastInputRevisionMs())?.let(::add)
        durationToken("firstInputToLastInputRevision", firstInputToLastInputRevisionMs())?.let(::add)
        durationToken("stopToLastTranscriptRevision", stopToLastTranscriptRevisionMs())?.let(::add)
        durationToken("stopToFirstEcho", stopToFirstEchoMs())?.let(::add)
        durationToken("stopToLastEcho", stopToLastEchoMs())?.let(::add)
        durationToken("stopToGenerationComplete", stopToGenerationCompleteMs())?.let(::add)
        durationToken("firstEchoToLastEcho", firstEchoToLastEchoMs())?.let(::add)
        durationToken("generationCompleteToSettled", generationCompleteToSettledMs())?.let(::add)
        durationToken("stopToQuietBarrier", stopToQuietBarrierMs())?.let(::add)
        durationToken(
            "lastTranscriptRevisionToQuietBarrier",
            lastTranscriptRevisionToQuietBarrierMs(),
        )?.let(::add)
        durationToken("quietBarrierToSettled", quietBarrierToSettledMs())?.let(::add)
        durationToken("stopToTranscriptSettled", stopToTranscriptSettledMs())?.let(::add)
        durationToken("stopToInsertionResult", stopToInsertionResultMs())?.let(::add)
        durationToken("insertionRequestedToResult", insertionRequestedToResultMs())?.let(::add)
        durationToken("repair", repairDurationMs())?.let(::add)
        durationToken("targetReservation", targetReservationDurationMs())?.let(::add)
        durationToken("insertionRequestedToIpcReceived", insertionRequestedToIpcReceivedMs())
            ?.let(::add)
        durationToken("insertionIpcReceivedToCommitStarted", insertionIpcReceivedToCommitStartedMs())
            ?.let(::add)
        durationToken("insertionCommit", insertionCommitDurationMs())?.let(::add)
        durationToken(
            "insertionCommitCompletedToReplySent",
            insertionCommitCompletedToReplySentMs(),
        )?.let(::add)
        durationToken("insertionReplySentToReceived", insertionReplySentToReceivedMs())
            ?.let(::add)
        durationToken("insertionIpcRoundTrip", insertionIpcRoundTripMs())?.let(::add)
        durationToken("tapToTerminal", tapToTerminalMs())?.let(::add)
        if (copiedToClipboardAt != null) add("copied=true")
        add("capturedFrames=$capturedFrames")
        add("acceptedFrames=$acceptedFrames")
        add("rejectedFrames=$rejectedFrames")
        add("maxWebSocketQueueBytes=$maxWebSocketQueueBytes")
        add("maxFrameQueueDepthFrames=$maxFrameQueueDepth")
        maxFrameQueueAgeMs()?.let { add("maxFrameQueueAgeMs=$it") }
        add("inputTranscriptMessages=$inputTranscriptionCount")
        add("echoTranscriptMessages=$outputTranscriptionCount")
        add("turnComplete=$turnCompleteArrived")
        add("generationComplete=$generationCompleteArrived")
        add("hardDeadline=$usedHardDeadline")
        add("overflow=$audioBufferOverflow")
        targetReserved?.let { add("targetReserved=$it") }
        settlementReason?.let { add("settlementReason=$it") }
        settlePath?.let { add("settlePath=$it") }
        lastRejection?.let { add("reject=${safeDiagnosticCode(it)}") }
        if (usedLenientFallback) add("lenient=true")
        terminalOutcome?.let { add("terminalOutcome=$it") }
    }.joinToString(" ")

    private fun timestamp(event: Event): Long? = timestamps.get(event.ordinal)?.nanos

    private fun setTimestamp(event: Event, value: Long?) {
        timestamps.set(event.ordinal, value?.let(::Timestamp))
    }

    private fun latestTranscriptRevisionAt(): Long? {
        val inputAt = lastInputRevisionAt ?: firstInputRevisionAt
        val echoAt = lastEchoRevisionAt ?: firstEchoRevisionAt
        return when {
            inputAt == null -> echoAt
            echoAt == null -> inputAt
            else -> maxOf(inputAt, echoAt)
        }
    }

    private fun recordEarliest(event: Event, atNanos: Long) {
        while (true) {
            val current = timestamps.get(event.ordinal)
            if (current != null && current.nanos <= atNanos) return
            if (timestamps.compareAndSet(event.ordinal, current, Timestamp(atNanos))) return
        }
    }

    private fun recordLatest(event: Event, atNanos: Long) {
        while (true) {
            val current = timestamps.get(event.ordinal)
            if (current != null && current.nanos >= atNanos) return
            if (timestamps.compareAndSet(event.ordinal, current, Timestamp(atNanos))) return
        }
    }

    private fun Boolean?.toAtomicBoolean(): Int =
        when (this) {
            null -> BOOLEAN_UNRECORDED
            false -> BOOLEAN_FALSE
            true -> BOOLEAN_TRUE
        }

    private fun Int.toNullableBoolean(): Boolean? =
        when (this) {
            BOOLEAN_UNRECORDED -> null
            BOOLEAN_FALSE -> false
            BOOLEAN_TRUE -> true
            else -> error("Invalid nullable-boolean atomic value: $this")
        }

    private fun addCounter(counter: AtomicLong, count: Long): Long {
        require(count >= 0L) { "Counter increment must be non-negative" }
        return counter.addAndGet(count)
    }

    private fun updateMax(target: AtomicLong, candidate: Long) {
        while (true) {
            val current = target.get()
            if (candidate <= current) return
            if (target.compareAndSet(current, candidate)) return
        }
    }

    private fun updateMax(target: AtomicInteger, candidate: Int) {
        while (true) {
            val current = target.get()
            if (candidate <= current) return
            if (target.compareAndSet(current, candidate)) return
        }
    }

    private fun safeDiagnosticCode(code: String): String =
        code.takeIf {
            it.length in 1..MAX_DIAGNOSTIC_CODE_LENGTH &&
                it.all { char -> char == '_' || char in 'A'..'Z' || char in '0'..'9' }
        } ?: REDACTED_DIAGNOSTIC_CODE

    private fun durationToken(name: String, ms: Long?): String? = ms?.let { "$name=${it}ms" }

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val UNRECORDED_AGE_NANOS = -1L
        const val BOOLEAN_UNRECORDED = -1
        const val BOOLEAN_FALSE = 0
        const val BOOLEAN_TRUE = 1
        const val MAX_DIAGNOSTIC_CODE_LENGTH = 64
        const val REDACTED_DIAGNOSTIC_CODE = "OTHER"
    }
}

/**
 * Privacy-safe warm-lease claim classification. Results describe only lifecycle
 * state; they never contain profile values, credential material, or identifiers.
 */
enum class WarmClaimResult(val isHit: Boolean) {
    HIT(true),
    MISS(false),
    SHUT_DOWN(false),
    NOT_READY(false),
    CONNECTING(false),
    BACKING_OFF(false),
    NO_READY_SESSION(false),
    MISSING_PROFILE(false),
    PROFILE_MISMATCH(false),
    TOO_OLD(false),
    UNHEALTHY(false),
    TERMINATED(false),
    EXPIRING(false),
    INELIGIBLE(false),
}

/** Aggregate trigger that caused transcript settlement; never transcript content. */
enum class SettlementReason {
    GENERATION_COMPLETE,
    TURN_COMPLETE,
    QUIET_BARRIER,
    GENERATION_COMPLETE_QUIET,
    TURN_COMPLETE_QUIET,
    SOURCE_MISSING_GRACE,
    ECHO_DEBOUNCE,
    RAW_FALLBACK_TIMEOUT,
    HARD_DEADLINE,
    TERMINAL_EVENT,
    EXPLICIT_FINALIZATION,
}

/** Privacy-safe terminal state recorded once per session. */
enum class TerminalOutcome {
    SUCCESS,
    INSERTED,
    COPIED_TO_CLIPBOARD,
    NO_RELIABLE_TRANSCRIPT,
    TARGET_REJECTED,
    AMBIGUOUS_COMMIT,
    TRANSPORT_FAILURE,
    TIMED_OUT,
    CANCELLED,
    ERROR,
}

/**
 * Which source produced the settled dictation transcript (0.4.2). This is a
 * reliability diagnostic: ECHO_COMPLETE and RAW_ONLY are the expected healthy
 * paths; ECHO_PARTIAL_RAW records that the echo was truncated/summarized and
 * the complete raw ASR was salvaged; ECHO_ONLY means the raw never arrived and
 * the echo was the only available text; NONE means nothing settled.
 */
enum class SettlePath {
    ECHO_COMPLETE,
    ECHO_PARTIAL_RAW,
    RAW_ONLY,
    /** Last revisable interim used because no final segment arrived before tail. */
    PREVIEW_FALLBACK,
    ECHO_ONLY,
    NONE,
}
