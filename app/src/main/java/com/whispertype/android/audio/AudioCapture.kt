package com.whispertype.android.audio

import android.annotation.SuppressLint
import android.media.AudioDeviceInfo
import android.media.AudioRecord
import android.media.MediaRecorder
import com.whispertype.android.core.audio.GemAudioFormat
import com.whispertype.android.core.model.AudioChunk
import com.whispertype.android.core.model.DictationFailure
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.sqrt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Low-level PCM16 producer. Reads are serialized and [release] is called exactly once. */
interface PcmSource {
    /** Reads up to [out].size bytes; returns the count read, 0 if none, or < 0 on error. */
    fun read(out: ByteArray): Int

    /** Releases underlying resources; must be called exactly once. */
    fun release()
}

/**
 * A [PcmSource] whose blocking [PcmSource.read] can be unblocked without
 * releasing it. [requestStop] may race an active read, must be idempotent, and
 * must not release the source; [AudioCapture] performs the one final [release].
 */
interface InterruptiblePcmSource : PcmSource {
    fun requestStop()
}

sealed interface AudioStartResult {
    data object Started : AudioStartResult
    data class Failed(val failure: DictationFailure) : AudioStartResult
}

private object DefaultAudioCaptureScope : CoroutineScope {
    override val coroutineContext = Dispatchers.IO
}

/**
 * [AudioPipeline] implementation: [PcmSource] -> [Chunker] -> bounded Channel,
 * with a ~20 Hz amplitude [StateFlow].
 *
 * Release C6: the producer owns the [Chunker] exclusively — no external caller
 * reads `remaining()`. [requestStop] unblocks the producer's blocking read and
 * lets it process already-returned bytes, emit all complete frames, emit the
 * zero-padded partial frame from `remaining()`, and close the chunk channel.
 * [awaitQuiescence] joins that orderly drain with a bounded timeout; [stop] is
 * the idempotent hard-cancel fallback. A timed-out join triggers that fallback
 * automatically.
 *
 * Read/permission/init failures surface as typed failures on [failures], never
 * as raw throws.
 */
class AudioCapture(
    private val sampleRateHz: Int = GemAudioFormat.SAMPLE_RATE_HZ,
    private val sourceFactory: () -> PcmSource? = { createDefaultSource() },
    scope: CoroutineScope = DefaultAudioCaptureScope,
    readDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AudioPipeline {
    /*
     * Do not make a possibly-blocked microphone read a structural child of a
     * service scope: a broken source must not keep that service's Job joining
     * forever. We still mirror caller cancellation into this owned job below.
     */
    private val lifecycleJob = SupervisorJob()
    private val captureScope = CoroutineScope(lifecycleJob + readDispatcher)
    private val queue = Channel<AudioChunk>(QUEUE_CAPACITY)

    /** Bounded FIFO of 20 ms frames (capacity 64). Closed by the producer on shutdown. */
    override val chunks: ReceiveChannel<AudioChunk> = queue

    override val frameQueueCapacity: Int = QUEUE_CAPACITY

    private val latestQueuedSequence = AtomicLong(NO_SEQUENCE)

    override fun queuedFrameDepth(chunk: AudioChunk): Int {
        val latestSequence = latestQueuedSequence.get()
        return if (latestSequence == NO_SEQUENCE) {
            0
        } else {
            chunk.depthBehind(latestSequence, QUEUE_CAPACITY)
        }
    }

    private val _amplitude = MutableStateFlow(0f)

    /** Smoothed input level in [0, 1], updated at ~20 Hz. */
    override val amplitude: StateFlow<Float> = _amplitude.asStateFlow()

    private val _failures = MutableSharedFlow<DictationFailure>(replay = 1, extraBufferCapacity = 4)

    /** Typed terminal failures (mic init/read); the latest is replayed to late subscribers. */
    override val failures: SharedFlow<DictationFailure> = _failures.asSharedFlow()

    private val chunker = Chunker(sampleRateHz)
    private val readBufferBytes =
        GemAudioFormat.FRAME_MILLIS * sampleRateHz * GemAudioFormat.CHAR_BYTES / 1000

    private val started = AtomicBoolean(false)
    private val startupCompleted = CompletableDeferred<Unit>()
    private val stopRequested = AtomicBoolean(false)
    private val hardStopped = AtomicBoolean(false)
    private val sourceLifecycleLock = Any()
    private var sourceStopSignalled = false
    private var sourceReleased = false

    @Volatile
    private var source: PcmSource? = null

    @Volatile
    private var producerJob: Job? = null

    @Volatile
    private var callerCancellationHandle: DisposableHandle? = null

    init {
        val handle = scope.coroutineContext[Job]?.invokeOnCompletion { stop() }
        callerCancellationHandle = handle
        if (hardStopped.get()) detachCallerCancellation()
    }

    /** Starts capture. Safe to call once; subsequent calls return [AudioStartResult.Started]. */
    override fun start(): AudioStartResult {
        if (!started.compareAndSet(false, true)) return AudioStartResult.Started
        try {
            if (stopRequested.get()) {
                finishWithoutProducer()
                return AudioStartResult.Started
            }

            val acquired = runCatching(sourceFactory).getOrNull()
            if (acquired == null) {
                val failure = DictationFailure(MIC_INIT, "Microphone could not start", recoverable = true)
                _failures.tryEmit(failure)
                finishWithoutProducer()
                return AudioStartResult.Failed(failure)
            }
            source = acquired
            if (stopRequested.get()) {
                signalSourceStop(acquired)
                releaseSourceOnce(acquired)
                finishWithoutProducer()
                return AudioStartResult.Started
            }

            val job = captureScope.launch(start = CoroutineStart.LAZY) { producerLoop(acquired) }
            producerJob = job
            job.invokeOnCompletion { finishProducer(acquired) }
            job.start()
            return AudioStartResult.Started
        } finally {
            startupCompleted.complete(Unit)
        }
    }

    /**
     * Requests an orderly stop and unblocks the active read. Interruptible
     * sources are stopped without being released; legacy sources are released
     * once as the compatibility fallback.
     */
    override fun requestStop() {
        if (!stopRequested.compareAndSet(false, true)) return
        val current = source
        if (current != null) {
            signalSourceStop(current)
        } else if (!started.get()) {
            finishWithoutProducer()
        }
    }

    /**
     * Joins the orderly producer drain with a bounded timeout. A timeout
     * immediately invokes [stop], so no caller can accidentally retain this
     * capture as an unbounded child join.
     */
    override suspend fun awaitQuiescence(timeoutMs: Long): Boolean {
        require(timeoutMs >= 0) { "timeoutMs must be >= 0, was $timeoutMs" }
        if (!started.get()) return true
        val quiesced = withTimeoutOrNull(timeoutMs) {
            startupCompleted.await()
            producerJob?.join()
            true
        } ?: false
        if (!quiesced) stop()
        return quiesced
    }

    /** Idempotent hard stop: cancels ownership, closes the queue, and releases once. */
    override fun stop() {
        if (!hardStopped.compareAndSet(false, true)) return
        stopRequested.set(true)
        lifecycleJob.cancel()
        queue.close()
        releaseSourceOnce(source)
        detachCallerCancellation()
    }

    private suspend fun producerLoop(source: PcmSource) {
        val buffer = ByteArray(readBufferBytes)
        var amplitudeTick = 0
        try {
            try {
                while (!stopRequested.get()) {
                    val read = source.read(buffer)
                    if (read < 0 || read > buffer.size) {
                        if (!stopRequested.get()) emitReadFailure()
                        break
                    }
                    if (read > 0) {
                        for (chunk in chunker.push(buffer, read)) {
                            enqueue(chunk)
                            // UI amplitude is sampled at ~16.7 Hz (every 3rd 20 ms frame);
                            // capture and transmission stay at the full 50 Hz cadence.
                            if (++amplitudeTick % AMPLITUDE_SAMPLE_EVERY == 0) {
                                _amplitude.value =
                                    smoothedAmplitude(chunk.pcm16Bytes, chunk.byteCount)
                            }
                        }
                    }
                    // No artificial delay: AudioRecord's blocking read paces the stream
                    // at exactly real time. Any gap here would reach the Gemini Live
                    // ASR as choppy audio and break its voice-activity detection
                    // (inputTranscription silently never fires).
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!stopRequested.get()) emitReadFailure()
            }

            // Orderly shutdown: emit the zero-padded partial frame exactly once,
            // then close the channel so the ordered sender drains and stops.
            if (stopRequested.get() && !hardStopped.get()) {
                chunker.remaining()?.let { enqueue(it) }
            }
        } finally {
            queue.close()
            releaseSourceOnce(source)
        }
    }

    private fun emitReadFailure() {
        _failures.tryEmit(DictationFailure(MIC_READ, "Microphone read failed", recoverable = true))
    }

    private suspend fun enqueue(chunk: AudioChunk) {
        latestQueuedSequence.set(chunk.sequence)
        queue.send(chunk)
    }

    private fun signalSourceStop(source: PcmSource) {
        synchronized(sourceLifecycleLock) {
            if (sourceStopSignalled || sourceReleased) return
            sourceStopSignalled = true
            if (source is InterruptiblePcmSource) {
                if (runCatching { source.requestStop() }.isSuccess) return
            }
            sourceReleased = true
            runCatching { source.release() }
        }
    }

    private fun releaseSourceOnce(source: PcmSource?) {
        if (source == null) return
        synchronized(sourceLifecycleLock) {
            if (sourceReleased) return
            sourceReleased = true
            runCatching { source.release() }
        }
    }

    private fun finishProducer(source: PcmSource) {
        queue.close()
        releaseSourceOnce(source)
        detachCallerCancellation()
        lifecycleJob.complete()
    }

    private fun finishWithoutProducer() {
        queue.close()
        detachCallerCancellation()
        lifecycleJob.complete()
    }

    private fun detachCallerCancellation() {
        val handle = callerCancellationHandle
        callerCancellationHandle = null
        handle?.dispose()
    }

    private fun smoothedAmplitude(pcm16: ByteArray, byteCount: Int): Float {
        val current = _amplitude.value
        val raw = amplitudeOf(pcm16, byteCount)
        return current + AMPLITUDE_ALPHA * (raw - current)
    }

    /** RMS over a small slice of the frame, normalized to [0, 1]. */
    private fun amplitudeOf(pcm16: ByteArray, byteCount: Int): Float {
        val maxSamples = minOf(AMPLITUDE_SLICE_SAMPLES, byteCount / 2)
        var sum = 0.0
        var i = 0
        while (i < maxSamples) {
            val sample = (pcm16[i * 2].toInt() and 0xFF) or (pcm16[i * 2 + 1].toInt() shl 8)
            sum += (sample.toLong() * sample).toDouble()
            i++
        }
        if (i == 0) return 0f
        val rms = sqrt(sum / i)
        return (rms / MAX_PCM16_AMPLITUDE).toFloat().coerceIn(0f, 1f)
    }

    companion object {
        /**
         * Builds a mono, 16-bit, 16 kHz [AudioRecord]-backed source, or null when
         * the microphone cannot be initialized.
         *
         * Permission is enforced by the caller ([FlowRuntimeService] checks
         * RECORD_AUDIO before starting capture); init/read failures surface as
         * typed failures, never raw throws.
         */
        @SuppressLint("MissingPermission")
        fun createDefaultSource(): PcmSource? {
            val minBuffer = AudioRecord.getMinBufferSize(
                GemAudioFormat.SAMPLE_RATE_HZ,
                GemAudioFormat.CHANNEL_IN,
                GemAudioFormat.AUDIO_FORMAT,
            )
            val bufferSize = if (minBuffer > 0) minBuffer else DEFAULT_BUFFER_BYTES
            val record = runCatching {
                AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    GemAudioFormat.SAMPLE_RATE_HZ,
                    GemAudioFormat.CHANNEL_IN,
                    GemAudioFormat.AUDIO_FORMAT,
                    bufferSize,
                )
            }.getOrNull() ?: return null
            return initializedRecordSource(record)
        }

        /**
         * Builds a mono, 16-bit, 16 kHz [AudioRecord]-backed source pinned
         * to a specific input [device] (e.g. a connected bluetooth headset), or
         * null when the device cannot be initialized. Uses the
         * [MediaRecorder.AudioSource.VOICE_COMMUNICATION] source so the system
         * routes to the headset profile (SCO) when the device is a bluetooth
         * headset, and [AudioRecord.setPreferredDevice] pins the recording to it.
         * Init/read failures surface as typed failures, never raw throws.
         */
        @SuppressLint("MissingPermission")
        fun createDeviceSource(device: AudioDeviceInfo): PcmSource? {
            val minBuffer = AudioRecord.getMinBufferSize(
                GemAudioFormat.SAMPLE_RATE_HZ,
                GemAudioFormat.CHANNEL_IN,
                GemAudioFormat.AUDIO_FORMAT,
            )
            val bufferSize = if (minBuffer > 0) minBuffer else DEFAULT_BUFFER_BYTES
            val record = runCatching {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    GemAudioFormat.SAMPLE_RATE_HZ,
                    GemAudioFormat.CHANNEL_IN,
                    GemAudioFormat.AUDIO_FORMAT,
                    bufferSize,
                )
            }.getOrNull() ?: return null
            val preferred = runCatching { record.setPreferredDevice(device) }.getOrDefault(false)
            if (!preferred) {
                runCatching { record.release() }
                return null
            }
            return initializedRecordSource(record)
        }

        /** Shared state/start validation and [PcmSource] adapter for a built record. */
        private fun initializedRecordSource(record: AudioRecord): PcmSource? {
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                runCatching { record.release() }
                return null
            }
            val recording = runCatching {
                record.startRecording()
                record.recordingState == AudioRecord.RECORDSTATE_RECORDING
            }.getOrDefault(false)
            if (!recording) {
                runCatching { record.release() }
                return null
            }
            return object : InterruptiblePcmSource {
                private val stopped = AtomicBoolean(false)

                override fun read(out: ByteArray): Int =
                    record.read(out, 0, out.size, AudioRecord.READ_BLOCKING)

                override fun requestStop() {
                    if (stopped.compareAndSet(false, true)) {
                        record.stop()
                    }
                }

                override fun release() {
                    try {
                        requestStop()
                    } finally {
                        record.release()
                    }
                }
            }
        }

        private const val QUEUE_CAPACITY = 64
        private const val AMPLITUDE_SLICE_SAMPLES = 256
        private const val AMPLITUDE_ALPHA = 0.5f
        /** Publish the waveform state every Nth 20 ms frame (~16.7 Hz at 50 Hz reads). */
        private const val AMPLITUDE_SAMPLE_EVERY = 3
        private const val MAX_PCM16_AMPLITUDE = 32768.0
        private const val DEFAULT_BUFFER_BYTES = 4096
        private const val MIC_INIT = "MIC_INIT"
        private const val MIC_READ = "MIC_READ"
        private const val NO_SEQUENCE = -1L
    }
}
