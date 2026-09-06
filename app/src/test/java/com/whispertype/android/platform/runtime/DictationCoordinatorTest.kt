package com.whispertype.android.platform.runtime

import com.whispertype.android.audio.AudioPipeline
import com.whispertype.android.audio.AudioStartResult
import com.whispertype.android.core.contracts.GeminiLiveSession
import com.whispertype.android.core.model.AudioChunk
import com.whispertype.android.core.model.CancelReason
import com.whispertype.android.core.model.DictationFailure
import com.whispertype.android.core.model.DictationState
import com.whispertype.android.core.model.GeminiEvent
import com.whispertype.android.core.model.InsertionResult
import com.whispertype.android.core.model.LanguageMode
import com.whispertype.android.core.model.MutableSessionMetrics
import com.whispertype.android.core.model.SendResult
import com.whispertype.android.core.model.SessionId
import com.whispertype.android.core.model.SettlePath
import com.whispertype.android.core.model.SettlementReason
import com.whispertype.android.core.model.TerminalOutcome
import kotlin.coroutines.ContinuationInterceptor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Host-testable orchestration races (Release C8), run under virtual time with a
 * fake [DictationHost], [GeminiLiveSession], and [AudioPipeline].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DictationCoordinatorTest {

    private class FakeSession : GeminiLiveSession {
        val events = Channel<GeminiEvent>(Channel.UNLIMITED)
        var readyError: Throwable? = null
        var readyGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        var endGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        var startResult: SendResult = SendResult.Accepted
        var endResult: SendResult = SendResult.Accepted
        var audioResult: SendResult = SendResult.Accepted
        var startCalls = 0
        var endCalls = 0
        var audioCalls = 0
        var closed = false
        val receivedChunks = mutableListOf<AudioChunk>()
        val wireCalls = mutableListOf<String>()

        override suspend fun awaitReady() {
            readyError?.let { throw it }
            readyGate?.await()
        }

        override suspend fun startActivity(): SendResult {
            startCalls++
            wireCalls += "start"
            return startResult
        }

        override suspend fun sendAudio(chunk: AudioChunk): SendResult {
            audioCalls++
            receivedChunks += chunk
            wireCalls += "audio:${chunk.sequence}"
            return audioResult
        }

        override suspend fun endActivity(): SendResult {
            endCalls++
            endGate?.await()
            wireCalls += "end"
            return endResult
        }

        override fun events() = events.receiveAsFlow()

        override suspend fun close() {
            closed = true
            events.close()
        }
    }

    private class FakeCapture : AudioPipeline {
        val chunksChannel = Channel<AudioChunk>(Channel.UNLIMITED)
        override val chunks = chunksChannel
        override val amplitude: StateFlow<Float> = MutableStateFlow(0f)
        private val _failures = MutableSharedFlow<DictationFailure>(replay = 1)
        override val failures = _failures
        var startResult = AudioStartResult.Started
        var stopRequested = false
        var stopCalls = 0
        var queueCapacity = 0
        var queueDepth = 0

        override val frameQueueCapacity: Int get() = queueCapacity

        override fun queuedFrameDepth(chunk: AudioChunk): Int = queueDepth

        fun setAmplitude(level: Float) {
            (amplitude as MutableStateFlow<Float>).value = level
        }

        override fun start(): AudioStartResult = startResult

        override fun requestStop() {
            stopRequested = true
            chunksChannel.close()
        }

        override suspend fun awaitQuiescence(timeoutMs: Long): Boolean = true

        override fun stop() {
            stopCalls++
            chunksChannel.close()
        }

        suspend fun fail(failure: DictationFailure) {
            _failures.emit(failure)
        }
    }

    private class FakeHost : DictationHost {
        val published = mutableListOf<DictationState>()
        val insertions = mutableListOf<Pair<SessionId, String>>()
        val session = FakeSession()
        val capture = FakeCapture()
        val finished = mutableListOf<Pair<DictationState, MutableSessionMetrics>>()
        val finishedTranscripts = mutableListOf<String?>()
        var resolveResult: SessionResolve = SessionResolve.Ok(SessionResolution(session, LanguageMode.ENGLISH))
        var captureStart: CaptureStart = CaptureStart.Started(capture)
        var insertionAccepted = true
        val transliterateCalls = mutableListOf<String>()
        var clipboardResult = true
        val clipboardCopies = mutableListOf<Pair<SessionId, String>>()

        override fun publish(state: DictationState) {
            published += state
        }

        override suspend fun resolveSession(metrics: MutableSessionMetrics): SessionResolve = resolveResult

        override suspend fun startCapture(metrics: MutableSessionMetrics): CaptureStart = captureStart

        override fun sendInsertion(sessionId: SessionId, text: String): Boolean {
            insertions += sessionId to text
            return insertionAccepted
        }

        override suspend fun copyToClipboard(sessionId: SessionId, text: String): Boolean {
            clipboardCopies += sessionId to text
            return clipboardResult
        }


        override fun onSessionFinished(state: DictationState, metrics: MutableSessionMetrics, transcript: String?) {
            finished += state to metrics
            finishedTranscripts += transcript
        }
    }

    private fun states(host: FakeHost): List<DictationState> = host.published

    private fun FakeHost.last(): DictationState = published.last()

    private fun listeningId(host: FakeHost): SessionId =
        (states(host).first { it is DictationState.Listening } as DictationState.Listening).sessionId

    private fun coordinator(scope: kotlinx.coroutines.test.TestScope, host: FakeHost): DictationCoordinator =
        DictationCoordinator(
            scope = scope,
            host = host,
            config = DictationCoordinator.Config(insertionResultTimeoutMs = 0)
                .withTestShutdownDispatcher(scope),
            metricsFactory = { sessionId ->
                MutableSessionMetrics(sessionId) { scope.testScheduler.currentTime * 1_000_000L }
            },
        )

    private fun coordinator(
        scope: kotlinx.coroutines.test.TestScope,
        host: FakeHost,
        config: DictationCoordinator.Config,
    ): DictationCoordinator =
        DictationCoordinator(
            scope = scope,
            host = host,
            config = config.copy(insertionResultTimeoutMs = 0).withTestShutdownDispatcher(scope),
            metricsFactory = { sessionId ->
                MutableSessionMetrics(sessionId) { scope.testScheduler.currentTime * 1_000_000L }
            },
        )

    /** Keeps the capture-shutdown dispatcher on the virtual scheduler so the
     *  host tests stay deterministic instead of hopping to Dispatchers.IO. */
    private fun DictationCoordinator.Config.withTestShutdownDispatcher(
        scope: kotlinx.coroutines.test.TestScope,
    ): DictationCoordinator.Config {
        val interceptor = scope.coroutineContext[ContinuationInterceptor]
        return if (interceptor is CoroutineDispatcher) {
            copy(captureShutdownDispatcher = interceptor)
        } else {
            this
        }
    }

    private fun chunk(
        seq: Long,
        frameMillis: Int = 20,
        capturedAtNanos: Long? = null,
    ): AudioChunk = AudioChunk(
        sequence = seq,
        pcm16Bytes = ByteArray(640) { it.toByte() },
        sampleRateHz = 16_000,
        frameMillis = frameMillis,
        capturedAtMonotonicNanos = capturedAtNanos,
    )

    @Test
    fun `duplicate START is rejected synchronously`() = runTest {
        val host = FakeHost()
        val coordinator = coordinator(this, host)
        assertTrue(coordinator.start())
        assertFalse(coordinator.start())
        advanceUntilIdle()
        coordinator.cancel()
        advanceUntilIdle()
    }

    @Test
    fun `cancel during setup publishes Cancelled then resets to Idle and allows restart`() = runTest {
        val host = FakeHost()
        val coordinator = coordinator(this, host)
        assertTrue(coordinator.start())
        coordinator.cancel()
        advanceUntilIdle()

        assertIs<DictationState.Cancelled>(states(host).first { it is DictationState.Cancelled })
        assertEquals(DictationState.Idle, states(host).last())
        // After the delayed reset the same coordinator accepts a new session.
        assertTrue(coordinator.start())
        advanceUntilIdle()
        assertIs<DictationState.Listening>(states(host).last())
        coordinator.cancel()
        advanceUntilIdle()
    }

    @Test
    fun `STOP immediately after listening starts finalizes then fails with no transcript and resets`() = runTest {
        val host = FakeHost()
        val coordinator = coordinator(this, host)
        coordinator.start()
        advanceUntilIdle()
        assertIs<DictationState.Listening>(states(host).last())
        assertEquals(1, host.session.startCalls)

        coordinator.stop()
        advanceUntilIdle()

        val finalizing = states(host).first { it is DictationState.Finalizing }
        assertEquals(1, host.session.endCalls)
        val error = states(host).first { it is DictationState.Error }
        assertEquals("gemini_no_transcript", (error as DictationState.Error).failure.code)
        assertTrue(error.failure.retryAllowed)
        assertTrue(host.insertions.isEmpty())
        // Retryable error persists until dismissed/retried.
        assertIs<DictationState.Error>(states(host).last())
        coordinator.dismiss()
        advanceUntilIdle()
        assertEquals(DictationState.Idle, states(host).last())
    }

    @Test
    fun `STOP with a transcript inserts exactly once and insertion result completes the session`() = runTest {
        val host = FakeHost()
        val coordinator = coordinator(this, host)
        coordinator.start()
        advanceUntilIdle()
        val sessionId = listeningId(host)

        coordinator.stop()
        // Trailing inputTranscription arrives after STOP; turn complete arrives too.
        host.session.events.send(
            GeminiEvent.TranscriptCandidates(
                listOf(com.whispertype.android.core.model.ResultCandidate(raw = "the birch canoe slid", cleaned = null, language = LanguageMode.ENGLISH)),
                isFinal = true,
            ),
        )
        host.session.events.send(GeminiEvent.TurnComplete)
        advanceUntilIdle()

        assertEquals(1, host.insertions.size)
        assertEquals("the birch canoe slid", host.insertions[0].second)
        assertIs<DictationState.Inserting>(states(host).last())

        coordinator.onInsertionResult(sessionId, InsertionResult.Inserted)
        advanceUntilIdle()
        assertIs<DictationState.Success>(states(host).first { it is DictationState.Success })
        assertEquals(DictationState.Idle, states(host).last())
    }

    @Test
    fun `late insertion response for a cancelled session is ignored`() = runTest {
        val host = FakeHost()
        val coordinator = coordinator(this, host)
        coordinator.start()
        advanceUntilIdle()
        val sessionId = listeningId(host)
        coordinator.cancel()
        advanceUntilIdle()

        coordinator.onInsertionResult(sessionId, InsertionResult.Inserted)
        advanceUntilIdle()
        assertTrue(states(host).none { it is DictationState.Success })
        assertEquals(DictationState.Idle, states(host).last())
    }

    @Test
    fun `capture read failure fails the session and tears down`() = runTest {
        val host = FakeHost()
        val coordinator = coordinator(this, host)
        coordinator.start()
        advanceUntilIdle()
        assertIs<DictationState.Listening>(states(host).last())

        host.capture.fail(DictationFailure(code = "MIC_READ", message = "Microphone read failed", recoverable = true))
        advanceUntilIdle()

        val error = states(host).first { it is DictationState.Error }
        assertEquals("MIC_READ", (error as DictationState.Error).failure.code)
        assertTrue(host.session.closed)
        assertEquals(DictationState.Idle, states(host).last())
    }

    @Test
    fun `interruption and go-away notice do not terminate an active session`() = runTest {
        val host = FakeHost()
        val coordinator = coordinator(this, host)
        coordinator.start()
        runCurrent()

        host.session.events.send(GeminiEvent.Interrupted)
        host.session.events.send(GeminiEvent.GoAway("12.5s"))
        runCurrent()

        assertIs<DictationState.Listening>(states(host).last())
        coordinator.cancel()
        advanceUntilIdle()
    }

    @Test
    fun `rejected activity start fails promptly with a transport failure`() = runTest {
        val host = FakeHost()
        host.session.startResult = SendResult.Rejected("socket_closed")
        val coordinator = coordinator(this, host)
        coordinator.start()
        advanceUntilIdle()

        val error = states(host).first { it is DictationState.Error }
        assertEquals("gemini_transport", (error as DictationState.Error).failure.code)
        assertTrue(error.failure.retryAllowed)
        // Retryable errors persist until the user dismisses or retries.
        assertIs<DictationState.Error>(states(host).last())
        coordinator.dismiss()
        advanceUntilIdle()
        assertEquals(DictationState.Idle, states(host).last())
    }

    @Test
    fun `STOP during Starting is ignored`() = runTest {
        val host = FakeHost()
        val coordinator = coordinator(this, host)
        coordinator.start()
        coordinator.stop() // before the session even resolves
        advanceUntilIdle()

        // Not finalizing: the session still reached Listening.
        assertTrue(states(host).none { it is DictationState.Finalizing })
        assertIs<DictationState.Listening>(states(host).last())
        coordinator.cancel()
        advanceUntilIdle()
    }

    @Test
    fun `no transcript before hard deadline produces no_transcript and single teardown`() = runTest {
        val host = FakeHost()
        val coordinator = coordinator(this, host)
        coordinator.start()
        advanceUntilIdle()
        coordinator.stop()
        advanceUntilIdle()

        assertTrue(host.session.closed)
        assertEquals(1, states(host).count { it is DictationState.Error })
        val error = states(host).first { it is DictationState.Error } as DictationState.Error
        assertTrue(error.failure.retryAllowed)
        assertIs<DictationState.Error>(states(host).last())
        coordinator.dismiss()
        advanceUntilIdle()
        assertEquals(DictationState.Idle, states(host).last())
    }

    // ------------------------------------------------------------------
    // Release E: transcript settlement and fast finalization
    // ------------------------------------------------------------------

    private suspend fun sendTranscript(host: FakeHost, text: String) {
        host.session.events.send(
            GeminiEvent.TranscriptCandidates(
                listOf(com.whispertype.android.core.model.ResultCandidate(raw = text, cleaned = null, language = LanguageMode.ENGLISH)),
                isFinal = true,
            ),
        )
    }

    /** Emits a revisable partial (interim) — never sufficient for settlement. */
    private suspend fun sendInterim(host: FakeHost, text: String) {
        host.session.events.send(
            GeminiEvent.TranscriptCandidates(
                listOf(com.whispertype.android.core.model.ResultCandidate(raw = text, cleaned = null, language = LanguageMode.ENGLISH)),
                isFinal = false,
            ),
        )
    }

    @Test
    fun `settlement never fires on a post-stop interim and waits for the tail final`() = runTest {
        // 1.0.0 regression guard for the transcribe model: the server commits
        // the tail segment's final AFTER activityEnd. A quiet interim must not
        // settle (that cut the end of sentences on device); the final must.
        val host = FakeHost()
        val coordinator = coordinator(this, host)
        coordinator.start()
        advanceUntilIdle()
        coordinator.stop()
        runCurrent()

        sendInterim(host, "the quick brown fox jumps over the lazy dog and the meeting is on")
        advanceTimeBy(400)
        runCurrent()
        assertTrue(host.insertions.isEmpty(), "a quiet interim must never settle the session")

        sendTranscript(host, "the quick brown fox jumps over the lazy dog and the meeting is on Thursday at 10")
        advanceTimeBy(250)
        runCurrent()

        assertEquals(1, host.insertions.size)
        assertEquals(
            "the quick brown fox jumps over the lazy dog and the meeting is on Thursday at 10",
            host.insertions[0].second,
            "the settled text must be the committed final, never the partial",
        )
        coordinator.onInsertionResult(host.insertions.single().first, InsertionResult.Inserted)
        advanceUntilIdle()
    }

    @Test
    fun `a smart-shortened final replaces the longer interim and is what settles`() = runTest {
        // 1.0.6: smart-mode finals can be SHORTER than the last interim (fillers
        // removed, no shared leading edge). The final is authoritative and must
        // replace the provisional text, not be dropped by revision heuristics.
        val host = FakeHost()
        val coordinator = coordinator(this, host)
        coordinator.start()
        advanceUntilIdle()
        coordinator.stop()
        runCurrent()

        sendInterim(host, "so um we should uh meet on thursday for the like project review at ten")
        advanceTimeBy(300)
        runCurrent()
        assertTrue(host.insertions.isEmpty(), "an interim must never settle")

        sendTranscript(host, "we should meet on thursday for the project review at 10")
        advanceTimeBy(250)
        runCurrent()

        assertEquals(1, host.insertions.size)
        assertEquals(
            "we should meet on thursday for the project review at 10",
            host.insertions[0].second,
            "the committed final must replace the interim even when shorter",
        )
        coordinator.onInsertionResult(host.insertions.single().first, InsertionResult.Inserted)
        advanceUntilIdle()
    }

    @Test
    fun `interims fall back at the tail backstop when no final arrives`() = runTest {
        // When the model streams only revisable partials, fail only after the
        // tail backstop — then insert the last interim rather than erroring out.
        val host = FakeHost()
        val coordinator = coordinator(this, host)
        coordinator.start()
        advanceUntilIdle()
        coordinator.stop()
        runCurrent()

        val interim = "so um we should uh meet on thursday for the like project review at ten"
        sendInterim(host, interim)
        advanceTimeBy(6_000)
        runCurrent()

        assertEquals(1, host.insertions.size, "tail backstop must fall back to the last interim")
        assertEquals(interim, host.insertions[0].second)
        assertEquals(SettlePath.PREVIEW_FALLBACK, coordinator.activeMetrics()!!.settlePath)
        coordinator.onInsertionResult(host.insertions.single().first, InsertionResult.Inserted)
        advanceUntilIdle()
    }

    @Test
    fun `transcript before turn completion settles via debounce and inserts the final revision`() = runTest {
        val host = FakeHost()
        val coordinator = coordinator(this, host)
        coordinator.start()
        advanceUntilIdle()
        coordinator.stop()
        runCurrent()

        sendTranscript(host, "schedule")
        host.session.events.send(GeminiEvent.TurnComplete)
        sendTranscript(host, "schedule the meeting")
        advanceUntilIdle()

        assertEquals(1, host.insertions.size)
        assertEquals("schedule the meeting", host.insertions[0].second)
        assertFalse(
            coordinator.activeMetrics()!!.usedHardDeadline,
            "settlement must come from the settle debounce, not the hard deadline",
        )
        coordinator.onInsertionResult(host.insertions[0].first, InsertionResult.Inserted)
        advanceUntilIdle()
    }

    @Test
    fun `multiple revisions during debounce settle exactly once with the longest value`() = runTest {
        val host = FakeHost()
        val coordinator = coordinator(this, host)
        coordinator.start()
        advanceUntilIdle()
        coordinator.stop()
        runCurrent()

        sendTranscript(host, "schedule")
        advanceTimeBy(100)
        sendTranscript(host, "schedule the")
        advanceTimeBy(100)
        sendTranscript(host, "schedule the meeting")
        advanceUntilIdle()

        assertEquals(1, host.insertions.size)
        assertEquals("schedule the meeting", host.insertions[0].second)
        assertFalse(coordinator.activeMetrics()!!.usedHardDeadline)
        coordinator.onInsertionResult(host.insertions[0].first, InsertionResult.Inserted)
        advanceUntilIdle()
    }

    @Test
    fun `settlement is prohibited until activity end is queued`() = runTest {
        val host = FakeHost()
        host.session.endGate = kotlinx.coroutines.CompletableDeferred()
        val coordinator = coordinator(this, host)
        coordinator.start()
        runCurrent()
        coordinator.stop()
        runCurrent()

        sendTranscript(host, "hello world")
        host.session.events.send(GeminiEvent.TurnComplete)
        runCurrent()
        advanceTimeBy(1_000)
        assertTrue(host.insertions.isEmpty())
        assertEquals(null, coordinator.activeMetrics()!!.activityEndQueuedAt)

        host.session.endGate!!.complete(Unit)
        runCurrent()
        // 0.8.0: one ASR quiet window (250 ms), no echo barrier.
        advanceTimeBy(249)
        assertTrue(host.insertions.isEmpty())
        advanceTimeBy(2)
        runCurrent()

        assertEquals("hello world", host.insertions.single().second)
        coordinator.onInsertionResult(host.insertions.single().first, InsertionResult.Inserted)
        advanceUntilIdle()
    }

    @Test
    fun `turn complete before STOP is retained and used during finalization`() = runTest {
        val host = FakeHost()
        val coordinator = coordinator(this, host)
        coordinator.start()
        advanceUntilIdle()
        // Server completes the turn while still listening (E7).
        host.session.events.send(GeminiEvent.TurnComplete)
        advanceTimeBy(1)

        coordinator.stop()
        runCurrent()
        sendTranscript(host, "hello world")
        advanceUntilIdle()

        assertEquals(1, host.insertions.size)
        assertEquals("hello world", host.insertions[0].second)
        assertFalse(coordinator.activeMetrics()!!.usedHardDeadline)
        coordinator.onInsertionResult(host.insertions[0].first, InsertionResult.Inserted)
        advanceUntilIdle()
    }

    @Test
    fun `cold session buffers pre-ready audio and drains in strict order once ready`() = runTest {
        val host = FakeHost()
        val session = host.session
        session.readyGate = kotlinx.coroutines.CompletableDeferred()
        val coordinator = coordinator(this, host)
        coordinator.start()
        advanceTimeBy(1)

        val connecting = states(host).filterIsInstance<DictationState.Listening>().last()
        assertTrue(connecting.connecting, "cold path must publish recording-while-connecting")

        host.capture.chunksChannel.send(chunk(0))
        host.capture.chunksChannel.send(chunk(1))
        advanceTimeBy(1)
        assertEquals(0, session.audioCalls, "audio must be buffered while the session connects")

        session.readyGate!!.complete(Unit)
        host.capture.chunksChannel.send(chunk(2))
        advanceTimeBy(1)

        assertEquals(listOf(0L, 1L, 2L), session.receivedChunks.map { it.sequence })
        assertFalse(
            states(host).filterIsInstance<DictationState.Listening>().last().connecting,
            "the connecting indicator must clear once the session is ready",
        )
        coordinator.cancel()
        advanceUntilIdle()
    }

    @Test
    fun `STOP before readiness waits then preserves start audio end ordering`() = runTest {
        val host = FakeHost()
        host.session.readyGate = kotlinx.coroutines.CompletableDeferred()
        val coordinator = coordinator(
            this,
            host,
            DictationCoordinator.Config(readyAfterStopTimeoutMs = 1_000),
        )
        coordinator.start()
        runCurrent()

        host.capture.chunksChannel.send(chunk(0))
        host.capture.chunksChannel.send(chunk(1))
        runCurrent()
        coordinator.stop()
        runCurrent()

        assertTrue(host.session.wireCalls.isEmpty(), "capture closure is not readiness")
        assertEquals(0, host.session.endCalls)

        host.session.readyGate!!.complete(Unit)
        runCurrent()

        assertEquals(
            listOf("start", "audio:0", "audio:1", "end"),
            host.session.wireCalls,
        )
        coordinator.cancel()
        advanceUntilIdle()
    }

    @Test
    fun `STOP before readiness fails after the bounded wait`() = runTest {
        val host = FakeHost()
        host.session.readyGate = kotlinx.coroutines.CompletableDeferred()
        val coordinator = coordinator(
            this,
            host,
            DictationCoordinator.Config(readyAfterStopTimeoutMs = 500),
        )
        coordinator.start()
        runCurrent()
        coordinator.stop()
        runCurrent()

        advanceTimeBy(501)
        runCurrent()

        val error = states(host).filterIsInstance<DictationState.Error>().last()
        assertEquals("gemini_setup_timeout", error.failure.code)
        assertTrue(host.session.wireCalls.isEmpty())
        coordinator.dismiss()
        advanceUntilIdle()
    }

    @Test
    fun `rejected audio send fails immediately and never queues activity end`() = runTest {
        val host = FakeHost()
        host.session.audioResult = SendResult.Rejected("socket_closed")
        val coordinator = coordinator(this, host)
        coordinator.start()
        runCurrent()

        host.capture.chunksChannel.send(chunk(0))
        runCurrent()

        val error = states(host).filterIsInstance<DictationState.Error>().last()
        assertEquals("gemini_transport", error.failure.code)
        assertEquals(1L, coordinator.activeMetrics()!!.rejectedFrames)
        assertEquals(0L, coordinator.activeMetrics()!!.acceptedFrames)
        assertEquals(0, host.session.endCalls)
        coordinator.dismiss()
        advanceUntilIdle()
    }

    @Test
    fun `audio queue metadata records atomic depth and age high water marks`() = runTest {
        val host = FakeHost()
        host.capture.queueCapacity = 8
        host.capture.queueDepth = 5
        val coordinator = coordinator(this, host)
        coordinator.start()
        advanceTimeBy(25)

        host.capture.chunksChannel.send(chunk(0, capturedAtNanos = 0L))
        runCurrent()

        val metrics = coordinator.activeMetrics()!!
        assertEquals(1L, metrics.capturedFrames)
        assertEquals(1L, metrics.acceptedFrames)
        assertEquals(5, metrics.maxFrameQueueDepth)
        assertEquals(25L, metrics.maxFrameQueueAgeMs())
        coordinator.cancel()
        advanceUntilIdle()
    }

    @Test
    fun `cold session ready after four seconds does not overflow default buffer`() = runTest {
        val host = FakeHost()
        val session = host.session
        session.readyGate = kotlinx.coroutines.CompletableDeferred()
        val coordinator = coordinator(this, host)
        coordinator.start()
        advanceTimeBy(1)

        repeat(200) { index ->
            host.capture.chunksChannel.send(chunk(index.toLong()))
            advanceTimeBy(20)
        }
        runCurrent()

        assertFalse(states(host).any { it is DictationState.Error })
        session.readyGate!!.complete(Unit)
        advanceUntilIdle()

        assertTrue(session.audioCalls > 0)
        coordinator.cancel()
        advanceUntilIdle()
    }

    @Test
    fun `resolveSession runs in parallel with startCapture`() = runTest {
        val host = FakeHost()
        val captureGate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val resolveGate = kotlinx.coroutines.CompletableDeferred<Unit>()
        var captureEntered = false
        var resolveEntered = false
        var overlapped = false

        val coordinator = DictationCoordinator(
            scope = this,
            host = object : DictationHost by host {
                override suspend fun startCapture(metrics: MutableSessionMetrics): CaptureStart {
                    captureEntered = true
                    if (resolveEntered) overlapped = true
                    captureGate.complete(Unit)
                    resolveGate.await()
                    return host.startCapture(metrics)
                }

                override suspend fun resolveSession(metrics: MutableSessionMetrics): SessionResolve {
                    resolveEntered = true
                    if (captureEntered) overlapped = true
                    resolveGate.complete(Unit)
                    captureGate.await()
                    return host.resolveSession(metrics)
                }
            },
            config = DictationCoordinator.Config(insertionResultTimeoutMs = 0)
                .withTestShutdownDispatcher(this),
            metricsFactory = { sessionId ->
                MutableSessionMetrics(sessionId) { testScheduler.currentTime * 1_000_000L }
            },
        )

        coordinator.start()
        advanceUntilIdle()

        assertTrue(overlapped, "capture and resolve must overlap")
        coordinator.cancel()
        advanceUntilIdle()
    }

    @Test
    fun `pre-ready buffer overflow fails with connection-too-slow`() = runTest {
        val host = FakeHost()
        val session = host.session
        session.readyGate = kotlinx.coroutines.CompletableDeferred() // never becomes ready
        val coordinator = coordinator(
            this,
            host,
            DictationCoordinator.Config(preReadyMaxFrames = 2),
        )
        coordinator.start()
        advanceTimeBy(1)

        host.capture.chunksChannel.send(chunk(0))
        host.capture.chunksChannel.send(chunk(1))
        advanceTimeBy(1)
        host.capture.chunksChannel.send(chunk(2)) // overflow
        advanceTimeBy(1)

        val error = states(host).first { it is DictationState.Error }
        assertEquals("gemini_connection_too_slow", (error as DictationState.Error).failure.code)
        assertTrue(session.audioCalls == 0)
        assertTrue(coordinator.activeMetrics()!!.audioBufferOverflow)
        advanceUntilIdle()
    }

    // ------------------------------------------------------------------
    // Failsafes: lenient fallback, rejection diagnostics, retry
    // ------------------------------------------------------------------

    @Test
    fun `lenient fallback inserts rejected user speech instead of erroring`() = runTest {
        val host = FakeHost()
        val coordinator = coordinator(this, host)
        coordinator.start()
        advanceUntilIdle()
        coordinator.stop()
        runCurrent()

        // English mode with a Devanagari transcript: strict validation rejects it
        // (DEVANAGARI), but the lenient failsafe inserts the user's own speech.
        sendTranscript(host, "नमस्ते दोस्तों")
        advanceUntilIdle()

        assertEquals(1, host.insertions.size)
        assertEquals("नमस्ते दोस्तों", host.insertions[0].second)
        assertEquals("DEVANAGARI", coordinator.activeMetrics()!!.lastRejection)
        assertTrue(coordinator.activeMetrics()!!.usedLenientFallback)
        coordinator.onInsertionResult(host.insertions[0].first, InsertionResult.Inserted)
        advanceUntilIdle()
    }

    @Test
    fun `retry clears a retryable error and starts a fresh session`() = runTest {
        val host = FakeHost()
        val coordinator = coordinator(this, host)
        coordinator.start()
        advanceUntilIdle()
        coordinator.stop()
        advanceUntilIdle()

        val error = states(host).first { it is DictationState.Error } as DictationState.Error
        assertTrue(error.failure.retryAllowed)

        assertTrue(coordinator.retry())
        advanceUntilIdle()
        assertIs<DictationState.Listening>(states(host).last())
        // The errored session logged its outcome exactly once.
        assertEquals(1, host.finished.count { it.first is DictationState.Error })
        coordinator.cancel()
        advanceUntilIdle()
    }

    @Test
    fun `retry is refused while a session is still running`() = runTest {
        val host = FakeHost()
        val coordinator = coordinator(this, host)
        coordinator.start()
        advanceUntilIdle()
        assertFalse(coordinator.retry(), "retry must be refused during an active session")
        coordinator.cancel()
        advanceUntilIdle()
    }

    // ------------------------------------------------------------------
    // 0.4.0: auto-stop timeout (silence + hard cap) and history transcript
    // ------------------------------------------------------------------

    private fun coordinatorWith(
        scope: kotlinx.coroutines.test.TestScope,
        host: FakeHost,
        autoStopSeconds: Long = 0,
        maxRecordingSeconds: Long = 0,
    ): DictationCoordinator =
        DictationCoordinator(
            scope = scope,
            host = host,
            config = DictationCoordinator.Config(
                autoStopSeconds = { autoStopSeconds },
                maxRecordingSeconds = { maxRecordingSeconds },
            ).withTestShutdownDispatcher(scope),
            metricsFactory = { sessionId -> MutableSessionMetrics(sessionId) { 0L } },
        )

    @Test
    fun `auto-stop hard cap finalizes even with continuous speech`() = runTest {
        val host = FakeHost()
        host.capture.setAmplitude(0.5f) // continuous speech: silence never accumulates
        val coordinator = coordinatorWith(this, host, maxRecordingSeconds = 2)
        coordinator.start()
        advanceTimeBy(1)
        assertIs<DictationState.Listening>(states(host).last())

        advanceTimeBy(2_200)
        assertTrue(states(host).any { it is DictationState.Finalizing }, "cap must fire at N seconds")
        assertEquals(1, host.session.endCalls)
        advanceUntilIdle()
        coordinator.cancel()
        advanceUntilIdle()
    }

    @Test
    fun `auto-stop silence fires after N seconds of no speech`() = runTest {
        val host = FakeHost()
        val coordinator = coordinatorWith(this, host, autoStopSeconds = 2)
        coordinator.start()
        advanceTimeBy(1)
        assertIs<DictationState.Listening>(states(host).last())
        host.capture.setAmplitude(0f) // silence

        advanceTimeBy(2_200)
        assertTrue(states(host).any { it is DictationState.Finalizing }, "silence must auto-stop")
        advanceUntilIdle()
        coordinator.cancel()
        advanceUntilIdle()
    }

    @Test
    fun `speech resets the silence timer so auto-stop waits for N seconds of quiet`() = runTest {
        val host = FakeHost()
        val coordinator = coordinatorWith(this, host, autoStopSeconds = 2)
        coordinator.start()
        advanceTimeBy(1)
        assertIs<DictationState.Listening>(states(host).last())

        // Speak at t=1s, go silent at t=2s.
        advanceTimeBy(1_000)
        host.capture.setAmplitude(0.5f)
        advanceTimeBy(1_000)
        host.capture.setAmplitude(0f)

        // Silence is now ~0s: the 2s silence timer must NOT fire at t≈2.2s.
        advanceTimeBy(200)
        assertFalse(states(host).any { it is DictationState.Finalizing }, "recent speech must hold off silence auto-stop")

        // ~2s of quiet later it fires.
        advanceTimeBy(2_000)
        assertTrue(states(host).any { it is DictationState.Finalizing })
        advanceUntilIdle()
        coordinator.cancel()
        advanceUntilIdle()
    }

    @Test
    fun `auto-stop never fires before Listening`() = runTest {
        val host = FakeHost()
        val coordinator = coordinatorWith(this, host, autoStopSeconds = 1)
        coordinator.start()
        coordinator.cancel() // cancelled while connecting/starting
        advanceTimeBy(2_000)
        assertFalse(states(host).any { it is DictationState.Finalizing })
        advanceUntilIdle()
    }

    @Test
    fun `segmentation closes and reopens the activity at a pause`() = runTest {
        // 0.6.0 experimental: with segmentAtSilence on, a quiet pause closes the
        // current activity and reopens it so the model echoes the segment while
        // the user keeps talking.
        val host = FakeHost()
        val coordinator = coordinator(
            this,
            host,
            DictationCoordinator.Config(segmentAtSilence = { true }, segmentSilenceMs = 700),
        )
        coordinator.start()
        runCurrent()
        advanceTimeBy(1)
        assertIs<DictationState.Listening>(states(host).last())
        assertEquals(listOf("start"), host.session.wireCalls)

        // Speak (no silence), then pause for >= 700ms.
        host.capture.setAmplitude(0.5f)
        advanceTimeBy(1_000)
        host.capture.setAmplitude(0f)
        advanceTimeBy(800)
        runCurrent()

        assertEquals(listOf("start", "end", "start"), host.session.wireCalls)
        coordinator.cancel()
        advanceUntilIdle()
    }

    @Test
    fun `segmentation does not fire without the setting or during speech`() = runTest {
        val host = FakeHost()
        val coordinator = coordinator(this, host) // segmentAtSilence default false
        coordinator.start()
        runCurrent()
        advanceTimeBy(1)
        assertIs<DictationState.Listening>(states(host).last())

        host.capture.setAmplitude(0f)
        advanceTimeBy(2_000)
        runCurrent()

        assertEquals(listOf("start"), host.session.wireCalls, "no activity reopen without the setting")
        coordinator.cancel()
        advanceUntilIdle()
    }

    @Test
    fun `onSessionFinished carries the settled transcript`() = runTest {
        val host = FakeHost()
        val coordinator = coordinator(this, host)
        coordinator.start()
        advanceUntilIdle()
        val sessionId = listeningId(host)
        coordinator.stop()
        sendTranscript(host, "hello world")
        advanceUntilIdle()

        assertEquals(1, host.insertions.size)
        coordinator.onInsertionResult(sessionId, InsertionResult.Inserted)
        advanceUntilIdle()
        assertEquals(listOf<String?>("hello world"), host.finishedTranscripts)
    }

    @Test
    fun `onSessionFinished transcript is null when nothing settled`() = runTest {
        val host = FakeHost()
        val coordinator = coordinator(this, host)
        coordinator.start()
        advanceUntilIdle()
        coordinator.stop()
        advanceUntilIdle()

        assertTrue(host.insertions.isEmpty())
        assertTrue(host.finishedTranscripts.isEmpty(), "a persistent retryable error has not reset yet")
        coordinator.dismiss()
        advanceUntilIdle()
        assertEquals(listOf<String?>(null), host.finishedTranscripts)
    }

    // ------------------------------------------------------------------
    // 0.4.1: echo (outputTranscription) is the primary dictation source
    // ------------------------------------------------------------------


    @Test
    fun `session settles on the raw ASR right after quiet elapses`() = runTest {
        // 0.8.0: the raw ASR is the only dictation source, so settlement is one
        // quiet debounce with no echo barriers at all.
        val host = FakeHost()
        host.resolveResult = SessionResolve.Ok(
            SessionResolution(host.session, LanguageMode.ENGLISH),
        )
        val coordinator = coordinator(this, host)
        coordinator.start()
        runCurrent()
        coordinator.stop()
        runCurrent()

        sendTranscript(host, "the birch canoe slid on the smooth planks")
        advanceTimeBy(249)
        assertTrue(host.insertions.isEmpty(), "must still wait for the quiet debounce")
        advanceTimeBy(2)
        runCurrent()

        assertEquals(1, host.insertions.size)
        assertEquals("the birch canoe slid on the smooth planks", host.insertions[0].second)
        coordinator.onInsertionResult(host.insertions.single().first, InsertionResult.Inserted)
        advanceUntilIdle()
    }

    @Test
    fun `session inserts raw ASR with no further waiting`() = runTest {
        val host = FakeHost()
        host.resolveResult = SessionResolve.Ok(
            SessionResolution(host.session, LanguageMode.ENGLISH),
        )
        val coordinator = coordinator(this, host)
        coordinator.start()
        runCurrent()
        coordinator.stop()
        runCurrent()

        // The server sends only inputTranscription (echo channel disabled).
        sendTranscript(host, "please order a large pepperoni")
        advanceTimeBy(300)
        runCurrent()

        assertEquals(1, host.insertions.size)
        assertEquals("please order a large pepperoni", host.insertions[0].second)
        assertEquals(SettlePath.RAW_ONLY, coordinator.activeMetrics()!!.settlePath)
        coordinator.onInsertionResult(host.insertions.single().first, InsertionResult.Inserted)
        advanceUntilIdle()
    }

    // ------------------------------------------------------------------
    // 0.6.1: long-dictation fragment guard
    // ------------------------------------------------------------------

    @Test
    fun `long recording with a tiny transcript fails with a fragment error instead of inserting`() = runTest {
        val host = FakeHost()
        val coordinator = coordinator(this, host)
        coordinator.start()
        runCurrent()
        // One 30 s "frame" drives the captured duration to 30 s.
        host.capture.chunksChannel.send(chunk(0, frameMillis = 30_000))
        runCurrent()
        coordinator.stop()
        runCurrent()

        // A 2-word transcript for a 30 s recording is a condensed echo fragment.
        // Echo never arrives, so the raw fallback settles after the 2 s grace.
        sendTranscript(host, "yes okay")
        advanceTimeBy(2_500)
        runCurrent()

        assertTrue(host.insertions.isEmpty(), "a fragment must never be inserted")
        val error = states(host).filterIsInstance<DictationState.Error>().last()
        assertEquals("gemini_transcript_fragment", error.failure.code)
        assertTrue(error.failure.retryAllowed)
        coordinator.dismiss()
        advanceUntilIdle()
    }

    @Test
    fun `short recording with a short transcript inserts normally`() = runTest {
        val host = FakeHost()
        val coordinator = coordinator(this, host)
        coordinator.start()
        runCurrent()
        host.capture.chunksChannel.send(chunk(0, frameMillis = 1_000))
        runCurrent()
        coordinator.stop()
        runCurrent()

        sendTranscript(host, "yes okay")
        advanceTimeBy(2_500)
        runCurrent()

        assertEquals(1, host.insertions.size)
        assertEquals("yes okay", host.insertions[0].second)
        coordinator.onInsertionResult(host.insertions.single().first, InsertionResult.Inserted)
        advanceUntilIdle()
    }

    @Test
    fun `long recording that settles on a genuinely long transcript still inserts`() = runTest {
        val host = FakeHost()
        val coordinator = coordinator(this, host)
        coordinator.start()
        runCurrent()
        host.capture.chunksChannel.send(chunk(0, frameMillis = 30_000))
        runCurrent()
        coordinator.stop()
        runCurrent()

        // A complete, appropriately long transcript passes the fragment guard.
        val full = "the quick brown fox jumps over the lazy dog and runs to the river " +
            "where it drinks water and rests under a tall tree for a while"
        sendTranscript(host, full)
        advanceTimeBy(2_500)
        runCurrent()

        assertEquals(1, host.insertions.size)
        assertEquals(full, host.insertions[0].second)
        coordinator.onInsertionResult(host.insertions.single().first, InsertionResult.Inserted)
        advanceUntilIdle()
    }

    @Test
    fun `Hinglish inserts the raw transcription verbatim`() = runTest {
        // 0.10.0: there is no script repair stage. The transcribe model handles
        // code-mixing natively (hi-IN language hint) and whatever text it
        // returns is inserted as-is — the dictation never hard-fails on script.
        val host = FakeHost()
        host.resolveResult = SessionResolve.Ok(
            SessionResolution(host.session, LanguageMode.HINGLISH),
        )
        val coordinator = coordinator(this, host)
        coordinator.start()
        advanceUntilIdle()
        coordinator.stop()
        runCurrent()

        sendTranscript(host, "aaj ka mausam bahut achha hai")
        advanceUntilIdle()

        assertEquals(1, host.insertions.size)
        assertEquals("aaj ka mausam bahut achha hai", host.insertions[0].second)
        coordinator.onInsertionResult(host.insertions.single().first, InsertionResult.Inserted)
        advanceUntilIdle()
    }

    @Test
    fun `missing insertion result times out without retrying the commit`() = runTest {
        val host = FakeHost()
        val coordinator = DictationCoordinator(
            scope = this,
            host = host,
            config = DictationCoordinator.Config(
                insertionResultTimeoutMs = 500,
                returnToIdleMs = 1_000,
            ).withTestShutdownDispatcher(this),
            metricsFactory = { sessionId ->
                MutableSessionMetrics(sessionId) { testScheduler.currentTime * 1_000_000L }
            },
        )
        coordinator.start()
        runCurrent()
        val sessionId = listeningId(host)
        coordinator.stop()
        runCurrent()
        sendTranscript(host, "commit this once")
        host.session.events.send(GeminiEvent.TurnComplete)
        runCurrent()
        advanceTimeBy(601)
        runCurrent()

        assertIs<DictationState.Inserting>(states(host).last())
        assertEquals(1, host.insertions.size)

        advanceTimeBy(501)
        runCurrent()
        val error = states(host).filterIsInstance<DictationState.Error>().last()
        assertEquals("insert_result_timeout", error.failure.code)
        assertFalse(error.failure.retryAllowed)
        assertEquals(TerminalOutcome.TIMED_OUT, coordinator.activeMetrics()!!.terminalOutcome)
        assertFalse(coordinator.retry(), "an ambiguous commit must never be retried")

        coordinator.onInsertionResult(sessionId, InsertionResult.Inserted)
        runCurrent()
        assertEquals(1, host.insertions.size)
        assertTrue(states(host).none { it is DictationState.Success })
        advanceUntilIdle()
        assertEquals(DictationState.Idle, states(host).last())
    }

    // ------------------------------------------------------------------
    // 0.5.8: clipboard fallback when the transcript cannot reach a field
    // ------------------------------------------------------------------

    @Test
    fun `failed insertion with no focused field copies the transcript to clipboard`() = runTest {
        val host = FakeHost()
        val coordinator = coordinator(this, host)
        coordinator.start()
        advanceUntilIdle()
        val sessionId = listeningId(host)
        coordinator.stop()
        sendTranscript(host, "hello world")
        advanceUntilIdle()

        coordinator.onInsertionResult(
            sessionId,
            InsertionResult.Failed(
                DictationFailure(
                    code = "insert_target_ineligible",
                    message = "No safe text field is focused. Not a password or secure field.",
                    recoverable = true,
                ),
            ),
        )
        advanceUntilIdle()

        assertEquals(listOf(sessionId to "hello world"), host.clipboardCopies)
        assertIs<DictationState.CopiedToClipboard>(
            states(host).first { it is DictationState.CopiedToClipboard },
        )
        assertEquals(DictationState.Idle, states(host).last())
        assertTrue(
            host.finished.last().second.copiedToClipboardAt != null,
            "the session must record the clipboard fallback metric",
        )
    }

    @Test
    fun `failed insertion with an unreachable input connection copies the transcript`() = runTest {
        val host = FakeHost()
        val coordinator = coordinator(this, host)
        coordinator.start()
        advanceUntilIdle()
        val sessionId = listeningId(host)
        coordinator.stop()
        sendTranscript(host, "schedule the meeting")
        advanceUntilIdle()

        coordinator.onInsertionResult(
            sessionId,
            InsertionResult.Failed(
                DictationFailure(
                    code = "insert_connection_unavailable",
                    message = "Could not reach the focused text field. Tap the field and try again.",
                    recoverable = true,
                ),
            ),
        )
        advanceUntilIdle()

        assertEquals(listOf(sessionId to "schedule the meeting"), host.clipboardCopies)
        assertIs<DictationState.CopiedToClipboard>(
            states(host).first { it is DictationState.CopiedToClipboard },
        )
        assertEquals(DictationState.Idle, states(host).last())
    }

    @Test
    fun `failed insertion with a stale target copies the transcript`() = runTest {
        val host = FakeHost()
        val coordinator = coordinator(this, host)
        coordinator.start()
        advanceUntilIdle()
        val sessionId = listeningId(host)
        coordinator.stop()
        sendTranscript(host, "the birch canoe slid")
        advanceUntilIdle()

        coordinator.onInsertionResult(
            sessionId,
            InsertionResult.Failed(
                DictationFailure(
                    code = "insert_target_stale",
                    message = "The focused field changed before insertion. Tap the field and try again.",
                    recoverable = true,
                ),
            ),
        )
        advanceUntilIdle()

        assertEquals(listOf(sessionId to "the birch canoe slid"), host.clipboardCopies)
        assertIs<DictationState.CopiedToClipboard>(
            states(host).first { it is DictationState.CopiedToClipboard },
        )
        assertEquals(DictationState.Idle, states(host).last())
    }

    @Test
    fun `a protected field failure never copies to clipboard and surfaces an error`() = runTest {
        val host = FakeHost()
        val coordinator = coordinator(this, host)
        coordinator.start()
        advanceUntilIdle()
        val sessionId = listeningId(host)
        coordinator.stop()
        sendTranscript(host, "do not leak me")
        advanceUntilIdle()

        coordinator.onInsertionResult(
            sessionId,
            InsertionResult.Failed(
                DictationFailure(
                    code = "insert_target_not_safe",
                    message = "A protected field was focused; text was not inserted.",
                    recoverable = true,
                ),
            ),
        )
        advanceUntilIdle()

        assertTrue(host.clipboardCopies.isEmpty(), "a secure field must never copy the transcript")
        val error = states(host).first { it is DictationState.Error } as DictationState.Error
        assertEquals("insert_target_not_safe", error.failure.code)
    }

    @Test
    fun `clipboard copy failure falls back to an Error surface`() = runTest {
        val host = FakeHost()
        host.clipboardResult = false
        val coordinator = coordinator(this, host)
        coordinator.start()
        advanceUntilIdle()
        val sessionId = listeningId(host)
        coordinator.stop()
        sendTranscript(host, "hello world")
        advanceUntilIdle()

        coordinator.onInsertionResult(
            sessionId,
            InsertionResult.Failed(
                DictationFailure(
                    code = "insert_target_ineligible",
                    message = "No safe text field is focused. Not a password or secure field.",
                    recoverable = true,
                ),
            ),
        )
        advanceUntilIdle()

        assertEquals(1, host.clipboardCopies.size)
        val error = states(host).first { it is DictationState.Error } as DictationState.Error
        assertEquals("insert_target_ineligible", error.failure.code)
        assertTrue(states(host).none { it is DictationState.CopiedToClipboard })
    }

    // ------------------------------------------------------------------
    // 0.8.0: raw-ASR settlement latency guards (no echo barriers)
    // ------------------------------------------------------------------

    @Test
    fun `settlement waits only one quiet window and never the old echo barriers`() = runTest {
        // 0.8.0 latency guard: post-stop settlement is a single 250 ms ASR quiet
        // window. The 0.6.2 stack (900 ms echo quiet + 2.5 s stall + 2 s grace +
        // 20 s deadline) is gone, so nothing may delay insertion beyond it.
        val host = FakeHost()
        host.resolveResult = SessionResolve.Ok(
            SessionResolution(host.session, LanguageMode.ENGLISH),
        )
        val coordinator = coordinator(this, host)
        coordinator.start()
        runCurrent()
        coordinator.stop()
        runCurrent()

        sendTranscript(host, "the birch canoe slid on the smooth planks")
        advanceTimeBy(249)
        assertTrue(host.insertions.isEmpty(), "must observe the quiet window")
        advanceTimeBy(2)
        runCurrent()

        assertEquals(1, host.insertions.size, "insertion must happen at the quiet window, not later")
        coordinator.onInsertionResult(host.insertions.single().first, InsertionResult.Inserted)
        advanceUntilIdle()
    }

    @Test
    fun `a silent session settles at the ASR tail backstop instead of hanging`() = runTest {
        // No transcript ever arrives: the single backstop must terminate the
        // session promptly (2.5 s), where 0.6.2 waited on the 20 s deadline.
        val host = FakeHost()
        host.resolveResult = SessionResolve.Ok(
            SessionResolution(host.session, LanguageMode.ENGLISH),
        )
        val coordinator = coordinator(this, host)
        coordinator.start()
        runCurrent()
        coordinator.stop()
        runCurrent()

        advanceTimeBy(5_999)
        assertTrue(states(host).none { it is DictationState.Error })
        advanceTimeBy(2)
        runCurrent()

        assertTrue(host.insertions.isEmpty())
        val error = states(host).first { it is DictationState.Error } as DictationState.Error
        assertEquals("gemini_no_transcript", error.failure.code)
    }
}
