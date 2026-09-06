package com.whispertype.android.platform.gemini

import com.whispertype.android.core.privacy.LogRedactor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Pure, host-testable mapping of the Gemini Live WebSocket
 * (`BidiGenerateContent`) wire messages. Building and parsing never touch a
 * socket; [OkHttpGeminiLiveSession] only forwards the produced JSON strings and
 * feeds received strings back through [parseServerMessage].
 *
 * Wire contract (per the Live API reference for `gemini-3.5-transcribe-live`):
 *  - the first client message is `{"setup": {model, generationConfig, inputAudioTranscription}}`
 *    and the server replies `setupComplete` (or `setupError`);
 *  - audio is sent as `{"realtimeInput": {"audio": {"data": <base64>, "mimeType": "audio/pcm;rate=16000"}}}`;
 *  - the server streams `serverContent` objects carrying the transcription
 *    text: `interimInputTranscription` (revisable partials) and
 *    `inputTranscription` (committed final segments), plus turn lifecycle flags.
 */
object GeminiLiveWire {

    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = false
    }

    // Fixed segments of the audio-chunk frame; see [buildAudioChunk].
    private const val AUDIO_CHUNK_HEAD = "{\"realtimeInput\":{\"audio\":{\"data\":\""
    private const val AUDIO_CHUNK_MIME_TAIL = "\",\"mimeType\":\"audio/pcm;rate="
    private const val AUDIO_CHUNK_CLOSE = "\"}}}"

    // ------------------------------------------------------------------
    // Client -> server
    // ------------------------------------------------------------------

    /** The mandatory first message: session setup with model + config. */
    fun buildSetup(config: GeminiSessionConfig): String {
        val setup = buildJsonObject {
            put("model", "models/${config.model}")
            if (!config.omitGenerationConfig) {
                put(
                    "generationConfig",
                    buildJsonObject {
                        put(
                            "responseModalities",
                            kotlinx.serialization.json.buildJsonArray {
                                config.responseModalities.forEach { add(JsonPrimitive(it)) }
                            },
                        )
                        config.maxOutputTokens?.let { put("maxOutputTokens", it) }
                    },
                )
            }
            // Transcription of the user's speech: the server returns
            // serverContent.interimInputTranscription (partials) and
            // serverContent.inputTranscription (final segments) — the ONLY
            // dictation source. `mode` selects server-side shaping ("smart" or
            // "verbatim"); `languageCodes` is an optional BCP-47 hint.
            if (config.inputAudioTranscription) {
                put(
                    "inputAudioTranscription",
                    buildJsonObject {
                        put("mode", config.transcriptionMode)
                        config.transcriptionLanguageCode?.let {
                            put(
                                "languageCodes",
                                kotlinx.serialization.json.buildJsonArray { add(JsonPrimitive(it)) },
                            )
                        }
                    },
                )
            }
            // Push-to-talk manual activity signaling (Release A experiment): with
            // automatic detection disabled, the client delimits each utterance
            // with explicit activityStart / activityEnd realtime-input boundaries.
            if (config.automaticActivityDetectionDisabled) {
                put(
                    "realtimeInputConfig",
                    buildJsonObject {
                        put(
                            "automaticActivityDetection",
                            buildJsonObject { put("disabled", true) },
                        )
                        // 0.6.0 experimental segmentation: a new activity must
                        // not interrupt the previous segment's transcription.
                        if (config.activityHandlingNoInterruption) {
                            put("activityHandling", "NO_INTERRUPTION")
                        }
                    },
                )
            }
        }
        return buildJsonObject { put("setup", setup) }.toString()
    }

    /**
     * One realtime audio chunk. [dataBase64] is base64 of raw PCM16 bytes.
     *
     * Hot path (one frame per ~20 ms at 50 fps): the byte-identical JSON string
     * is assembled directly instead of through [buildJsonObject]. This is safe
     * without an escaper — the base64 alphabet (`A-Z a-z 0-9 + / =`) contains no
     * characters that need JSON escaping, and the mime type is a fixed
     * template around an integer.
     */
    fun buildAudioChunk(dataBase64: String, sampleRateHz: Int): String =
        StringBuilder(AUDIO_CHUNK_HEAD.length + dataBase64.length + 32)
            .append(AUDIO_CHUNK_HEAD)
            .append(dataBase64)
            .append(AUDIO_CHUNK_MIME_TAIL)
            .append(sampleRateHz)
            .append(AUDIO_CHUNK_CLOSE)
            .toString()

    /**
     * Sends ongoing text through the Gemini realtime-input channel. With
     * manual activity detection, callers must surround this message with
     * [buildActivityStart] and [buildActivityEnd].
     */
    fun buildRealtimeText(text: String): String =
        buildJsonObject {
            put("realtimeInput", buildJsonObject { put("text", text) })
        }.toString()

    /** Builds a client-content turn boundary; not valid for ongoing realtime text. */
    fun buildTurnComplete(): String =
        buildJsonObject {
            put("clientContent", buildJsonObject { put("turnComplete", true) })
        }.toString()

    /**
     * Explicit realtime activity boundary: start of a push-to-talk utterance
     * (manual activity detection). Sent as a realtime-input message before the
     * first audio or text payload.
     */
    fun buildActivityStart(): String =
        buildJsonObject {
            put(
                "realtimeInput",
                buildJsonObject { put("activityStart", buildJsonObject {}) },
            )
        }.toString()

    /**
     * Explicit realtime activity boundary: end of a push-to-talk utterance
     * (manual activity detection). Sent as a realtime-input message after the
     * final audio or text payload.
     */
    fun buildActivityEnd(): String =
        buildJsonObject {
            put(
                "realtimeInput",
                buildJsonObject { put("activityEnd", buildJsonObject {}) },
            )
        }.toString()

    /**
     * Realtime completion signal used with automatic activity detection: marks
     * the end of the microphone stream. A realtime-input message with Boolean
     * `true`.
     */
    fun buildAudioStreamEnd(): String =
        buildJsonObject {
            put(
                "realtimeInput",
                buildJsonObject { put("audioStreamEnd", true) },
            )
        }.toString()

    /** Parse one server WebSocket frame into a transport-level message. */
    fun parseServerMessage(raw: String): ServerMessage =
        try {
            val root = json.parseToJsonElement(raw).jsonObject
            when {
                root.containsKey("setupComplete") -> ServerMessage.SetupComplete
                root.containsKey("setupError") -> parseSetupError(root)
                root.containsKey("error") -> parseTopLevelError(root)
                root.containsKey("serverContent") -> parseServerContent(root)
                root.containsKey("goAway") -> parseGoAway(root)
                else -> unknownServerMessage()
            }
        } catch (_: Throwable) {
            unknownServerMessage()
        }

    private fun parseSetupError(root: JsonObject): ServerMessage {
        val error = root["setupError"]?.jsonObject
        val message = error?.get("error")?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
        return ServerMessage.SetupError(
            sanitizeServerDetail(message),
        )
    }

    private fun parseTopLevelError(root: JsonObject): ServerMessage {
        val message = root["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
        return ServerMessage.SetupError(
            sanitizeServerDetail(message),
        )
    }

    private fun sanitizeServerDetail(message: String?): String =
        message
            ?.takeIf { it.isNotBlank() }
            ?.let(LogRedactor::sanitize)
            ?: "Unknown setup error"

    private fun unknownServerMessage(): ServerMessage =
        ServerMessage.Unknown(LogRedactor.REDACTED_PLACEHOLDER)

    private fun parseGoAway(root: JsonObject): ServerMessage {
        val timeLeft = root["goAway"]
            ?.jsonObject
            ?.get("timeLeft")
            ?.jsonPrimitive
            ?.contentOrNull
        return ServerMessage.GoAway(timeLeft)
    }

    private fun parseServerContent(root: JsonObject): ServerMessage {
        val content = root["serverContent"]?.jsonObject
        val textParts = content?.get("modelTurn")
            ?.jsonObject
            ?.get("parts")
            ?.let { parts -> extractTextParts(parts) }
            ?: emptyList()
        val interimInputTranscription = content
            ?.get("interimInputTranscription")
            ?.jsonObject
            ?.get("text")
            ?.jsonPrimitive
            ?.contentOrNull
        val inputTranscription = content
            ?.get("inputTranscription")
            ?.jsonObject
            ?.get("text")
            ?.jsonPrimitive
            ?.contentOrNull
        val outputTranscription = content
            ?.get("outputTranscription")
            ?.jsonObject
            ?.get("text")
            ?.jsonPrimitive
            ?.contentOrNull
        val generationComplete = content
            ?.get("generationComplete")
            ?.jsonPrimitive
            ?.booleanOrNull
            ?: false
        val turnComplete = content?.get("turnComplete")?.jsonPrimitive?.booleanOrNull ?: false
        val interrupted = content?.get("interrupted")?.jsonPrimitive?.booleanOrNull ?: false
        return ServerMessage.ServerContent(
            textParts = textParts,
            interimInputTranscription = interimInputTranscription,
            inputTranscription = inputTranscription,
            outputTranscription = outputTranscription,
            generationComplete = generationComplete,
            turnComplete = turnComplete,
            interrupted = interrupted,
        )
    }

    private fun extractTextParts(parts: kotlinx.serialization.json.JsonElement): List<String> {
        val array = parts as? JsonArray ?: return emptyList()
        val texts = ArrayList<String>()
        for (part in array) {
            val obj = part as? JsonObject ?: continue
            val text = obj["text"]?.jsonPrimitive?.contentOrNull ?: continue
            texts.add(text)
        }
        return texts
    }

    /**
     * The parts of a server message that map to session behavior. Fields are
     * intentionally nullable/flags rather than nested objects so the session
     * stays free of JsonElement handling.
     */
    sealed interface ServerMessage {
        /** `setupComplete` received; audio transmission may begin. */
        data object SetupComplete : ServerMessage

        /** `setupError` received; the session cannot proceed. */
        data class SetupError(val message: String) : ServerMessage

        /**
         * A `serverContent` frame. [interimInputTranscription] is the current
         * revisable partial of the recognized user speech (0.10.0 transcribe
         * model), [inputTranscription] is the committed final segment text,
         * [outputTranscription] is never populated (no audio output on the
         * transcribe model) and the flags describe the turn lifecycle.
         */
        data class ServerContent(
            val textParts: List<String>,
            val interimInputTranscription: String?,
            val inputTranscription: String?,
            val outputTranscription: String?,
            val turnComplete: Boolean,
            val interrupted: Boolean,
            val generationComplete: Boolean = false,
        ) : ServerMessage

        /**
         * `goAway` received; [timeLeft] is the protocol's protobuf JSON duration
         * string and is kept intact for lifecycle policy.
         */
        data class GoAway(val timeLeft: String?) : ServerMessage

        /** A message with no session action; [raw] is always a redacted marker. */
        data class Unknown(val raw: String) : ServerMessage
    }
}