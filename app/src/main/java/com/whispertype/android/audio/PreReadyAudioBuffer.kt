package com.whispertype.android.audio

import com.whispertype.android.core.model.AudioChunk

/** Result of [PreReadyAudioBuffer.offer]. */
sealed interface PreReadyOffer {
    data object Accepted : PreReadyOffer
    data object Overflow : PreReadyOffer
    data object Closed : PreReadyOffer
}

/**
 * Bounded, memory-only, session-scoped PCM buffer used while a cold Gemini
 * session is still connecting
 * Release F5). Frames buffered here are replayed in strict order once the
 * session becomes ready.
 *
 * Default capacity is 500 frames (20 ms frames ≈ 10 seconds ≈ ~320 KB PCM16 at
 * 16 kHz mono). Overflow is never silently dropped: the caller must observe
 * [PreReadyOffer.Overflow] and surface an explicit connection-too-slow failure.
 *
 * Not thread-safe. All access must happen on the single audio dispatcher
 * producer; there is no synchronization.
 */
class PreReadyAudioBuffer(private val maxFrames: Int = 500) {

    init {
        require(maxFrames > 0) { "maxFrames must be > 0, was $maxFrames" }
    }

    private val frames = ArrayDeque<AudioChunk>()
    private var closed = false

    /** Currently buffered frame count. */
    val size: Int get() = frames.size

    val isFull: Boolean get() = frames.size >= maxFrames

    /**
     * Buffers [chunk] in order. Returns [PreReadyOffer.Accepted] on success,
     * [PreReadyOffer.Overflow] when the buffer is full (nothing is dropped),
     * and [PreReadyOffer.Closed] once [close] has been called.
     */
    fun offer(chunk: AudioChunk): PreReadyOffer {
        if (closed) return PreReadyOffer.Closed
        if (frames.size >= maxFrames) return PreReadyOffer.Overflow
        frames.addLast(chunk)
        return PreReadyOffer.Accepted
    }

    /**
     * Returns buffered frames in FIFO order and clears the buffer. Returns an
     * empty list when nothing is buffered. Works after [close].
     */
    fun drain(): List<AudioChunk> {
        val result = ArrayList<AudioChunk>(frames.size)
        while (frames.isNotEmpty()) {
            result.add(frames.removeFirst())
        }
        return result
    }

    /** Empties the buffer and leaves it open for reuse. Idempotent. */
    fun clear() {
        frames.clear()
        closed = false
    }

    /** Marks the buffer closed; subsequent offers return [PreReadyOffer.Closed]. */
    fun close() {
        closed = true
    }
}
