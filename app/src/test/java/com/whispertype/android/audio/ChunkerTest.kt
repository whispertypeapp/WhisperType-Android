package com.whispertype.android.audio

import com.whispertype.android.core.model.AudioChunk
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChunkerTest {

    @Test
    fun `all deterministic split patterns preserve bytes and 20 ms frame order`() {
        val pcm = ByteArray(FRAME_BYTES * 3 + 137) { index -> (index * 31 + 7).toByte() }
        val patterns = listOf(
            splitPattern(pcm.size, intArrayOf(pcm.size)),
            splitPattern(pcm.size, intArrayOf(1)),
            splitPattern(pcm.size, intArrayOf(2, 3, 5, 7, 11, 13)),
            splitPattern(pcm.size, intArrayOf(639, 1, 641, 17, 997)),
        )

        for (pattern in patterns) {
            val chunks = chunk(pcm, pattern)
            assertEquals(listOf(0L, 1L, 2L, 3L), chunks.map { it.sequence })
            assertTrue(chunks.all { it.frameMillis == FRAME_MILLIS })
            assertTrue(chunks.all { it.byteCount == FRAME_BYTES })

            val actual = ByteArray(chunks.size * FRAME_BYTES)
            chunks.forEachIndexed { index, chunk ->
                chunk.pcm16Bytes.copyInto(actual, destinationOffset = index * FRAME_BYTES)
            }
            assertContentEquals(pcm.copyOf(actual.size), actual, "split pattern $pattern")
        }
    }

    @Test
    fun `byteCount overload ignores unread buffer capacity without a temporary copy`() {
        val valid = ByteArray(FRAME_BYTES) { index -> index.toByte() }
        val readBuffer = ByteArray(FRAME_BYTES + 53) { 0x55.toByte() }
        valid.copyInto(readBuffer)
        val chunker = Chunker(SAMPLE_RATE_HZ)

        val chunks = chunker.push(readBuffer, FRAME_BYTES)
        readBuffer.fill(0)

        assertEquals(1, chunks.size)
        assertContentEquals(valid, chunks.single().pcm16Bytes)
        assertNull(chunker.remaining())
        assertFailsWith<IllegalArgumentException> { chunker.push(readBuffer, -1) }
        assertFailsWith<IllegalArgumentException> { chunker.push(readBuffer, readBuffer.size + 1) }
    }

    @Test
    fun `remaining emits one zero-padded frame and is then empty`() {
        val chunker = Chunker(SAMPLE_RATE_HZ)
        val partial = byteArrayOf(1, 2, 3, 4, 5)

        assertTrue(chunker.push(partial).isEmpty())
        assertTrue(chunker.push(ByteArray(0)).isEmpty())
        val remaining = chunker.remaining()

        requireNotNull(remaining)
        assertEquals(0L, remaining.sequence)
        assertEquals(FRAME_BYTES, remaining.byteCount)
        assertContentEquals(partial, remaining.pcm16Bytes.copyOf(partial.size))
        assertTrue(remaining.pcm16Bytes.drop(partial.size).all { it == 0.toByte() })
        assertNull(chunker.remaining())
    }

    @Test
    fun `complete and flushed frames have contiguous sequences and monotonic timestamps`() {
        var nowNanos = 1_000L
        val chunker = Chunker(SAMPLE_RATE_HZ) {
            nowNanos.also { nowNanos += FRAME_NANOS }
        }

        val complete = chunker.push(ByteArray(FRAME_BYTES * 2 + 5))
        val flushed = requireNotNull(chunker.remaining())
        val chunks = complete + flushed

        assertEquals(listOf(0L, 1L, 2L), chunks.map { it.sequence })
        assertEquals(
            listOf(1_000L, 1_000L + FRAME_NANOS, 1_000L + FRAME_NANOS * 2),
            chunks.map { it.capturedAtMonotonicNanos },
        )
    }

    private fun chunk(pcm: ByteArray, pattern: List<Int>): List<AudioChunk> {
        val chunker = Chunker(SAMPLE_RATE_HZ)
        val chunks = mutableListOf<AudioChunk>()
        var offset = 0
        for (size in pattern) {
            chunks += chunker.push(pcm.copyOfRange(offset, offset + size))
            offset += size
        }
        assertEquals(pcm.size, offset)
        chunker.remaining()?.let(chunks::add)
        return chunks
    }

    private fun splitPattern(totalSize: Int, cycle: IntArray): List<Int> {
        require(cycle.isNotEmpty() && cycle.all { it > 0 })
        val pattern = mutableListOf<Int>()
        var remaining = totalSize
        var index = 0
        while (remaining > 0) {
            val size = minOf(cycle[index % cycle.size], remaining)
            pattern += size
            remaining -= size
            index++
        }
        return pattern
    }

    private companion object {
        const val SAMPLE_RATE_HZ = 16_000
        const val FRAME_MILLIS = 20
        const val FRAME_BYTES = 640
        const val FRAME_NANOS = 20_000_000L
    }
}
