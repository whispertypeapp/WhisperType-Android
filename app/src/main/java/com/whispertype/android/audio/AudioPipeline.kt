package com.whispertype.android.audio

import com.whispertype.android.core.model.AudioChunk
import com.whispertype.android.core.model.DictationFailure
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Producer pipeline abstraction so orchestration ([DictationCoordinator]) is
 * host-testable without Android's [AudioRecord]. [AudioCapture] is the
 * production implementation.
 *
 * Lifecycle: [start] once, then [requestStop] + [awaitQuiescence] for an
 * orderly shutdown (the producer drains its final partial frame before closing
 * the chunk channel), with [stop] as the idempotent hard-cancel fallback.
 */
interface AudioPipeline {
    /** Bounded FIFO of 20 ms frames; closes after orderly shutdown or hard stop. */
    val chunks: ReceiveChannel<AudioChunk>

    /** Smoothed input level in [0, 1] at ~20 Hz for the waveform. */
    val amplitude: StateFlow<Float>

    /** Typed terminal failures (mic init/read); the latest is replayed. */
    val failures: SharedFlow<DictationFailure>

    /** Maximum number of frames buffered by [chunks], when known. */
    val frameQueueCapacity: Int get() = 0

    /**
     * Bounded estimate of frames queued after [chunk]. Implementations with no
     * producer sequence visibility return zero for source compatibility.
     */
    fun queuedFrameDepth(chunk: AudioChunk): Int = 0

    /** Starts capture; safe to call once. */
    fun start(): AudioStartResult

    /** Marks stop requested and unblocks the source; the producer then flushes
     *  its final partial frame and closes [chunks]. */
    fun requestStop()

    /** Joins the producer with a bounded timeout; implementations hard-stop on
     *  timeout and return true only when it quiesced in time. */
    suspend fun awaitQuiescence(timeoutMs: Long): Boolean

    /** Idempotent hard stop: cancels the producer, releases the source, closes [chunks]. */
    fun stop()
}
