package com.whispertype.android.platform.gemini

import com.whispertype.android.core.audio.GemAudioFormat
import com.whispertype.android.core.model.LanguageMode

/**
 * Immutable configuration for one [OkHttpGeminiLiveSession]. The model name
 * and the WebSocket API path version are explicit so tests and future upgrades
 * never depend on a hard-coded endpoint.
 *
 * 0.10.0: the Live session is **pure streaming transcription transport** on
 * `gemini-3.5-transcribe-live`. There is no echo channel, no
 * `systemInstruction`, and no audio output — the dictation text is always the
 * server's `inputTranscription` (final segments) and `interimInputTranscription`
 * (live partials), and all text shaping happens server-side in `smart` mode.
 */
class GeminiSessionConfig(
    /** Full model identifier without the `models/` prefix. Must be the pinned Live model. */
    val model: String,
    /** Server text output modalities. The transcribe model is TEXT-only STT; it
     *  has no audio output. The dictation text is read from the server's
     *  `inputTranscription` / `interimInputTranscription`. */
    val responseModalities: List<String> = listOf("TEXT"),
    /** 0.10.0: explicit output token budget. Null omits the field — the
     *  transcript is the model's entire output, so no server-side cap may
     *  truncate a long dictation. */
    val maxOutputTokens: Int? = null,
    /** When true, the setup enables `inputAudioTranscription` so the server
     *  returns `serverContent.inputTranscription.text` for the user's speech.
     *  This is the only dictation source. */
    val inputAudioTranscription: Boolean = true,
    /** Transcription shaping mode: `"verbatim"` (literal) or `"smart"`
     *  (server-side disfluency removal, self-corrections, formatting, casing polish).
     *  Default is `"smart"`. */
    val transcriptionMode: String = "smart",
    /** Optional BCP-47 language hint (`languageCodes`) for the transcription,
     *  e.g. `en-US` or `hi-IN`. Null omits the field (automatic detection). */
    val transcriptionLanguageCode: String? = null,
    /** 0.10.0 empirical flag: a generationConfig carrying
     *  `responseModalities: ["TEXT"]` was observed to suppress final
     *  `inputTranscription` segments on the preview endpoint (only interims
     *  arrived). When true, `generationConfig` is omitted from setup
     *  entirely — the working shape. Device-verified; see docs/GEMINI_LIVE.md. */
    val omitGenerationConfig: Boolean = false,
    /** When true, the setup disables automatic activity detection
     *  (`realtimeInputConfig.automaticActivityDetection.disabled`) so the
     *  client must delimit push-to-talk utterances with explicit
     *  activityStart / activityEnd realtime-input boundaries. WhisperType is
     *  push-to-talk, so manual activity signaling is the preferred production
     *  design. */
    val automaticActivityDetectionDisabled: Boolean = true,
    /** 0.6.0 experimental: when true, setup declares
     *  `realtimeInputConfig.activityHandling = NO_INTERRUPTION` so a new activity
     *  (segment) does not cut off the previous segment's transcription. */
    val activityHandlingNoInterruption: Boolean = false,
    /** Input PCM16 sample rate advertised in the audio mime type. */
    val inputSampleRateHz: Int = GemAudioFormat.SAMPLE_RATE_HZ,
    /** API version segment in the WebSocket path. */
    val apiVersion: String = DEFAULT_API_VERSION,
    /** Language mode stamped on emitted [com.whispertype.android.core.model.ResultCandidate]s. */
    val language: LanguageMode = LanguageMode.ENGLISH,
) {
    companion object {
        const val DEFAULT_API_VERSION = "v1beta"
    }
}