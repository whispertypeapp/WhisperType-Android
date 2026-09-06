package com.whispertype.android.platform.runtime

import com.whispertype.android.audio.AudioPipeline
import com.whispertype.android.audio.PreReadyAudioBuffer
import com.whispertype.android.audio.PreReadyOffer
import com.whispertype.android.core.contracts.GeminiLiveSession
import com.whispertype.android.core.model.AudioChunk
import com.whispertype.android.core.model.CancelReason
import com.whispertype.android.core.model.DictationFailure
import com.whispertype.android.core.model.DictationState
import com.whispertype.android.core.model.GeminiEvent
import com.whispertype.android.core.model.InsertionResult
import com.whispertype.android.core.model.LanguageMode
import com.whispertype.android.core.model.MutableSessionMetrics
import com.whispertype.android.core.model.ResultCandidate
import com.whispertype.android.core.model.SendResult
import com.whispertype.android.core.model.SessionId
import com.whispertype.android.core.model.SettlePath
import com.whispertype.android.core.model.SettlementReason
import com.whispertype.android.core.model.TargetSnapshot
import com.whispertype.android.core.model.TerminalOutcome
import com.whispertype.android.core.model.isClipboardFallback
import com.whispertype.android.core.transcript.RejectionDiagnosis
import com.whispertype.android.core.transcript.RejectionRule
import com.whispertype.android.core.transcript.TranscriptAccumulator
import com.whispertype.android.core.transcript.TranscriptCompleteness
import com.whispertype.android.core.transcript.TranscriptSelection
import com.whispertype.android.core.transcript.TranscriptSelector
import com.whispertype.android.platform.gemini.GeminiLiveException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Host services the [DictationCoordinator] needs. Implemented by
 * [FlowRuntimeService]; kept to pure interfaces so the coordinator is
 * host-testable on the JVM (no Android types).
 */
interface DictationHost {
    /** Publishes the UI-renderable session state. */
    fun publish(state: DictationState)

    /** Resolves the API key, runtime settings, and constructs the Live session. */
    suspend fun resolveSession(metrics: MutableSessionMetrics): SessionResolve

    /** Verifies mic permission, promotes foreground mode, creates and starts the
     *  capture pipeline. Returns the started pipeline or a typed failure. */
    suspend fun startCapture(metrics: MutableSessionMetrics): CaptureStart

    /** Sends the insert request over IPC; false when accessibility is absent. */
    fun sendInsertion(sessionId: SessionId, text: String): Boolean

    /**
     * 0.5.8: copies [text] to the system clipboard (sensitive-marked). Returns
     * true only on a confirmed write; false falls back to an Error surface.
     */
    suspend fun copyToClipboard(sessionId: SessionId, text: String): Boolean

    /**
     * Per-session aggregate diagnostics at terminal state. Must never contain
     * audio, keys, or full server frames. [transcript] is the settled dictation
     * text when one existed (used for opt-in history), or null otherwise.
     */
    fun onSessionFinished(state: DictationState, metrics: MutableSessionMetrics, transcript: String?)
}

/** Outcome of [DictationHost.resolveSession]. */
sealed interface SessionResolve {
    data class Ok(val resolution: SessionResolution) : SessionResolve
    data class Failed(val failure: DictationFailure) : SessionResolve
}

/** A created Live session plus the language it should stamp on candidates.
 *  [ready] is true when the session was already connected (warm claim).
 *  0.10.0: the session is raw transcription transport only — the server's
 *  `inputTranscription` / `interimInputTranscription` are the only dictation
 *  source, so there is no echo flag. */
data class SessionResolution(
    val session: GeminiLiveSession,
    val language: LanguageMode,
    val ready: Boolean = false,
)

/** Outcome of [DictationHost.startCapture]. */
sealed interface CaptureStart {
    data class Started(val capture: AudioPipeline) : CaptureStart
    data class Failed(val failure: DictationFailure) : CaptureStart
}

/**
 * Per-session owner of all live resources, jobs, transcript state, and metrics
 * (Release C1). The runtime keeps exactly one `activeSession` reference instead
 * of parallel service-wide mutable fields, so an older session's callbacks can
 * never act on a newer session: every handler first verifies `active === this`.
 */
private class ActiveLiveSession(
    val sessionId: SessionId,
    val metrics: MutableSessionMetrics,
    /** 1.0.7: committed final `inputTranscription` segments ONLY. Interims are
     *  never accumulated here — the smart final is the only dictation source. */
    val accumulator: TranscriptAccumulator,
    /** 1.0.7: revisable partials (`interimInputTranscription`) — live preview
     *  only, never settled. Kept for the change signal that resets the quiet /
     *  tail barriers while the model is still transcribing. */
    val previewAccumulator: TranscriptAccumulator,
    var language: LanguageMode = LanguageMode.ENGLISH,
) {
    var session: GeminiLiveSession? = null
    var capture: AudioPipeline? = null

    /** True once activityStart was accepted (segmentation only acts mid-turn). */
    var activityStarted: Boolean = false
    var sessionJob: Job? = null
    var eventJob: Job? = null
    var readyJob: Job? = null
    var audioJob: Job? = null
    var amplitudeJob: Job? = null
    var captureFailureJob: Job? = null
    var finalizationJob: Job? = null
    var settleJob: Job? = null
    var asrTailJob: Job? = null
    var insertionResultJob: Job? = null
    var inserted: Boolean = false

    /** The settled dictation text when one existed (opt-in history hook). */
    var settledText: String? = null

    /** Auto-stop watcher (silence + hard cap) while listening. */
    var autoStopJob: Job? = null

    /** Retained even when the server sends it before STOP (Release E7). */
    var turnCompleteSeen: Boolean = false

    /** Server done-hint; a settlement gate alongside [tailFinalSeen]. */
    var generationCompleteSeen: Boolean = false

    /** True only after an accepted activity-end send for this holder. */
    var activityEndQueued: Boolean = false

    /**
     * 1.0.0: a committed final `inputTranscription` segment was received after
     * the activity-end boundary. The transcribe model commits final segments
     * incrementally while speaking, and the tail segment's final lands only
     * after `activityEnd`; settlement waits for this so the end of the
     * utterance is never cut off.
     */
    var tailFinalSeen: Boolean = false

    /** The ASR revision stream has been quiet long enough to settle. */
    var transcriptQuiet: Boolean = false

    /** 0.8.0: the single settlement backstop fired (ASR tail took too long). */
    var asrTailElapsed: Boolean = false

    /** Exact duration represented by captured frames (fragment plausibility). */
    var capturedAudioDurationMs: Long = 0L
}

/** A closed capture channel must never be mistaken for Live-session readiness. */
private sealed interface AudioStreamSignal {
    data class Frame(val chunk: AudioChunk) : AudioStreamSignal
    data object Ready : AudioStreamSignal
    data object CaptureClosed : AudioStreamSignal
}

/**
 * Pure, host-testable orchestration of one dictation session (Release C).
 *
 * Responsibilities:
 *  - reject duplicate START synchronously (C2),
 *  - validate session identity before every event/callback (C3),
 *  - compare-and-clear the active holder during cleanup (C4),
 *  - correlate insertion results by session id (C5),
 *  - drain audio through the producer before the completion boundary (C6),
 *  - consume capture failures and tear down (C7).
 *
 * No Android types appear here; [DictationHost] supplies them.
 */
class DictationCoordinator(
    private val scope: CoroutineScope,
    private val host: DictationHost,
    private val selector: TranscriptSelector = TranscriptSelector(),
    private val config: Config = Config(),
    private val metricsFactory: (SessionId) -> MutableSessionMetrics = { MutableSessionMetrics(it) },
) {

    /** Tunable timing knobs (all monotonic delays, host-tested via virtual time). */
    data class Config(
        /** 0.8.0: quiet required after an ASR revision before settling. The raw
         *  `inputTranscription` is delivered as one final text (measured: 2
         *  messages, 0-2 ms apart), so a short window is safe — and it is now
         *  the ONLY barrier on the settlement path. */
        val settleDebounceMs: Long = 250,
        /** 0.8.0: the single settlement backstop, measured from the activity-end
         *  boundary. If the ASR tail never goes quiet (or never arrives at all),
         *  settlement proceeds on whatever text exists once this elapses. This
         *  replaces the 0.6.2 stack of echo-quiet (900 ms), echo-stall (2.5 s),
         *  source-missing grace (2 s) and hard deadline (20 s) barriers that
         *  dominated post-stop latency. */
        val asrTailTimeoutMs: Long = 6_000,
        val returnToIdleMs: Long = 1_200,
        val captureShutdownTimeoutMs: Long = 1_500,
        /** Dispatcher for the blocking AudioRecord stop/release calls during
         *  finalization/teardown so they never run on the main dispatcher
         *  (0.6.0). Injected so virtual-time host tests can keep everything on
         *  the test scheduler. */
        val captureShutdownDispatcher: CoroutineDispatcher = Dispatchers.IO,
        /** Maximum wait after STOP closes capture while a cold session is still
         * connecting. This preserves activityStart -> buffered audio ->
         * activityEnd ordering without allowing finalization to hang forever. */
        val readyAfterStopTimeoutMs: Long = 5_000,
        /** Commit is exactly-once. A missing IPC result becomes an explicit,
         * non-retryable ambiguity instead of leaving Inserting stuck forever.
         * Set to 0 only in deterministic tests that complete insertion manually. */
        val insertionResultTimeoutMs: Long = 2_000,
        /** Bounded pre-ready PCM frames buffered while a cold session connects (F5).
         *  1.0.8: 150 → 500 (20 ms × 500 ≈ 10 s backstop; overflow is last resort). */
        val preReadyMaxFrames: Int = 500,
        /** Auto-stop: stop after this many seconds of silence (0 disables). The
         *  runtime supplies the product default (60 s) via the settings-backed
         *  provider. Disabled by default so host tests keep deterministic time. */
        val autoStopSeconds: () -> Long = { 0L },
        /** Auto-stop: hard recording cap in seconds (0 disables). The runtime
         *  mirrors the same user option here, so the cap and the silence timeout
         *  share the configured value ("both combined"). Kept as a separate knob
         *  so each arm is independently testable and can diverge later. */
        val maxRecordingSeconds: () -> Long = { 0L },
        /** 0.6.0 experimental (off by default): when true, the recording is
         *  split at pauses of [segmentSilenceMs] so the model echoes each segment
         *  while the user keeps talking. Requires on-device validation. */
        val segmentAtSilence: () -> Boolean = { false },
        /** Minimum quiet before the current activity is closed and reopened. */
        val segmentSilenceMs: Long = 700,
        /** Mic amplitude (0..1) above which the user is considered speaking. */
        val speechAmplitudeThreshold: Float = 0.02f,
    )

    @Volatile
    private var active: ActiveLiveSession? = null

    /** True when a live session is running or finalizing (guards duplicate START). */
    val isActive: Boolean get() = active != null

    /**
     * True when an in-flight dictation owns the warm Live socket. The warm pool
     * must stay eligible during [DictationState.Starting] so [claim] can succeed
     * before [DictationState.Listening] is published.
     */
    fun blocksWarmPool(): Boolean =
        WarmPoolEligibility.blocksWarmPool(lastPublished, active != null)

    /** Test visibility: metrics of the currently active session, or null. */
    internal fun activeMetrics(): MutableSessionMetrics? = active?.metrics

    /** Starts a new dictation session. Returns false (and does nothing) when a
     *  session is still in flight (Starting/Listening/Finalizing/Inserting) —
     *  duplicate START is rejected synchronously. A lingering terminal state
     *  (Success/CopiedToClipboard/Error/Cancelled) is cleared so a fresh tap can
     *  start immediately instead of waiting out the UI feedback timer. */
    fun start(): Boolean {
        val existing = active
        if (existing != null) {
            val state = lastPublished
            val inFlight = state is DictationState.Starting ||
                state is DictationState.Listening ||
                state is DictationState.Finalizing ||
                state is DictationState.Inserting
            if (inFlight) return false
            resetToIdle(existing)
        }
        val sessionId = SessionId.new()
        val metrics = metricsFactory(sessionId)
        metrics.mark(MutableSessionMetrics.Event.Tap)
        val holder = ActiveLiveSession(
            sessionId = sessionId,
            metrics = metrics,
            accumulator = TranscriptAccumulator(),
            previewAccumulator = TranscriptAccumulator(),
        )
        active = holder
        publish(DictationState.Starting(sessionId, EMPTY_TARGET(sessionId)))
        holder.sessionJob = scope.launch { runSession(holder) }
        return true
    }

    /** STOPS the active turn (Listening -> Finalizing) with an orderly producer drain. */
    fun stop() {
        val holder = active ?: return
        if (!isListening(holder)) return
        publish(DictationState.Finalizing(holder.sessionId))
        holder.metrics.mark(MutableSessionMetrics.Event.Stop)
        startFinalization(holder)
    }

    /** CANCELS the active turn without insertion and tears the session down. */
    fun cancel() {
        val holder = active ?: return
        publish(DictationState.Cancelled(holder.sessionId, CancelReason.USER))
        holder.metrics.mark(MutableSessionMetrics.Event.Stop)
        holder.metrics.recordTerminalOutcome(TerminalOutcome.CANCELLED)
        teardown(holder)
        scope.launch {
            delay(config.returnToIdleMs)
            resetToIdle(holder)
        }
    }

    /** Routes an insertion result; a stale session id is ignored (C5). */
    fun onInsertionResult(sessionId: SessionId, result: InsertionResult) {
        val holder = active ?: return
        if (holder.sessionId != sessionId) return
        if (!holder.inserted) return
        if (!isInserting(holder)) return
        holder.insertionResultJob?.cancel()
        holder.metrics.mark(MutableSessionMetrics.Event.InsertionReplyReceived)
        holder.metrics.mark(MutableSessionMetrics.Event.InsertionResult)
        // 0.5.8: the transcript could not be committed to a focused field
        // (no field, no input connection, or a field change) — copy it to the
        // clipboard instead of dropping it into an Error surface.
        if (result is InsertionResult.Failed && result.failure.isClipboardFallback()) {
            val transcript = holder.settledText
            if (!transcript.isNullOrBlank()) {
                scope.launch {
                    val copied = host.copyToClipboard(holder.sessionId, transcript)
                    if (active !== holder) return@launch
                    if (copied) {
                        holder.metrics.mark(MutableSessionMetrics.Event.CopiedToClipboard)
                        holder.metrics.recordTerminalOutcome(TerminalOutcome.COPIED_TO_CLIPBOARD)
                        publish(DictationState.CopiedToClipboard(sessionId))
                    } else {
                        holder.metrics.recordTerminalOutcome(TerminalOutcome.TARGET_REJECTED)
                        publish(DictationState.Error(sessionId, result.failure))
                    }
                    delay(config.returnToIdleMs)
                    resetToIdle(holder)
                }
                return
            }
        }
        publish(
            when (result) {
                InsertionResult.Inserted -> {
                    holder.metrics.recordTerminalOutcome(TerminalOutcome.INSERTED)
                    DictationState.Success(sessionId)
                }
                InsertionResult.Ambiguous -> {
                    holder.metrics.recordTerminalOutcome(TerminalOutcome.AMBIGUOUS_COMMIT)
                    DictationState.Error(
                        sessionId,
                        DictationFailure(
                            code = "insert_ambiguous",
                            message = "Could not confirm the text was inserted. Use Copy to grab it.",
                            recoverable = true,
                            retryAllowed = false,
                        ),
                    )
                }
                is InsertionResult.Failed -> {
                    holder.metrics.recordTerminalOutcome(TerminalOutcome.TARGET_REJECTED)
                    DictationState.Error(sessionId, result.failure)
                }
            },
        )
        scope.launch {
            delay(config.returnToIdleMs)
            resetToIdle(holder)
        }
    }

    // ------------------------------------------------------------------
    // Session setup
    // ------------------------------------------------------------------

    private suspend fun runSession(holder: ActiveLiveSession) {
        val sessionId = holder.sessionId
        try {
            // Release F4 (tap-to-recording fix): capture starts immediately on the
            // accepted tap. Session resolution runs in parallel so TLS/handshake
            // overlaps AudioRecord init; cold-session PCM is buffered in the bounded
            // pre-ready buffer until the session is ready.
            val captureDeferred = scope.async { host.startCapture(holder.metrics) }
            val resolveDeferred = scope.async { host.resolveSession(holder.metrics) }

            when (val outcome = captureDeferred.await()) {
                is CaptureStart.Failed -> {
                    resolveDeferred.cancel()
                    fail(holder, outcome.failure)
                    return
                }
                is CaptureStart.Started -> {
                    holder.capture = outcome.capture
                    holder.captureFailureJob = scope.launch {
                        outcome.capture.failures.collect { failure -> onCaptureFailure(holder, failure) }
                    }
                }
            }
            if (active !== holder) {
                resolveDeferred.cancel()
                return
            }
            publish(DictationState.Listening(sessionId, connecting = true))
            val resolution = when (val resolved = resolveDeferred.await()) {
                is SessionResolve.Failed -> {
                    fail(holder, resolved.failure)
                    return
                }
                is SessionResolve.Ok -> resolved.resolution
            }
            holder.session = resolution.session
            holder.language = resolution.language
            // Event collector installed as soon as the session exists, before
            // awaiting readiness, so setup failures/closure are processed promptly.
            holder.eventJob = scope.launch {
                try {
                    resolution.session.events().collect { event -> onLiveEvent(holder, event) }
                } catch (e: CancellationException) {
                    throw e
                }
            }
            if (active !== holder) return
            // A warm (already connected) session is not "connecting".
            val state = lastPublished
            if (state is DictationState.Listening && state.sessionId == holder.sessionId) {
                publish(state.copy(connecting = !resolution.ready))
            }
            val ready = CompletableDeferred<Unit>()
            holder.readyJob = scope.launch { awaitReadyAndStart(holder, ready) }
            holder.audioJob = scope.launch { streamAudio(holder, ready) }
            holder.amplitudeJob = scope.launch { publishAmplitude(holder) }
            holder.autoStopJob = scope.launch { runAutoStop(holder) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fail(
                holder,
                DictationFailure(
                    code = "gemini_events",
                    message = "The Gemini session stopped unexpectedly.",
                    recoverable = true,
                ),
            )
        }
    }

    /** Awaits the session, opens the activity, then signals the audio sender. */
    private suspend fun awaitReadyAndStart(holder: ActiveLiveSession, ready: CompletableDeferred<Unit>) {
        val session = holder.session ?: return
        try {
            session.awaitReady()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val failure = (e as? GeminiLiveException)?.failure
                ?: DictationFailure(
                    code = "gemini_setup",
                    message = "Could not reach Gemini. Check your network and API key.",
                    recoverable = true,
                )
            fail(holder, failure)
            return
        }
        if (active !== holder) return
        when (val start = session.startActivity()) {
            is SendResult.Rejected -> {
                fail(holder, transportFailure(start.reason))
                return
            }
            SendResult.Accepted -> {
                holder.activityStarted = true
                holder.metrics.mark(MutableSessionMetrics.Event.ActivityStartQueued)
            }
        }
        val current = lastPublished
        if (current is DictationState.Listening && current.sessionId == holder.sessionId) {
            publish(current.copy(connecting = false))
        }
        ready.complete(Unit)
    }

    /**
     * One ordered sender coroutine (Release F4): while the session is still
     * connecting, capture chunks go into the bounded pre-ready buffer; when the
     * session becomes ready the buffered frames are drained in strict order and
     * then live frames stream directly. Overflow of the bounded buffer fails with
     * an explicit connection-too-slow failure (F5).
     */
    private suspend fun streamAudio(holder: ActiveLiveSession, ready: CompletableDeferred<Unit>) {
        val session = holder.session ?: return
        val capture = holder.capture ?: return
        val preReady = PreReadyAudioBuffer(config.preReadyMaxFrames)
        var connected = false
        while (true) {
            // Select on both the capture channel and the readiness signal so the
            // drain is not deferred to a later microphone frame.
            val signal = select<AudioStreamSignal> {
                capture.chunks.onReceiveCatching { result ->
                    result.getOrNull()?.let(AudioStreamSignal::Frame)
                        ?: AudioStreamSignal.CaptureClosed
                }
                if (!connected) ready.onAwait { AudioStreamSignal.Ready }
            }
            when (signal) {
                AudioStreamSignal.Ready -> {
                    // The session became ready while we were blocked: drain the
                    // bounded pre-ready buffer in strict order, then stream live.
                    if (!drainPreReady(holder, session, preReady)) return
                    connected = true
                }

                AudioStreamSignal.CaptureClosed -> {
                    if (!connected) {
                        // STOP can close capture before a cold session is ready.
                        // Wait a bounded interval for awaitReadyAndStart() to
                        // queue activityStart, then drain all retained audio.
                        val started = withTimeoutOrNull(config.readyAfterStopTimeoutMs) {
                            ready.await()
                            true
                        } ?: false
                        if (!started) {
                            fail(holder, setupReadinessTimeout())
                            return
                        }
                        if (active !== holder) return
                        if (!drainPreReady(holder, session, preReady)) return
                    }
                    break
                }

                is AudioStreamSignal.Frame -> {
                    val chunk = signal.chunk
                    recordCapturedFrame(holder, capture, chunk)
                    if (connected) {
                        if (!sendChunk(holder, session, chunk)) return
                    } else {
                        when (preReady.offer(chunk)) {
                            PreReadyOffer.Accepted ->
                                holder.metrics.recordFrameQueueDepth(preReady.size)
                            PreReadyOffer.Overflow -> {
                                holder.metrics.audioBufferOverflow = true
                                fail(holder, connectionTooSlow())
                                return
                            }
                            PreReadyOffer.Closed -> return
                        }
                    }
                }
            }
        }
        holder.metrics.mark(MutableSessionMetrics.Event.LastAudioQueued)
    }

    private fun recordCapturedFrame(
        holder: ActiveLiveSession,
        capture: AudioPipeline,
        chunk: AudioChunk,
    ) {
        holder.metrics.incrementCapturedFrames()
        holder.capturedAudioDurationMs = saturatingAdd(
            holder.capturedAudioDurationMs,
            chunk.frameMillis.toLong().coerceAtLeast(0L),
        )
        val reportedDepth = capture.queuedFrameDepth(chunk).coerceAtLeast(0)
        val boundedDepth = capture.frameQueueCapacity.takeIf { it > 0 }
            ?.let { reportedDepth.coerceAtMost(it) }
            ?: reportedDepth
        holder.metrics.recordFrameDequeued(
            depthFrames = boundedDepth,
            capturedAtMonotonicNanos = chunk.capturedAtMonotonicNanos,
        )
    }

    private suspend fun drainPreReady(
        holder: ActiveLiveSession,
        session: GeminiLiveSession,
        preReady: PreReadyAudioBuffer,
    ): Boolean {
        val buffered = preReady.drain()
        for ((index, chunk) in buffered.withIndex()) {
            holder.metrics.recordFrameDequeued(
                depthFrames = buffered.lastIndex - index,
                capturedAtMonotonicNanos = chunk.capturedAtMonotonicNanos,
            )
            if (!sendChunk(holder, session, chunk)) return false
        }
        return true
    }

    private suspend fun sendChunk(
        holder: ActiveLiveSession,
        session: GeminiLiveSession,
        chunk: AudioChunk,
    ): Boolean =
        when (val result = session.sendAudio(chunk)) {
            SendResult.Accepted -> {
                holder.metrics.incrementAcceptedFrames()
                holder.metrics.mark(MutableSessionMetrics.Event.FirstAudioQueued)
                true
            }
            is SendResult.Rejected -> {
                holder.metrics.incrementRejectedFrames()
                fail(holder, transportFailure(result.reason))
                false
            }
        }

    private suspend fun publishAmplitude(holder: ActiveLiveSession) {
        val capture = holder.capture ?: return
        capture.amplitude.collect { level ->
            // UI publication only applies while this holder is still Listening.
            val state = lastPublished
            if (state is DictationState.Listening && state.sessionId == holder.sessionId) {
                publish(state.copy(amplitude = level))
            }
        }
    }

    /**
     * Auto-stop watcher (0.4.0): a hard recording cap ([Config.maxRecordingSeconds])
     * and a silence auto-stop ([Config.autoStopSeconds]) — whichever fires first —
     * both ending through the same [stop] path as a user STOP. Elapsed time is
     * tracked in check-ticks so virtual-time tests drive it.
     */
    private suspend fun runAutoStop(holder: ActiveLiveSession) {
        val silenceTimeoutMs = config.autoStopSeconds() * 1000L
        val maxTimeoutMs = config.maxRecordingSeconds() * 1000L
        val segmentEnabled = config.segmentAtSilence()
        if (silenceTimeoutMs <= 0 && maxTimeoutMs <= 0 && !segmentEnabled) return
        val capture = holder.capture ?: return
        var elapsedMs = 0L
        var silenceMs = 0L
        var segmentSilenceMs = 0L
        while (active === holder && isListening(holder)) {
            delay(AUTO_STOP_CHECK_MS)
            elapsedMs += AUTO_STOP_CHECK_MS
            val speaking = capture.amplitude.value >= config.speechAmplitudeThreshold
            silenceMs = if (speaking) 0L else silenceMs + AUTO_STOP_CHECK_MS
            segmentSilenceMs = if (speaking) 0L else segmentSilenceMs + AUTO_STOP_CHECK_MS
            val capHit = maxTimeoutMs > 0 && elapsedMs >= maxTimeoutMs
            val silenceHit = silenceTimeoutMs > 0 && silenceMs >= silenceTimeoutMs
            if (capHit || silenceHit) {
                stop()
                break
            }
            // 0.6.0 experimental segmentation: close the current activity at a
            // pause and reopen it, so the model echoes the finished segment
            // while the user keeps speaking the next one.
            if (segmentEnabled && holder.activityStarted && segmentSilenceMs >= config.segmentSilenceMs) {
                segment(holder)
                segmentSilenceMs = 0L
            }
        }
    }

    /**
     * 0.6.0 experimental: ends the current activity and immediately reopens it.
     * Both calls are suspension-free in the manual-activity path, so the reopen
     * lands before the next audio frame. Device-pending: only valid when
     * `activityHandling = NO_INTERRUPTION` lets the previous segment's echo
     * continue generating.
     */
    private suspend fun segment(holder: ActiveLiveSession) {
        val session = holder.session ?: return
        if (!holder.activityStarted) return
        if (session.endActivity() !is SendResult.Accepted) return
        holder.activityStarted = session.startActivity() is SendResult.Accepted
    }

    // ------------------------------------------------------------------
    // Events
    // ------------------------------------------------------------------

    private fun onLiveEvent(holder: ActiveLiveSession, event: GeminiEvent) {
        if (active !== holder) return
        when (event) {
            GeminiEvent.Ready -> Unit
            is GeminiEvent.TranscriptCandidates -> {
                // 0.8.0: the raw transcription (INPUT) is the only dictation
                // source. The echo channel is not enabled, so any ECHO candidate
                // is ignored.
                if (event.source == GeminiEvent.TranscriptSource.ECHO) return
                event.candidates.forEach { candidate ->
                    // 1.0.7: only committed FINAL segments feed the settlement
                    // accumulator. Interims are preview-only and are never
                    // inserted; they still reset the quiet / tail barriers so
                    // the model is not cut off while it is still transcribing.
                    val accepted = if (event.isFinal) {
                        holder.accumulator.acceptAuthoritative(candidate.raw)
                    } else {
                        holder.previewAccumulator.acceptWithResult(candidate.raw)
                    }
                    if (accepted.changed) {
                        holder.metrics.recordInputRevision()
                        onTranscriptRevision(holder)
                    }
                }
                // 1.0.0: only a committed final segment that lands after the
                // activity-end boundary proves the tail is done. Pre-boundary
                // finals (segments committed during speech) must not satisfy the
                // settlement gate.
                if (event.isFinal && holder.activityEndQueued) {
                    holder.tailFinalSeen = true
                }
            }
            GeminiEvent.TurnComplete -> onTurnComplete(holder)
            GeminiEvent.GenerationComplete -> onGenerationComplete(holder)
            GeminiEvent.Interrupted -> Unit // no echo generation to interrupt (0.8.0)
            is GeminiEvent.GoAway -> Unit // advance notice; not a terminal event
            is GeminiEvent.Failed -> fail(holder, event.failure)
            GeminiEvent.SessionEnd -> if (!isInserting(holder)) {
                fail(
                    holder,
                    DictationFailure(
                        code = "gemini_transport",
                        message = "The Gemini session closed.",
                        recoverable = true,
                    ),
                )
            }
        }
    }

    private fun onCaptureFailure(holder: ActiveLiveSession, failure: DictationFailure) {
        if (active !== holder) return
        fail(holder, failure)
    }

    // ------------------------------------------------------------------
    // Finalization
    // ------------------------------------------------------------------

    private fun startFinalization(holder: ActiveLiveSession) {
        val session = holder.session ?: return
        val capture = holder.capture
        // Orderly producer drain before the completion boundary: request stop,
        // let the producer flush its final partial frame and close the channel,
        // join the ordered sender, then send the boundary.
        holder.finalizationJob = scope.launch {
            // AudioRecord stop/release are blocking native calls; never run them
            // on the main dispatcher at STOP time (0.6.0).
            val quiesced = withContext(config.captureShutdownDispatcher) {
                capture?.requestStop()
                val done = capture?.awaitQuiescence(config.captureShutdownTimeoutMs) ?: true
                if (!done) capture.stop()
                done
            }
            // streamAudio terminates in every case (capture close while connected,
            // or a bounded readyAfterStopTimeout wait while cold), so joining it
            // cannot hang finalization; the join preserves the activityStart ->
            // audio -> activityEnd wire order before the completion boundary.
            holder.audioJob?.join()
            holder.metrics.mark(MutableSessionMetrics.Event.CaptureQuiesced)
            when (val result = session.endActivity()) {
                is SendResult.Rejected -> {
                    fail(holder, transportFailure(result.reason))
                    return@launch
                }
                SendResult.Accepted -> {
                    holder.metrics.mark(MutableSessionMetrics.Event.ActivityEndQueued)
                    holder.activityEndQueued = true
                    afterActivityEnd(holder)
                }
            }
        }
    }

    /**
     * Every actual ASR text change invalidates the quiet barrier. Exact
     * duplicates are filtered by [TranscriptAccumulator] and do not extend
     * settlement.
     */
    private fun onTranscriptRevision(holder: ActiveLiveSession) {
        if (active !== holder) return
        if (!isFinalizing(holder)) return
        holder.transcriptQuiet = false
        if (holder.activityEndQueued) {
            // 1.0.6: the tail backstop is rearmed on every post-boundary revision
            // so a model still streaming the tail (or a slow final segment) can
            // never be cut off by the old fixed boundary timer.
            restartAsrTailBackstop(holder)
            restartQuietDebounce(holder)
        }
    }

    /** 1.0.6: rearmable tail backstop — fires after [Config.asrTailTimeoutMs] of
     *  no post-boundary transcription activity. */
    private fun restartAsrTailBackstop(holder: ActiveLiveSession) {
        holder.asrTailJob?.cancel()
        holder.asrTailJob = scope.launch {
            delay(config.asrTailTimeoutMs)
            if (active === holder && isFinalizing(holder) && holder.activityEndQueued) {
                holder.asrTailElapsed = true
                reevaluateSettlement(holder)
            }
        }
    }

    /** Server lifecycle events are hints; transcript quiet is still mandatory. */
    private fun onTurnComplete(holder: ActiveLiveSession) {
        if (active !== holder) return
        holder.turnCompleteSeen = true
        holder.metrics.recordTurnComplete()
        onLifecycleHint(holder)
    }

    private fun onGenerationComplete(holder: ActiveLiveSession) {
        if (active !== holder) return
        holder.generationCompleteSeen = true
        holder.metrics.recordGenerationComplete()
        onLifecycleHint(holder)
    }

    private fun onLifecycleHint(holder: ActiveLiveSession) {
        if (!isFinalizing(holder) || !holder.activityEndQueued) return
        if (holder.transcriptQuiet) {
            reevaluateSettlement(holder)
        } else if (hasTranscriptEvidence(holder) && holder.settleJob?.isActive != true) {
            restartQuietDebounce(holder)
        }
    }

    /**
     * 0.8.0: the whole post-boundary wait. One quiet debounce over ASR
     * revisions, plus the [Config.asrTailTimeoutMs] backstop (rearmed by every
     * revision — 1.0.6). No echo barriers, no generation gates.
     */
    private fun afterActivityEnd(holder: ActiveLiveSession) {
        if (active !== holder) return
        holder.asrTailElapsed = false
        restartAsrTailBackstop(holder)
        if (hasTranscriptEvidence(holder)) {
            restartQuietDebounce(holder)
        }
    }

    /** Resets the ASR quiet debounce; never extends the tail backstop. */
    private fun restartQuietDebounce(holder: ActiveLiveSession) {
        holder.settleJob?.cancel()
        holder.transcriptQuiet = false
        holder.settleJob = scope.launch {
            delay(config.settleDebounceMs)
            if (active === holder && isFinalizing(holder) && holder.activityEndQueued) {
                holder.transcriptQuiet = true
                reevaluateSettlement(holder)
            }
        }
    }

    private fun reevaluateSettlement(holder: ActiveLiveSession) {
        if (active !== holder || !isFinalizing(holder) || !holder.activityEndQueued) return
        val hasText = holder.accumulator.settledText()?.isNotBlank() == true
        // The tail backstop is the only hard stop: settle on whatever exists,
        // or fail explicitly when nothing arrived.
        if (holder.asrTailElapsed) {
            settle(
                holder,
                if (holder.turnCompleteSeen) {
                    SettlementReason.TURN_COMPLETE_QUIET
                } else {
                    SettlementReason.RAW_FALLBACK_TIMEOUT
                },
            )
            return
        }
        if (!holder.transcriptQuiet || !hasText) return
        // 1.0.0: never settle on provisional text. The transcribe model commits
        // final segments incrementally during speech; the tail segment's final
        // arrives only after activityEnd. Quiet alone is not enough — wait for
        // a post-boundary final segment (or a server done-hint); only the tail
        // backstop above may settle on a partial.
        if (!holder.tailFinalSeen && !holder.turnCompleteSeen && !holder.generationCompleteSeen) return
        holder.metrics.recordQuietBarrierSatisfied()
        settle(
            holder,
            if (holder.turnCompleteSeen) {
                SettlementReason.TURN_COMPLETE_QUIET
            } else {
                SettlementReason.RAW_FALLBACK_TIMEOUT
            },
        )
    }

    private fun hasTranscriptEvidence(holder: ActiveLiveSession): Boolean =
        holder.accumulator.settledText()?.isNotBlank() == true ||
            holder.previewAccumulator.settledText()?.isNotBlank() == true

    /**
     * 0.8.0 settlement: one source (the raw transcription), no text stage.
     * The server-side `smart` mode already shapes the transcript; the raw
     * text is inserted verbatim. No echo, no script branch.
     */
    private fun settle(holder: ActiveLiveSession, reason: SettlementReason) {
        if (active !== holder || !isFinalizing(holder) || !holder.activityEndQueued) return
        holder.settleJob?.cancel()
        holder.asrTailJob?.cancel()
        holder.metrics.recordSettlement(reason)
        if (holder.asrTailElapsed) {
            holder.metrics.usedHardDeadline = true
        }
        val raw = settlementRawText(holder)
        holder.metrics.settlePath = when {
            raw == null -> SettlePath.NONE
            holder.accumulator.settledText()?.isNotBlank() == true -> SettlePath.RAW_ONLY
            else -> SettlePath.PREVIEW_FALLBACK
        }
        val candidate = raw?.let {
            ResultCandidate(raw = it, cleaned = null, language = holder.language)
        }
        val selection = candidate?.let { selector.select(listOf(it)) } ?: TranscriptSelection.None
        when (selection) {
            is TranscriptSelection.None -> {
                // Failsafe: the source is the user's own ASR speech, so a strict
                // rejection is not final. Log WHY it was rejected, then accept it
                // unless it is truly unusable (blank, garbled, or a clearly
                // provisional fragment at the hard deadline).
                val diagnosis = candidate?.let { selector.diagnose(listOf(it)) }
                holder.metrics.lastRejection = diagnosis?.rule?.name
                if (candidate != null &&
                    lenientAccept(holder, candidate, diagnosis) &&
                    !isFragmentForDuration(holder, candidate.raw)
                ) {
                    holder.metrics.usedLenientFallback = true
                    holder.settledText = candidate.raw
                    insertSettled(holder, candidate.raw)
                } else {
                    if (candidate != null && isFragmentForDuration(holder, candidate.raw)) {
                        failFragment(holder)
                    } else {
                        failNoTranscript(holder)
                    }
                }
            }
            is TranscriptSelection.Cleaned, is TranscriptSelection.Raw -> {
                // E6: a clearly provisional fragment at the hard deadline is
                // rejected rather than silently inserted.
                if (holder.metrics.usedHardDeadline && isClearlyProvisional(selection.text)) {
                    failNoTranscript(holder)
                    return
                }
                // 0.6.1: a long recording that settled on a tiny transcript is a
                // failed dictation (the echo condensed and could not be recovered);
                // never insert the fragment silently.
                if (isFragmentForDuration(holder, selection.text)) {
                    failFragment(holder)
                    return
                }
                holder.settledText = selection.text
                insertSettled(holder, selection.text)
            }
        }
    }

    /** Final committed text first; at the tail backstop, fall back to the last
     *  revisable interim when the model never emitted a final segment. */
    private fun settlementRawText(holder: ActiveLiveSession): String? {
        holder.accumulator.settledText()?.takeIf { it.isNotBlank() }?.let { return it }
        if (holder.asrTailElapsed) {
            return holder.previewAccumulator.settledText()?.takeIf { it.isNotBlank() }
        }
        return null
    }

    /**
     * handles code-mixing natively (a `hi-IN` language hint is sent in setup),
     * and whatever text it returns is inserted verbatim — including, at worst,
     * Devanagari, which is inserted as-is rather than hard-failing the session.
     */
    private fun lenientAccept(
        holder: ActiveLiveSession,
        candidate: ResultCandidate,
        diagnosis: RejectionDiagnosis?,
    ): Boolean {
        val text = candidate.raw
        if (text.isBlank() || !text.any { it.isLetterOrDigit() }) return false
        if (diagnosis?.rule == RejectionRule.GARBLED) return false
        if (holder.metrics.usedHardDeadline && text.trim().length < 2) return false
        return true
    }

    private fun insertSettled(holder: ActiveLiveSession, text: String) {
        holder.metrics.mark(MutableSessionMetrics.Event.InsertionRequested)
        if (!host.sendInsertion(holder.sessionId, text)) {
            holder.metrics.recordTerminalOutcome(TerminalOutcome.TARGET_REJECTED)
            fail(holder, accessibilityUnavailable())
            return
        }
        holder.inserted = true
        publish(DictationState.Inserting(holder.sessionId))
        teardown(holder)
        if (config.insertionResultTimeoutMs > 0L) {
            holder.insertionResultJob = scope.launch {
                delay(config.insertionResultTimeoutMs)
                if (active === holder && holder.inserted && isInserting(holder)) {
                    holder.metrics.recordTerminalOutcome(TerminalOutcome.TIMED_OUT)
                    fail(holder, insertionResultTimeout())
                }
            }
        }
    }

    private fun failNoTranscript(holder: ActiveLiveSession) {
        holder.metrics.recordTerminalOutcome(TerminalOutcome.NO_RELIABLE_TRANSCRIPT)
        fail(
            holder,
            DictationFailure(
                code = "gemini_no_transcript",
                message = "No transcript could be recognized. Try again.",
                recoverable = true,
                retryAllowed = true,
            ),
        )
    }

    /** 0.6.1: a long recording whose settled text is far too short to be the
     *  whole dictation (the echo condensed and the repair could not recover it).
     *  Such a fragment is never inserted — the user retries instead. */
    private fun isFragmentForDuration(holder: ActiveLiveSession, text: String): Boolean {
        if (holder.capturedAudioDurationMs < FRAGMENT_MIN_RECORDING_MS) return false
        val words = TranscriptCompleteness.contentWords(text).size
        val expected = TranscriptCompleteness.expectedWords(holder.capturedAudioDurationMs)
        return expected > 0 && words < expected * FRAGMENT_MIN_RATIO
    }

    private fun failFragment(holder: ActiveLiveSession) {
        holder.metrics.recordTerminalOutcome(TerminalOutcome.NO_RELIABLE_TRANSCRIPT)
        fail(
            holder,
            DictationFailure(
                code = "gemini_transcript_fragment",
                message = "The dictation was too long to transcribe fully. Try again.",
                recoverable = true,
                retryAllowed = true,
            ),
        )
    }

    /** A bare 1-character fragment (e.g. "t" from "the") is a cut-off provisional
     *  transcript, never a completed utterance. */
    private fun isClearlyProvisional(text: String): Boolean = text.trim().length < 2

    // ------------------------------------------------------------------
    // Failure / teardown / reset
    // ------------------------------------------------------------------

    private fun fail(holder: ActiveLiveSession, failure: DictationFailure) {
        if (active !== holder) return
        holder.metrics.recordTerminalOutcome(
            when {
                failure.code.startsWith("gemini_") -> TerminalOutcome.TRANSPORT_FAILURE
                failure.code.startsWith("insert_") ||
                    failure.code == "runtime_no_accessibility" -> TerminalOutcome.TARGET_REJECTED
                else -> TerminalOutcome.ERROR
            },
        )
        publish(DictationState.Error(holder.sessionId, failure))
        teardown(holder)
        if (failure.retryAllowed) return // error persists until Retry/Dismiss
        scope.launch {
            delay(config.returnToIdleMs)
            resetToIdle(holder)
        }
    }

    /**
     * Re-starts dictation after a retryable Error state. Clears the errored
     * session and opens a fresh one; returns false while a session is still
     * running (i.e. not in an Error state).
     */
    fun retry(): Boolean {
        val holder = active
        if (holder != null) {
            val state = lastPublished
            if (state !is DictationState.Error || !state.failure.retryAllowed) return false
            resetToIdle(holder)
        }
        return start()
    }

    /** Dismisses a terminal Error panel and returns to Idle. */
    fun dismiss() {
        val holder = active ?: return
        if (lastPublished is DictationState.Error) {
            resetToIdle(holder)
        }
    }

    /** Cancels session resources; does NOT clear [active] (that happens on reset). */
    private fun teardown(holder: ActiveLiveSession) {
        holder.sessionJob?.cancel()
        holder.readyJob?.cancel()
        holder.settleJob?.cancel()
        holder.asrTailJob?.cancel()
        holder.finalizationJob?.cancel()
        holder.audioJob?.cancel()
        holder.amplitudeJob?.cancel()
        holder.autoStopJob?.cancel()
        holder.captureFailureJob?.cancel()
        holder.eventJob?.cancel()
        holder.insertionResultJob?.cancel()
        holder.capture?.let { capture ->
            scope.launch {
                // AudioRecord stop/release are blocking native calls; never run
                // them on the main dispatcher (0.6.0).
                withContext(config.captureShutdownDispatcher) {
                    capture.requestStop()
                    capture.awaitQuiescence(config.captureShutdownTimeoutMs)
                    capture.stop()
                }
            }
        }
        holder.session?.let { session ->
            scope.launch { session.close() }
        }
    }

    /** Compare-and-clear (C4): only clears if the holder is still the active one. */
    private fun resetToIdle(holder: ActiveLiveSession) {
        if (active === holder) {
            active = null
            host.onSessionFinished(lastPublished, holder.metrics, holder.settledText)
            publish(DictationState.Idle)
        }
    }

    // ------------------------------------------------------------------
    // State predicates
    // ------------------------------------------------------------------

    @Volatile
    private var lastPublished: DictationState = DictationState.Idle

    /** Tracks the last published state locally AND forwards it to the host. */
    private fun publish(state: DictationState) {
        lastPublished = state
        host.publish(state)
    }

    private fun isListening(holder: ActiveLiveSession): Boolean {
        val state = lastPublished
        return state is DictationState.Listening && state.sessionId == holder.sessionId
    }

    private fun isFinalizing(holder: ActiveLiveSession): Boolean {
        val state = lastPublished
        return state is DictationState.Finalizing && state.sessionId == holder.sessionId
    }

    private fun isInserting(holder: ActiveLiveSession): Boolean {
        val state = lastPublished
        return state is DictationState.Inserting && state.sessionId == holder.sessionId
    }

    private fun transportFailure(reason: String): DictationFailure = DictationFailure(
        code = "gemini_transport",
        message = "Gemini rejected a realtime send ($reason).",
        recoverable = true,
        retryAllowed = true,
    )

    private fun setupReadinessTimeout(): DictationFailure = DictationFailure(
        code = "gemini_setup_timeout",
        message = "Gemini did not become ready in time.",
        recoverable = true,
        retryAllowed = true,
    )

    private fun insertionResultTimeout(): DictationFailure = DictationFailure(
        code = "insert_result_timeout",
        message = "Could not confirm whether the text was inserted. It will not be sent again.",
        recoverable = true,
        retryAllowed = false,
    )

    private fun accessibilityUnavailable(): DictationFailure = DictationFailure(
        code = "runtime_no_accessibility",
        message = "Could not reach the accessibility service.",
        recoverable = true,
        retryAllowed = true,
    )

    /** F5: the pre-ready buffer overflowed, so the connection was too slow. */
    private fun connectionTooSlow(): DictationFailure = DictationFailure(
        code = "gemini_connection_too_slow",
        message = "The Gemini connection is too slow. Try again.",
        recoverable = true,
        retryAllowed = true,
    )

    private fun saturatingAdd(current: Long, increment: Long): Long =
        if (increment > Long.MAX_VALUE - current) Long.MAX_VALUE else current + increment

    companion object {
        /** Auto-stop watcher sampling interval (pure elapsed-time tick). */
        const val AUTO_STOP_CHECK_MS = 200L

        /** 0.6.1: recordings under this are never judged as "fragments" — short
         *  genuine utterances always pass. */
        const val FRAGMENT_MIN_RECORDING_MS = 5_000L

        /** 0.6.1: a settled transcript below this fraction of the words expected
         *  from the captured duration is a failed long dictation, never inserted. */
        const val FRAGMENT_MIN_RATIO = 0.3

        /** Placeholder target used while the accessibility process resolves the real one. */
        fun EMPTY_TARGET(sessionId: SessionId): TargetSnapshot = TargetSnapshot(
            sessionId = sessionId,
            packageName = "",
            displayId = 0,
            windowId = -1,
            editorIdentity = "",
            generation = 0L,
            inputTypeMask = 0,
            isSecure = false,
            isUncertain = false,
            selectionStart = null,
            selectionEnd = null,
            capturedAtMillis = 0L,
        )
    }
}
