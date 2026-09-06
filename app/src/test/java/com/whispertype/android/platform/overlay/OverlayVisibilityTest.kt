package com.whispertype.android.platform.overlay

import com.whispertype.android.core.model.CancelReason
import com.whispertype.android.core.model.DictationFailure
import com.whispertype.android.core.model.DictationState
import com.whispertype.android.core.model.LanguageMode
import com.whispertype.android.core.model.OverlayUiState
import com.whispertype.android.core.model.ResultCandidate
import com.whispertype.android.core.model.SessionId
import com.whispertype.android.core.model.TargetEligibility
import com.whispertype.android.core.model.TargetSnapshot
import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure §17.2 visibility mapping tests; no Android runtime required. */
class OverlayVisibilityTest {

    private val eligible = TargetEligibility(
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

    private fun ui(state: DictationState, eligibility: TargetEligibility = eligible): OverlayUiState =
        OverlayUiState(eligibility = eligibility, state = state)

    private fun session(id: String): SessionId = SessionId(id)

    private fun target(id: String): TargetSnapshot = TargetSnapshot(
        sessionId = session(id),
        packageName = "com.example.test",
        displayId = 0,
        windowId = 0,
        editorIdentity = "editor-$id",
        generation = 1L,
        inputTypeMask = 0,
        isSecure = false,
        isUncertain = false,
        selectionStart = 0,
        selectionEnd = 0,
        capturedAtMillis = 0L,
    )

    private fun failure(code: String = "net"): DictationFailure =
        DictationFailure(code = code, message = "failure: $code", recoverable = false)

    @Test
    fun `unavailable maps to hidden`() {
        assertEquals(OverlayVisibility.Hidden, visibilityOf(ui(DictationState.Unavailable)))
    }

    @Test
    fun `eligible idle maps to idle bubble`() {
        assertEquals(
            OverlayVisibility.IdleBubble,
            visibilityOf(ui(DictationState.Idle, eligible)),
        )
    }

    @Test
    fun `ineligible idle maps to hidden`() {
        assertEquals(
            OverlayVisibility.Hidden,
            visibilityOf(ui(DictationState.Idle, TargetEligibility.Ineligible)),
        )
    }

    @Test
    fun `starting maps to starting panel regardless of eligibility`() {
        assertEquals(
            OverlayVisibility.Starting,
            visibilityOf(ui(DictationState.Starting(session("s"), target("s")), TargetEligibility.Ineligible)),
        )
    }

    @Test
    fun `listening maps to listening panel`() {
        assertEquals(
            OverlayVisibility.Listening,
            visibilityOf(ui(DictationState.Listening(session("s"), amplitude = 0.5f, elapsedMillis = 12L))),
        )
    }

    @Test
    fun `finalizing maps to finalizing panel`() {
        assertEquals(
            OverlayVisibility.Finalizing,
            visibilityOf(ui(DictationState.Finalizing(session("s")))),
        )
    }

    @Test
    fun `inserting maps to inserting panel`() {
        assertEquals(
            OverlayVisibility.Inserting,
            visibilityOf(ui(DictationState.Inserting(session("s")))),
        )
    }

    @Test
    fun `copy available maps to copy available panel`() {
        assertEquals(
            OverlayVisibility.CopyAvailable,
            visibilityOf(ui(DictationState.CopyAvailable(session("s"), candidate()))),
        )
    }

    @Test
    fun `copied to clipboard maps to copied status pill`() {
        assertEquals(
            OverlayVisibility.CopiedToClipboard,
            visibilityOf(ui(DictationState.CopiedToClipboard(session("s")))),
        )
    }

    @Test
    fun `error maps to error panel`() {
        assertEquals(
            OverlayVisibility.Error,
            visibilityOf(ui(DictationState.Error(session("s"), failure()))),
        )
    }

    @Test
    fun `success maps to short completion cue`() {
        assertEquals(
            OverlayVisibility.Success,
            visibilityOf(ui(DictationState.Success(session("s")))),
        )
    }

    @Test
    fun `cancelled maps to hidden`() {
        assertEquals(
            OverlayVisibility.Hidden,
            visibilityOf(ui(DictationState.Cancelled(session("s"), CancelReason.USER))),
        )
    }

    @Test
    fun `starting while eligible still shows panel not bubble`() {
        assertEquals(
            OverlayVisibility.Starting,
            visibilityOf(ui(DictationState.Starting(session("s"), target("s")), eligible)),
        )
    }

    private fun candidate(raw: String = "hello world"): ResultCandidate =
        ResultCandidate(raw = raw, cleaned = null, language = LanguageMode.ENGLISH)
}
