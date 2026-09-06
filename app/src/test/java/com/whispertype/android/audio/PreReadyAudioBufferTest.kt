package com.whispertype.android.audio

import com.whispertype.android.core.model.AudioChunk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Tests for [PreReadyAudioBuffer] (Release F5). */
class PreReadyAudioBufferTest {

    private fun chunk(seq: Long): AudioChunk =
        AudioChunk(sequence = seq, pcm16Bytes = ByteArray(640) { it.toByte() }, sampleRateHz = 16_000)

    @Test
    fun `default capacity is 500 frames`() {
        val buffer = PreReadyAudioBuffer()
        repeat(500) { assertSame(PreReadyOffer.Accepted, buffer.offer(chunk(it.toLong()))) }
        assertTrue(buffer.isFull)
        assertEquals(500, buffer.size)
    }

    @Test
    fun `offer and drain preserve FIFO order`() {
        val buffer = PreReadyAudioBuffer(maxFrames = 5)
        buffer.offer(chunk(0))
        buffer.offer(chunk(1))
        buffer.offer(chunk(2))
        buffer.offer(chunk(3))

        assertEquals(listOf(0L, 1L, 2L, 3L), buffer.drain().map { it.sequence })
    }

    @Test
    fun `overflow returns Overflow at capacity and never silently drops`() {
        val buffer = PreReadyAudioBuffer(maxFrames = 3)
        buffer.offer(chunk(0))
        buffer.offer(chunk(1))
        buffer.offer(chunk(2))

        assertSame(PreReadyOffer.Overflow, buffer.offer(chunk(3)))

        assertEquals(3, buffer.size)
        assertEquals(listOf(0L, 1L, 2L), buffer.drain().map { it.sequence })
    }

    @Test
    fun `custom capacity is honored`() {
        val buffer = PreReadyAudioBuffer(maxFrames = 2)
        assertTrue(!buffer.isFull)
        buffer.offer(chunk(0))
        buffer.offer(chunk(1))
        assertTrue(buffer.isFull)
        assertEquals(2, buffer.size)
        assertSame(PreReadyOffer.Overflow, buffer.offer(chunk(2)))
    }

    @Test
    fun `clear empties the buffer and keeps it usable`() {
        val buffer = PreReadyAudioBuffer(maxFrames = 2)
        buffer.offer(chunk(0))
        buffer.offer(chunk(1))

        buffer.clear()

        assertEquals(0, buffer.size)
        assertTrue(!buffer.isFull)
        assertSame(PreReadyOffer.Accepted, buffer.offer(chunk(2)))
        assertEquals(listOf(2L), buffer.drain().map { it.sequence })
    }

    @Test
    fun `close makes subsequent offers return Closed`() {
        val buffer = PreReadyAudioBuffer(maxFrames = 3)
        buffer.offer(chunk(0))
        buffer.offer(chunk(1))

        buffer.close()

        assertSame(PreReadyOffer.Closed, buffer.offer(chunk(2)))
    }

    @Test
    fun `drain returns remaining frames in order after close and empty after draining`() {
        val buffer = PreReadyAudioBuffer(maxFrames = 3)
        buffer.offer(chunk(0))
        buffer.offer(chunk(1))

        buffer.close()

        assertEquals(listOf(0L, 1L), buffer.drain().map { it.sequence })
        assertTrue(buffer.drain().isEmpty())
    }

    @Test
    fun `drain empties the buffer`() {
        val buffer = PreReadyAudioBuffer(maxFrames = 3)
        buffer.offer(chunk(0))
        buffer.offer(chunk(1))
        buffer.offer(chunk(2))

        buffer.drain()

        assertEquals(0, buffer.size)
        assertTrue(!buffer.isFull)
    }
}
