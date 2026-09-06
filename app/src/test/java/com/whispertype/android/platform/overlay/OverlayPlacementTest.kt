package com.whispertype.android.platform.overlay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure geometry tests for [OverlayPlacement]; no Android runtime required. */
class OverlayPlacementTest {

    @Test
    fun `default min touch target is at least 48dp`() {
        assertTrue(OverlayPlacement().minTouchDp >= 48f)
        assertTrue(OverlayPlacement().isValid)
    }

    @Test
    fun `default edge margin is non-negative`() {
        assertTrue(OverlayPlacement().edgeMarginDp >= 0f)
    }

    @Test
    fun `default anchors to the right edge and vertical center`() {
        assertTrue(OverlayPlacement().edge == OverlayEdge.Right)
        assertTrue(OverlayPlacement().verticallyCentered)
    }

    @Test
    fun `custom min touch equal to 48 is valid`() {
        assertTrue(OverlayPlacement(minTouchDp = 48f).isValid)
    }

    @Test
    fun `min touch below 48 is invalid`() {
        assertFalse(OverlayPlacement(minTouchDp = 40f).isValid)
    }

    @Test
    fun `negative edge margin is invalid`() {
        assertFalse(OverlayPlacement(edgeMarginDp = -1f).isValid)
    }

    @Test
    fun `zero edge margin is valid`() {
        assertTrue(OverlayPlacement(edgeMarginDp = 0f).isValid)
    }

    @Test
    fun `zero bubble size is invalid`() {
        assertFalse(OverlayPlacement(bubbleDp = 0f).isValid)
    }
}
