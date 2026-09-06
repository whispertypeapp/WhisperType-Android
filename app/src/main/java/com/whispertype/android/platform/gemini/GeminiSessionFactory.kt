package com.whispertype.android.platform.gemini

import com.whispertype.android.core.model.MutableSessionMetrics
import java.time.Duration
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/**
 * Constructs [OkHttpGeminiLiveSession]s against the Gemini Live WebSocket
 * endpoint. The API key is required per session; the URL is derived from the
 * key and the [GeminiSessionConfig] so callers never assemble endpoints.
 *
 * MODEL POLICY (non-negotiable): [LIVE_MODEL] is the ONLY Gemini model this app
 * may ever call. See the policy block at the top of `README.md`; enforced by
 * `ModelPolicyTest`.
 */
object GeminiSessionFactory {

    /**
     * The one and only Gemini model this project is permitted to call: the
     * Gemini **Live** transcription model, over `BidiGenerateContent`.
     *
     * No other Gemini model may be used — not `generateContent`, not Flash,
     * Pro, Flash-Lite, native-audio or TTS variants, and not the non-live
     * synchronous transcribe model. Only this live model is covered at no cost
     * by the owner's Google Pro API key; every other Gemini model is
     * explicitly refused. Audio goes only to this model; text shaping runs
     * server-side in the model's `smart` transcription mode.
     */
    const val LIVE_MODEL = "gemini-3.5-transcribe-live"

    fun create(
        apiKey: String,
        config: GeminiSessionConfig = GeminiSessionConfig(model = LIVE_MODEL),
        client: OkHttpClient = defaultClient(),
        metrics: MutableSessionMetrics? = null,
    ): OkHttpGeminiLiveSession {
        require(apiKey.isNotEmpty()) { "apiKey must not be empty" }
        return OkHttpGeminiLiveSession(client, buildWsUrl(apiKey, config), config, metrics)
    }

    /** WebSocket endpoint for the [GeminiSessionConfig.apiVersion] path segment. */
    fun buildWsUrl(apiKey: String, config: GeminiSessionConfig): String =
        "wss://generativelanguage.googleapis.com/ws/" +
            "google.ai.generativelanguage.${config.apiVersion}." +
            "GenerativeService.BidiGenerateContent?key=$apiKey"

    /** Long-lived client: no read timeout, periodic pings to keep the socket warm. */
    fun defaultClient(): OkHttpClient =
        OkHttpClient.Builder()
            .pingInterval(Duration.ofSeconds(20))
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
}