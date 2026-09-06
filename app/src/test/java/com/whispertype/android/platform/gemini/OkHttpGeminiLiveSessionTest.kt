package com.whispertype.android.platform.gemini

import com.whispertype.android.core.contracts.GeminiLiveSession
import com.whispertype.android.core.model.AudioChunk
import com.whispertype.android.core.model.GeminiEvent
import com.whispertype.android.core.model.SendResult
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.encodeUtf8

/**
 * End-to-end WebSocket protocol tests against MockWebServer's WebSocket
 * upgrade. The server stands in for the Gemini Live endpoint: it acknowledges
 * setup, records client messages, and can push serverContent frames.
 */
class OkHttpGeminiLiveSessionTest {

    private lateinit var server: MockWebServer
    private lateinit var serverSocket: ServerSocket
    private var activeClient: OkHttpClient? = null

    @BeforeTest
    fun setUp() {
        server = MockWebServer()
        serverSocket = ServerSocket()
        server.enqueue(
            MockResponse.Builder()
                .webSocketUpgrade(serverSocket)
                .build(),
        )
        server.start()
    }

    @AfterTest
    fun tearDown() {
        activeClient?.dispatcher?.cancelAll()
        activeClient?.connectionPool?.evictAll()
        activeClient?.dispatcher?.executorService?.shutdown()
        server.close()
    }

    private fun newSession(
        config: GeminiSessionConfig = GeminiSessionConfig(model = "test-model"),
        target: MockWebServer = server,
        sendTextFrame: (WebSocket, String) -> Boolean = { webSocket, text ->
            webSocket.send(text)
        },
    ): OkHttpGeminiLiveSession {
        val client = OkHttpClient()
        activeClient = client
        return OkHttpGeminiLiveSession(
            client = client,
            wsUrl = target.url("/live").toString(),
            config = config,
            sendTextFrame = sendTextFrame,
        )
    }

    private fun CoroutineScope.bufferEvents(session: GeminiLiveSession): Channel<GeminiEvent> {
        val channel = Channel<GeminiEvent>(Channel.UNLIMITED)
        launch { session.events().collect { channel.trySend(it) } }
        return channel
    }

    private suspend fun <T> receiveWithTimeout(channel: Channel<T>): T = withTimeout(5_000) { channel.receive() }

    private fun awaitMessages(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(20)
        }
        fail("condition not met within ${timeoutMs}ms; messages=${serverSocket.clientMessages}")
    }

    private fun realtimeInputFrames(messages: List<String>): List<kotlinx.serialization.json.JsonObject> =
        messages.mapNotNull { raw ->
            val root = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return@mapNotNull null
            root["realtimeInput"]?.jsonObject
        }

    private fun countMessages(messages: List<String>, key: String): Int =
        messages.count { raw ->
            runCatching { Json.parseToJsonElement(raw).jsonObject["realtimeInput"]?.jsonObject?.containsKey(key) == true }
                .getOrDefault(false)
        }

    // ------------------------------------------------------------------

    @Test
    fun `setup is the first client message and carries the configured model and manual activity config`() = runBlocking {
        val session = newSession(
            GeminiSessionConfig(model = "gemini-test-live"),
        )
        session.awaitReady()

        awaitMessages { serverSocket.clientMessages.isNotEmpty() }
        val first = serverSocket.clientMessages.first()
        val setup = Json.parseToJsonElement(first).jsonObject["setup"]!!.jsonObject
        assertEquals("models/gemini-test-live", setup["model"]!!.jsonPrimitive.content)
        val modalities = setup["generationConfig"]!!.jsonObject["responseModalities"]!!
        assertEquals(
            listOf("TEXT"),
            (modalities as kotlinx.serialization.json.JsonArray).map { it.jsonPrimitive.content },
        )
        assertTrue(setup.containsKey("inputAudioTranscription"))
        assertFalse(
            setup.containsKey("outputAudioTranscription"),
            "0.8.0: the echo channel must never be requested",
        )
        val automaticActivityDetection = setup["realtimeInputConfig"]!!.jsonObject["automaticActivityDetection"]!!.jsonObject
        assertTrue(automaticActivityDetection["disabled"]!!.jsonPrimitive.boolean)
        assertFalse(setup.containsKey("systemInstruction"))
        assertFalse(first.contains("turns"))
        session.close()
    }

    @Test
    fun `awaitReady completes on setupComplete and emits Ready`() = runBlocking {
        val session = newSession()
        val events = bufferEvents(session)

        session.awaitReady()
        assertEquals(GeminiEvent.Ready, receiveWithTimeout(events))
        session.close()
    }

    @Test
    fun `setup send false fails readiness and emits one terminal transport failure`() = runBlocking {
        val noAckServer = MockWebServer()
        noAckServer.enqueue(
            MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {}).build(),
        )
        noAckServer.start()
        try {
            val session = newSession(
                target = noAckServer,
                sendTextFrame = { _, _ -> false },
            )
            val events = bufferEvents(session)

            val error = assertFailsWith<GeminiLiveException> {
                withTimeout(5_000) { session.awaitReady() }
            }
            assertEquals("gemini_transport", error.failure.code)
            assertEquals(
                "The Gemini setup message could not be queued.",
                error.failure.message,
            )
            val failed = assertIs<GeminiEvent.Failed>(receiveWithTimeout(events))
            assertEquals("gemini_transport", failed.failure.code)
            assertEquals(SendResult.Rejected("socket_closed"), session.startActivity())
            assertNull(events.tryReceive().getOrNull(), "terminal send failure must be emitted once")
            session.close()
        } finally {
            activeClient?.dispatcher?.cancelAll()
            activeClient?.connectionPool?.evictAll()
            noAckServer.close()
        }
    }

    @Test
    fun `startActivity before ready is rejected`() = runBlocking {
        val noAckServer = MockWebServer()
        noAckServer.enqueue(
            MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {}).build(),
        )
        noAckServer.start()
        try {
            val session = newSession(target = noAckServer)
            assertEquals(SendResult.Rejected("session_not_ready"), session.startActivity())
            session.close()
        } finally {
            activeClient?.dispatcher?.cancelAll()
            activeClient?.connectionPool?.evictAll()
            noAckServer.close()
        }
    }

    @Test
    fun `startActivity sends exactly one activityStart realtime-input message`() = runBlocking {
        val session = newSession()
        session.awaitReady()

        assertEquals(SendResult.Accepted, session.startActivity())
        awaitMessages { serverSocket.clientMessages.any { it.contains("activityStart") } }
        assertEquals(1, countMessages(serverSocket.clientMessages, "activityStart"))
        assertFalse(serverSocket.clientMessages.any { it.contains("clientContent") })
        session.close()
    }

    @Test
    fun `duplicate startActivity sends no second wire message`() = runBlocking {
        val session = newSession()
        session.awaitReady()
        session.startActivity()
        awaitMessages { serverSocket.clientMessages.any { it.contains("activityStart") } }

        assertEquals(SendResult.Accepted, session.startActivity())
        assertEquals(1, countMessages(serverSocket.clientMessages, "activityStart"))
        session.close()
    }

    @Test
    fun `audio before activity start is rejected`() = runBlocking {
        val session = newSession()
        session.awaitReady()

        val chunk = AudioChunk(0, byteArrayOf(1, 2), sampleRateHz = 16_000)
        assertEquals(SendResult.Rejected("audio_before_activity_start"), session.sendAudio(chunk))
        assertEquals(0, countMessages(serverSocket.clientMessages, "realtimeInput"))
        session.close()
    }

    @Test
    fun `sendAudio sends base64 pcm with rate mime type after start`() = runBlocking {
        val session = newSession()
        session.awaitReady()
        session.startActivity()

        val bytes = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        val result = session.sendAudio(AudioChunk(0, bytes, sampleRateHz = 16_000))
        assertEquals(SendResult.Accepted, result)

        awaitMessages { serverSocket.clientMessages.any { it.contains("audio") } }
        val audio = realtimeInputFrames(serverSocket.clientMessages).first { it.containsKey("audio") }["audio"]!!.jsonObject
        assertEquals(Base64.getEncoder().encodeToString(bytes), audio["data"]!!.jsonPrimitive.content)
        assertEquals("audio/pcm;rate=16000", audio["mimeType"]!!.jsonPrimitive.content)
        session.close()
    }

    @Test
    fun `audio send false closes the session and emits a terminal transport failure`() = runBlocking {
        val session = newSession(
            sendTextFrame = { webSocket, text ->
                val root = Json.parseToJsonElement(text).jsonObject
                val isAudio = root["realtimeInput"]
                    ?.jsonObject
                    ?.containsKey("audio")
                    ?: false
                if (isAudio) false else webSocket.send(text)
            },
        )
        val events = bufferEvents(session)
        session.awaitReady()
        assertEquals(GeminiEvent.Ready, receiveWithTimeout(events))
        assertEquals(SendResult.Accepted, session.startActivity())

        val result = session.sendAudio(
            AudioChunk(0, byteArrayOf(1, 2), sampleRateHz = 16_000),
        )

        assertEquals(SendResult.Rejected("socket_closed"), result)
        val failed = assertIs<GeminiEvent.Failed>(receiveWithTimeout(events))
        assertEquals("gemini_transport", failed.failure.code)
        assertEquals(
            "A Gemini audio frame could not be queued.",
            failed.failure.message,
        )
        assertEquals(SendResult.Rejected("socket_closed"), session.endActivity())
        assertNull(events.tryReceive().getOrNull(), "terminal send failure must be emitted once")
        session.close()
    }

    @Test
    fun `dictation preserves setup then activityStart then ordered audio then activityEnd`() = runBlocking {
        val session = newSession()
        session.awaitReady()
        session.startActivity()
        session.sendAudio(AudioChunk(1, byteArrayOf(1, 2), sampleRateHz = 16_000))
        session.sendAudio(AudioChunk(2, byteArrayOf(3, 4), sampleRateHz = 16_000))
        assertEquals(SendResult.Accepted, session.endActivity())
        awaitMessages { countMessages(serverSocket.clientMessages, "activityEnd") == 1 }

        val messages = serverSocket.clientMessages.toList()
        assertTrue(Json.parseToJsonElement(messages.first()).jsonObject.containsKey("setup"))
        val frames = realtimeInputFrames(messages)
        assertEquals("activityStart", frames.first().keys.first())
        val audioFrames = frames.filter { it.containsKey("audio") }
        assertEquals(2, audioFrames.size)
        assertEquals("AQI=", audioFrames[0]["audio"]!!.jsonObject["data"]!!.jsonPrimitive.content)
        assertEquals("AwQ=", audioFrames[1]["audio"]!!.jsonObject["data"]!!.jsonPrimitive.content)
        assertEquals("activityEnd", frames.last().keys.first())
        assertTrue(messages.none { it.contains("clientContent") })
        assertTrue(messages.none { it.contains("audioStreamEnd") })
        session.close()
    }

    @Test
    fun `duplicate endActivity sends no second wire message`() = runBlocking {
        val session = newSession()
        session.awaitReady()
        session.startActivity()
        session.endActivity()
        awaitMessages { countMessages(serverSocket.clientMessages, "activityEnd") == 1 }

        assertEquals(SendResult.Accepted, session.endActivity())
        assertEquals(1, countMessages(serverSocket.clientMessages, "activityEnd"))
        session.close()
    }

    @Test
    fun `audio after activity end is rejected`() = runBlocking {
        val session = newSession()
        session.awaitReady()
        session.startActivity()
        session.endActivity()
        awaitMessages { countMessages(serverSocket.clientMessages, "activityEnd") == 1 }

        val chunk = AudioChunk(3, byteArrayOf(1, 2), sampleRateHz = 16_000)
        assertEquals(SendResult.Rejected("audio_after_activity_end"), session.sendAudio(chunk))
        val audioCount = realtimeInputFrames(serverSocket.clientMessages).count { it.containsKey("audio") }
        assertEquals(0, audioCount)
        session.close()
    }

    @Test
    fun `a completed activity can be reopened for the next segment`() = runBlocking {
        // 0.6.0 experimental segmentation: endActivity is followed by a second
        // startActivity (manual activity signaling is a repeated cycle), after
        // which audio flows again.
        val session = newSession()
        session.awaitReady()
        session.startActivity()
        session.endActivity()
        awaitMessages { countMessages(serverSocket.clientMessages, "activityEnd") == 1 }

        assertEquals(SendResult.Accepted, session.startActivity())
        awaitMessages { countMessages(serverSocket.clientMessages, "activityStart") == 2 }

        val chunk = AudioChunk(3, byteArrayOf(1, 2), sampleRateHz = 16_000)
        assertEquals(SendResult.Accepted, session.sendAudio(chunk))
        assertEquals(SendResult.Accepted, session.endActivity())
        awaitMessages { countMessages(serverSocket.clientMessages, "activityEnd") == 2 }
        session.close()
    }

    @Test
    fun `automatic VAD session uses audioStreamEnd completion instead of activity boundaries`() = runBlocking {
        val session = newSession(GeminiSessionConfig(model = "test-model", automaticActivityDetectionDisabled = false))
        session.awaitReady()
        awaitMessages { serverSocket.clientMessages.isNotEmpty() }
        val setup = Json.parseToJsonElement(serverSocket.clientMessages.first()).jsonObject["setup"]!!.jsonObject
        assertFalse(setup.containsKey("realtimeInputConfig"))

        assertEquals(SendResult.Accepted, session.startActivity())
        assertEquals(SendResult.Accepted, session.endActivity())
        awaitMessages { serverSocket.clientMessages.any { it.contains("audioStreamEnd") } }

        val frames = realtimeInputFrames(serverSocket.clientMessages)
        assertTrue(frames.none { it.containsKey("activityStart") })
        assertTrue(frames.none { it.containsKey("activityEnd") })
        assertTrue(frames.last().containsKey("audioStreamEnd"))
        session.close()
    }

    @Test
    fun `inputTranscription emits a final candidate`() = runBlocking {
        val session = newSession()
        val events = bufferEvents(session)
        session.awaitReady()
        receiveWithTimeout(events)

        serverSocket.push("""{"serverContent":{"inputTranscription":{"text":"recognized speech"}}}""")
        val event = receiveWithTimeout(events)
        assertIs<GeminiEvent.TranscriptCandidates>(event)
        assertEquals(listOf("recognized speech"), event.candidates.map { it.raw })
        assertTrue(event.isFinal, "a committed inputTranscription segment must be marked final")
        session.close()
    }

    @Test
    fun `interimInputTranscription emits a non-final INPUT candidate`() = runBlocking {
        // 0.10.0 transcribe model: revisable partials arrive on the interim
        // field and must never be treated as final.
        val session = newSession()
        val events = bufferEvents(session)
        session.awaitReady()
        receiveWithTimeout(events)

        serverSocket.push("""{"serverContent":{"interimInputTranscription":{"text":"recognized spe"}}}""")
        val event = receiveWithTimeout(events)
        assertIs<GeminiEvent.TranscriptCandidates>(event)
        assertEquals(listOf("recognized spe"), event.candidates.map { it.raw })
        assertEquals(GeminiEvent.TranscriptSource.INPUT, event.source)
        assertFalse(event.isFinal, "a revisable interim must never be marked final")
        session.close()
    }

    @Test
    fun `interim and final transcription in one frame emit candidates in order`() = runBlocking {
        val session = newSession()
        val events = bufferEvents(session)
        session.awaitReady()
        receiveWithTimeout(events)

        serverSocket.push(
            """{"serverContent":{"interimInputTranscription":{"text":"recognized spe"},"inputTranscription":{"text":"recognized speech"}}}""",
        )
        val interim = receiveWithTimeout(events)
        assertIs<GeminiEvent.TranscriptCandidates>(interim)
        assertEquals(listOf("recognized spe"), interim.candidates.map { it.raw })
        assertFalse(interim.isFinal)
        val final = receiveWithTimeout(events)
        assertIs<GeminiEvent.TranscriptCandidates>(final)
        assertEquals(listOf("recognized speech"), final.candidates.map { it.raw })
        assertTrue(final.isFinal)
        session.close()
    }

    @Test
    fun `outputTranscription emits an ECHO candidate and modelTurn text never emits`() = runBlocking {
        val session = newSession()
        val events = bufferEvents(session)
        session.awaitReady()
        receiveWithTimeout(events)

        serverSocket.push("""{"serverContent":{"outputTranscription":{"text":"We should meet on Thursday."}}}""")
        val echo = receiveWithTimeout(events)
        assertIs<GeminiEvent.TranscriptCandidates>(echo)
        assertEquals(listOf("We should meet on Thursday."), echo.candidates.map { it.raw })
        assertEquals(GeminiEvent.TranscriptSource.ECHO, echo.source, "echo must be tagged as ECHO")

        serverSocket.push("""{"serverContent":{"modelTurn":{"parts":[{"text":"hello world"}]}}}""")
        serverSocket.push("""{"serverContent":{"inputTranscription":{"text":"the birch canoe"}}}""")
        val input = receiveWithTimeout(events)
        assertIs<GeminiEvent.TranscriptCandidates>(input)
        assertEquals(listOf("the birch canoe"), input.candidates.map { it.raw })
        assertEquals(GeminiEvent.TranscriptSource.INPUT, input.source)
        // modelTurn text must never emit a candidate.
        assertNull(events.tryReceive().getOrNull(), "modelTurn text must never emit a candidate")
        session.close()
    }

    @Test
    fun `input and output transcription in one frame emit independent candidates`() = runBlocking {
        val session = newSession()
        val events = bufferEvents(session)
        session.awaitReady()
        receiveWithTimeout(events)

        serverSocket.push(
            """{"serverContent":{"inputTranscription":{"text":"user speech"},"outputTranscription":{"text":"model echo"}}}""",
        )
        val input = assertIs<GeminiEvent.TranscriptCandidates>(receiveWithTimeout(events))
        val output = assertIs<GeminiEvent.TranscriptCandidates>(receiveWithTimeout(events))
        assertEquals(GeminiEvent.TranscriptSource.INPUT, input.source)
        assertEquals(listOf("user speech"), input.candidates.map { it.raw })
        assertEquals(GeminiEvent.TranscriptSource.ECHO, output.source)
        assertEquals(listOf("model echo"), output.candidates.map { it.raw })
        session.close()
    }

    @Test
    fun `server lifecycle flags and goAway timeLeft emit typed events`() = runBlocking {
        val session = newSession()
        val events = bufferEvents(session)
        session.awaitReady()
        receiveWithTimeout(events)

        serverSocket.push("""{"serverContent":{"generationComplete":true}}""")
        assertEquals(GeminiEvent.GenerationComplete, receiveWithTimeout(events))

        serverSocket.push("""{"serverContent":{"interrupted":true}}""")
        assertEquals(GeminiEvent.Interrupted, receiveWithTimeout(events))

        serverSocket.push("""{"goAway":{"timeLeft":"12.500s"}}""")
        val goAway = assertIs<GeminiEvent.GoAway>(receiveWithTimeout(events))
        assertEquals("12.500s", goAway.timeLeft)
        assertNull(events.tryReceive().getOrNull(), "goAway is advance notice, not SessionEnd")
        session.close()
    }

    @Test
    fun `serverContent turnComplete emits TurnComplete`() = runBlocking {
        val session = newSession()
        val events = bufferEvents(session)
        session.awaitReady()
        receiveWithTimeout(events)

        serverSocket.push("""{"serverContent":{"turnComplete":true}}""")
        assertEquals(GeminiEvent.TurnComplete, receiveWithTimeout(events))
        session.close()
    }

    @Test
    fun `setupError fails awaitReady and emits Failed`() = runBlocking {
        val failing = MockWebServer()
        failing.enqueue(
            MockResponse.Builder()
                .webSocketUpgrade(object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.send("""{"setupError":{"error":{"message":"API key not valid."}}}""".encodeUtf8())
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        webSocket.close(1000, null)
                    }
                })
                .build(),
        )
        failing.start()
        try {
            val session = newSession(target = failing)
            val events = bufferEvents(session)

            val error = assertFailsWith<GeminiLiveException> { session.awaitReady() }
            assertEquals("API key not valid.", error.failure.message)
            assertEquals("gemini_setup", error.failure.code)

            val failed = receiveWithTimeout(events)
            assertIs<GeminiEvent.Failed>(failed)
            assertEquals("API key not valid.", failed.failure.message)
            session.close()
        } finally {
            activeClient?.dispatcher?.cancelAll()
            activeClient?.connectionPool?.evictAll()
            failing.close()
        }
    }

    @Test
    fun `close during setup fails awaitReady with the close reason`() = runBlocking {
        val closing = MockWebServer()
        closing.enqueue(
            MockResponse.Builder()
                .webSocketUpgrade(object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.close(1008, "connection rejected key=not-a-real-credential")
                    }
                })
                .build(),
        )
        closing.start()
        try {
            val session = newSession(target = closing)
            val error = assertFailsWith<GeminiLiveException> { session.awaitReady() }
            assertEquals("gemini_setup", error.failure.code)
            assertEquals("connection rejected key=[REDACTED]", error.failure.message)
            session.close()
        } finally {
            activeClient?.dispatcher?.cancelAll()
            activeClient?.connectionPool?.evictAll()
            closing.close()
        }
    }

    @Test
    fun `awaitReady timeout cancels the socket and takes the session terminal`() = runBlocking {
        // A server that upgrades but never sends setupComplete.
        val silent = MockWebServer()
        silent.enqueue(
            MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {}).build(),
        )
        silent.start()
        try {
            val client = OkHttpClient()
            activeClient = client
            val session = OkHttpGeminiLiveSession(
                client = client,
                wsUrl = silent.url("/live").toString(),
                config = GeminiSessionConfig(model = "test-model"),
                readyTimeoutMs = 250L,
            )
            val events = bufferEvents(session)

            val error = assertFailsWith<GeminiLiveException> { session.awaitReady() }
            assertEquals("gemini_setup", error.failure.code)
            assertEquals("Timed out waiting for the Gemini session to start.", error.failure.message)

            // The session self-cleans: the failure is terminal and emitted once,
            // and later calls are rejected as closed instead of hanging.
            val failed = assertIs<GeminiEvent.Failed>(receiveWithTimeout(events))
            assertEquals("gemini_setup", failed.failure.code)
            assertNull(events.tryReceive().getOrNull(), "the timeout failure must be emitted once")
            assertEquals(SendResult.Rejected("socket_closed"), session.startActivity())
            assertEquals(SendResult.Rejected("socket_closed"), session.endActivity())
            session.close()
        } finally {
            activeClient?.dispatcher?.cancelAll()
            activeClient?.connectionPool?.evictAll()
            activeClient?.dispatcher?.executorService?.shutdown()
            silent.close()
        }
    }

    @Test
    fun `close is idempotent`() = runBlocking {
        val session = newSession()
        session.awaitReady()
        session.close()
        session.close() // must not throw
        assertTrue(true)
    }

    private inner class ServerSocket : WebSocketListener() {
        val clientMessages = CopyOnWriteArrayList<String>()
        @Volatile
        private var socket: WebSocket? = null

        override fun onOpen(webSocket: WebSocket, response: Response) {
            socket = webSocket
            // The live Gemini endpoint sends server->client messages as binary
            // frames; mirror that so the session's binary onMessage is covered.
            webSocket.send("""{"setupComplete":{}}""".encodeUtf8())
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            clientMessages.add(text)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        fun push(message: String) {
            socket?.send(message.encodeUtf8())
        }
    }
}
