package com.whispertype.android.core.model

/**
 * Immutable capture of the editor target at bubble-tap time. Captured once and
 * never reused across sessions. The [generation] invalidates on every
 * relevant focus change.
 */
data class TargetSnapshot(
    val sessionId: SessionId,
    val packageName: String,
    val displayId: Int,
    val windowId: Int,
    val editorIdentity: String,
    val generation: Long,
    val inputTypeMask: Int,
    val isSecure: Boolean,
    val isUncertain: Boolean,
    val selectionStart: Int?,
    val selectionEnd: Int?,
    val capturedAtMillis: Long,
)