package com.whispertype.android.core.model

/**
 * Immutable eligibility snapshot published by the accessibility service. The
 * bubble is shown only when [eligible] is true; any uncertainty fails closed.
 *
 * [displayId] identifies the display hosting the focused editor. On a secondary
 * display (e.g. Samsung DeX) the [keyboardVisible] gate is relaxed: desktop
 * setups use a hardware keyboard or an IME on another display, so a safe
 * focused editor is sufficient there.
 */
data class TargetEligibility(
    val serviceConnected: Boolean,
    val editorFocused: Boolean,
    val editorSecure: Boolean,
    val editorUncertain: Boolean,
    val keyboardVisible: Boolean,
    val microphoneGranted: Boolean,
    val apiKeyConfigured: Boolean,
    val appEnabled: Boolean,
    val sessionActive: Boolean,
    /** Display hosting the focused editor; [DEFAULT_DISPLAY_ID] when unknown. */
    val displayId: Int = DEFAULT_DISPLAY_ID,
) {
    val eligible: Boolean
        get() = serviceConnected && editorFocused && !editorSecure && !editorUncertain &&
            (keyboardVisible || displayId != DEFAULT_DISPLAY_ID) &&
            microphoneGranted && apiKeyConfigured && appEnabled && !sessionActive

    /**
     * The hotkey path relaxes only the [keyboardVisible] gate: a physical
     * keyboard user typically has no soft IME window, yet a focused safe editor
     * is a perfectly good dictation target. All security gates
     * ([editorSecure] / [editorUncertain]) and the config gates remain closed.
     */
    val eligibleForHotkey: Boolean
        get() = serviceConnected && editorFocused && !editorSecure && !editorUncertain &&
            microphoneGranted && apiKeyConfigured && appEnabled && !sessionActive

    companion object {
        /** Default (phone) display id; kept framework-free so core stays JVM-testable. */
        const val DEFAULT_DISPLAY_ID = 0

        val Ineligible = TargetEligibility(
            serviceConnected = false,
            editorFocused = false,
            editorSecure = false,
            editorUncertain = false,
            keyboardVisible = false,
            microphoneGranted = false,
            apiKeyConfigured = false,
            appEnabled = true,
            sessionActive = false,
        )
    }
}