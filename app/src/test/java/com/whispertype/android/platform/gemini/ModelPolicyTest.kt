package com.whispertype.android.platform.gemini

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * MODEL POLICY ENFORCEMENT (non-negotiable).
 *
 * This project is permitted to call **exactly one** Gemini model: the Gemini
 * Live transcription model ([GeminiSessionFactory.LIVE_MODEL]) over
 * `BidiGenerateContent`. Only that model is covered at no cost by the owner's
 * Google Pro API key; every other Gemini model is explicitly refused. Audio
 * goes only to Gemini Live; text shaping runs server-side in the model's
 * `smart` transcription mode — no other service ever receives audio or
 * transcript text.
 *
 * This test scans the production sources and fails the build if a second Gemini
 * model ID, a non-Live Gemini endpoint, or an audio-upload endpoint appears.
 * Test sources are intentionally excluded — fakes may use dummy model names
 * against MockWebServer, which never reaches Google.
 */
class ModelPolicyTest {

    private val mainSources: List<File> =
        File("src/main/java").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    @Test
    fun `production sources exist to scan`() {
        assertTrue(
            mainSources.size > 50,
            "expected to scan the app sources but found ${mainSources.size} files; " +
                "the scan root moved and the policy would silently stop being enforced",
        )
    }

    @Test
    fun `the only Gemini model id in production sources is the pinned Live model`() {
        val modelIdPattern = Regex("""gemini-[A-Za-z0-9._-]+""")
        val offenders = mutableListOf<String>()
        mainSources.forEach { file ->
            modelIdPattern.findAll(file.readText()).forEach { match ->
                if (match.value != GeminiSessionFactory.LIVE_MODEL) {
                    offenders += "${file.path}: ${match.value}"
                }
            }
        }
        assertEquals(
            emptyList(),
            offenders,
            "MODEL POLICY VIOLATION — only ${GeminiSessionFactory.LIVE_MODEL} may appear in " +
                "production sources. Offending references: $offenders",
        )
    }

    @Test
    fun `no non-Live Gemini endpoint appears in production sources`() {
        // generateContent/streamGenerateContent are the REST/batch surfaces of
        // OTHER Gemini models (including the non-live gemini-3.5-transcribe),
        // and the Files API is their upload path. None of them may exist here.
        val forbidden = listOf(
            ":generateContent",
            ":streamGenerateContent",
            "generativelanguage.googleapis.com/v1beta/models",
            "/upload/v1beta/files",
        )
        val offenders = mutableListOf<String>()
        mainSources.forEach { file ->
            val text = file.readText()
            forbidden.forEach { needle ->
                if (text.contains(needle)) offenders += "${file.path}: $needle"
            }
        }
        assertEquals(
            emptyList(),
            offenders,
            "MODEL POLICY VIOLATION — only the Gemini Live WebSocket endpoint is allowed. " +
                "Offending references: $offenders",
        )
    }

    @Test
    fun `audio never leaves to a non-Gemini-Live service`() {
        // Audio goes only to Gemini Live. No other speech-to-text provider may
        // ever receive it.
        // NOTE: the needles must not match this app's own `whispertype`
        // package name, hence the model-id shaped pattern.
        val forbiddenLiterals = listOf("audio/transcriptions", "audio/translations")
        val forbiddenPatterns = listOf(Regex("""whisper-[a-z0-9]""", RegexOption.IGNORE_CASE))
        val offenders = mutableListOf<String>()
        mainSources.forEach { file ->
            val text = file.readText()
            forbiddenLiterals.forEach { needle ->
                if (text.contains(needle, ignoreCase = true)) offenders += "${file.path}: $needle"
            }
            forbiddenPatterns.forEach { pattern ->
                pattern.find(text)?.let { offenders += "${file.path}: ${it.value}" }
            }
        }
        assertEquals(
            emptyList(),
            offenders,
            "POLICY VIOLATION — audio may only be sent to Gemini Live. " +
                "Offending references: $offenders",
        )
    }

    @Test
    fun `the Live websocket url is the only Gemini endpoint builder`() {
        val url = GeminiSessionFactory.buildWsUrl(
            "test-key",
            GeminiSessionConfig(model = GeminiSessionFactory.LIVE_MODEL),
        )
        assertTrue(url.startsWith("wss://"), "the Gemini endpoint must be the Live WebSocket: $url")
        assertTrue(
            url.contains("BidiGenerateContent"),
            "the Gemini endpoint must be BidiGenerateContent (Live API): $url",
        )
    }
}