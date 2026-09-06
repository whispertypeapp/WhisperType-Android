package com.whispertype.android.core.model

/**
 * User intents emitted by the overlay. The overlay is strictly presentational
 * and emits generic intents; it never touches dictation orchestration.
 */
enum class OverlayIntent {
    START_DICTATION,
    STOP,
    CANCEL,
    COPY,
    DISMISS,
    RETRY,
}