package com.whispertype.android.platform.overlay

import com.whispertype.android.core.model.DictationState
import com.whispertype.android.core.model.OverlayUiState

/**
 * The visual surface the overlay should present for a given [OverlayUiState].
 * Mirrors PRD §17.2. Pure and framework-free so it runs on the JVM host test.
 */
enum class OverlayVisibility {
    Hidden,
    IdleBubble,
    Starting,
    Listening,
    Finalizing,
    Inserting,
    Success,
    CopyAvailable,
    CopiedToClipboard,
    Error,
}

/**
 * Pure §17.2 visibility mapping with no Android runtime dependencies.
 *
 * The Idle bubble shows only while the target is [com.whispertype.android.core.model.TargetEligibility.eligible];
 * any other Idle (ineligible/uncertain) configuration fails closed to [OverlayVisibility.Hidden].
 *
 * Every active session surface (Starting/Listening/Finalizing/Inserting/CopyAvailable/Error)
 * shows regardless of eligibility: a live session necessarily flips eligibility off via
 * `sessionActive`, so gating those on eligibility would incorrectly hide the recording panel.
 *
 * [DictationState.Success] maps to a short completion cue for the lifetime of
 * that existing terminal state; [DictationState.Cancelled] remains hidden.
 * This mapping is intentionally deterministic and host-testable.
 */
fun visibilityOf(ui: OverlayUiState): OverlayVisibility = when (ui.state) {
    is DictationState.Unavailable -> OverlayVisibility.Hidden
    is DictationState.Idle ->
        if (ui.eligibility.eligible) OverlayVisibility.IdleBubble else OverlayVisibility.Hidden
    is DictationState.Starting -> OverlayVisibility.Starting
    is DictationState.Listening -> OverlayVisibility.Listening
    is DictationState.Finalizing -> OverlayVisibility.Finalizing
    is DictationState.Inserting -> OverlayVisibility.Inserting
    is DictationState.Success -> OverlayVisibility.Success
    is DictationState.Cancelled -> OverlayVisibility.Hidden
    is DictationState.CopyAvailable -> OverlayVisibility.CopyAvailable
    is DictationState.CopiedToClipboard -> OverlayVisibility.CopiedToClipboard
    is DictationState.Error -> OverlayVisibility.Error
}

/**
 * Edge of the display the overlay window anchors to. Wispr Flow anchors the
 * production bubble to the RIGHT edge near vertical center; the rebuild mirrors
 * that interaction and geometry model (§4.2 / §4.3). Kept framework-free.
 */
enum class OverlayEdge { Right }

/**
 * Pure, host-testable overlay geometry expressed in dp (Wispr Flow parity,
 * §4.2). The WindowManager adapter converts these into pixel LayoutParams
 * (gravity + margin) using the display-context density; it does not mix in
 * IME/display guessed metrics.
 *
 * Reference-derived starting measurements (not a substitute for same-device
 * visual comparison): 56dp bubble, 20dp edge margin, right edge around vertical
 * center ([verticallyCentered] = true).
 *
 * [minTouchDp] guarantees the bubble meets the §17.3 >= 48dp touch target.
 */
data class OverlayPlacement(
    val edge: OverlayEdge = OverlayEdge.Right,
    val edgeMarginDp: Float = 20f,
    val bubbleDp: Float = 56f,
    val minTouchDp: Float = 56f,
    val verticallyCentered: Boolean = true,
) {
    val isValid: Boolean
        get() = edgeMarginDp >= 0f && bubbleDp > 0f && minTouchDp >= 48f
}
