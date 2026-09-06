package com.whispertype.android.platform.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OverlayAnchoringTest {

    @Test
    fun `starting keeps its leading cancel action on the bubble center`() {
        assertEquals(
            440 to 536,
            anchoredTopLeft(
                mode = OverlayAnchorMode.LeadingAction,
                bubbleCenter = 500 to 600,
                windowWidth = 272,
                windowHeight = 128,
                displayWidth = 1080,
                displayHeight = 1920,
                density = 2f,
            ),
        )
    }

    @Test
    fun `listening keeps its trailing done action on the bubble center`() {
        assertEquals(
            184 to 536,
            anchoredTopLeft(
                mode = OverlayAnchorMode.TrailingAction,
                bubbleCenter = 500 to 600,
                windowWidth = 376,
                windowHeight = 128,
                displayWidth = 1080,
                displayHeight = 1920,
                density = 2f,
            ),
        )
    }

    @Test
    fun `status uses its fresh dimensions to stay centered`() {
        assertEquals(
            400 to 560,
            anchoredTopLeft(
                mode = OverlayAnchorMode.Center,
                bubbleCenter = 500 to 600,
                windowWidth = 200,
                windowHeight = 80,
                displayWidth = 1080,
                displayHeight = 1920,
                density = 2f,
            ),
        )
    }

    @Test
    fun `fresh dimensions are clamped fully on screen`() {
        assertEquals(
            780 to 1720,
            anchoredTopLeft(
                mode = OverlayAnchorMode.Center,
                bubbleCenter = 1070 to 1910,
                windowWidth = 300,
                windowHeight = 200,
                displayWidth = 1080,
                displayHeight = 1920,
                density = 2f,
            ),
        )
    }

    @Test
    fun `pill states select their intended anchor`() {
        assertEquals(OverlayAnchorMode.LeadingAction, anchorModeFor(OverlayVisibility.Starting))
        assertEquals(OverlayAnchorMode.TrailingAction, anchorModeFor(OverlayVisibility.Listening))
        assertEquals(OverlayAnchorMode.Center, anchorModeFor(OverlayVisibility.Finalizing))
        assertEquals(OverlayAnchorMode.Center, anchorModeFor(OverlayVisibility.Inserting))
        assertEquals(OverlayAnchorMode.Center, anchorModeFor(OverlayVisibility.Success))
        assertEquals(OverlayAnchorMode.Center, anchorModeFor(OverlayVisibility.CopiedToClipboard))
        assertNull(anchorModeFor(OverlayVisibility.IdleBubble))
    }
}
