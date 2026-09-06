package com.whispertype.android.audio

import com.whispertype.android.core.audio.GemAudioFormat
import com.whispertype.android.core.model.AudioChunk

/**
 * Pure audio accumulator that frames raw PCM16 bytes into exact 20 ms
 * [AudioChunk]s. Partial reads are never dropped: bytes are buffered until a
 * complete frame is available. Frame size is derived from the sample rate and
 * the fixed 20 ms duration, never from a hard-coded byte count.
 */
class Chunker(
    private val sampleRateHz: Int = GemAudioFormat.SAMPLE_RATE_HZ,
    private val nowNanos: () -> Long = System::nanoTime,
) {

    private val bytesPerFrame: Int =
        GemAudioFormat.FRAME_MILLIS * sampleRateHz * GemAudioFormat.CHAR_BYTES / 1000

    private var pending = ByteArray(bytesPerFrame)
    private var pendingSize = 0
    private var nextSequence = 0L

    init {
        require(bytesPerFrame > 0) { "sampleRateHz must admit a positive frame size" }
    }

    /**
     * Accumulates [pcm16] and returns every complete [AudioChunk] produced.
     * Any partial trailing bytes are retained for the next [push] or are
     * forced out by [remaining]. Returns an empty list when the buffer does
     * not yet hold a full frame. Empty input is a no-op.
     */
    fun push(pcm16: ByteArray): List<AudioChunk> = push(pcm16, pcm16.size)

    /**
     * Accumulates the first [byteCount] bytes of [pcm16]. This avoids copying a
     * short capture read into a right-sized temporary array before framing it.
     */
    fun push(pcm16: ByteArray, byteCount: Int): List<AudioChunk> {
        require(byteCount in 0..pcm16.size) {
            "byteCount must be in 0..${pcm16.size}, was $byteCount"
        }
        if (byteCount == 0) return emptyList()

        val frameCount = (pendingSize + byteCount) / bytesPerFrame
        if (frameCount == 0) {
            pcm16.copyInto(pending, pendingSize, 0, byteCount)
            pendingSize += byteCount
            return emptyList()
        }

        val result = ArrayList<AudioChunk>(frameCount)
        var offset = 0

        if (pendingSize > 0) {
            val needed = bytesPerFrame - pendingSize
            pcm16.copyInto(pending, pendingSize, 0, needed)
            offset = needed
            result.add(toChunk(pending))
            pending = ByteArray(bytesPerFrame)
            pendingSize = 0
        }

        while (byteCount - offset >= bytesPerFrame) {
            val frame = ByteArray(bytesPerFrame)
            pcm16.copyInto(frame, 0, offset, offset + bytesPerFrame)
            result.add(toChunk(frame))
            offset += bytesPerFrame
        }

        if (offset < byteCount) {
            pendingSize = byteCount - offset
            pcm16.copyInto(pending, 0, offset, byteCount)
        }
        return result
    }

    /**
     * Forces any incomplete final frame out as a complete frame, zero-padding
     * the trailing bytes. Returns null when there is nothing buffered. This is
     * how capture finalization avoids dropping a partial read.
     */
    fun remaining(): AudioChunk? {
        if (pendingSize == 0) return null
        val frame = pending
        pending = ByteArray(bytesPerFrame)
        pendingSize = 0
        return toChunk(frame)
    }

    private fun toChunk(frame: ByteArray): AudioChunk = AudioChunk(
        sequence = nextSequence++,
        pcm16Bytes = frame,
        sampleRateHz = sampleRateHz,
        capturedAtMonotonicNanos = nowNanos(),
    )
}
