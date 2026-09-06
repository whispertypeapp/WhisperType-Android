package com.whispertype.android.audio

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.collections.ArrayDeque
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Deterministic lifecycle coverage: every producer is synchronized at its
 * blocking read before shutdown starts, without timing sleeps.
 */
class AudioCaptureOrderlyShutdownTest {

    private class BlockingInterruptibleSource(
        chunks: List<ByteArray> = emptyList(),
        private val stopUnblocks: Boolean = true,
        private val releaseUnblocks: Boolean = true,
    ) : InterruptiblePcmSource {
        private val chunks = ArrayDeque(chunks)
        private val unblockRead = CountDownLatch(1)
        private val blockedRead = CountDownLatch(1)
        private val stopCounter = AtomicInteger()
        private val releaseCounter = AtomicInteger()

        override fun read(out: ByteArray): Int {
            val data = chunks.removeFirstOrNull()
            if (data != null) {
                data.copyInto(out)
                return data.size
            }
            blockedRead.countDown()
            unblockRead.await()
            return 0
        }

        override fun requestStop() {
            stopCounter.incrementAndGet()
            if (stopUnblocks) unblockRead.countDown()
        }

        override fun release() {
            releaseCounter.incrementAndGet()
            if (releaseUnblocks) unblockRead.countDown()
        }

        val stopCount: Int get() = stopCounter.get()
        val releaseCount: Int get() = releaseCounter.get()

        fun awaitBlocked() {
            assertTrue(blockedRead.await(2, TimeUnit.SECONDS), "producer never entered blocking read")
        }

        fun unblockExternally() {
            unblockRead.countDown()
        }
    }

    private class LegacyBlockingSource : PcmSource {
        private val unblockRead = CountDownLatch(1)
        private val blockedRead = CountDownLatch(1)
        private val releaseCounter = AtomicInteger()

        override fun read(out: ByteArray): Int {
            blockedRead.countDown()
            unblockRead.await()
            return 0
        }

        override fun release() {
            releaseCounter.incrementAndGet()
            unblockRead.countDown()
        }

        val releaseCount: Int get() = releaseCounter.get()

        fun awaitBlocked() {
            assertTrue(blockedRead.await(2, TimeUnit.SECONDS), "producer never entered blocking read")
        }
    }

    private val fullFrame = ByteArray(640) { 0x01 }
    private val partialFrame = ByteArray(100) { 0x02 }

    @Test
    fun `orderly stop flushes complete and zero-padded partial frames in order then closes`() = runBlocking {
        val source = BlockingInterruptibleSource(listOf(fullFrame, partialFrame))
        val capture = AudioCapture(sourceFactory = { source })

        assertIs<AudioStartResult.Started>(capture.start())
        source.awaitBlocked()
        capture.requestStop()

        assertTrue(capture.awaitQuiescence(timeoutMs = 2_000))
        val emitted = withTimeout(2_000) { capture.chunks.toList() }

        assertEquals(1, source.stopCount)
        assertEquals(1, source.releaseCount)
        assertEquals(2, emitted.size)
        assertEquals(0, emitted[0].sequence)
        assertEquals(1, emitted[1].sequence)
        assertEquals(640, emitted[0].byteCount)
        assertEquals(640, emitted[1].byteCount, "the partial frame must be zero-padded to a full frame")
        assertContentEquals(fullFrame, emitted[0].pcm16Bytes)
        assertTrue(emitted[1].pcm16Bytes.take(100).all { it == 0x02.toByte() })
        assertTrue(emitted[1].pcm16Bytes.drop(100).all { it == 0x00.toByte() })
        assertEquals(64, capture.frameQueueCapacity)
        assertEquals(1, capture.queuedFrameDepth(emitted[0]))
        assertEquals(0, capture.queuedFrameDepth(emitted[1]))
    }

    @Test
    fun `requestStop and stop are idempotent and final release stays separate`() = runBlocking {
        val source = BlockingInterruptibleSource(stopUnblocks = false)
        val capture = AudioCapture(sourceFactory = { source })
        assertIs<AudioStartResult.Started>(capture.start())
        source.awaitBlocked()

        capture.requestStop()
        capture.requestStop()

        assertEquals(1, source.stopCount)
        assertEquals(0, source.releaseCount)

        source.unblockExternally()
        val quiesced = capture.awaitQuiescence(timeoutMs = 2_000)
        capture.stop()
        capture.stop()

        assertTrue(quiesced)
        assertEquals(1, source.stopCount)
        assertEquals(1, source.releaseCount)
        assertTrue(capture.chunks.toList().isEmpty())
    }

    @Test
    fun `legacy source uses one release to unblock and is never released twice`() = runBlocking {
        val source = LegacyBlockingSource()
        val capture = AudioCapture(sourceFactory = { source })
        assertIs<AudioStartResult.Started>(capture.start())
        source.awaitBlocked()

        capture.requestStop()
        capture.requestStop()
        assertTrue(capture.awaitQuiescence(timeoutMs = 2_000))
        capture.stop()
        capture.stop()

        assertEquals(1, source.releaseCount)
        assertTrue(capture.chunks.toList().isEmpty())
    }

    @Test
    fun `timed out producer is hard-stopped without an unbounded follow-up join`() = runBlocking {
        val source = BlockingInterruptibleSource(
            stopUnblocks = false,
            releaseUnblocks = false,
        )
        val capture = AudioCapture(sourceFactory = { source })
        assertIs<AudioStartResult.Started>(capture.start())
        source.awaitBlocked()
        capture.requestStop()

        assertFalse(capture.awaitQuiescence(timeoutMs = 100))
        assertEquals(1, source.stopCount)
        assertEquals(1, source.releaseCount)
        assertTrue(withTimeout(2_000) { capture.chunks.toList() }.isEmpty())

        source.unblockExternally()
        assertTrue(capture.awaitQuiescence(timeoutMs = 2_000))
        capture.stop()
        assertEquals(1, source.releaseCount)
    }

    @Test
    fun `caller scope cancellation cannot be held open by a stuck producer`() = runBlocking {
        val source = BlockingInterruptibleSource(
            stopUnblocks = false,
            releaseUnblocks = false,
        )
        val callerJob = Job()
        val callerScope = CoroutineScope(callerJob + Dispatchers.Default)
        val capture = AudioCapture(sourceFactory = { source }, scope = callerScope)
        assertIs<AudioStartResult.Started>(capture.start())
        source.awaitBlocked()

        callerJob.cancel()
        withTimeout(2_000) { callerJob.join() }

        assertEquals(1, source.releaseCount)
        assertTrue(withTimeout(2_000) { capture.chunks.toList() }.isEmpty())

        source.unblockExternally()
        assertTrue(capture.awaitQuiescence(timeoutMs = 2_000))
        capture.stop()
    }

    @Test
    fun `stop racing source acquisition releases once and closes the channel`() = runBlocking {
        val factoryEntered = CountDownLatch(1)
        val finishFactory = CountDownLatch(1)
        val source = LegacyBlockingSource()
        val capture = AudioCapture(
            sourceFactory = {
                factoryEntered.countDown()
                finishFactory.await()
                source
            },
        )

        val startResult = async(Dispatchers.Default) { capture.start() }
        assertTrue(factoryEntered.await(2, TimeUnit.SECONDS), "source factory did not start")
        capture.stop()
        finishFactory.countDown()

        assertIs<AudioStartResult.Started>(startResult.await())
        assertEquals(1, source.releaseCount)
        assertTrue(withTimeout(2_000) { capture.chunks.toList() }.isEmpty())
    }

    @Test
    fun `requestStop before start does not acquire a source or leak the channel`() = runBlocking {
        val factoryCalls = AtomicInteger()
        val capture = AudioCapture(
            sourceFactory = {
                factoryCalls.incrementAndGet()
                LegacyBlockingSource()
            },
        )

        capture.requestStop()
        assertIs<AudioStartResult.Started>(capture.start())

        assertEquals(0, factoryCalls.get())
        assertTrue(withTimeout(2_000) { capture.chunks.toList() }.isEmpty())
    }

    @Test
    fun `blocking reads run on the explicit capture dispatcher`() = runBlocking {
        val readThread = AtomicReference<String>()
        val source = object : InterruptiblePcmSource {
            private val unblockRead = CountDownLatch(1)
            val readStarted = CountDownLatch(1)

            override fun read(out: ByteArray): Int {
                readThread.set(Thread.currentThread().name)
                readStarted.countDown()
                unblockRead.await()
                return 0
            }

            override fun requestStop() {
                unblockRead.countDown()
            }

            override fun release() = Unit
        }
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "capture-read-test")
        }
        val dispatcher = executor.asCoroutineDispatcher()
        try {
            val capture = AudioCapture(
                sourceFactory = { source },
                readDispatcher = dispatcher,
            )

            assertIs<AudioStartResult.Started>(capture.start())
            assertTrue(source.readStarted.await(2, TimeUnit.SECONDS), "capture read did not start")
            // Coroutine dispatchers append " @coroutine#N"; assert the dispatcher's
            // thread is the one driving the read.
            assertTrue(readThread.get()?.startsWith("capture-read-test") == true)
            capture.requestStop()
            assertTrue(capture.awaitQuiescence(timeoutMs = 2_000))
        } finally {
            dispatcher.close()
        }
    }

    @Test
    fun `a source factory that yields null surfaces a typed mic-init failure`() = runBlocking {
        // Models the bluetooth fallback: when no usable device is available the
        // factory returns null, and start() reports a typed failure, never a throw.
        val capture = AudioCapture(sourceFactory = { null })

        val result = capture.start()

        assertIs<AudioStartResult.Failed>(result)
        assertTrue(capture.failures.replayCache.single().code.contains("MIC_INIT"))
        assertTrue(capture.chunks.toList().isEmpty())
    }
}
