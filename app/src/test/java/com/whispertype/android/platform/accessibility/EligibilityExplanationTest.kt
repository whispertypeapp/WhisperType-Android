package com.whispertype.android.platform.accessibility

import com.whispertype.android.core.model.TargetEligibility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure host tests for [EligibilityExplanation] (Phase 3 per-condition diagnostics). */
class EligibilityExplanationTest {

    private fun elig(
        serviceConnected: Boolean = true,
        editorFocused: Boolean = true,
        editorSecure: Boolean = false,
        editorUncertain: Boolean = false,
        keyboardVisible: Boolean = true,
        microphoneGranted: Boolean = true,
        apiKeyConfigured: Boolean = true,
        appEnabled: Boolean = true,
        sessionActive: Boolean = false,
    ): TargetEligibility = TargetEligibility(
        serviceConnected = serviceConnected,
        editorFocused = editorFocused,
        editorSecure = editorSecure,
        editorUncertain = editorUncertain,
        keyboardVisible = keyboardVisible,
        microphoneGranted = microphoneGranted,
        apiKeyConfigured = apiKeyConfigured,
        appEnabled = appEnabled,
        sessionActive = sessionActive,
    )

    @Test
    fun `fully eligible has no blocking reasons`() {
        assertTrue(EligibilityExplanation.blockingReasons(elig()).isEmpty())
    }

    @Test
    fun `nothing ready reports all foundational blockers`() {
        val reasons = EligibilityExplanation.blockingReasons(TargetEligibility.Ineligible)
        assertTrue(reasons.contains(EligibilityExplanation.REASON_SERVICE_NOT_CONNECTED))
        assertTrue(reasons.contains(EligibilityExplanation.REASON_NO_EDITOR_FOCUS))
        assertTrue(reasons.contains(EligibilityExplanation.REASON_KEYBOARD_HIDDEN))
        assertTrue(reasons.contains(EligibilityExplanation.REASON_MICROPHONE_NOT_GRANTED))
        assertTrue(reasons.contains(EligibilityExplanation.REASON_API_KEY_MISSING))
    }

    @Test
    fun `each unmet condition is named exactly once`() {
        val reasons = EligibilityExplanation.blockingReasons(
            elig(editorSecure = true, editorUncertain = true, sessionActive = true, appEnabled = false),
        )
        assertEquals(
            listOf(
                EligibilityExplanation.REASON_SECURE_FIELD,
                EligibilityExplanation.REASON_UNCERTAIN_FIELD,
                EligibilityExplanation.REASON_APP_DISABLED,
                EligibilityExplanation.REASON_SESSION_ACTIVE,
            ),
            reasons,
        )
    }
}
