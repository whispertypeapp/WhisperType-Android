package com.whispertype.android.core.model

/** Result of commanding a dictation session to start. */
sealed interface StartResult {
    data object Accepted : StartResult
    data object Rejected : StartResult

    /** A typed failure prevented the session from starting. */
    data class Failed(val failure: DictationFailure) : StartResult
}