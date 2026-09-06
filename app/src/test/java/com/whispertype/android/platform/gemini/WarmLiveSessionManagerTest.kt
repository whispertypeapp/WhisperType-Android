package com.whispertype.android.platform.gemini

import com.whispertype.android.core.contracts.GeminiLiveSession
import com.whispertype.android.core.model.AudioChunk
import com.whispertype.android.core.model.GeminiEvent
import com.whispertype.android.core.model.LanguageMode
import com.whispertype.android.core.model.SendResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Warm-session lifecycle tests use the coroutine test scheduler for both delay
 * and monotonic age, so no assertion depends on wall-clock timing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WarmLiveSessionManagerTest {

    private class FakeWarmSession(
        private val awaitReadyAction: suspend () -> Unit = {},
    ) : GeminiLiveSession {
        private val eventChannel = Channel<GeminiEvent>(Channel.UNLIMITED)

        var closeCalls = 0
            private set

        val closed: Boolean
            get() = closeCalls > 0

        override suspend fun awaitReady() = awaitReadyAction()
        override suspend fun startActivity(): SendResult = SendResult.Accepted
        override suspend fun sendAudio(chunk: AudioChunk): SendResult = SendResult.Accepted
        override suspend fun endActivity(): SendResult = SendResult.Accepted
        override fun events(): Flow<GeminiEvent> = eventChannel.receiveAsFlow()

        fun emit(event: GeminiEvent) {
            check(eventChannel.trySend(event).isSuccess)
        }

        override suspend fun close() {
            closeCalls++
            eventChannel.close()
        }
    }

    private class Harness(
        private val sessionFactory: (Int, WarmSessionProfile) -> FakeWarmSession =
            { _, _ -> FakeWarmSession() },
    ) {
        val sessions = mutableListOf<FakeWarmSession>()
        val createdProfiles = mutableListOf<WarmSessionProfile>()

        fun managerFor(
            scope: CoroutineScope,
            nowMs: () -> Long,
            config: WarmLiveSessionManager.Config = WarmLiveSessionManager.Config(),
        ): WarmLiveSessionManager =
            WarmLiveSessionManager.profiled(
                scope = scope,
                createSession = { profile ->
                    createdProfiles += profile
                    sessionFactory(createdProfiles.size, profile).also(sessions::add)
                },
                config = config,
                monotonicTimeMs = nowMs,
            )
    }

    @Test
    fun `exact claim reports mismatch age and profile change replaces stale session`() = runTest {
        // 0.8.0: language is the profile dimension that can differ (the echo
        // instruction digest is gone with the echo channel).
        val firstProfile = profile(language = LanguageMode.ENGLISH)
        val secondProfile = profile(language = LanguageMode.HINGLISH)
        val h = Harness()
        val manager = h.managerFor(
            scope = backgroundScope,
            nowMs = { testScheduler.currentTime },
        )

        manager.onEligibilityChanged(isEligible = true, profile = firstProfile)
        runCurrent()
        assertIs<WarmSessionState.Ready>(manager.state.value)

        advanceTimeBy(25L)
        runCurrent()
        val mismatch = assertIs<WarmSessionClaim.Miss>(manager.claim(secondProfile))
        assertEquals(WarmSessionClaimMissReason.PROFILE_MISMATCH, mismatch.reason)
        assertEquals(25L, mismatch.ageMs)
        assertFalse(h.sessions[0].closed)

        manager.onProfileChanged(secondProfile)
        assertIs<WarmSessionState.Connecting>(manager.state.value)
        runCurrent()
        assertEquals(1, h.sessions[0].closeCalls)
        assertEquals(listOf(firstProfile, secondProfile), h.createdProfiles)

        val hit = assertIs<WarmSessionClaim.Hit>(manager.claim(secondProfile))
        assertEquals(secondProfile, hit.lease.profile)
        assertEquals(0L, hit.ageMs)

        manager.onEligibilityChanged(false)
        runCurrent()
        assertEquals(0, h.sessions[1].closeCalls, "claimed exact-profile session must stay caller-owned")
        hit.lease.session.close()
    }

    @Test
    fun `eligibility cancellation closes a stored connecting session`() = runTest {
        val readyGate = CompletableDeferred<Unit>()
        val connecting = FakeWarmSession { readyGate.await() }
        val h = Harness { _, _ -> connecting }
        val manager = h.managerFor(
            scope = backgroundScope,
            nowMs = { testScheduler.currentTime },
        )

        manager.onEligibilityChanged(isEligible = true, profile = profile())
        runCurrent()
        assertIs<WarmSessionState.Connecting>(manager.state.value)
        assertSame(connecting, manager.warmSessionForTest())

        manager.onEligibilityChanged(false)
        runCurrent()
        assertIs<WarmSessionState.None>(manager.state.value)
        assertEquals(1, connecting.closeCalls)
    }

    @Test
    fun `setup error closes connecting session before bounded retry`() = runTest {
        val failed = FakeWarmSession {
            throw IllegalStateException("intentional readiness failure")
        }
        val recovered = FakeWarmSession()
        val h = Harness { call, _ -> if (call == 1) failed else recovered }
        val manager = h.managerFor(
            scope = backgroundScope,
            nowMs = { testScheduler.currentTime },
            config = config(idleMs = 1_000L, backoffMs = listOf(10L, 20L)),
        )

        manager.onEligibilityChanged(isEligible = true, profile = profile())
        runCurrent()
        val backoff = assertIs<WarmSessionState.Backoff>(manager.state.value)
        assertEquals(10L, backoff.retryMs)
        assertEquals(1, failed.closeCalls)

        advanceTimeBy(10L)
        runCurrent()
        assertIs<WarmSessionState.Ready>(manager.state.value)
        assertEquals(2, h.sessions.size)

        manager.shutdown()
        runCurrent()
        assertEquals(1, recovered.closeCalls)
    }

    @Test
    fun `dead idle-ready socket is discarded and rewarmed`() = runTest {
        val h = Harness()
        val manager = h.managerFor(
            scope = backgroundScope,
            nowMs = { testScheduler.currentTime },
            config = config(idleMs = 1_000L, backoffMs = listOf(10L)),
        )
        val expectedProfile = profile()

        manager.onEligibilityChanged(isEligible = true, profile = expectedProfile)
        runCurrent()
        assertIs<WarmSessionState.Ready>(manager.state.value)

        h.sessions[0].emit(GeminiEvent.SessionEnd)
        runCurrent()
        assertEquals(1, h.sessions[0].closeCalls)
        assertIs<WarmSessionState.Backoff>(manager.state.value)
        val miss = assertIs<WarmSessionClaim.Miss>(manager.claim(expectedProfile))
        assertEquals(WarmSessionClaimMissReason.BACKING_OFF, miss.reason)

        advanceTimeBy(10L)
        runCurrent()
        assertEquals(2, h.sessions.size)
        assertIs<WarmSessionState.Ready>(manager.state.value)

        manager.shutdown()
        runCurrent()
    }

    @Test
    fun `idle expiry closes then rewarms while eligibility remains true`() = runTest {
        val h = Harness()
        val manager = h.managerFor(
            scope = backgroundScope,
            nowMs = { testScheduler.currentTime },
            config = config(idleMs = 100L, backoffMs = listOf(10L, 20L)),
        )

        manager.onEligibilityChanged(isEligible = true, profile = profile())
        runCurrent()
        assertIs<WarmSessionState.Ready>(manager.state.value)

        advanceTimeBy(100L)
        runCurrent()
        assertEquals(1, h.sessions[0].closeCalls)
        assertEquals(WarmSessionState.Backoff(10L), manager.state.value)

        advanceTimeBy(10L)
        runCurrent()
        assertEquals(2, h.sessions.size)
        assertIs<WarmSessionState.Ready>(manager.state.value)

        manager.shutdown()
        runCurrent()
    }

    @Test
    fun `claim before ineligible transfers ownership before replacement cancellation`() = runTest {
        val h = Harness()
        val manager = h.managerFor(
            scope = backgroundScope,
            nowMs = { testScheduler.currentTime },
        )
        val expectedProfile = profile()

        manager.onEligibilityChanged(isEligible = true, profile = expectedProfile)
        runCurrent()
        val hit = assertIs<WarmSessionClaim.Hit>(manager.claim(expectedProfile))

        // Do not run the replacement first: this is the production ordering
        // where active-dictation eligibility follows the successful claim.
        manager.onEligibilityChanged(false)
        runCurrent()
        assertIs<WarmSessionState.None>(manager.state.value)
        assertEquals(0, h.sessions[0].closeCalls)

        hit.lease.session.close()
        assertEquals(1, h.sessions[0].closeCalls)
    }

    @Test
    fun `claimed session keeps terminal events and manager never closes it`() = runTest {
        // 0.8.0: language is the profile dimension that can differ (the echo
        // instruction digest is gone with the echo channel).
        val firstProfile = profile(language = LanguageMode.ENGLISH)
        val secondProfile = profile(language = LanguageMode.HINGLISH)
        val h = Harness()
        val manager = h.managerFor(
            scope = backgroundScope,
            nowMs = { testScheduler.currentTime },
        )

        manager.onEligibilityChanged(isEligible = true, profile = firstProfile)
        runCurrent()
        val hit = assertIs<WarmSessionClaim.Hit>(manager.claim(firstProfile))

        manager.onProfileChanged(secondProfile)
        runCurrent()
        manager.shutdown()
        runCurrent()
        assertEquals(0, h.sessions[0].closeCalls)
        assertTrue(h.sessions.drop(1).all(FakeWarmSession::closed))

        val received = async { hit.lease.session.events().first() }
        runCurrent()
        h.sessions[0].emit(GeminiEvent.SessionEnd)
        runCurrent()
        assertEquals(GeminiEvent.SessionEnd, received.await())
        assertEquals(0, h.sessions[0].closeCalls, "idle monitor must relinquish lifecycle authority on claim")

        hit.lease.session.close()
        assertEquals(1, h.sessions[0].closeCalls)
    }

    @Test
    fun `claimOrAwait waits for connecting prewarm then hits`() = runTest {
        val readyGate = CompletableDeferred<Unit>()
        val connecting = FakeWarmSession { readyGate.await() }
        val h = Harness { _, _ -> connecting }
        val manager = h.managerFor(
            scope = backgroundScope,
            nowMs = { testScheduler.currentTime },
            config = config(idleMs = 1_000L, backoffMs = listOf(10L), claimAwaitMs = 500L),
        )
        val expectedProfile = profile()

        manager.onEligibilityChanged(isEligible = true, profile = expectedProfile)
        runCurrent()
        assertIs<WarmSessionState.Connecting>(manager.state.value)

        val claimJob = backgroundScope.async { manager.claimOrAwait(expectedProfile) }
        runCurrent()
        readyGate.complete(Unit)
        advanceUntilIdle()

        val hit = assertIs<WarmSessionClaim.Hit>(claimJob.await())
        assertEquals(expectedProfile, hit.lease.profile)
        assertSame(connecting, h.sessions.first(), "must adopt the in-flight prewarm, not a duplicate cold socket")
        hit.lease.session.close()
    }

    @Test
    fun `claimOrAwait returns connecting miss when await budget elapses`() = runTest {
        val readyGate = CompletableDeferred<Unit>()
        val connecting = FakeWarmSession { readyGate.await() }
        val h = Harness { _, _ -> connecting }
        val manager = h.managerFor(
            scope = backgroundScope,
            nowMs = { testScheduler.currentTime },
            config = config(idleMs = 1_000L, backoffMs = listOf(10L), claimAwaitMs = 50L),
        )
        val expectedProfile = profile()

        manager.onEligibilityChanged(isEligible = true, profile = expectedProfile)
        runCurrent()
        assertIs<WarmSessionState.Connecting>(manager.state.value)

        val miss = assertIs<WarmSessionClaim.Miss>(
            manager.claimOrAwait(expectedProfile, timeoutMs = 50L),
        )
        assertEquals(WarmSessionClaimMissReason.CONNECTING, miss.reason)
        assertEquals(1, h.sessions.size)
    }

    @Test
    fun `legacy claim remains nullable and does not close claimed session`() = runTest {
        val raw = FakeWarmSession()
        val manager = WarmLiveSessionManager(
            scope = backgroundScope,
            createSession = { raw },
            config = config(idleMs = 1_000L, backoffMs = listOf(10L)),
            monotonicTimeMs = { testScheduler.currentTime },
        )

        assertNull(manager.claim())
        manager.onEligibilityChanged(true)
        runCurrent()
        val claimed = assertNotNull(manager.claim())
        manager.onEligibilityChanged(false)
        runCurrent()
        assertEquals(0, raw.closeCalls)

        claimed.close()
        assertEquals(1, raw.closeCalls)
    }

    private fun profile(
        language: LanguageMode = LanguageMode.ENGLISH,
        credentialRevision: Long = 1L,
    ): WarmSessionProfile =
        WarmSessionProfile(
            model = "gemini-live-test",
            apiVersion = "v1beta",
            language = language,
            automaticActivityDetectionDisabled = true,
            inputAudioTranscription = true,
            credentialRevision = credentialRevision,
        )

    private fun config(
        idleMs: Long,
        backoffMs: List<Long>,
        claimAwaitMs: Long = 10_000L,
    ): WarmLiveSessionManager.Config =
        WarmLiveSessionManager.Config(
            warmIdleTimeoutMs = idleMs,
            backoffStepsMs = backoffMs,
            claimAwaitTimeoutMs = claimAwaitMs,
        )
}
