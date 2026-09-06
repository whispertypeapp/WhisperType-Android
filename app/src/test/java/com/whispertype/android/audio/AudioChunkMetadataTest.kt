package com.whispertype.android.audio

import com.whispertype.android.core.model.AudioChunk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class AudioChunkMetadataTest {

    @Test
    fun `legacy constructor leaves capture time unspecified`() {
        val chunk = AudioChunk(7L, ByteArray(640), 16_000)

        assertNull(chunk.capturedAtMonotonicNanos)
        assertNull(chunk.ageNanos(nowMonotonicNanos = 1_000L))
    }

    @Test
    fun `age is non-negative and bounded`() {
        val chunk = AudioChunk(
            sequence = 7L,
            pcm16Bytes = ByteArray(640),
            sampleRateHz = 16_000,
            capturedAtMonotonicNanos = 100L,
        )

        assertEquals(0L, chunk.ageNanos(nowMonotonicNanos = 90L))
        assertEquals(25L, chunk.ageNanos(nowMonotonicNanos = 125L))
        assertEquals(20L, chunk.ageNanos(nowMonotonicNanos = 125L, maxAgeNanos = 20L))
        assertFailsWith<IllegalArgumentException> {
            chunk.ageNanos(nowMonotonicNanos = 125L, maxAgeNanos = -1L)
        }
    }

    @Test
    fun `depth uses contiguous sequences and queue bound`() {
        val chunk = AudioChunk(10L, ByteArray(640), 16_000)

        assertEquals(0, chunk.depthBehind(latestSequence = 9L, maxDepth = 64))
        assertEquals(0, chunk.depthBehind(latestSequence = 10L, maxDepth = 64))
        assertEquals(4, chunk.depthBehind(latestSequence = 14L, maxDepth = 64))
        assertEquals(64, chunk.depthBehind(latestSequence = 1_000L, maxDepth = 64))
        assertFailsWith<IllegalArgumentException> {
            chunk.depthBehind(latestSequence = 11L, maxDepth = -1)
        }
    }
}
