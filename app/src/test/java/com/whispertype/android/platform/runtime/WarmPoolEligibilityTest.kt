package com.whispertype.android.platform.runtime

import com.whispertype.android.core.model.DictationFailure
import com.whispertype.android.core.model.DictationState
import com.whispertype.android.core.model.SessionId
import com.whispertype.android.core.model.TargetEligibility
import com.whispertype.android.core.model.TargetSnapshot
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WarmPoolEligibilityTest {

    private fun eligibleEditor(): TargetEligibility = TargetEligibility(
        serviceConnected = true,
        editorFocused = true,
        editorSecure = false,
        editorUncertain = false,
        keyboardVisible = true,
        microphoneGranted = true,
        apiKeyConfigured = true,
        appEnabled = true,
        sessionActive = false,
        displayId = 0,
    )

    @Test
    fun `starting does not block warm pool`() {
        assertFalse(
            WarmPoolEligibility.blocksWarmPool(
                DictationState.Starting(SessionId("s"), TargetSnapshot(
                    sessionId = SessionId("s"),
                    packageName = "",
                    displayId = 0,
                    windowId = -1,
                    editorIdentity = "",
                    generation = 0L,
                    inputTypeMask = 0,
                    isSecure = false,
                    isUncertain = false,
                    selectionStart = null,
                    selectionEnd = null,
                    capturedAtMillis = 0L,
                )),
                hasActiveHolder = true,
            ),
        )
    }

    @Test
    fun `listening blocks warm pool`() {
        assertTrue(
            WarmPoolEligibility.blocksWarmPool(
                DictationState.Listening(SessionId("s")),
                hasActiveHolder = true,
            ),
        )
    }

    @Test
    fun `retryable error does not block warm pool`() {
        assertFalse(
            WarmPoolEligibility.blocksWarmPool(
                DictationState.Error(
                    SessionId("s"),
                    DictationFailure(
                        code = "gemini_transport",
                        message = "fail",
                        recoverable = true,
                        retryAllowed = true,
                    ),
                ),
                hasActiveHolder = true,
            ),
        )
    }

    @Test
    fun `compute stays true during starting when editor is eligible`() {
        assertTrue(
            WarmPoolEligibility.compute(
                eligibility = eligibleEditor(),
                blocksWarmPool = false,
                appEnabled = true,
                hasApiKey = true,
            ),
        )
    }

    @Test
    fun `compute is false while listening owns the socket`() {
        assertFalse(
            WarmPoolEligibility.compute(
                eligibility = eligibleEditor(),
                blocksWarmPool = true,
                appEnabled = true,
                hasApiKey = true,
            ),
        )
    }
}
