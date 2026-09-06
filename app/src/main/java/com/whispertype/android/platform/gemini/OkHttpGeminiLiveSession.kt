package com.whispertype.android.platform.gemini

import android.util.Log
import com.whispertype.android.core.contracts.GeminiLiveSession
import com.whispertype.android.core.model.AudioChunk
import com.whispertype.android.core.model.DictationFailure
import com.whispertype.android.core.model.GeminiEvent
import com.whispertype.android.core.model.MutableSessionMetrics
import com.whispertype.android.core.model.ResultCandidate
import com.whispertype.android.core.model.SendResult
import com.whispertype.android.core.privacy.LogRedactor
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

/** Transport-level failure carrying a typed, non-sensitive [DictationFailure]. */
class GeminiLiveException(val failure: DictationFailure) : Exception(failure.message)

/** Ceiling for waiting out [OkHttpGeminiLiveSession.awaitReady]; tests override it. */
private const val DEFAULT_READY_TIMEOUT_MS = 15_000L

/**
 * [GeminiLiveSession] over an OkHttp [WebSocket] to the Gemini Live
 * `BidiGenerateContent` endpoint. Readiness is the server's `setupComplete`,
 * not socket-open ([awaitReady]). Setup is sent on open; audio is base64 PCM16
 * in `realtimeInput.audio` frames; the push-to-talk activity is delimited by an
 * explicit `realtimeInput.activityStart` / `activityEnd` pair (manual activity
 * detection, the production design), or `audioStreamEnd:true` under automatic
 * VAD.
 *
 * Session state machine (Release B4):
 *   Connecting -> Ready -> ActivityStarted -> ActivityEnded -> Closed
 *
 * Enforced rules:
 *  - [startActivity] before Ready is rejected; a duplicate start sends no wire
 *    message; start after end/close is rejected.
 *  - [sendAudio] before start or after end is rejected.
 *  - [endActivity] before start or after close is rejected; a duplicate end
 *    sends no wire message.
 *  - A failed `WebSocket.send` returns [SendResult.Rejected] immediately.
 *  - [close] is idempotent and takes the session to Closed from any state.
 *
 * Outbound state transitions and WebSocket sends share one lock, so setup,
 * activity boundaries, audio, and close cannot be reordered by concurrent
 * callers. The atomic state also lets OkHttp callbacks terminate the session.
 */
class OkHttpGeminiLiveSession(
    private val client: OkHttpClient,
    private val wsUrl: String,
    private val config: GeminiSessionConfig,
    private val metrics: MutableSessionMetrics? = null,
    private val sendTextFrame: (WebSocket, String) -> Boolean = { webSocket, text ->
        webSocket.send(text)
    },
    private val readyTimeoutMs: Long = DEFAULT_READY_TIMEOUT_MS,
) : GeminiLiveSession {

    private enum class State { Connecting, Ready, ActivityStarted, ActivityEnded, Closed }

    private val _events = Channel<GeminiEvent>(Channel.UNLIMITED)
    private val ready = CompletableDeferred<Unit>()
    private val closed = AtomicBoolean(false)
    private val state = AtomicReference(State.Connecting)
    private val outboundLock = Any()

    private var socket: WebSocket? = client.newWebSocket(
        Request.Builder().url(wsUrl).build(),
        listener(),
    )

    override suspend fun awaitReady() {
        try {
            withTimeout(readyTimeoutMs) { ready.await() }
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            val failure = failure(
                FAIL_SETUP,
                "Timed out waiting for the Gemini session to start.",
                recoverable = true,
            )
            // The session must self-clean on a setup timeout: take the terminal
            // state, emit the failure once, and tear the socket down instead of
            // leaving the transport open for the caller to reap.
            synchronized(outboundLock) {
                val transitioned = state.getAndSet(State.Closed) != State.Closed
                ready.completeExceptionally(GeminiLiveException(failure))
                if (transitioned && !closed.get()) {
                    _events.trySend(GeminiEvent.Failed(failure))
                }
                socket?.cancel()
            }
            throw GeminiLiveException(failure)
        }
    }

    override suspend fun startActivity(): SendResult = synchronized(outboundLock) {
        when (state.get()) {
            State.Connecting -> SendResult.Rejected(REASON_NOT_READY)
            State.Ready -> {
                val ws = socket
                if (ws == null) {
                    state.compareAndSet(State.Ready, State.Closed)
                    return@synchronized SendResult.Rejected(REASON_CLOSED)
                }
                if (config.automaticActivityDetectionDisabled) {
                    if (!sendTextFrame(ws, GeminiLiveWire.buildActivityStart())) {
                        state.set(State.Closed)
                        return@synchronized SendResult.Rejected(REASON_CLOSED)
                    }
                }
                if (!state.compareAndSet(State.Ready, State.ActivityStarted)) {
                    return@synchronized sendResultFor(state.get())
                }
                if (config.automaticActivityDetectionDisabled) {
                    metrics?.mark(MutableSessionMetrics.Event.ActivityStartQueued)
                }
                SendResult.Accepted
            }
            State.ActivityStarted -> SendResult.Accepted // duplicate start: no second wire message
            // 0.6.0 experimental segmentation: a completed activity is reopened
            // for the next segment (manual activity signaling is a repeated
            // start/end cycle, not single-shot).
            State.ActivityEnded -> {
                val ws = socket
                if (ws == null) {
                    state.compareAndSet(State.ActivityEnded, State.Closed)
                    return@synchronized SendResult.Rejected(REASON_CLOSED)
                }
                if (config.automaticActivityDetectionDisabled) {
                    if (!sendTextFrame(ws, GeminiLiveWire.buildActivityStart())) {
                        state.set(State.Closed)
                        return@synchronized SendResult.Rejected(REASON_CLOSED)
                    }
                }
                if (!state.compareAndSet(State.ActivityEnded, State.ActivityStarted)) {
                    return@synchronized sendResultFor(state.get())
                }
                if (config.automaticActivityDetectionDisabled) {
                    metrics?.mark(MutableSessionMetrics.Event.ActivityStartQueued)
                }
                SendResult.Accepted
            }
            State.Closed -> SendResult.Rejected(REASON_CLOSED)
        }
    }

    override suspend fun sendAudio(chunk: AudioChunk): SendResult = withContext(Dispatchers.IO) {
        synchronized(outboundLock) {
            when (state.get()) {
                State.Connecting -> return@synchronized SendResult.Rejected(REASON_NOT_READY)
                State.Ready -> return@synchronized SendResult.Rejected(REASON_AUDIO_BEFORE_START)
                State.ActivityEnded -> return@synchronized SendResult.Rejected(REASON_AUDIO_AFTER_END)
                State.Closed -> return@synchronized SendResult.Rejected(REASON_CLOSED)
                State.ActivityStarted -> Unit
            }
            val ws = socket ?: return@synchronized SendResult.Rejected(REASON_CLOSED)
            val dataBase64 = Base64.getEncoder().encodeToString(chunk.pcm16Bytes)
            val sent = sendTextFrame(
                ws,
                GeminiLiveWire.buildAudioChunk(dataBase64, chunk.sampleRateHz),
            )
            if (!sent) {
                failTransport(ws, DETAIL_AUDIO_SEND)
                return@synchronized SendResult.Rejected(REASON_CLOSED)
            }
            metrics?.recordWebSocketQueue(ws.queueSize().toInt())
            SendResult.Accepted
        }
    }

    override suspend fun endActivity(): SendResult = synchronized(outboundLock) {
        when (state.get()) {
            State.Connecting -> SendResult.Rejected(REASON_NOT_READY)
            State.Ready -> SendResult.Rejected(REASON_ACTIVITY_NOT_STARTED)
            State.ActivityStarted -> {
                val ws = socket
                if (ws == null) {
                    state.compareAndSet(State.ActivityStarted, State.Closed)
                    return@synchronized SendResult.Rejected(REASON_CLOSED)
                }
                val message =
                    if (config.automaticActivityDetectionDisabled) {
                        GeminiLiveWire.buildActivityEnd()
                    } else {
                        GeminiLiveWire.buildAudioStreamEnd()
                    }
                if (!sendTextFrame(ws, message)) {
                    state.set(State.Closed)
                    return@synchronized SendResult.Rejected(REASON_CLOSED)
                }
                if (!state.compareAndSet(State.ActivityStarted, State.ActivityEnded)) {
                    return@synchronized sendResultFor(state.get())
                }
                metrics?.mark(MutableSessionMetrics.Event.ActivityEndQueued)
                SendResult.Accepted
            }
            State.ActivityEnded -> SendResult.Accepted // duplicate end: no second wire message
            State.Closed -> SendResult.Rejected(REASON_CLOSED)
        }
    }

    override fun events(): Flow<GeminiEvent> = _events.receiveAsFlow()

    override suspend fun close() {
        synchronized(outboundLock) {
            if (closed.compareAndSet(false, true)) {
                state.set(State.Closed)
                socket?.close(NORMAL_CLOSE_CODE, "session closed")
                socket = null
                ready.completeExceptionally(CancellationException("Session closed"))
                _events.close()
            }
        }
    }

    private fun sendResultFor(s: State): SendResult = when (s) {
        State.Connecting -> SendResult.Rejected(REASON_NOT_READY)
        State.Ready -> SendResult.Rejected(REASON_NOT_READY)
        State.ActivityStarted -> SendResult.Accepted
        State.ActivityEnded -> SendResult.Rejected(REASON_ACTIVITY_ENDED)
        State.Closed -> SendResult.Rejected(REASON_CLOSED)
    }

    private fun listener(): WebSocketListener = object : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            GeminiLog.i(TAG, "onOpen code=${response.code} url=${redactUrl(wsUrl)}")
            metrics?.mark(MutableSessionMetrics.Event.SocketOpen)
            synchronized(outboundLock) {
                if (state.get() != State.Connecting) return@synchronized
                val sent = sendTextFrame(webSocket, GeminiLiveWire.buildSetup(config))
                GeminiLog.i(TAG, "setupSent=$sent")
                if (!sent) {
                    failTransport(webSocket, DETAIL_SETUP_SEND)
                }
            }
        }

        // The Gemini Live server sends every server->client message as a BINARY
        // frame (opcode 0x2), so OkHttp routes it to this overload rather than
        // the text one. Both decode to the same parser.
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            onMessage(webSocket, bytes.utf8())
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (state.get() == State.Closed) return
            when (val message = GeminiLiveWire.parseServerMessage(text)) {
                GeminiLiveWire.ServerMessage.SetupComplete -> {
                    if (state.compareAndSet(State.Connecting, State.Ready)) {
                        metrics?.mark(MutableSessionMetrics.Event.SetupComplete)
                        ready.complete(Unit)
                        _events.trySend(GeminiEvent.Ready)
                    }
                }

                is GeminiLiveWire.ServerMessage.SetupError -> {
                    val failure = failure(FAIL_SETUP, message.message, recoverable = true)
                    val transitioned = state.getAndSet(State.Closed) != State.Closed
                    ready.completeExceptionally(GeminiLiveException(failure))
                    if (transitioned && !closed.get()) {
                        _events.trySend(GeminiEvent.Failed(failure))
                    }
                }

                is GeminiLiveWire.ServerMessage.ServerContent -> onServerContent(message)

                is GeminiLiveWire.ServerMessage.GoAway ->
                    _events.trySend(GeminiEvent.GoAway(message.timeLeft))

                is GeminiLiveWire.ServerMessage.Unknown -> Unit
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            GeminiLog.w(TAG, "onFailure type=${t.javaClass.simpleName} httpCode=${response?.code}")
            val failure = failure(
                FAIL_TRANSPORT,
                sanitizeTransportDetail(t.message, DETAIL_CONNECTION_FAILED),
                recoverable = true,
            )
            val transitioned = state.getAndSet(State.Closed) != State.Closed
            ready.completeExceptionally(GeminiLiveException(failure))
            if (transitioned && !closed.get()) {
                _events.trySend(GeminiEvent.Failed(failure))
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            GeminiLog.i(TAG, "onClosing code=$code at=${System.currentTimeMillis()}")
            if (!closed.get() && !ready.isCompleted) {
                ready.completeExceptionally(
                    GeminiLiveException(
                        failure(
                            FAIL_SETUP,
                            sanitizeTransportDetail(
                                reason,
                                "Connection closed (code $code) before setup.",
                            ),
                            recoverable = true,
                        ),
                    ),
                )
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            GeminiLog.i(TAG, "onClosed code=$code")
            val transitioned = state.getAndSet(State.Closed) != State.Closed
            if (transitioned && !closed.get()) {
                if (!ready.isCompleted) {
                    ready.completeExceptionally(
                        GeminiLiveException(
                            failure(
                                FAIL_SETUP,
                                sanitizeTransportDetail(
                                    reason,
                                    "Connection closed (code $code) before setup.",
                                ),
                                recoverable = true,
                            ),
                        ),
                    )
                }
                _events.trySend(GeminiEvent.SessionEnd)
            }
        }
    }

    private fun onServerContent(message: GeminiLiveWire.ServerMessage.ServerContent) {
        // Aggregate debug-only counters and flags; never log transcript content,
        // audio, or full server frames. The string is only assembled when frame
        // logging is enabled — this runs per server frame.
        if (FRAME_LOGS_ENABLED) {
            GeminiLog.i(
                TAG,
                "serverContent: interimTx=${message.interimInputTranscription?.length ?: 0} inputTx=${message.inputTranscription?.length ?: 0} outputTx=${message.outputTranscription?.length ?: 0} textParts=${message.textParts.size} generationComplete=${message.generationComplete} interrupted=${message.interrupted} turnComplete=${message.turnComplete}",
            )
        }
        // 0.10.0: the transcription is the only dictation source. The transcribe
        // model streams revisable partials on interimInputTranscription and
        // committed final segments on inputTranscription. Only finals are
        // marked isFinal — the coordinator settles exclusively on them.
        // Never log transcript text.
        val m = metrics
        val interim = message.interimInputTranscription
        if (interim != null && interim.isNotEmpty()) {
            _events.trySend(
                GeminiEvent.TranscriptCandidates(
                    listOf(ResultCandidate(raw = interim, cleaned = null, language = config.language)),
                    source = GeminiEvent.TranscriptSource.INPUT,
                    isFinal = false,
                ),
            )
        }
        val inputTranscription = message.inputTranscription
        if (inputTranscription != null && inputTranscription.isNotEmpty()) {
            if (m != null) {
                m.inputTranscriptionCount += 1
                m.mark(MutableSessionMetrics.Event.FirstInputTranscript)
            }
            _events.trySend(
                GeminiEvent.TranscriptCandidates(
                    listOf(ResultCandidate(raw = inputTranscription, cleaned = null, language = config.language)),
                    source = GeminiEvent.TranscriptSource.INPUT,
                    isFinal = true,
                ),
            )
        }
        val outputTranscription = message.outputTranscription
        if (outputTranscription != null && outputTranscription.isNotEmpty()) {
            if (m != null) m.outputTranscriptionCount += 1
            _events.trySend(
                GeminiEvent.TranscriptCandidates(
                    listOf(ResultCandidate(raw = outputTranscription, cleaned = null, language = config.language)),
                    source = GeminiEvent.TranscriptSource.ECHO,
                ),
            )
        }
        if (message.generationComplete) {
            _events.trySend(GeminiEvent.GenerationComplete)
        }
        if (message.interrupted) {
            _events.trySend(GeminiEvent.Interrupted)
        }
        if (message.turnComplete) {
            if (m != null) {
                m.turnCompleteArrived = true
                m.mark(MutableSessionMetrics.Event.TurnComplete)
            }
            _events.trySend(GeminiEvent.TurnComplete)
        }
    }

    private fun failTransport(webSocket: WebSocket, detail: String) {
        val failure = failure(FAIL_TRANSPORT, detail, recoverable = true)
        val transitioned = state.getAndSet(State.Closed) != State.Closed
        ready.completeExceptionally(GeminiLiveException(failure))
        if (transitioned && !closed.get()) {
            _events.trySend(GeminiEvent.Failed(failure))
        }
        webSocket.cancel()
    }

    private fun failure(code: String, detail: String, recoverable: Boolean): DictationFailure =
        DictationFailure(
            code = code,
            message = detail,
            recoverable = recoverable,
            retryAllowed = recoverable,
        )

    private fun sanitizeTransportDetail(detail: String?, fallback: String): String =
        detail
            ?.takeIf { it.isNotBlank() }
            ?.let(LogRedactor::sanitize)
            ?: fallback

    private fun redactUrl(url: String): String = LogRedactor.sanitize(url)

    private companion object {
        const val TAG = "OkHttpGeminiLiveSession"

        /**
         * Resolved once per process: per-frame aggregate logs are debug-only, so
         * release/host builds never even build their strings. Enabled when the
         * platform has `log.tag.OkHttpGeminiLiveSession` at DEBUG; defaults to
         * false (and stays false on the host JVM, where `android.util.Log` is
         * a stub).
         */
        private val FRAME_LOGS_ENABLED: Boolean = try {
            Log.isLoggable(TAG, Log.DEBUG)
        } catch (_: Throwable) {
            false
        }
        const val NORMAL_CLOSE_CODE = 1000
        const val REASON_NOT_READY = "session_not_ready"
        const val REASON_CLOSED = "socket_closed"
        const val REASON_AUDIO_BEFORE_START = "audio_before_activity_start"
        const val REASON_AUDIO_AFTER_END = "audio_after_activity_end"
        const val REASON_ACTIVITY_NOT_STARTED = "activity_not_started"
        const val REASON_ACTIVITY_ENDED = "activity_ended"
        const val FAIL_SETUP = "gemini_setup"
        const val FAIL_TRANSPORT = "gemini_transport"
        const val DETAIL_SETUP_SEND = "The Gemini setup message could not be queued."
        const val DETAIL_AUDIO_SEND = "A Gemini audio frame could not be queued."
        const val DETAIL_CONNECTION_FAILED = "The Gemini connection failed."
    }
}