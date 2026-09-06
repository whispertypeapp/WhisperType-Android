package com.whispertype.android.core.overlay

import kotlin.math.roundToInt

/** Pure math for the freely-draggable overlay bubble: converts a saved dp
 *  position to clamped pixel coordinates. Pure so it is host-testable. */
object BubblePlacement {

    /** Converts a saved bubble position (dp) to clamped top-left pixel
     *  coordinates for a WRAP_CONTENT window. Returns null when either axis is
     *  null (no saved position) — the host then keeps its default anchor. */
    fun positionPx(
        savedX: Float?,
        savedY: Float?,
        density: Float,
        windowW: Int,
        windowH: Int,
        displayW: Int,
        displayH: Int,
    ): Pair<Int, Int>? {
        if (savedX == null || savedY == null) return null
        val x = (savedX * density).roundToInt()
        val y = (savedY * density).roundToInt()
        return clamp(x, y, windowW, windowH, displayW, displayH)
    }

    /** Clamps pixel coordinates so the window stays fully on-screen. */
    fun clamp(
        x: Int,
        y: Int,
        windowW: Int,
        windowH: Int,
        displayW: Int,
        displayH: Int,
    ): Pair<Int, Int> = Pair(
        x.coerceIn(0, (displayW - windowW).coerceAtLeast(0)),
        y.coerceIn(0, (displayH - windowH).coerceAtLeast(0)),
    )
}
