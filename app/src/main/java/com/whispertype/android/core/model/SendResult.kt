package com.whispertype.android.core.model

/** Result of handing one [AudioChunk] (or the activity-end boundary) to Gemini. */
sealed interface SendResult {
    data object Accepted : SendResult

    /** Not recorded; the caller reports the reason as a typed failure. */
    data class Rejected(val reason: String) : SendResult
}