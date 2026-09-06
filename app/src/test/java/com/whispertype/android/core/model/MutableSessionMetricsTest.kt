package com.whispertype.android.core.model

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Unit tests for [MutableSessionMetrics] using a fake monotonic clock. */
class MutableSessionMetricsTest {

    private var now = 0L
    private val metrics = MutableSessionMetrics(SessionId("s1")) { now }

    private fun advanceMillis(ms: Long) {
        now += ms * 1_000_000
    }

    private fun nanos(ms: Long): Long = ms * 1_000_000

    @Test
    fun `timestamps start null and counters start zero`() {
        assertNull(metrics.tapAt)
        assertNull(metrics.captureStartedAt)
        assertNull(metrics.stopAt)
        assertNull(metrics.insertionResultAt)
        assertEquals(0L, metrics.capturedFrames)
        assertEquals(0L, metrics.acceptedFrames)
        assertEquals(0L, metrics.rejectedFrames)
        assertEquals(0, metrics.maxWebSocketQueueSize)
        assertEquals(0L, metrics.inputTranscriptionCount)
        assertEquals(0L, metrics.outputTranscriptionCount)
        assertEquals(0L, metrics.maxWebSocketQueueBytes)
        assertEquals(0, metrics.maxFrameQueueDepth)
        assertNull(metrics.maxFrameQueueAgeNanos)
        assertNull(metrics.warmSessionHit)
        assertNull(metrics.warmClaimResult)
        assertNull(metrics.warmClaimAgeNanos)
        assertNull(metrics.lastInputRevisionAt)
        assertNull(metrics.lastEchoRevisionAt)
        assertNull(metrics.quietBarrierSatisfiedAt)
        assertNull(metrics.targetReserved)
        assertNull(metrics.settlementReason)
        assertNull(metrics.terminalOutcome)
        assertFalse(metrics.turnCompleteArrived)
        assertFalse(metrics.generationCompleteArrived)
        assertFalse(metrics.usedHardDeadline)
        assertFalse(metrics.audioBufferOverflow)
    }

    @Test
    fun `mark records events and is first-wins`() {
        metrics.mark(MutableSessionMetrics.Event.Tap)
        advanceMillis(2)
        metrics.mark(MutableSessionMetrics.Event.Tap)
        metrics.mark(MutableSessionMetrics.Event.CaptureStarted)
        advanceMillis(1)
        metrics.mark(MutableSessionMetrics.Event.CaptureStarted)

        assertEquals(0L, metrics.tapAt)
        assertEquals(2_000_000L, metrics.captureStartedAt)
        assertEquals(2L, metrics.tapToCaptureMs())
    }

    @Test
    fun `concurrent first marks have exactly one winner`() {
        val concurrent = MutableSessionMetrics(SessionId("concurrent")) { error("unused") }
        val candidates = (1L..32L).toList()
        val start = CountDownLatch(1)
        val done = CountDownLatch(candidates.size)
        val winners = AtomicInteger()
        val workers = candidates.map { candidate ->
            thread(start = true) {
                try {
                    start.await()
                    if (concurrent.markIfFirst(MutableSessionMetrics.Event.Tap, candidate)) {
                        winners.incrementAndGet()
                    }
                } finally {
                    done.countDown()
                }
            }
        }

        start.countDown()

        assertTrue(done.await(5, TimeUnit.SECONDS))
        workers.forEach { it.join() }
        assertEquals(1, winners.get())
        val winner = concurrent.tapAt
        assertTrue(winner != null && winner in candidates)
        concurrent.mark(MutableSessionMetrics.Event.Tap, 999L)
        assertEquals(winner, concurrent.tapAt)
    }

    @Test
    fun `atomic counter methods do not lose concurrent increments`() {
        val concurrent = MutableSessionMetrics(SessionId("counters")) { error("unused") }
        val workerCount = 8
        val incrementsPerWorker = 2_000
        val start = CountDownLatch(1)
        val done = CountDownLatch(workerCount)
        val workers = List(workerCount) {
            thread(start = true) {
                try {
                    start.await()
                    repeat(incrementsPerWorker) {
                        concurrent.incrementCapturedFrames()
                        concurrent.incrementAcceptedFrames()
                        concurrent.incrementRejectedFrames()
                    }
                } finally {
                    done.countDown()
                }
            }
        }

        start.countDown()

        assertTrue(done.await(5, TimeUnit.SECONDS))
        workers.forEach { it.join() }
        val expected = (workerCount * incrementsPerWorker).toLong()
        assertEquals(expected, concurrent.capturedFrames)
        assertEquals(expected, concurrent.acceptedFrames)
        assertEquals(expected, concurrent.rejectedFrames)
    }

    @Test
    fun `concurrent echo recording retains chronological endpoints and exact count`() {
        val concurrent = MutableSessionMetrics(SessionId("echo")) { error("unused") }
        val echoTimes = listOf(nanos(30), nanos(10), nanos(20), nanos(40))
        val start = CountDownLatch(1)
        val done = CountDownLatch(echoTimes.size)
        val workers = echoTimes.map { atNanos ->
            thread(start = true) {
                try {
                    start.await()
                    concurrent.recordEcho(atNanos)
                } finally {
                    done.countDown()
                }
            }
        }

        start.countDown()

        assertTrue(done.await(5, TimeUnit.SECONDS))
        workers.forEach { it.join() }
        assertEquals(echoTimes.size.toLong(), concurrent.outputTranscriptionCount)
        assertEquals(nanos(10), concurrent.firstEchoAt)
        assertEquals(nanos(40), concurrent.lastEchoAt)
        assertEquals(30L, concurrent.firstEchoToLastEchoMs())
    }

    @Test
    fun `concurrent input recording retains chronological endpoints and exact count`() {
        val concurrent = MutableSessionMetrics(SessionId("input")) { error("unused") }
        val revisionTimes = listOf(nanos(30), nanos(10), nanos(20), nanos(40))
        val start = CountDownLatch(1)
        val done = CountDownLatch(revisionTimes.size)
        val workers = revisionTimes.map { atNanos ->
            thread(start = true) {
                try {
                    start.await()
                    concurrent.recordInputRevision(atNanos)
                } finally {
                    done.countDown()
                }
            }
        }

        start.countDown()

        assertTrue(done.await(5, TimeUnit.SECONDS))
        workers.forEach { it.join() }
        assertEquals(revisionTimes.size.toLong(), concurrent.inputTranscriptionCount)
        assertEquals(nanos(10), concurrent.firstInputRevisionAt)
        assertEquals(nanos(40), concurrent.lastInputRevisionAt)
        assertEquals(30L, concurrent.firstInputToLastInputRevisionMs())
    }

    @Test
    fun `derived durations are computed from the fake monotonic clock`() {
        metrics.mark(MutableSessionMetrics.Event.Tap)
        advanceMillis(10)
        metrics.mark(MutableSessionMetrics.Event.SetupComplete)
        metrics.mark(MutableSessionMetrics.Event.CaptureStarted)
        advanceMillis(5)
        metrics.mark(MutableSessionMetrics.Event.FirstAudioQueued)
        advanceMillis(30)
        metrics.mark(MutableSessionMetrics.Event.FirstInputTranscript)
        advanceMillis(7)
        metrics.mark(MutableSessionMetrics.Event.Stop)
        advanceMillis(20)
        metrics.mark(MutableSessionMetrics.Event.CaptureQuiesced)
        metrics.mark(MutableSessionMetrics.Event.ActivityEndQueued)
        advanceMillis(40)
        metrics.mark(MutableSessionMetrics.Event.TurnComplete)
        advanceMillis(60)
        metrics.mark(MutableSessionMetrics.Event.TranscriptSettled)
        metrics.mark(MutableSessionMetrics.Event.InsertionRequested)
        advanceMillis(80)
        metrics.mark(MutableSessionMetrics.Event.InsertionResult)

        assertEquals(10L, metrics.tapToCaptureMs())
        assertEquals(10L, metrics.tapToSetupCompleteMs())
        assertEquals(15L, metrics.tapToFirstAudioQueuedMs())
        assertEquals(30L, metrics.firstAudioToFirstTranscriptMs())
        assertEquals(20L, metrics.stopToCaptureQuiescedMs())
        assertEquals(20L, metrics.stopToActivityEndQueuedMs())
        assertEquals(60L, metrics.stopToTurnCompleteMs())
        assertEquals(120L, metrics.stopToSettledMs())
        assertEquals(200L, metrics.stopToInsertionResultMs())
        assertEquals(80L, metrics.insertionRequestedToResultMs())
    }

    @Test
    fun `durations are null until both endpoints exist`() {
        metrics.mark(MutableSessionMetrics.Event.Tap)
        assertNull(metrics.tapToCaptureMs())
        assertNull(metrics.stopToInsertionResultMs())
        metrics.mark(MutableSessionMetrics.Event.CaptureStarted)
        assertEquals(0L, metrics.tapToCaptureMs())
    }

    @Test
    fun `completion events before stop produce zero remaining latency`() {
        metrics.recordInputRevision(nanos(4))
        metrics.mark(MutableSessionMetrics.Event.TurnComplete, nanos(5))
        metrics.recordEcho(nanos(6))
        metrics.recordGenerationComplete(nanos(7))
        metrics.mark(MutableSessionMetrics.Event.Stop, nanos(10))

        assertEquals(0L, metrics.stopToTurnCompleteMs())
        assertEquals(0L, metrics.stopToFirstInputRevisionMs())
        assertEquals(0L, metrics.stopToLastInputRevisionMs())
        assertEquals(0L, metrics.stopToFirstEchoMs())
        assertEquals(0L, metrics.stopToLastEchoMs())
        assertEquals(0L, metrics.stopToGenerationCompleteMs())
        assertTrue(metrics.turnCompleteArrived)
        assertTrue(metrics.generationCompleteArrived)
    }

    @Test
    fun `counters and flags accumulate correctly`() {
        metrics.capturedFrames += 10
        metrics.acceptedFrames += 8
        metrics.rejectedFrames += 2
        metrics.inputTranscriptionCount += 3
        metrics.outputTranscriptionCount += 1
        metrics.turnCompleteArrived = true
        metrics.usedHardDeadline = true
        metrics.audioBufferOverflow = true

        assertEquals(10L, metrics.capturedFrames)
        assertEquals(8L, metrics.acceptedFrames)
        assertEquals(2L, metrics.rejectedFrames)
        assertEquals(3L, metrics.inputTranscriptionCount)
        assertEquals(1L, metrics.outputTranscriptionCount)
        assertTrue(metrics.turnCompleteArrived)
        assertTrue(metrics.usedHardDeadline)
        assertTrue(metrics.audioBufferOverflow)
    }

    @Test
    fun `single-result aggregate fields are first-wins`() {
        assertTrue(metrics.recordWarmSessionResult(hit = true, atNanos = nanos(1)))
        assertFalse(metrics.recordWarmSessionResult(hit = false, atNanos = nanos(2)))
        assertTrue(metrics.recordTargetReservationResult(reserved = false, atNanos = nanos(3)))
        assertFalse(metrics.recordTargetReservationResult(reserved = true, atNanos = nanos(4)))
        assertTrue(metrics.recordSettlementReason(SettlementReason.ECHO_DEBOUNCE))
        assertFalse(metrics.recordSettlementReason(SettlementReason.HARD_DEADLINE))
        assertTrue(metrics.recordTerminalOutcome(TerminalOutcome.ERROR, nanos(5)))
        assertFalse(metrics.recordTerminalOutcome(TerminalOutcome.SUCCESS, nanos(6)))

        assertTrue(metrics.warmSessionHit == true)
        assertTrue(metrics.targetReserved == false)
        assertEquals(SettlementReason.ECHO_DEBOUNCE, metrics.settlementReason)
        assertFalse(metrics.usedHardDeadline)
        assertEquals(TerminalOutcome.ERROR, metrics.terminalOutcome)
        assertEquals(nanos(5), metrics.terminalAt)
    }

    @Test
    fun `typed warm claim records hit and lease age with explicit units`() {
        assertTrue(
            metrics.recordWarmClaim(
                result = WarmClaimResult.HIT,
                ageNanos = nanos(125),
                atNanos = nanos(130),
            ),
        )

        val snapshot = metrics.snapshot()
        assertEquals(WarmClaimResult.HIT, snapshot.warmClaimResult)
        assertTrue(snapshot.warmSessionHit == true)
        assertEquals(125L, snapshot.warmClaimAgeMs)
        assertEquals(nanos(130), snapshot.warmClaimResolvedAtMonotonicNanos)
        assertFailsWith<IllegalArgumentException> {
            MutableSessionMetrics(SessionId("negative-age")) { 0L }.recordWarmClaim(
                result = WarmClaimResult.HIT,
                ageNanos = -1L,
            )
        }
    }

    @Test
    fun `typed warm miss retains reason and observed ready age`() {
        assertTrue(
            metrics.recordWarmClaim(
                result = WarmClaimResult.PROFILE_MISMATCH,
                ageNanos = nanos(25),
                atNanos = nanos(30),
            ),
        )

        val snapshot = metrics.snapshot()
        assertEquals(WarmClaimResult.PROFILE_MISMATCH, snapshot.warmClaimResult)
        assertTrue(snapshot.warmSessionHit == false)
        assertEquals(25L, snapshot.warmClaimAgeMs)
    }

    @Test
    fun `high-water recorders track explicit units and reject invalid samples`() {
        metrics.recordWebSocketQueue(4)
        metrics.recordWebSocketQueue(2)
        metrics.recordWebSocketQueue(9)
        metrics.recordWebSocketQueue(7)
        metrics.recordFrameQueue(depthFrames = 3, ageNanos = nanos(12))
        metrics.recordFrameQueue(depthFrames = 2, ageNanos = nanos(5))
        metrics.recordFrameQueueFromTimestamp(
            depthFrames = 4,
            enqueuedAtNanos = nanos(20),
            observedAtNanos = nanos(35),
        )
        metrics.recordFrameQueueFromTimestamp(
            depthFrames = 5,
            enqueuedAtNanos = nanos(40),
            observedAtNanos = nanos(35),
        )
        now = nanos(35)
        metrics.recordFrameDequeued(
            depthFrames = 6,
            capturedAtMonotonicNanos = nanos(20),
        )

        assertEquals(9, metrics.maxWebSocketQueueSize)
        assertEquals(9L, metrics.maxWebSocketQueueBytes)
        assertEquals(6, metrics.maxFrameQueueDepth)
        assertEquals(nanos(15), metrics.maxFrameQueueAgeNanos)
        assertEquals(15L, metrics.maxFrameQueueAgeMs())
        assertTrue("maxWebSocketQueueBytes=9" in metrics.summary())
        assertTrue("maxFrameQueueDepthFrames=6" in metrics.summary())
        assertTrue("maxFrameQueueAgeMs=15" in metrics.summary())
        assertFailsWith<IllegalArgumentException> { metrics.recordWebSocketQueueBytes(-1L) }
        assertFailsWith<IllegalArgumentException> { metrics.recordFrameQueueDepth(-1) }
        assertFailsWith<IllegalArgumentException> { metrics.recordFrameQueueAgeNanos(-1L) }
    }

    @Test
    fun `high-water updates remain correct when recorders race`() {
        val concurrent = MutableSessionMetrics(SessionId("high-water")) { error("unused") }
        val samples = (0..100 step 5).shuffled(kotlin.random.Random(7))
        val start = CountDownLatch(1)
        val done = CountDownLatch(samples.size)
        val workers = samples.map { sample ->
            thread(start = true) {
                try {
                    start.await()
                    concurrent.recordWebSocketQueueBytes(sample.toLong())
                    concurrent.recordFrameQueueDepth(sample)
                    concurrent.recordFrameQueueAgeNanos(nanos(sample.toLong()))
                } finally {
                    done.countDown()
                }
            }
        }

        start.countDown()

        assertTrue(done.await(5, TimeUnit.SECONDS))
        workers.forEach { it.join() }
        assertEquals(100L, concurrent.maxWebSocketQueueBytes)
        assertEquals(100, concurrent.maxFrameQueueDepth)
        assertEquals(nanos(100), concurrent.maxFrameQueueAgeNanos)
    }

    @Test
    fun `snapshot maps derived fields and started and ended timestamps`() {
        metrics.mark(MutableSessionMetrics.Event.Tap)
        advanceMillis(12)
        metrics.mark(MutableSessionMetrics.Event.SetupComplete)
        metrics.mark(MutableSessionMetrics.Event.CaptureStarted)
        advanceMillis(5)
        metrics.mark(MutableSessionMetrics.Event.FirstAudioQueued)
        advanceMillis(30)
        metrics.mark(MutableSessionMetrics.Event.FirstInputTranscript)
        advanceMillis(7)
        metrics.mark(MutableSessionMetrics.Event.Stop)
        advanceMillis(100)
        metrics.mark(MutableSessionMetrics.Event.TurnComplete)
        advanceMillis(60)
        metrics.mark(MutableSessionMetrics.Event.TranscriptSettled)
        advanceMillis(80)
        metrics.mark(MutableSessionMetrics.Event.InsertionResult)
        metrics.acceptedFrames = 42
        metrics.rejectedFrames = 3
        metrics.maxWebSocketQueueSize = 16
        metrics.inputTranscriptionCount = 5
        metrics.outputTranscriptionCount = 2
        metrics.turnCompleteArrived = true
        metrics.usedHardDeadline = false
        metrics.audioBufferOverflow = false

        val snapshot = metrics.snapshot()

        assertEquals(SessionId("s1"), snapshot.sessionId)
        assertEquals(0L, snapshot.startedAtMillis)
        assertEquals(294L, snapshot.endedAtMillis)
        assertEquals(12L, snapshot.tapToCaptureMs)
        assertEquals(12L, snapshot.tapToSetupCompleteMs)
        assertEquals(17L, snapshot.tapToFirstAudioQueuedMs)
        assertEquals(30L, snapshot.firstAudioToFirstTranscriptMs)
        assertEquals(100L, snapshot.stopToTurnCompleteMs)
        assertEquals(160L, snapshot.stopToSettledMs)
        assertEquals(240L, snapshot.stopToInsertionResultMs)
        assertNull(snapshot.insertionRequestedToResultMs)
        assertEquals(42L, snapshot.acceptedFrames)
        assertEquals(3L, snapshot.rejectedFrames)
        assertEquals(16, snapshot.maxWebSocketQueueSize)
        assertEquals(16L, snapshot.maxWebSocketQueueBytes)
        assertEquals(5L, snapshot.inputTranscriptionCount)
        assertEquals(2L, snapshot.outputTranscriptionCount)
        assertEquals(12L, snapshot.tapToCaptureStartedMs)
        assertEquals(17L, snapshot.tapToFirstAudioFrameQueuedMs)
        assertEquals(30L, snapshot.firstAudioFrameQueuedToFirstInputTranscriptMs)
        assertEquals(160L, snapshot.stopToTranscriptSettledMs)
        assertEquals(0L, snapshot.startedAtMonotonicNanos)
        assertEquals(nanos(294), snapshot.endedAtMonotonicNanos)
        assertTrue(snapshot.turnCompleteArrived)
        assertFalse(snapshot.usedHardDeadline)
        assertFalse(snapshot.audioBufferOverflow)
    }

    @Test
    fun `snapshot with no timestamps maps startedAt to zero and endedAt to null`() {
        val snapshot = metrics.snapshot()
        assertEquals(0L, snapshot.startedAtMillis)
        assertNull(snapshot.endedAtMillis)
        assertNull(snapshot.tapToCaptureMs)
        assertNull(snapshot.stopToInsertionResultMs)
    }

    @Test
    fun `snapshot captures warm echo queue settlement repair reservation IPC and terminal metrics`() {
        metrics.mark(MutableSessionMetrics.Event.Tap, nanos(1))
        assertTrue(metrics.recordWarmSessionResult(hit = true, atNanos = nanos(2)))
        metrics.mark(MutableSessionMetrics.Event.TargetReservationStarted, nanos(3))
        assertTrue(metrics.recordTargetReservationResult(reserved = true, atNanos = nanos(8)))
        metrics.recordInputTranscription(nanos(9))
        metrics.recordEcho(nanos(9))
        metrics.mark(MutableSessionMetrics.Event.Stop, nanos(10))
        metrics.mark(MutableSessionMetrics.Event.ActivityEndQueued, nanos(11))
        metrics.recordInputRevision(nanos(13))
        metrics.recordEcho(nanos(14))
        metrics.recordGenerationComplete(nanos(15))
        metrics.mark(MutableSessionMetrics.Event.RepairStarted, nanos(16))
        metrics.recordQuietBarrierSatisfied(nanos(18))
        metrics.mark(MutableSessionMetrics.Event.RepairCompleted, nanos(19))
        metrics.recordSettlement(SettlementReason.HARD_DEADLINE, nanos(20))
        metrics.settlePath = SettlePath.ECHO_COMPLETE
        metrics.mark(MutableSessionMetrics.Event.InsertionRequested, nanos(21))
        metrics.mark(MutableSessionMetrics.Event.InsertionIpcReceived, nanos(23))
        metrics.mark(MutableSessionMetrics.Event.InsertionCommitStarted, nanos(24))
        metrics.mark(MutableSessionMetrics.Event.InsertionCommitCompleted, nanos(28))
        metrics.mark(MutableSessionMetrics.Event.InsertionReplySent, nanos(29))
        metrics.mark(MutableSessionMetrics.Event.InsertionReplyReceived, nanos(32))
        metrics.mark(MutableSessionMetrics.Event.InsertionResult, nanos(33))
        assertTrue(metrics.recordTerminalOutcome(TerminalOutcome.SUCCESS, nanos(35)))
        metrics.recordFrameQueue(depthFrames = 7, ageNanos = nanos(12) + 500_000L)
        metrics.recordWebSocketQueueBytes(3_000_000_000L)

        val snapshot = metrics.snapshot()

        assertTrue(snapshot.warmSessionHit == true)
        assertEquals(0L, snapshot.stopToFirstEchoMs)
        assertEquals(4L, snapshot.stopToLastEchoMs)
        assertEquals(5L, snapshot.stopToGenerationCompleteMs)
        assertEquals(5L, snapshot.firstEchoToLastEchoMs)
        assertEquals(4L, snapshot.activityEndQueuedToGenerationCompleteMs)
        assertEquals(5L, snapshot.generationCompleteToSettledMs)
        assertEquals(0L, snapshot.stopToFirstInputRevisionMs)
        assertEquals(3L, snapshot.stopToLastInputRevisionMs)
        assertEquals(4L, snapshot.firstInputToLastInputRevisionMs)
        assertEquals(4L, snapshot.stopToLastTranscriptRevisionMs)
        assertEquals(8L, snapshot.stopToQuietBarrierMs)
        assertEquals(4L, snapshot.lastTranscriptRevisionToQuietBarrierMs)
        assertEquals(2L, snapshot.quietBarrierToSettledMs)
        assertTrue(snapshot.generationCompleteArrived)
        assertEquals(7, snapshot.maxFrameQueueDepth)
        assertEquals(12L, snapshot.maxFrameQueueAgeMs)
        assertEquals(3_000_000_000L, snapshot.maxWebSocketQueueBytes)
        assertEquals(Int.MAX_VALUE, snapshot.maxWebSocketQueueSize)
        assertEquals(SettlementReason.HARD_DEADLINE, snapshot.settlementReason)
        assertEquals(SettlePath.ECHO_COMPLETE, snapshot.settlePath)
        assertTrue(snapshot.usedHardDeadline)
        assertEquals(3L, snapshot.repairDurationMs)
        assertEquals(5L, snapshot.targetReservationDurationMs)
        assertTrue(snapshot.targetReserved == true)
        assertEquals(2L, snapshot.insertionRequestedToIpcReceivedMs)
        assertEquals(1L, snapshot.insertionIpcReceivedToCommitStartedMs)
        assertEquals(4L, snapshot.insertionCommitDurationMs)
        assertEquals(1L, snapshot.insertionCommitCompletedToReplySentMs)
        assertEquals(3L, snapshot.insertionReplySentToReceivedMs)
        assertEquals(11L, snapshot.insertionIpcRoundTripMs)
        assertEquals(TerminalOutcome.SUCCESS, snapshot.terminalOutcome)
        assertEquals(34L, snapshot.tapToTerminalMs)
        assertEquals(nanos(1), snapshot.startedAtMonotonicNanos)
        assertEquals(nanos(35), snapshot.endedAtMonotonicNanos)
        assertEquals(nanos(9), snapshot.firstInputRevisionAtMonotonicNanos)
        assertEquals(nanos(13), snapshot.lastInputRevisionAtMonotonicNanos)
        assertEquals(nanos(9), snapshot.firstEchoRevisionAtMonotonicNanos)
        assertEquals(nanos(14), snapshot.lastEchoRevisionAtMonotonicNanos)
        assertEquals(nanos(14), snapshot.lastTranscriptRevisionAtMonotonicNanos)
        assertEquals(nanos(15), snapshot.generationCompleteAtMonotonicNanos)
        assertEquals(nanos(18), snapshot.quietBarrierSatisfiedAtMonotonicNanos)
        assertEquals(nanos(16), snapshot.repairStartedAtMonotonicNanos)
        assertEquals(nanos(19), snapshot.repairEndedAtMonotonicNanos)
        assertEquals(nanos(23), snapshot.insertionIpcReceivedAtMonotonicNanos)
        assertEquals(nanos(24), snapshot.insertionCommitStartedAtMonotonicNanos)
        assertEquals(nanos(28), snapshot.insertionCommitCompletedAtMonotonicNanos)
        assertEquals(nanos(29), snapshot.insertionReplySentAtMonotonicNanos)
        assertEquals(nanos(32), snapshot.insertionReplyReceivedAtMonotonicNanos)
        assertEquals(nanos(35), snapshot.terminalAtMonotonicNanos)
    }

    @Test
    fun `summary contains only derived duration flag and counter tokens`() {
        metrics.mark(MutableSessionMetrics.Event.Tap)
        advanceMillis(12)
        metrics.mark(MutableSessionMetrics.Event.SetupComplete)
        advanceMillis(140)
        metrics.mark(MutableSessionMetrics.Event.Stop)
        advanceMillis(800)
        metrics.mark(MutableSessionMetrics.Event.InsertionResult)
        metrics.acceptedFrames = 123
        metrics.rejectedFrames = 0
        metrics.maxWebSocketQueueSize = 32
        metrics.inputTranscriptionCount = 5
        metrics.turnCompleteArrived = true

        val summary = metrics.summary()

        assertEquals(
            "tapToSetupComplete=12ms stopToInsertionResult=800ms " +
                "capturedFrames=0 acceptedFrames=123 rejectedFrames=0 " +
                "maxWebSocketQueueBytes=32 maxFrameQueueDepthFrames=0 " +
                "inputTranscriptMessages=5 echoTranscriptMessages=0 " +
                "turnComplete=true generationComplete=false hardDeadline=false overflow=false",
            summary,
        )
        assertTrue("insertionRequestedToResult" !in summary)
        assertTrue("transcript" !in summary)
        assertTrue("s1" !in summary)
    }

    @Test
    fun `summary omits null duration segments but keeps all counts and flags`() {
        val summary = metrics.summary()
        assertTrue("tapToCapture" !in summary)
        assertTrue("tapToSetupComplete" !in summary)
        assertTrue("acceptedFrames=0" in summary)
        assertTrue("turnComplete=false" in summary)
    }

    @Test
    fun `summary redacts non-code rejection values`() {
        metrics.lastRejection = "do not log candidate words"

        val summary = metrics.summary()

        assertTrue("reject=OTHER" in summary)
        assertTrue("candidate words" !in summary)
        assertEquals("OTHER", metrics.snapshot().lastRejection)
    }
}
