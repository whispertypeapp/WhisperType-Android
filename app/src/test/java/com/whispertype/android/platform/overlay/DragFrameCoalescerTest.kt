package com.whispertype.android.platform.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DragFrameCoalescerTest {

    @Test
    fun `many pointer updates request only one frame and preserve total movement`() {
        val coalescer = DragFrameCoalescer()

        assertTrue(coalescer.enqueue(1.25f, 2f))
        assertFalse(coalescer.enqueue(3.75f, -1f))
        assertFalse(coalescer.enqueue(-2f, 4f))

        assertEquals(DragDelta(3f, 5f), coalescer.consume())
        assertFalse(coalescer.hasPendingFrame)
    }

    @Test
    fun `new movement after consume requests the next frame`() {
        val coalescer = DragFrameCoalescer()
        coalescer.enqueue(1f, 1f)
        coalescer.consume()

        assertTrue(coalescer.enqueue(2f, 3f))
        assertEquals(DragDelta(2f, 3f), coalescer.consume())
    }

    @Test
    fun `opposite deltas avoid a redundant layout update`() {
        val coalescer = DragFrameCoalescer()
        coalescer.enqueue(4f, -2f)
        coalescer.enqueue(-4f, 2f)

        assertNull(coalescer.consume())
    }

    @Test
    fun `zero and invalid deltas do not schedule a frame`() {
        val coalescer = DragFrameCoalescer()

        assertFalse(coalescer.enqueue(0f, 0f))
        assertFalse(coalescer.enqueue(Float.NaN, 1f))
        assertFalse(coalescer.enqueue(1f, Float.POSITIVE_INFINITY))
        assertFalse(coalescer.hasPendingFrame)
    }

    @Test
    fun `clear drops pending movement`() {
        val coalescer = DragFrameCoalescer()
        coalescer.enqueue(4f, 5f)

        coalescer.clear()

        assertFalse(coalescer.hasPendingFrame)
        assertNull(coalescer.consume())
    }
}
