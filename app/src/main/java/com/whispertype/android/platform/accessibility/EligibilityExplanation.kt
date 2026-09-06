package com.whispertype.android.platform.accessibility

import com.whispertype.android.core.model.TargetEligibility

/**
 * Per-condition diagnostics for a hidden bubble (Phase 3 requirement, §2.3).
 *
 * The prior implementation only exposed a boolean `eligible`, so there was no
 * on-device way to know which of the many conditions hid the bubble. This pure
 * mapping names exactly which conditions are unmet, so operators and users can
 * see why the bubble is not shown (service, focus, secure/uncertain editor,
 * keyboard, mic, key, app-enabled, or active-session).
 */
object EligibilityExplanation {

    /** Returns the ordered list of blocking reason codes, empty when eligible. */
    fun blockingReasons(e: TargetEligibility): List<String> {
        val reasons = ArrayList<String>(9)
        if (!e.serviceConnected) reasons += REASON_SERVICE_NOT_CONNECTED
        if (!e.editorFocused) reasons += REASON_NO_EDITOR_FOCUS
        if (e.editorSecure) reasons += REASON_SECURE_FIELD
        if (e.editorUncertain) reasons += REASON_UNCERTAIN_FIELD
        // 0.6.0: the keyboard gate is relaxed on secondary displays (DeX), so
        // it is not a blocking reason there either.
        if (!e.keyboardVisible && e.displayId == TargetEligibility.DEFAULT_DISPLAY_ID) {
            reasons += REASON_KEYBOARD_HIDDEN
        }
        if (!e.microphoneGranted) reasons += REASON_MICROPHONE_NOT_GRANTED
        if (!e.apiKeyConfigured) reasons += REASON_API_KEY_MISSING
        if (!e.appEnabled) reasons += REASON_APP_DISABLED
        if (e.sessionActive) reasons += REASON_SESSION_ACTIVE
        return reasons
    }

    const val REASON_SERVICE_NOT_CONNECTED = "service_not_connected"
    const val REASON_NO_EDITOR_FOCUS = "no_editor_focus"
    const val REASON_SECURE_FIELD = "secure_field"
    const val REASON_UNCERTAIN_FIELD = "uncertain_field"
    const val REASON_KEYBOARD_HIDDEN = "keyboard_hidden"
    const val REASON_MICROPHONE_NOT_GRANTED = "microphone_not_granted"
    const val REASON_API_KEY_MISSING = "api_key_missing"
    const val REASON_APP_DISABLED = "app_disabled"
    const val REASON_SESSION_ACTIVE = "session_active"
}
