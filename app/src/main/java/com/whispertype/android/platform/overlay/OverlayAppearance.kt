package com.whispertype.android.platform.overlay

/**
 * 0.4.2 user-configurable bubble appearance, combined from the settings flows
 * by [PersistentOverlayHost] and rendered by [WhisperTypeOverlayContent].
 */
data class OverlayAppearance(
    val bubbleSizeDp: Int = DEFAULT_BUBBLE_SIZE_DP,
    /** Bubble alpha as a percentage (10..100). */
    val opacityPercent: Int = 100,
    val miniDotEnabled: Boolean = true,
    /** Idle seconds before the bubble auto-minimizes to the mini dot. */
    val miniDotAutoMinimizeMs: Long = 3_000L,
) {
    /** Bubble alpha in [0,1]. */
    val opacity: Float get() = opacityPercent.coerceIn(10, 100) / 100f

    companion object {
        const val DEFAULT_BUBBLE_SIZE_DP = 36
        const val DEFAULT_MINI_DOT_DELAY_SECONDS = 3
    }
}
