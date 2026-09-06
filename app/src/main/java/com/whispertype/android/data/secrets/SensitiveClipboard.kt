package com.whispertype.android.data.secrets

/**
 * Clipboard writes are explicit and sensitive-marked. The implementation must
 * mark clipboard content as sensitive where the platform supports it and
 * return a Boolean success (never silently fail).
 */
interface SensitiveClipboard {
    suspend fun copySensitive(text: String): Boolean
}