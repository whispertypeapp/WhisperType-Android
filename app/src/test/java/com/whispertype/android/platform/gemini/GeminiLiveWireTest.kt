package com.whispertype.android.platform.gemini

import com.whispertype.android.core.model.LanguageMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonArray

class GeminiLiveWireTest {

    private val config = GeminiSessionConfig(
        model = "gemini-test-live",
        responseModalities = listOf("TEXT"),
        inputSampleRateHz = 16_000,
        language = LanguageMode.HINGLISH,
    )

    // ------------------------------------------------------------------
    // Client -> server builders
    // ------------------------------------------------------------------

    @Test
    fun `buildSetup emits model with models prefix`() {
        val root = Json.parseToJsonElement(GeminiLiveWire.buildSetup(config)).jsonObject
        val setup = root["setup"]!!.jsonObject
        assertEquals("models/gemini-test-live", setup["model"]!!.jsonPrimitive.content)
    }

    @Test
    fun `buildSetup emits responseModalities`() {
        val root = Json.parseToJsonElement(GeminiLiveWire.buildSetup(config)).jsonObject
        val setup = root["setup"]!!.jsonObject
        val generationConfig = setup["generationConfig"]!!.jsonObject
        val modalities = generationConfig["responseModalities"]!!.jsonArray
        assertEquals(listOf("TEXT"), modalities.map { it.jsonPrimitive.content })
    }

    @Test
    fun `buildSetup omits maxOutputTokens by default so a long transcript never truncates`() {
        val root = Json.parseToJsonElement(GeminiLiveWire.buildSetup(config)).jsonObject
        val generationConfig = root["setup"]!!.jsonObject["generationConfig"]!!.jsonObject
        assertFalse(generationConfig.containsKey("maxOutputTokens"))
    }

    @Test
    fun `buildSetup omits maxOutputTokens when null`() {
        val unbounded = GeminiSessionConfig(model = "m", maxOutputTokens = null)
        val root = Json.parseToJsonElement(GeminiLiveWire.buildSetup(unbounded)).jsonObject
        val generationConfig = root["setup"]!!.jsonObject["generationConfig"]!!.jsonObject
        assertFalse(generationConfig.containsKey("maxOutputTokens"))
    }

    @Test
    fun `buildSetup omits generationConfig entirely when omitGenerationConfig is set`() {
        val flag = GeminiSessionConfig(model = "m", omitGenerationConfig = true)
        val root = Json.parseToJsonElement(GeminiLiveWire.buildSetup(flag)).jsonObject
        assertFalse(
            root["setup"]!!.jsonObject.containsKey("generationConfig"),
            "the empirically working transcribe-live shape omits generationConfig",
        )
    }

    @Test
    fun `buildSetup enables inputAudioTranscription by default`() {
        val root = Json.parseToJsonElement(GeminiLiveWire.buildSetup(config)).jsonObject
        val setup = root["setup"]!!.jsonObject
        assertTrue(setup.containsKey("inputAudioTranscription"))
    }

    @Test
    fun `buildSetup sends the smart transcription mode by default`() {
        val root = Json.parseToJsonElement(GeminiLiveWire.buildSetup(config)).jsonObject
        val transcription = root["setup"]!!.jsonObject["inputAudioTranscription"]!!.jsonObject
        assertEquals("smart", transcription["mode"]!!.jsonPrimitive.content)
    }

    @Test
    fun `buildSetup sends the configured transcription mode`() {
        val verbatim = GeminiSessionConfig(model = "m", transcriptionMode = "verbatim")
        val root = Json.parseToJsonElement(GeminiLiveWire.buildSetup(verbatim)).jsonObject
        val transcription = root["setup"]!!.jsonObject["inputAudioTranscription"]!!.jsonObject
        assertEquals("verbatim", transcription["mode"]!!.jsonPrimitive.content)
    }

    @Test
    fun `buildSetup sends languageCodes only when configured`() {
        val hinted = GeminiSessionConfig(model = "m", transcriptionLanguageCode = "hi-IN")
        val root = Json.parseToJsonElement(GeminiLiveWire.buildSetup(hinted)).jsonObject
        val transcription = root["setup"]!!.jsonObject["inputAudioTranscription"]!!.jsonObject
        assertEquals(
            listOf("hi-IN"),
            transcription["languageCodes"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        val bare = GeminiSessionConfig(model = "m")
        val bareRoot = Json.parseToJsonElement(GeminiLiveWire.buildSetup(bare)).jsonObject
        val bareTranscription = bareRoot["setup"]!!.jsonObject["inputAudioTranscription"]!!.jsonObject
        assertFalse(bareTranscription.containsKey("languageCodes"))
    }

    @Test
    fun `buildSetup omits inputAudioTranscription when disabled`() {
        val bare = GeminiSessionConfig(model = "m", inputAudioTranscription = false)
        val root = Json.parseToJsonElement(GeminiLiveWire.buildSetup(bare)).jsonObject
        assertFalse(root["setup"]!!.jsonObject.containsKey("inputAudioTranscription"))
    }

    @Test
    fun `buildSetup declares activityHandling NO_INTERRUPTION for segmentation`() {
        val segmented = GeminiSessionConfig(model = "m", activityHandlingNoInterruption = true)
        val root = Json.parseToJsonElement(GeminiLiveWire.buildSetup(segmented)).jsonObject
        val realtime = root["setup"]!!.jsonObject["realtimeInputConfig"]!!.jsonObject
        assertEquals(
            "NO_INTERRUPTION",
            realtime["activityHandling"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `buildSetup omits activityHandling when segmentation is off`() {
        val root = Json.parseToJsonElement(GeminiLiveWire.buildSetup(GeminiSessionConfig(model = "m"))).jsonObject
        val realtime = root["setup"]!!.jsonObject["realtimeInputConfig"]!!.jsonObject
        assertFalse(realtime.containsKey("activityHandling"))
    }

    /**
     * 0.8.0 regression guard: the echo channel is gone. The Live session is raw
     * transcription transport, so the setup must never enable
     * `outputAudioTranscription` (which produced the reply that the
     * 900 ms/2.5 s barrier stack waited on) and must never carry a
     * `systemInstruction` (which is what made MEDIUM restructure the user's
     * speech). Shaping now lives in the transcribe model's `smart` mode.
     */
    @Test
    fun `buildSetup never enables the echo channel or a systemInstruction`() {
        listOf(config, GeminiSessionConfig(model = "m")).forEach { candidate ->
            val setup = Json.parseToJsonElement(GeminiLiveWire.buildSetup(candidate))
                .jsonObject["setup"]!!.jsonObject
            assertFalse(
                setup.containsKey("outputAudioTranscription"),
                "the echo channel must never be requested",
            )
            assertFalse(
                setup.containsKey("systemInstruction"),
                "the Live session must never carry a style instruction",
            )
            assertTrue(
                setup.containsKey("inputAudioTranscription"),
                "raw ASR is the only dictation source and must be enabled",
            )
        }
    }

    @Test
    fun `buildAudioChunk carries base64 data and pcm mime type`() {
        val root = Json.parseToJsonElement(GeminiLiveWire.buildAudioChunk("AQIDBA==", 16_000)).jsonObject
        val audio = root["realtimeInput"]!!.jsonObject["audio"]!!.jsonObject
        assertEquals("AQIDBA==", audio["data"]!!.jsonPrimitive.content)
        assertEquals("audio/pcm;rate=16000", audio["mimeType"]!!.jsonPrimitive.content)
    }

    @Test
    fun `buildAudioChunk emits the exact compact wire bytes`() {
        // The hot-path builder assembles this string by hand; it must stay
        // byte-identical to the kotlinx.serialization form (compact, insertion
        // order: data then mimeType).
        assertEquals(
            """{"realtimeInput":{"audio":{"data":"AQIDBA==","mimeType":"audio/pcm;rate=16000"}}}""",
            GeminiLiveWire.buildAudioChunk("AQIDBA==", 16_000),
        )
    }

    @Test
    fun `buildRealtimeText uses realtimeInput text and no clientContent`() {
        val root = Json.parseToJsonElement(GeminiLiveWire.buildRealtimeText("ongoing text")).jsonObject
        assertEquals("ongoing text", root["realtimeInput"]!!.jsonObject["text"]!!.jsonPrimitive.content)
        assertFalse(root.containsKey("clientContent"))
    }

    @Test
    fun `buildTurnComplete sets clientContent turnComplete true`() {
        val root = Json.parseToJsonElement(GeminiLiveWire.buildTurnComplete()).jsonObject
        val clientContent = root["clientContent"]!!.jsonObject
        assertTrue(clientContent["turnComplete"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `buildActivityStart is a realtimeInput activityStart message`() {
        val root = Json.parseToJsonElement(GeminiLiveWire.buildActivityStart()).jsonObject
        val realtimeInput = root["realtimeInput"]!!.jsonObject
        assertTrue(realtimeInput["activityStart"]!!.jsonObject.isEmpty())
        assertFalse(realtimeInput.containsKey("audio"))
    }

    @Test
    fun `buildActivityEnd is a realtimeInput activityEnd message`() {
        val root = Json.parseToJsonElement(GeminiLiveWire.buildActivityEnd()).jsonObject
        val realtimeInput = root["realtimeInput"]!!.jsonObject
        assertTrue(realtimeInput["activityEnd"]!!.jsonObject.isEmpty())
        assertFalse(realtimeInput.containsKey("audio"))
    }

    @Test
    fun `buildAudioStreamEnd is a realtimeInput message with Boolean true`() {
        val root = Json.parseToJsonElement(GeminiLiveWire.buildAudioStreamEnd()).jsonObject
        val realtimeInput = root["realtimeInput"]!!.jsonObject
        assertTrue(realtimeInput["audioStreamEnd"]!!.jsonPrimitive.boolean)
        assertFalse(realtimeInput.containsKey("audio"))
    }

    @Test
    fun `manual activity setup disables automatic activity detection with exact camelCase`() {
        val manual = GeminiSessionConfig(model = "m", automaticActivityDetectionDisabled = true)
        val root = Json.parseToJsonElement(GeminiLiveWire.buildSetup(manual)).jsonObject
        val realtimeInputConfig = root["setup"]!!.jsonObject["realtimeInputConfig"]!!.jsonObject
        val automaticActivityDetection = realtimeInputConfig["automaticActivityDetection"]!!.jsonObject
        assertTrue(automaticActivityDetection["disabled"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `manual activity setup is the default production config`() {
        val root = Json.parseToJsonElement(GeminiLiveWire.buildSetup(GeminiSessionConfig(model = "m"))).jsonObject
        val automaticActivityDetection = root["setup"]!!.jsonObject["realtimeInputConfig"]!!
            .jsonObject["automaticActivityDetection"]!!.jsonObject
        assertTrue(automaticActivityDetection["disabled"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `manual activity setup is omitted when automatic detection stays enabled`() {
        val automatic = GeminiSessionConfig(model = "m", automaticActivityDetectionDisabled = false)
        val root = Json.parseToJsonElement(GeminiLiveWire.buildSetup(automatic)).jsonObject
        assertFalse(root["setup"]!!.jsonObject.containsKey("realtimeInputConfig"))
    }

    // ------------------------------------------------------------------
    // Server -> client parsing
    // ------------------------------------------------------------------

    @Test
    fun `setupComplete parses to SetupComplete`() {
        val msg = GeminiLiveWire.parseServerMessage("""{"setupComplete":{}}""")
        assertEquals(GeminiLiveWire.ServerMessage.SetupComplete, msg)
    }

    @Test
    fun `setupError parses message from nested error`() {
        val raw = """{"setupError":{"error":{"message":"API key not valid.","status":"INVALID_ARGUMENT"}}}"""
        val msg = GeminiLiveWire.parseServerMessage(raw) as GeminiLiveWire.ServerMessage.SetupError
        assertEquals("API key not valid.", msg.message)
    }

    @Test
    fun `setupError redacts credentials from server detail`() {
        val raw = """{"setupError":{"error":{"message":"request rejected: key=not-a-real-credential"}}}"""
        val msg = GeminiLiveWire.parseServerMessage(raw) as GeminiLiveWire.ServerMessage.SetupError
        assertEquals("request rejected: key=[REDACTED]", msg.message)
    }

    @Test
    fun `top-level error parses to SetupError`() {
        val raw = """{"error":{"code":404,"message":"models/gemini-2.5-flash-live-preview not found","status":"NOT_FOUND"}}"""
        val msg = GeminiLiveWire.parseServerMessage(raw) as GeminiLiveWire.ServerMessage.SetupError
        assertEquals("models/gemini-2.5-flash-live-preview not found", msg.message)
    }

    @Test
    fun `serverContent modelTurn text parts are extracted in order`() {
        val raw = """{"serverContent":{"modelTurn":{"parts":[{"text":"first"},{"text":"second"}]}}}"""
        val msg = GeminiLiveWire.parseServerMessage(raw) as GeminiLiveWire.ServerMessage.ServerContent
        assertEquals(listOf("first", "second"), msg.textParts)
        assertNull(msg.inputTranscription)
        assertFalse(msg.generationComplete)
        assertFalse(msg.turnComplete)
        assertFalse(msg.interrupted)
    }

    @Test
    fun `serverContent inputTranscription is extracted`() {
        val raw = """{"serverContent":{"inputTranscription":{"text":"recognized speech"}}}"""
        val msg = GeminiLiveWire.parseServerMessage(raw) as GeminiLiveWire.ServerMessage.ServerContent
        assertEquals("recognized speech", msg.inputTranscription)
        assertTrue(msg.textParts.isEmpty())
    }

    @Test
    fun `serverContent interimInputTranscription is extracted`() {
        // 0.10.0 transcribe model: revisable partials arrive on the interim
        // field; finals on inputTranscription.
        val raw = """{"serverContent":{"interimInputTranscription":{"text":"recognized spe"}}}"""
        val msg = GeminiLiveWire.parseServerMessage(raw) as GeminiLiveWire.ServerMessage.ServerContent
        assertEquals("recognized spe", msg.interimInputTranscription)
        assertNull(msg.inputTranscription)
    }

    @Test
    fun `serverContent interim and final transcription parse independently`() {
        val raw = """
            {
              "serverContent": {
                "interimInputTranscription": {"text": "recognized spe"},
                "inputTranscription": {"text": "recognized speech"}
              }
            }
        """.trimIndent()
        val msg = GeminiLiveWire.parseServerMessage(raw) as GeminiLiveWire.ServerMessage.ServerContent
        assertEquals("recognized spe", msg.interimInputTranscription)
        assertEquals("recognized speech", msg.inputTranscription)
    }

    @Test
    fun `serverContent outputTranscription is extracted`() {
        val raw = """{"serverContent":{"outputTranscription":{"text":"echo of user speech"}}}"""
        val msg = GeminiLiveWire.parseServerMessage(raw) as GeminiLiveWire.ServerMessage.ServerContent
        assertEquals("echo of user speech", msg.outputTranscription)
        assertNull(msg.inputTranscription)
    }

    @Test
    fun `input and output transcription remain independent and unsupported finality is ignored`() {
        val raw = """
            {
              "serverContent": {
                "inputTranscription": {"text": "user speech", "finished": true},
                "outputTranscription": {"text": "model echo", "finished": false}
              }
            }
        """.trimIndent()
        val msg = GeminiLiveWire.parseServerMessage(raw) as GeminiLiveWire.ServerMessage.ServerContent
        assertEquals("user speech", msg.inputTranscription)
        assertEquals("model echo", msg.outputTranscription)
        assertFalse(msg.generationComplete)
        assertFalse(msg.turnComplete)
    }

    @Test
    fun `serverContent generationComplete parses independently from turnComplete`() {
        val raw = """{"serverContent":{"generationComplete":true,"turnComplete":false}}"""
        val msg = GeminiLiveWire.parseServerMessage(raw) as GeminiLiveWire.ServerMessage.ServerContent
        assertTrue(msg.generationComplete)
        assertFalse(msg.turnComplete)
    }

    @Test
    fun `serverContent interrupted and turnComplete lifecycle flags parse`() {
        val raw = """{"serverContent":{"interrupted":true,"turnComplete":true}}"""
        val msg = GeminiLiveWire.parseServerMessage(raw) as GeminiLiveWire.ServerMessage.ServerContent
        assertTrue(msg.turnComplete)
        assertTrue(msg.interrupted)
    }

    @Test
    fun `goAway parses protobuf duration timeLeft`() {
        assertEquals(
            GeminiLiveWire.ServerMessage.GoAway(timeLeft = "17.250s"),
            GeminiLiveWire.parseServerMessage("""{"goAway":{"timeLeft":"17.250s"}}"""),
        )
    }

    @Test
    fun `goAway without timeLeft remains a typed notice`() {
        assertEquals(
            GeminiLiveWire.ServerMessage.GoAway(timeLeft = null),
            GeminiLiveWire.parseServerMessage("""{"goAway":{}}"""),
        )
    }

    @Test
    fun `unknown and malformed messages expose no raw server payload`() {
        val tool = """{"toolCall":{"id":"1","functionCalls":[]}}"""
        val unknown = GeminiLiveWire.parseServerMessage(tool) as GeminiLiveWire.ServerMessage.Unknown
        val malformed = GeminiLiveWire.parseServerMessage("not json") as GeminiLiveWire.ServerMessage.Unknown
        assertEquals("[REDACTED]", unknown.raw)
        assertEquals("[REDACTED]", malformed.raw)
    }

    @Test
    fun `json is configured to ignore unknown keys`() {
        // Parsing must tolerate fields the client does not model.
        val raw = """{"serverContent":{"modelTurn":{"parts":[{"text":"ok","groundingMetadata":{}}]},"extra":{"x":1}}}"""
        val msg = GeminiLiveWire.parseServerMessage(raw) as GeminiLiveWire.ServerMessage.ServerContent
        assertEquals(listOf("ok"), msg.textParts)
    }
}
