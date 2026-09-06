package com.whispertype.android.core.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [TargetEligibility.eligible] vs the hotkey-relaxed
 * [TargetEligibility.eligibleForHotkey] gates.
 */
class TargetEligibilityTest {

    private fun eligibility(keyboardVisible: Boolean, displayId: Int = TargetEligibility.DEFAULT_DISPLAY_ID) =
        TargetEligibility(
            serviceConnected = true,
            editorFocused = true,
            editorSecure = false,
            editorUncertain = false,
            keyboardVisible = keyboardVisible,
            microphoneGranted = true,
            apiKeyConfigured = true,
            appEnabled = true,
            sessionActive = false,
            displayId = displayId,
        )

    @Test
    fun `soft keyboard required for bubble eligibility but not for hotkey`() {
        val withoutKeyboard = eligibility(keyboardVisible = false)
        assertFalse(withoutKeyboard.eligible)
        assertTrue(withoutKeyboard.eligibleForHotkey)
    }

    @Test
    fun `with soft keyboard both gates pass`() {
        val withKeyboard = eligibility(keyboardVisible = true)
        assertTrue(withKeyboard.eligible)
        assertTrue(withKeyboard.eligibleForHotkey)
    }

    @Test
    fun `secondary display drops the keyboard gate for the bubble`() {
        // 0.6.0: a safe editor focused on a non-default display (Samsung DeX)
        // is eligible even without a visible soft keyboard.
        val dex = eligibility(keyboardVisible = false, displayId = 1)
        assertTrue(dex.eligible)
        assertTrue(dex.eligibleForHotkey)
    }

    @Test
    fun `secondary display still fails closed on security and focus gates`() {
        val secure = eligibility(keyboardVisible = false, displayId = 1).copy(editorSecure = true)
        assertFalse(secure.eligible)
        assertFalse(secure.eligibleForHotkey)
        val noFocus = eligibility(keyboardVisible = false, displayId = 1).copy(editorFocused = false)
        assertFalse(noFocus.eligible)
        assertFalse(noFocus.eligibleForHotkey)
    }

    @Test
    fun `secure editor fails closed on both gates`() {
        val secure = eligibility(keyboardVisible = false).copy(editorSecure = true)
        assertFalse(secure.eligible)
        assertFalse(secure.eligibleForHotkey)
    }

    @Test
    fun `uncertain editor fails closed on both gates`() {
        val uncertain = eligibility(keyboardVisible = false).copy(editorUncertain = true)
        assertFalse(uncertain.eligible)
        assertFalse(uncertain.eligibleForHotkey)
    }

    @Test
    fun `no focused editor fails closed on both gates`() {
        val noFocus = eligibility(keyboardVisible = false).copy(editorFocused = false)
        assertFalse(noFocus.eligible)
        assertFalse(noFocus.eligibleForHotkey)
    }
}
