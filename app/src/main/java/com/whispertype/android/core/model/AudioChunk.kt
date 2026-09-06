package com.whispertype.android.core.model

/**
 * One immutable audio frame in the pipeline. Chunks are derived from the
 * sample rate and a 20 ms frame duration, never from a magic byte count.
 */
class AudioChunk(
    val sequence: Long,
    val pcm16Bytes: ByteArray,
    val sampleRateHz: Int,
    val frameMillis: Int = 20,
    /**
     * Capture time on the process monotonic clock, or null for legacy/test
     * callers that do not provide timing metadata. This is never wall time.
     */
    val capturedAtMonotonicNanos: Long? = null,
) {
    /** Expected bytes per frame: frameMillis / 1000 * sampleRate * 16-bit (2 bytes). */
    val expectedByteCount: Int get() = frameMillis * sampleRateHz * CHAR_BYTES / 1000
    val byteCount: Int get() = pcm16Bytes.size

    /**
     * Non-negative age on the same monotonic clock, capped at [maxAgeNanos].
     * Returns null when this chunk came from a source-compatible legacy caller.
     */
    fun ageNanos(
        nowMonotonicNanos: Long,
        maxAgeNanos: Long = Long.MAX_VALUE,
    ): Long? {
        require(maxAgeNanos >= 0L) { "maxAgeNanos must be >= 0, was $maxAgeNanos" }
        val capturedAt = capturedAtMonotonicNanos ?: return null
        return (nowMonotonicNanos - capturedAt).coerceIn(0L, maxAgeNanos)
    }

    /**
     * Number of contiguous newer frames through [latestSequence], capped at
     * [maxDepth]. This is suitable for bounded queue-depth instrumentation.
     */
    fun depthBehind(latestSequence: Long, maxDepth: Int): Int {
        require(maxDepth >= 0) { "maxDepth must be >= 0, was $maxDepth" }
        if (latestSequence <= sequence) return 0
        val difference = latestSequence - sequence
        return if (difference <= 0L || difference >= maxDepth.toLong()) {
            maxDepth
        } else {
            difference.toInt()
        }
    }

    override fun equals(other: Any?): Boolean =
        other is AudioChunk && other.sequence == sequence && other.frameMillis == frameMillis &&
            other.sampleRateHz == sampleRateHz && other.byteCount == byteCount

    override fun hashCode(): Int {
        var result = sequence.hashCode()
        result = 31 * result + frameMillis
        result = 31 * result + sampleRateHz
        result = 31 * result + byteCount
        return result
    }

    private companion object {
        const val CHAR_BYTES = 2
    }
}