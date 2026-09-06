package com.whispertype.android.core.overlay

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Unit tests for the pure overlay-bubble geometry in [BubblePlacement]. */
class BubblePlacementTest {

    @Test
    fun `positionPx returns null when savedX is null`() {
        assertNull(
            BubblePlacement.positionPx(savedX = null, savedY = 10f, density = 2f, windowW = 100, windowH = 100, displayW = 1080, displayH = 1920),
        )
    }

    @Test
    fun `positionPx returns null when savedY is null`() {
        assertNull(
            BubblePlacement.positionPx(savedX = 10f, savedY = null, density = 2f, windowW = 100, windowH = 100, displayW = 1080, displayH = 1920),
        )
    }

    @Test
    fun `positionPx returns null when both axes are null`() {
        assertNull(
            BubblePlacement.positionPx(savedX = null, savedY = null, density = 2f, windowW = 100, windowH = 100, displayW = 1080, displayH = 1920),
        )
    }

    @Test
    fun `converts dp to px at density 2`() {
        val (x, y) = BubblePlacement.positionPx(10f, 20f, 2f, 100, 100, 1080, 1920)!!
        assertEquals(20, x)
        assertEquals(40, y)
    }

    @Test
    fun `converts dp to px at density 3`() {
        val (x, y) = BubblePlacement.positionPx(10f, 10f, 3f, 100, 100, 1080, 1920)!!
        assertEquals(30, x)
        assertEquals(30, y)
    }

    @Test
    fun `clamps to the right edge when the saved position overflows horizontally`() {
        val (x, _) = BubblePlacement.positionPx(1000f, 10f, 2f, 100, 100, 1080, 1920)!!
        assertEquals(1080 - 100, x)
    }

    @Test
    fun `clamps to the bottom edge when the saved position overflows vertically`() {
        val (_, y) = BubblePlacement.positionPx(10f, 2000f, 2f, 100, 100, 1080, 1920)!!
        assertEquals(1920 - 100, y)
    }

    @Test
    fun `clamps negative saved positions to zero`() {
        val (x, y) = BubblePlacement.positionPx(-50f, -50f, 2f, 100, 100, 1080, 1920)!!
        assertEquals(0, x)
        assertEquals(0, y)
    }

    @Test
    fun `clamps to zero when the window is larger than the display`() {
        val (x, y) = BubblePlacement.clamp(500, 500, 1200, 2000, 1080, 1920)
        assertEquals(0, x)
        assertEquals(0, y)
    }

    @Test
    fun `positionPx clamps to zero when the window is larger than the display`() {
        val (x, y) = BubblePlacement.positionPx(1000f, 1000f, 2f, 1200, 2000, 1080, 1920)!!
        assertEquals(0, x)
        assertEquals(0, y)
    }

    @Test
    fun `returns a position fully inside the display unchanged`() {
        assertEquals(300 to 400, BubblePlacement.clamp(300, 400, 100, 100, 1080, 1920))
    }

    @Test
    fun `positionPx returns a fully inside position unchanged`() {
        val (x, y) = BubblePlacement.positionPx(150f, 200f, 2f, 100, 100, 1080, 1920)!!
        assertEquals(300, x)
        assertEquals(400, y)
    }

    @Test
    fun `rounds dp to the nearest pixel`() {
        val (x, y) = BubblePlacement.positionPx(10.6f, 10.4f, 1f, 100, 100, 1080, 1920)!!
        assertEquals(11, x)
        assertEquals(10, y)
    }

    @Test
    fun `clamp never returns negative or out-of-bounds values`() {
        val (x, y) = BubblePlacement.clamp(-1000, 5000, 100, 100, 1080, 1920)
        assertTrue(x in 0..(1080 - 100))
        assertTrue(y in 0..(1920 - 100))
    }
}
