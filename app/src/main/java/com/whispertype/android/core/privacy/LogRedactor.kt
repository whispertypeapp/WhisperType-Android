package com.whispertype.android.core.privacy

/**
 * Pure, stateless, deterministic log redactor implementing the privacy redaction requirements
 * (Security, Privacy): no API key, authenticated URL, transcript, editor
 * content, AccessibilityNode tree, clipboard content, or raw audio appears in
 * logs.
 *
 * ## Design & purity
 *  - Kotlin stdlib only. No reflection, no I/O, no Android framework calls.
 *  - Fully deterministic: for a given input (and a given set of registered
 *    exact secrets) the output is always identical. No clock, no PRNG, and no
 *    shared mutable state that could affect results.
 *  - Input is never mutated; a fresh redacted [String] is returned.
 *
 * ## What is auto-redacted
 *  1. Bearer tokens and `Authorization` / `x-api-key` header values.
 *  2. Authenticated URLs: `scheme://user:pass@host/...` -> `scheme://[REDACTED]@host/...`
 *     and sensitive query params (`?key=`, `?api_key=`, `?token=`,
 *     `?access_token=`, `?X-Goog-Api-Key=`, ...) -> `?name=[REDACTED]`.
 *  3. High-entropy secret-looking tokens (mixed-case alphanumeric runs of at
 *     least [HIGH_ENTROPY_MIN_LENGTH] chars) -> `[REDACTED]`.
 *  4. Raw-audio markers: `pcm16=<hex>` blobs and very long base64 byte blobs
 *     (at least [LONG_BASE64_MIN_LENGTH] chars) representing raw audio.
 *
 * ## Transcript / editor / AccessibilityNode / clipboard content
 * These are *not* auto-detected (deliberately — an automatic "transcript
 * detector" would falsely flag normal logs). Instead, callers that already know
 * an exact secret value (a transcript, editor content, node tree, clipboard
 * text, or raw-audio byte marker) explicitly redact it with [redactExact] (a
 * pure building block) before logging. This is the documented, unambiguous
 * mechanism for high-fidelity content redaction.
 *
 * ## False-positive protection
 * Short benign tokens, hyphenated UUIDs, lowercase-hex buffers, ordinary
 * identifiers and base64/hex of normal length are all preserved. Only clearly
 * secret / raw-audio shapes are replaced; normal log text (tags, timestamps,
 * benign messages) passes through unchanged.
 *
 * @see sanitize
 */
object LogRedactor {

    /** Fixed placeholder substituted for every redacted secret. */
    const val REDACTED_PLACEHOLDER: String = "[REDACTED]"

    /** A generic alphanumeric "secret-looking" run must be at least this long. */
    const val HIGH_ENTROPY_MIN_LENGTH: Int = 32

    /** A bare base64 blob is treated as raw audio only when at least this long. */
    const val LONG_BASE64_MIN_LENGTH: Int = 200

    // ------------------------------------------------------------------
    // Named regex constants (all `private val`, all documented).
    // ------------------------------------------------------------------

    /**
     * Authenticated URL userinfo: `scheme://user:pass@host/...`. Group 1 keeps
     * the scheme; the credentials (any chars other than `@`, `/`, whitespace)
     * are replaced, anchoring on a scheme so plain emails never match.
     */
    private val AUTH_URL_USERINFO_REGEX = Regex(
        """([a-z][a-z0-9+.-]*://)([^/@\s]+)@""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Sensitive query-parameter names, case-insensitive. Covers `?key=`,
     * `?api_key=`, `?apikey=`, `?x-api-key=`, `?X-Goog-Api-Key=`, `?token=`,
     * `?access_token=`, `?client_secret=`, etc.
     */
    private val QUERY_PARAM_NAMES =
        """api[_-]?key|apikey|x-api-key|x-goog-api-key|access[_-]?token|auth[_-]?token|""" +
            """client[_-]?secret|token|key|secret|password|passwd|sig|signature"""

    /**
     * Sensitive query parameter `?name=value` (also `&name=` mid-URL). Only the
     * value is replaced; the param name and the rest of the URL are preserved.
     */
    private val QUERY_PARAM_SECRET_REGEX = Regex(
        """([?&])($QUERY_PARAM_NAMES)=([^&\s"']*)""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Generic `name=value` / `name: value` secret assignment in free text
     * (including `x-api-key:` headers). Only the value (8+ chars of token
     * alphabet) is replaced, keeping the key name for readability.
     */
    private val SECRET_ASSIGNMENT_REGEX = Regex(
        """(?i)(api[_-]?key|apikey|x-api-key|access[_-]?token|auth[_-]?token|""" +
            """client[_-]?secret|secret|password|passwd|token|key)\s*[:=]\s*""" +
            """["']?[A-Za-z0-9._~+/=\-]{8,}["']?""",
    )

    /**
     * OAuth-style `Bearer <token>`. Group 1 is the `Bearer ` prefix; the token
     * is a broad token alphabet (may include `.`, `_`, `-`, `+`, `/`, `=`).
     */
    private val BEARER_TOKEN_REGEX = Regex(
        """(?i)(\bBearer\s+)([A-Za-z0-9._~+/=-]{12,})""",
    )

    /**
     * Raw-audio `pcm16=<hex>` marker. Redacts only the hex value while keeping
     * the `pcm16=` label so the log stays readable.
     */
    private val PCM16_MARKER_REGEX = Regex(
        """(?i)(\bpcm16\s*[:=]\s*)([0-9a-fA-F]+)""",
    )

    /**
     * Very long base64 byte blobs (raw-audio payloads). Requires at least
     * [LONG_BASE64_MIN_LENGTH] chars so normal-length base64/hex is preserved.
     */
    private val LONG_RAW_AUDIO_BASE64_REGEX = Regex("""[A-Za-z0-9+/]{200,}={0,2}""")

    /**
     * Long generic alphanumeric runs that may be secrets. The run must itself
     * "look like a secret" (see [looksLikeSecret]) to avoid false positives on
     * UUIDs, hex buffers, and ordinary identifiers.
     */
    private val HIGH_ENTROPY_TOKEN_REGEX = Regex("""\b[A-Za-z0-9]{32,}\b""")

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /**
     * Redacts a raw [line] into a safe-to-log string by applying every rule:
     * authenticated-URL credentials, sensitive URL query params, bearer /
     * header secrets, raw-audio markers, long base64 blobs, secret assignments,
     * and high-entropy token runs. Benign content is preserved.
     */
    fun sanitize(line: String): String {
        // Apply rules in a stable order so the result is deterministic.
        return line
            .redactAuthenticatedUrls()
            .redactSensitiveQueryParams()
            .redactBearerTokens()
            .redactPcm16Markers()
            .redactLongRawAudioBase64()
            .redactSecretAssignments()
            .redactHighEntropyTokens()
    }

    /** Convenience alias for [sanitize]; applies all rules. */
    fun sanitizeAll(line: String): String = sanitize(line)

    /**
     * Pure building block that removes an exact [secret] string wherever it
     * appears in [line], substituting [REDACTED_PLACEHOLDER]. Intended for
     * transcript / editor content / AccessibilityNode tree / clipboard text /
     * raw-audio byte markers that are known to the caller. Deterministic.
     */
    fun redactExact(line: String, secret: String): String {
        if (secret.isEmpty()) return line
        return line.replace(secret, REDACTED_PLACEHOLDER)
    }

    /**
     * Convenience that applies [redactExact] for each supplied [secrets] and
     * then runs all other [sanitize] rules. Deterministic for a fixed set.
     */
    fun sanitizeWithExactSecrets(line: String, secrets: Collection<String>): String {
        var out = line
        for (s in secrets) {
            out = redactExact(out, s)
        }
        return sanitize(out)
    }

    // ------------------------------------------------------------------
    // Private rule helpers
    // ------------------------------------------------------------------

    /** `scheme://user:pass@host` -> `scheme://[REDACTED]@host`. */
    private fun String.redactAuthenticatedUrls(): String =
        AUTH_URL_USERINFO_REGEX.replace(this) { m ->
            m.groupValues[1] + REDACTED_PLACEHOLDER + "@"
        }

    /** `?name=secret` -> `?name=[REDACTED]` (keeps name and URL). */
    private fun String.redactSensitiveQueryParams(): String =
        QUERY_PARAM_SECRET_REGEX.replace(this) { m ->
            m.groupValues[1] + m.groupValues[2] + "=" + REDACTED_PLACEHOLDER
        }

    /** `Bearer token` / `Authorization: Bearer token` -> `Bearer [REDACTED]`. */
    private fun String.redactBearerTokens(): String =
        BEARER_TOKEN_REGEX.replace(this) { m ->
            m.groupValues[1] + REDACTED_PLACEHOLDER
        }

    /** `pcm16=<hex>` -> `pcm16=[REDACTED]`. */
    private fun String.redactPcm16Markers(): String =
        PCM16_MARKER_REGEX.replace(this) { m ->
            m.groupValues[1] + REDACTED_PLACEHOLDER
        }

    /** Very long base64 blobs (raw audio) -> [REDACTED]. */
    private fun String.redactLongRawAudioBase64(): String =
        LONG_RAW_AUDIO_BASE64_REGEX.replace(this, REDACTED_PLACEHOLDER)

    /** `key=value` / `x-api-key: value` -> `key=[REDACTED]`. */
    private fun String.redactSecretAssignments(): String =
        SECRET_ASSIGNMENT_REGEX.replace(this) { m ->
            m.groupValues[1] + "=" + REDACTED_PLACEHOLDER
        }

    /** Long mixed-case secret-looking alnum runs -> [REDACTED]. */
    private fun String.redactHighEntropyTokens(): String =
        HIGH_ENTROPY_TOKEN_REGEX.replace(this) { m ->
            if (looksLikeSecret(m.value)) REDACTED_PLACEHOLDER else m.value
        }

    /**
     * A long run "looks like a secret" when it mixes uppercase, lowercase and
     * digits (high entropy). Purely lowercase/uppercase or hyphenated UUIDs
     * therefore pass through untouched.
     */
    private fun looksLikeSecret(run: String): Boolean {
        if (run.length < HIGH_ENTROPY_MIN_LENGTH) return false
        val hasUpper = run.any { it.isUpperCase() }
        val hasLower = run.any { it.isLowerCase() }
        val hasDigit = run.any { it.isDigit() }
        return hasUpper && hasLower && hasDigit
    }
}

