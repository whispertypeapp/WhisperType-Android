package com.whispertype.android.core.privacy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [LogRedactor] (PRD FR-6 / §12). Proves both prevention (no
 * secret remains) and preservation (benign content is left unchanged).
 */
class LogRedactorTest {

    private val redactor = LogRedactor

    // ------------------------------------------------------------------
    // API keys / secrets
    // ------------------------------------------------------------------

    @Test
    fun `bearer token is fully redacted with no partial secret remaining`() {
        val token = "ya29.a0AfH6SMCkE2iVhpLxRz1QmNnXoPqRsTuVwXyZz012345678"
        val line = "Authorization: Bearer $token"
        val out = redactor.sanitize(line)
        assertFalse(out.contains(token))
        assertFalse(out.contains(token.take(12)))
        assertEquals("Authorization: Bearer [REDACTED]", out)
    }

    @Test
    fun `x-api-key header value is fully redacted`() {
        val key = "AIzaSyDdQkL21pTnXm9wVcBr8Z4yHjF6aG0e5sRuN2oPq"
        val out = redactor.sanitize("x-api-key: $key")
        assertFalse(out.contains(key))
        assertTrue(out.contains(LogRedactor.REDACTED_PLACEHOLDER))
        assertTrue(out.startsWith("x-api-key="))
    }

    @Test
    fun `api_key assignment value is fully redacted`() {
        val key = "sk-9f8e7d6c5b4a3928172635647382910"
        val out = redactor.sanitize("connect using api_key=$key now")
        assertFalse(out.contains(key))
        assertTrue(out.contains("api_key=[REDACTED]"))
    }

    @Test
    fun `long high-entropy token like AIza style is redacted`() {
        val token = "AIzaSyTgZiP8qR2vXkLmN5oWc1jH7fD0sU4bY6eGr3tQa"
        val out = redactor.sanitize("gemini API key: $token")
        assertTrue(token.length >= 35)
        assertFalse(out.contains(token))
        assertTrue(out.contains(LogRedactor.REDACTED_PLACEHOLDER))
    }

    // ------------------------------------------------------------------
    // Authenticated URLs
    // ------------------------------------------------------------------

    @Test
    fun `authenticated url redacts only the credentials and preserves host`() {
        val line = "request to https://user:secretpass@api.example.com/v1/audio"
        val out = redactor.sanitize(line)
        assertEquals("request to https://[REDACTED]@api.example.com/v1/audio", out)
    }

    @Test
    fun `ordinary email address is not treated as authenticated url credentials`() {
        val line = "contact user@example.com for help"
        assertEquals(line, redactor.sanitize(line))
    }

    // ------------------------------------------------------------------
    // URL query secrets
    // ------------------------------------------------------------------

    @Test
    fun `query key secret redacts only the value not the surrounding url`() {
        val line = "GET https://api.example.com/data?key=abc123XYZ&page=2"
        val out = redactor.sanitize(line)
        assertEquals("GET https://api.example.com/data?key=[REDACTED]&page=2", out)
    }

    @Test
    fun `X-Goog-Api-Key query secret is redacted`() {
        val key = "AIzaSyVeryLongGoogleApiKeyValue0123456789abcdef"
        val line = "https://generativelanguage.googleapis.com/v1beta?X-Goog-Api-Key=$key"
        val out = redactor.sanitize(line)
        assertFalse(out.contains(key))
        assertTrue(out.contains("X-Goog-Api-Key=[REDACTED]"))
        assertTrue(out.startsWith("https://generativelanguage.googleapis.com/v1beta"))
    }

    @Test
    fun `token query secret is redacted leaving the rest of the url intact`() {
        val line = "https://ws.example.com/socket?token=rt-9f8e7d6c&channel=a"
        val out = redactor.sanitize(line)
        assertEquals("https://ws.example.com/socket?token=[REDACTED]&channel=a", out)
    }

    // ------------------------------------------------------------------
    // Base64 / hex: false-positive protection vs raw-audio blobs
    // ------------------------------------------------------------------

    @Test
    fun `normal-length base64 decode is preserved (no false positive)`() {
        // Normal-length base64 (well under the high-entropy/raw-audio length
        // thresholds) decodes to ordinary text and must pass through unchanged.
        val normal = "ZGlhZ25vc3RpYyBwYXlsb2FkIDQy"
        assertTrue(normal.length < LogRedactor.HIGH_ENTROPY_MIN_LENGTH)
        val line = "decoded=$normal"
        val out = redactor.sanitize(line)
        assertEquals(line, out)
    }

    @Test
    fun `normal-length hex buffer is preserved (no false positive)`() {
        val hex = "a1b2c3d4e5f60718293a4b5c6d7e8f90"
        val line = "buffer=$hex"
        val out = redactor.sanitize(line)
        assertEquals(line, out)
    }

    @Test
    fun `very long base64 raw audio blob is redacted`() {
        // No trailing '=' padding so the concatenation forms ONE contiguous
        // 200+ char base64 run, which is the raw-audio blob shape we redact.
        val blob = "QUJDREVGR0hJSktMTU5PUFFSU1RVVldYWVphYmNkZWZnaGlqa2xtbm9wcXJzdHV2d3h5ejAxMjM0NTY3ODk"
        val longBlob = blob.repeat(20) // well above 200 chars => raw audio
        assertTrue(longBlob.length >= LogRedactor.LONG_BASE64_MIN_LENGTH)
        val line = "sent audio data=$longBlob end"
        val out = redactor.sanitize(line)
        assertFalse(out.contains(longBlob.take(60)))
        assertEquals("sent audio data=[REDACTED] end", out)
    }

    @Test
    fun `pcm16 raw audio hex marker is redacted`() {
        val line = "chunk pcm16=deadbeefcafebabe0123456789abcdef01234567"
        val out = redactor.sanitize(line)
        assertTrue(out.contains("pcm16=[REDACTED]"))
        assertFalse(out.contains("deadbeefcafebabe"))
    }

    // ------------------------------------------------------------------
    // redactExact building block (transcripts / editor / clipboard etc.)
    // ------------------------------------------------------------------

    @Test
    fun `redactExact removes an exact supplied secret wherever it appears`() {
        val secret = "this is the secret transcript text 12345"
        val line = "start $secret middle $secret end"
        val out = redactor.redactExact(line, secret)
        assertFalse(out.contains(secret))
        assertEquals("start [REDACTED] middle [REDACTED] end", out)
    }

    @Test
    fun `sanitizeWithExactSecrets redacts multiple exact secrets and keeps rest`() {
        val clip = "clipboard-marker-ABC123"
        val node = "AccessibilityNode-tree-XYZ789"
        val line = "Got [$clip] and [$node], then pcm16=deadbeef"
        val out = redactor.sanitizeWithExactSecrets(line, listOf(clip, node))
        assertFalse(out.contains(clip))
        assertFalse(out.contains(node))
        assertTrue(out.contains("[REDACTED]"))
        assertFalse(out.contains("deadbeef"))
    }

    // ------------------------------------------------------------------
    // Preservation of benign logs
    // ------------------------------------------------------------------

    @Test
    fun `benign normal log line is left unchanged`() {
        val line = "INFO  2026-08-04T12:00:00.000Z DictationSession: session started id=abc-123"
        assertEquals(line, redactor.sanitize(line))
    }

    @Test
    fun `benign message with punctuation and numbers is preserved`() {
        val message = "INFO  Worker: processed 42 items in 0.87s, request ref #1293"
        assertEquals(message, redactor.sanitize(message))
    }

    @Test
    fun `hyphenated uuid is preserved (not mistaken for a secret)`() {
        val uuid = "550e8400-e29b-41d4-a716-446655440000"
        val line = "correlation $uuid"
        assertEquals(line, redactor.sanitize(line))
    }

    // ------------------------------------------------------------------
    // Determinism
    // ------------------------------------------------------------------

    @Test
    fun `sanitize is deterministic for the same input`() {
        val line = "token=abc123XYZ987 ends https://user:pw@host/p?X-Goog-Api-Key=AIzaSyLongKey0123456789"
        assertEquals(redactor.sanitize(line), redactor.sanitize(line))
        assertEquals(redactor.sanitize(line), redactor.sanitizeAll(line))
    }
}
