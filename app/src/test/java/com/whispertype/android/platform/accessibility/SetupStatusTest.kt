package com.whispertype.android.platform.accessibility

import com.whispertype.android.core.model.TargetEligibility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupStatusTest {

    private fun eligible() = TargetEligibility(
        serviceConnected = true,
        editorFocused = true,
        editorSecure = false,
        editorUncertain = false,
        keyboardVisible = true,
        microphoneGranted = true,
        apiKeyConfigured = true,
        appEnabled = true,
        sessionActive = false,
    )

    @Test
    fun `home banner lists only fixable setup reasons`() {
        val reasons = SetupStatus.homeBannerReasons(
            eligibility = eligible().copy(
                editorFocused = false,
                keyboardVisible = false,
                serviceConnected = false,
            ),
            overlayGranted = false,
            runtimeRunning = false,
            notificationsGranted = false,
        )
        assertTrue(EligibilityExplanation.REASON_SERVICE_NOT_CONNECTED in reasons)
        assertTrue(SetupStatus.REASON_OVERLAY_NOT_GRANTED in reasons)
        assertTrue(SetupStatus.REASON_RUNTIME_NOT_RUNNING in reasons)
        assertFalse(SetupStatus.REASON_NOTIFICATIONS_NOT_GRANTED in reasons)
        assertFalse(reasons.any { it.contains("focus") || it.contains("keyboard") })
    }

    @Test
    fun `system gates expose all runtime checks`() {
        val gates = SetupStatus.systemGateReasons(
            eligibility = eligible().copy(serviceConnected = false, microphoneGranted = false),
            overlayGranted = false,
            runtimeRunning = false,
            notificationsGranted = false,
            apiKeyConfigured = false,
        )
        assertEquals(5, gates.size)
        assertFalse(gates.first { it.id == "accessibility" }.on)
        assertFalse(gates.first { it.id == "microphone" }.on)
        assertFalse(gates.first { it.id == "overlay" }.on)
        assertFalse(gates.first { it.id == "gemini_key" }.on)
        assertFalse(gates.first { it.id == "runtime" }.on)
        assertFalse(gates.any { it.id == "notifications" })
    }
}
