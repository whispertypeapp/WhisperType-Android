package com.whispertype.android.platform.overlay

import kotlin.math.roundToInt

/** Point within freshly measured overlay content that stays on the bubble center. */
internal enum class OverlayAnchorMode {
    LeadingAction,
    TrailingAction,
    Center,
}

/** Stable anchoring semantics for each pill-shaped overlay surface. */
internal fun anchorModeFor(visibility: OverlayVisibility): OverlayAnchorMode? = when (visibility) {
    OverlayVisibility.Starting -> OverlayAnchorMode.LeadingAction
    OverlayVisibility.Listening -> OverlayAnchorMode.TrailingAction
    OverlayVisibility.Finalizing,
    OverlayVisibility.Inserting,
    OverlayVisibility.Success,
    OverlayVisibility.CopiedToClipboard,
    -> OverlayAnchorMode.Center

    OverlayVisibility.Hidden,
    OverlayVisibility.IdleBubble,
    OverlayVisibility.CopyAvailable,
    OverlayVisibility.Error,
    -> null
}

/**
 * Places freshly measured [windowWidth] x [windowHeight] content around
 * [bubbleCenter], then clamps it fully inside the display.
 */
internal fun anchoredTopLeft(
    mode: OverlayAnchorMode,
    bubbleCenter: Pair<Int, Int>,
    windowWidth: Int,
    windowHeight: Int,
    displayWidth: Int,
    displayHeight: Int,
    density: Float,
): Pair<Int, Int> {
    val actionCenterInset = (ACTION_CENTER_INSET_DP * density).roundToInt()
    val anchorX = when (mode) {
        OverlayAnchorMode.LeadingAction -> actionCenterInset
        OverlayAnchorMode.TrailingAction -> windowWidth - actionCenterInset
        OverlayAnchorMode.Center -> windowWidth / 2
    }
    val rawX = bubbleCenter.first - anchorX
    val rawY = bubbleCenter.second - windowHeight / 2
    return rawX.coerceIn(0, (displayWidth - windowWidth).coerceAtLeast(0)) to
        rawY.coerceIn(0, (displayHeight - windowHeight).coerceAtLeast(0))
}

/**
 * 6dp pill inset plus half of a 48dp action. Keep in sync with the pill
 * padding and [PillAction] minimum size in [WhisperTypeOverlayContent].
 */
private const val ACTION_CENTER_INSET_DP = 30f
