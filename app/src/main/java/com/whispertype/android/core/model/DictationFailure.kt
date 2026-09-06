package com.whispertype.android.core.model

/**
 * A typed, non-sensitive failure. Never contains API keys, authenticated
 * URLs, transcripts, editor content, or raw audio. `message` must be safe to
 * show to the user.
 */
data class DictationFailure(
    val code: String,
    val message: String,
    val recoverable: Boolean,
    val retryAllowed: Boolean = false,
)