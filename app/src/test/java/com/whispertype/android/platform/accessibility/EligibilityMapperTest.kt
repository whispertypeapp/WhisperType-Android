package com.whispertype.android.platform.accessibility

import com.whispertype.android.core.model.TargetEligibility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure host tests for [EligibilityMapper] and the fail-closed eligibility contract. */
class EligibilityMapperTest {

    private fun map(
        serviceConnected: Boolean = true,
        editorFocused: Boolean = true,
        classification: Classification = Classification.SAFE,
        keyboardVisible: Boolean = true,
        microphoneGranted: Boolean = true,
        apiKeyConfigured: Boolean = true,
        appEnabled: Boolean = true,
        sessionActive: Boolean = false,
        displayId: Int = TargetEligibility.DEFAULT_DISPLAY_ID,
    ): TargetEligibility = EligibilityMapper.toEligibility(
        serviceConnected = serviceConnected,
        editorFocused = editorFocused,
        classification = classification,
        keyboardVisible = keyboardVisible,
        microphoneGranted = microphoneGranted,
        apiKeyConfigured = apiKeyConfigured,
        appEnabled = appEnabled,
        sessionActive = sessionActive,
        displayId = displayId,
    )

    @Test
    fun `safe editor with everything ready is eligible`() {
        assertTrue(map().eligible)
    }

    @Test
    fun `secure classification fails closed and marks editorSecure`() {
        val e = map(classification = Classification.SECURE)
        assertTrue(e.editorSecure)
        assertFalse(e.eligible)
    }

    @Test
    fun `uncertain classification fails closed and marks editorUncertain`() {
        val e = map(classification = Classification.UNCERTAIN)
        assertTrue(e.editorUncertain)
        assertFalse(e.eligible)
    }

    @Test
    fun `no focused editor is not eligible`() {
        assertFalse(map(editorFocused = false).eligible)
    }

    @Test
    fun `keyboard hidden is not eligible`() {
        assertFalse(map(keyboardVisible = false).eligible)
    }

    @Test
    fun `display id is forwarded through the mapper`() {
        val e = map(displayId = 3)
        assertEquals(3, e.displayId)
    }

    @Test
    fun `secondary display with hidden keyboard is eligible`() {
        assertTrue(map(keyboardVisible = false, displayId = 1).eligible)
    }

    @Test
    fun `service not connected is not eligible`() {
        assertFalse(map(serviceConnected = false).eligible)
    }

    @Test
    fun `active session is not eligible`() {
        assertFalse(map(sessionActive = true).eligible)
    }

    @Test
    fun `mic not granted is not eligible`() {
        assertFalse(map(microphoneGranted = false).eligible)
    }

    @Test
    fun `api key not configured is not eligible`() {
        assertFalse(map(apiKeyConfigured = false).eligible)
    }

    @Test
    fun `app disabled is not eligible`() {
        assertFalse(map(appEnabled = false).eligible)
    }

    @Test
    fun `eligible matches the model computation`() {
        val e = map()
        assertEqualsSafe(e.eligible, e)
    }

    private fun assertEqualsSafe(expected: Boolean, e: TargetEligibility) {
        val recomputed = e.serviceConnected && e.editorFocused && !e.editorSecure &&
            !e.editorUncertain && (e.keyboardVisible || e.displayId != TargetEligibility.DEFAULT_DISPLAY_ID) &&
            e.microphoneGranted && e.apiKeyConfigured && e.appEnabled && !e.sessionActive
        assertTrue("mapper eligible $expected must equal model $recomputed", expected == recomputed)
    }
}
