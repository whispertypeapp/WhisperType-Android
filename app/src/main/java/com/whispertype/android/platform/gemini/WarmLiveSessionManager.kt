package com.whispertype.android.platform.gemini

import com.whispertype.android.core.contracts.GeminiLiveSession
import com.whispertype.android.core.model.GeminiEvent
import com.whispertype.android.core.model.LanguageMode
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Immutable, non-secret fingerprint of every setup choice that can make a warm
 * Gemini session unsuitable for the next dictation.
 *
 * 0.10.0: the session is raw transcription transport only (no echo, no
 * `systemInstruction`), so the instruction digest and echo flag are gone —
 * language and the activity flags are all that can differ. [credentialRevision]
 * is an opaque local revision counter, never key material or a digest of key
 * material.
 */
data class WarmSessionProfile(
    val model: String,
    val apiVersion: String,
    val language: LanguageMode,
    val automaticActivityDetectionDisabled: Boolean,
    val activityHandlingNoInterruption: Boolean = false,
    val inputAudioTranscription: Boolean,
    val credentialRevision: Long,
) {
    init {
        require(model.isNotBlank()) { "model must not be blank" }
        require(apiVersion.isNotBlank()) { "apiVersion must not be blank" }
        require(credentialRevision >= 0L) { "credentialRevision must not be negative" }
    }
}

/** A manager-owned warm session whose ownership has transferred to a caller. */
data class WarmSessionLease(
    val session: GeminiLiveSession,
    val profile: WarmSessionProfile,
    val readyAgeMs: Long,
)

/** Typed reason why an exact-profile warm claim did not succeed. */
enum class WarmSessionClaimMissReason {
    SHUT_DOWN,
    INELIGIBLE,
    MISSING_PROFILE,
    CONNECTING,
    BACKING_OFF,
    NO_READY_SESSION,
    PROFILE_MISMATCH,
    TERMINATED,
}

/** Exact-profile claim result suitable for aggregate warm-hit metrics. */
sealed interface WarmSessionClaim {
    val ageMs: Long?

    data class Hit(val lease: WarmSessionLease) : WarmSessionClaim {
        override val ageMs: Long = lease.readyAgeMs
    }

    data class Miss(
        val reason: WarmSessionClaimMissReason,
        override val ageMs: Long? = null,
    ) : WarmSessionClaim
}

/** Lifecycle of the prewarmed Live session pool (Release F3). */
sealed interface WarmSessionState {
    /** No warm session; prewarm disabled. */
    data object None : WarmSessionState

    /** A prewarm connection is being established. */
    data object Connecting : WarmSessionState

    /** A ready, idle warm session is available to claim. */
    data class Ready(
        val session: GeminiLiveSession,
        val profile: WarmSessionProfile? = null,
    ) : WarmSessionState

    /** A ready session was claimed by an active dictation. */
    data object Claimed : WarmSessionState

    /** The last prewarm ended; retrying after [retryMs]. */
    data class Backoff(val retryMs: Long) : WarmSessionState

    /** The manager-owned warm session is being closed. */
    data object Closing : WarmSessionState
}

private class WarmSessionCreator(
    val requiresProfile: Boolean,
    val create: suspend (WarmSessionProfile?) -> GeminiLiveSession,
)

private const val NANOS_PER_MILLISECOND = 1_000_000L

private fun defaultMonotonicTimeMs(): Long = System.nanoTime() / NANOS_PER_MILLISECOND

/**
 * Eligibility- and profile-driven warm Live session pool.
 *
 * The legacy constructor and parameterless [claim] remain available while the
 * runtime migrates to [profiled] and exact-profile [claim]. Every session stays
 * manager-owned until claim transfers ownership under one lock. Connecting,
 * failed, expired, invalidated, and idle-terminal sessions are always closed;
 * claimed sessions are never closed by this manager.
 *
 * Idle sessions are monitored through a relaying session wrapper. The relay
 * forwards every event to the eventual claimant before notifying this manager
 * about terminal events, so monitoring never competes for the transport's
 * single-consumer event flow.
 */
class WarmLiveSessionManager private constructor(
    private val scope: CoroutineScope,
    private val sessionCreator: WarmSessionCreator,
    private val config: Config,
    private val monotonicTimeMs: () -> Long,
) {

    /** Tunable prewarm timing (all monotonic delays). */
    data class Config(
        /** Idle timeout before a ready warm session is recycled (1.0.8: 30 s → 180 s). */
        val warmIdleTimeoutMs: Long = 180_000L,
        val backoffStepsMs: List<Long> = listOf(1_000L, 2_000L, 5_000L, 10_000L),
        /** Max wait while the pool is [WarmSessionState.Connecting] before a cold connect. */
        val claimAwaitTimeoutMs: Long = 10_000L,
    )

    /**
     * Compatibility constructor for callers that build sessions from captured
     * configuration. Profile-aware callers should use [profiled].
     */
    constructor(
        scope: CoroutineScope,
        createSession: suspend () -> GeminiLiveSession,
        config: Config = Config(),
        monotonicTimeMs: () -> Long = ::defaultMonotonicTimeMs,
    ) : this(
        scope = scope,
        sessionCreator = WarmSessionCreator(
            requiresProfile = false,
            create = { createSession() },
        ),
        config = config,
        monotonicTimeMs = monotonicTimeMs,
    )

    private enum class Ownership {
        MANAGER,
        CLAIMED,
        RELEASED,
    }

    private class PoolSlot(
        val runId: Long,
        val profile: WarmSessionProfile?,
        val rawSession: GeminiLiveSession,
    ) {
        val terminalSignal = Channel<Unit>(Channel.CONFLATED)
        var ownership = Ownership.MANAGER
        var readySession: MonitoredSession? = null
        var readyAtMs: Long? = null
        var terminalObserved = false
    }

    private data class Restart(
        val previousJob: Job?,
        val nextJob: Job?,
        val detachedSlot: PoolSlot?,
    )

    private data class TakeReady(
        val session: MonitoredSession? = null,
        val profile: WarmSessionProfile? = null,
        val ageMs: Long? = null,
        val missReason: WarmSessionClaimMissReason? = null,
        val restart: Restart? = null,
    )

    private val backoffStepsMs = config.backoffStepsMs.toList()
    private val _state = MutableStateFlow<WarmSessionState>(WarmSessionState.None)

    val state: StateFlow<WarmSessionState> = _state.asStateFlow()

    private val lock = Any()
    private var eligible = false
    private var shutDown = false
    private var desiredProfile: WarmSessionProfile? = null
    private var generation = 0L
    private var poolJob: Job? = null
    private var currentSlot: PoolSlot? = null

    init {
        require(config.warmIdleTimeoutMs > 0L) { "warmIdleTimeoutMs must be positive" }
        require(config.claimAwaitTimeoutMs > 0L) { "claimAwaitTimeoutMs must be positive" }
        require(backoffStepsMs.isNotEmpty()) { "backoffStepsMs must not be empty" }
        require(backoffStepsMs.all { it > 0L }) { "backoffStepsMs must contain only positive delays" }
    }

    /**
     * Creates a manager whose factory receives the exact immutable profile that
     * the resulting socket will be tagged with.
     */
    companion object {
        fun profiled(
            scope: CoroutineScope,
            createSession: suspend (WarmSessionProfile) -> GeminiLiveSession,
            config: Config = Config(),
            monotonicTimeMs: () -> Long = ::defaultMonotonicTimeMs,
        ): WarmLiveSessionManager =
            WarmLiveSessionManager(
                scope = scope,
                sessionCreator = WarmSessionCreator(
                    requiresProfile = true,
                    create = { profile -> createSession(checkNotNull(profile)) },
                ),
                config = config,
                monotonicTimeMs = monotonicTimeMs,
            )
    }

    /** Test visibility: the currently manager-owned connecting or ready session. */
    internal fun warmSessionForTest(): GeminiLiveSession? = synchronized(lock) {
        currentSlot?.let { slot -> slot.readySession ?: slot.rawSession }
    }

    /**
     * Compatibility eligibility update. A previously supplied profile is
     * retained; a profile-aware manager does not connect until one is supplied.
     */
    fun onEligibilityChanged(isEligible: Boolean) {
        reconfigure(isEligible = isEligible, profile = null, profileProvided = false)
    }

    /** Atomically updates eligibility and the desired exact session profile. */
    fun onEligibilityChanged(
        isEligible: Boolean,
        profile: WarmSessionProfile,
    ) {
        reconfigure(isEligible = isEligible, profile = profile, profileProvided = true)
    }

    /** Invalidates and replaces any manager-owned session for another profile. */
    fun onProfileChanged(profile: WarmSessionProfile) {
        reconfigure(isEligible = null, profile = profile, profileProvided = true)
    }

    /**
     * Compatibility claim: takes any ready session or returns null. New callers
     * should use [claim] with an expected profile to receive typed metrics.
     */
    fun claim(): GeminiLiveSession? {
        val taken = takeReady(expectedProfile = null, requireExactProfile = false)
        taken.restart?.let(::activate)
        taken.session?.startMonitoring()
        return taken.session
    }

    /** Atomically claims a ready session only when its profile equals [profile]. */
    fun claim(profile: WarmSessionProfile): WarmSessionClaim {
        val taken = takeReady(expectedProfile = profile, requireExactProfile = true)
        taken.restart?.let(::activate)
        val session = taken.session
        if (session == null) {
            return WarmSessionClaim.Miss(
                reason = checkNotNull(taken.missReason),
                ageMs = taken.ageMs,
            )
        }

        session.startMonitoring()
        return WarmSessionClaim.Hit(
            WarmSessionLease(
                session = session,
                profile = checkNotNull(taken.profile),
                readyAgeMs = checkNotNull(taken.ageMs),
            ),
        )
    }

    /**
     * Claims a ready session, or waits for an in-flight prewarm to reach Ready
     * instead of opening a duplicate cold socket. Returns immediately on any
     * non-[WarmSessionClaimMissReason.CONNECTING] miss.
     */
    suspend fun claimOrAwait(
        profile: WarmSessionProfile,
        timeoutMs: Long = config.claimAwaitTimeoutMs,
    ): WarmSessionClaim =
        withTimeoutOrNull(timeoutMs) {
            while (true) {
                when (val claim = claim(profile)) {
                    is WarmSessionClaim.Hit -> return@withTimeoutOrNull claim
                    is WarmSessionClaim.Miss ->
                        when (claim.reason) {
                            WarmSessionClaimMissReason.CONNECTING ->
                                state.first { it !is WarmSessionState.Connecting }
                            else -> return@withTimeoutOrNull claim
                        }
                }
            }
            @Suppress("UNREACHABLE_CODE")
            null
        } ?: WarmSessionClaim.Miss(
            reason = WarmSessionClaimMissReason.CONNECTING,
        )

    /**
     * Permanently stops this manager and closes every manager-owned session.
     * Claimed sessions remain owned by their dictations.
     */
    fun shutdown() {
        val restart = synchronized(lock) {
            if (shutDown) return
            shutDown = true
            eligible = false
            restartPoolLocked()
        }
        activate(restart)
    }

    private fun reconfigure(
        isEligible: Boolean?,
        profile: WarmSessionProfile?,
        profileProvided: Boolean,
    ) {
        val restart = synchronized(lock) {
            if (shutDown) return

            val nextEligibility = isEligible ?: eligible
            val nextProfile = if (profileProvided) profile else desiredProfile
            val eligibilityChanged = nextEligibility != eligible
            val profileChanged = nextProfile != desiredProfile
            val shouldBeRunning =
                nextEligibility && (!sessionCreator.requiresProfile || nextProfile != null)
            val needsRecovery = shouldBeRunning && poolJob?.isActive != true
            if (!eligibilityChanged && !profileChanged && !needsRecovery) return

            eligible = nextEligibility
            desiredProfile = nextProfile
            restartPoolLocked()
        }
        activate(restart)
    }

    private fun restartPoolLocked(): Restart {
        val previousJob = poolJob
        val previousSlot = currentSlot
        currentSlot = null

        val detachedSlot =
            if (
                previousSlot != null &&
                previousSlot.ownership == Ownership.MANAGER &&
                previousJob?.isActive != true
            ) {
                previousSlot.ownership = Ownership.RELEASED
                previousSlot
            } else {
                null
            }

        generation++
        val runId = generation
        val profile = desiredProfile
        val shouldRun =
            !shutDown && eligible && (!sessionCreator.requiresProfile || profile != null)
        val nextJob =
            if (shouldRun) {
                _state.value = WarmSessionState.Connecting
                scope.launch(start = CoroutineStart.LAZY) {
                    runPool(runId = runId, profile = profile)
                }
            } else {
                _state.value = WarmSessionState.None
                null
            }
        poolJob = nextJob
        return Restart(
            previousJob = previousJob,
            nextJob = nextJob,
            detachedSlot = detachedSlot,
        )
    }

    private fun activate(restart: Restart) {
        restart.previousJob?.cancel()
        restart.detachedSlot?.let(::closeDetached)
        restart.nextJob?.start()
    }

    private suspend fun runPool(
        runId: Long,
        profile: WarmSessionProfile?,
    ) {
        val ownerJob = currentCoroutineContext()[Job]
        var backoffIndex = 0
        try {
            while (isCurrentRun(runId, profile)) {
                if (!markConnecting(runId, profile)) return
                val reachedReady = runOneAttempt(runId, profile)
                if (!isCurrentRun(runId, profile)) return

                if (reachedReady) backoffIndex = 0
                val retryMs = backoffStepsMs[backoffIndex]
                if (backoffIndex < backoffStepsMs.lastIndex) backoffIndex++
                if (!markBackoff(runId, profile, retryMs)) return
                delay(retryMs)
            }
        } finally {
            synchronized(lock) {
                if (generation == runId && poolJob === ownerJob) {
                    poolJob = null
                    if (currentSlot == null) {
                        _state.value = WarmSessionState.None
                    }
                }
            }
        }
    }

    private suspend fun runOneAttempt(
        runId: Long,
        profile: WarmSessionProfile?,
    ): Boolean {
        var rawSession: GeminiLiveSession? = null
        var slot: PoolSlot? = null
        var reachedReady = false
        try {
            val created = sessionCreator.create(profile)
            rawSession = created
            currentCoroutineContext().let { context ->
                if (!context.isActive) throw CancellationException("Warm session creation cancelled")
            }

            val candidate = PoolSlot(
                runId = runId,
                profile = profile,
                rawSession = created,
            )
            slot = candidate
            if (!installConnectingSlot(candidate)) return false

            created.awaitReady()
            currentCoroutineContext().let { context ->
                if (!context.isActive) throw CancellationException("Warm session setup cancelled")
            }

            val monitored = MonitoredSession(
                delegate = created,
                scope = scope,
                onIdleTerminal = { onIdleTerminal(candidate) },
            )
            if (!publishReady(candidate, monitored)) return false
            reachedReady = true
            monitored.startMonitoring()

            withTimeout(config.warmIdleTimeoutMs) {
                candidate.terminalSignal.receive()
            }
        } catch (error: CancellationException) {
            if (!currentCoroutineContext().isActive) throw error
            // An active-context cancellation is the idle timeout or a transport
            // readiness cancellation. Both release the socket and retry.
        } catch (_: Exception) {
            // Setup and transport failures are retried after bounded backoff.
        } finally {
            if (slot != null) {
                releaseAndClose(slot)
            } else {
                val uninstalledSession = rawSession
                if (uninstalledSession != null) {
                    withContext(NonCancellable) { closeSafely(uninstalledSession) }
                }
            }
        }
        return reachedReady
    }

    private fun installConnectingSlot(slot: PoolSlot): Boolean = synchronized(lock) {
        if (!isCurrentRunLocked(slot.runId, slot.profile)) {
            false
        } else {
            currentSlot = slot
            true
        }
    }

    private fun publishReady(
        slot: PoolSlot,
        session: MonitoredSession,
    ): Boolean {
        val readyAtMs = monotonicTimeMs()
        return synchronized(lock) {
            if (
                !isCurrentRunLocked(slot.runId, slot.profile) ||
                currentSlot !== slot ||
                slot.ownership != Ownership.MANAGER
            ) {
                false
            } else {
                slot.readySession = session
                slot.readyAtMs = readyAtMs
                _state.value = WarmSessionState.Ready(session, slot.profile)
                true
            }
        }
    }

    private fun onIdleTerminal(slot: PoolSlot) {
        val signal = synchronized(lock) {
            if (
                currentSlot !== slot ||
                slot.ownership != Ownership.MANAGER ||
                slot.readyAtMs == null ||
                slot.terminalObserved
            ) {
                false
            } else {
                slot.terminalObserved = true
                _state.value = WarmSessionState.Closing
                true
            }
        }
        if (signal) slot.terminalSignal.trySend(Unit)
    }

    private suspend fun releaseAndClose(slot: PoolSlot) {
        val shouldClose = synchronized(lock) {
            if (slot.ownership != Ownership.MANAGER) {
                false
            } else {
                slot.ownership = Ownership.RELEASED
                if (currentSlot === slot) {
                    currentSlot = null
                    if (isCurrentRunLocked(slot.runId, slot.profile)) {
                        _state.value = WarmSessionState.Closing
                    }
                }
                true
            }
        }
        if (shouldClose) {
            val session = slot.readySession ?: slot.rawSession
            withContext(NonCancellable) { closeSafely(session) }
        }
    }

    private fun takeReady(
        expectedProfile: WarmSessionProfile?,
        requireExactProfile: Boolean,
    ): TakeReady = synchronized(lock) {
        if (shutDown) {
            return@synchronized TakeReady(missReason = WarmSessionClaimMissReason.SHUT_DOWN)
        }
        if (!eligible) {
            return@synchronized TakeReady(missReason = WarmSessionClaimMissReason.INELIGIBLE)
        }
        if (sessionCreator.requiresProfile && desiredProfile == null) {
            return@synchronized TakeReady(missReason = WarmSessionClaimMissReason.MISSING_PROFILE)
        }

        val slot = currentSlot
        val ageMs = slot?.readyAtMs?.let(::readyAgeMs)
        if (slot?.terminalObserved == true) {
            return@synchronized TakeReady(
                ageMs = ageMs,
                missReason = WarmSessionClaimMissReason.TERMINATED,
            )
        }

        val session = slot?.readySession
        val state = _state.value
        if (
            slot == null ||
            session == null ||
            slot.ownership != Ownership.MANAGER ||
            state !is WarmSessionState.Ready ||
            state.session !== session
        ) {
            return@synchronized TakeReady(
                ageMs = ageMs,
                missReason = missReasonForState(state),
            )
        }
        if (requireExactProfile && slot.profile != expectedProfile) {
            return@synchronized TakeReady(
                ageMs = ageMs,
                missReason = WarmSessionClaimMissReason.PROFILE_MISMATCH,
            )
        }

        slot.ownership = Ownership.CLAIMED
        currentSlot = null
        _state.value = WarmSessionState.Claimed
        val restart = restartPoolLocked()
        TakeReady(
            session = session,
            profile = slot.profile,
            ageMs = checkNotNull(ageMs),
            restart = restart,
        )
    }

    private fun missReasonForState(state: WarmSessionState): WarmSessionClaimMissReason =
        when (state) {
            WarmSessionState.Connecting -> WarmSessionClaimMissReason.CONNECTING
            is WarmSessionState.Backoff -> WarmSessionClaimMissReason.BACKING_OFF
            WarmSessionState.Closing -> WarmSessionClaimMissReason.TERMINATED
            WarmSessionState.None,
            WarmSessionState.Claimed,
            is WarmSessionState.Ready,
            -> WarmSessionClaimMissReason.NO_READY_SESSION
        }

    private fun readyAgeMs(readyAtMs: Long): Long =
        (monotonicTimeMs() - readyAtMs).coerceAtLeast(0L)

    private fun markConnecting(
        runId: Long,
        profile: WarmSessionProfile?,
    ): Boolean = synchronized(lock) {
        if (!isCurrentRunLocked(runId, profile)) {
            false
        } else {
            _state.value = WarmSessionState.Connecting
            true
        }
    }

    private fun markBackoff(
        runId: Long,
        profile: WarmSessionProfile?,
        retryMs: Long,
    ): Boolean = synchronized(lock) {
        if (!isCurrentRunLocked(runId, profile)) {
            false
        } else {
            _state.value = WarmSessionState.Backoff(retryMs)
            true
        }
    }

    private fun isCurrentRun(
        runId: Long,
        profile: WarmSessionProfile?,
    ): Boolean = synchronized(lock) {
        isCurrentRunLocked(runId, profile)
    }

    private fun isCurrentRunLocked(
        runId: Long,
        profile: WarmSessionProfile?,
    ): Boolean =
        !shutDown &&
            eligible &&
            generation == runId &&
            desiredProfile == profile

    private fun closeDetached(slot: PoolSlot) {
        val session = slot.readySession ?: slot.rawSession
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) { closeSafely(session) }
        }
    }

    private suspend fun closeSafely(session: GeminiLiveSession) {
        try {
            session.close()
        } catch (_: Throwable) {
            // Closing is best-effort and must not mask lifecycle transitions.
        }
    }

    /**
     * Owns the sole collection of the transport event flow and relays it to the
     * eventual claimant. Manager liveness observation is a side notification,
     * not a competing collector.
     */
    private class MonitoredSession(
        private val delegate: GeminiLiveSession,
        private val scope: CoroutineScope,
        private val onIdleTerminal: () -> Unit,
    ) : GeminiLiveSession by delegate {

        private val output = Channel<GeminiEvent>(Channel.UNLIMITED)
        private val closed = AtomicBoolean(false)
        private val relayLock = Any()
        private var relayJob: Job? = null

        fun startMonitoring() {
            val job = synchronized(relayLock) {
                if (closed.get() || relayJob != null) {
                    null
                } else {
                    scope.launch(start = CoroutineStart.LAZY) {
                        relayEvents()
                    }.also { relayJob = it }
                }
            }
            job?.start()
        }

        override fun events(): Flow<GeminiEvent> {
            startMonitoring()
            return output.receiveAsFlow()
        }

        override suspend fun close() {
            if (!closed.compareAndSet(false, true)) return
            val relay = synchronized(relayLock) { relayJob }
            withContext(NonCancellable) {
                relay?.cancel()
                try {
                    delegate.close()
                } finally {
                    relay?.join()
                    output.close()
                }
            }
        }

        private suspend fun relayEvents() {
            try {
                delegate.events().collect { event ->
                    if (event.invalidatesIdleWarmSession()) {
                        onIdleTerminal()
                    }
                    output.trySend(event)
                }
                if (!closed.get()) onIdleTerminal()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                output.close(error)
                if (!closed.get()) onIdleTerminal()
            } finally {
                output.close()
            }
        }
    }
}

private fun GeminiEvent.invalidatesIdleWarmSession(): Boolean =
    this is GeminiEvent.Failed ||
        this is GeminiEvent.GoAway ||
        this === GeminiEvent.SessionEnd
