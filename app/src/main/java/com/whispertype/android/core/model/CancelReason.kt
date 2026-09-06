package com.whispertype.android.core.model

/**
 * Reasons a dictation session may be cancelled. Cancellation establishes a
 * stale-session barrier before any resource cleanup runs.
 */
enum class CancelReason {
    USER,
    FOCUS_CHANGED,
    LOCKED,
    APP_SWITCHED,
    PERMISSION_REVOKED,
    SERVICE_DISCONNECTED,
    MIC_LOST,
    NETWORK_FAILURE,
    TIMEOUT,
}