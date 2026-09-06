package com.whispertype.android.platform.accessibility

import com.whispertype.android.core.model.InsertionResult
import com.whispertype.android.core.model.SessionId
import com.whispertype.android.core.model.TargetSnapshot

/**
 * Content-free identity used to decide whether a tap-time target is still the
 * focused safe editor. The full [TargetSnapshot] never leaves this process.
 */
internal data class ReservationTargetIdentity(
    val packageName: String,
    val displayId: Int,
    val windowId: Int,
    val editorIdentity: String,
    val generation: Long,
    val isSecure: Boolean,
    val isUncertain: Boolean,
)

internal fun TargetSnapshot.reservationIdentity(): ReservationTargetIdentity =
    ReservationTargetIdentity(
        packageName = packageName,
        displayId = displayId,
        windowId = windowId,
        editorIdentity = editorIdentity,
        generation = generation,
        isSecure = isSecure,
        isUncertain = isUncertain,
    )

internal fun FocusedEditor.reservationIdentity(): ReservationTargetIdentity =
    ReservationTargetIdentity(
        packageName = packageName,
        displayId = displayId,
        windowId = windowId,
        editorIdentity = editorIdentity.orEmpty(),
        generation = generation,
        isSecure = isSecure,
        isUncertain = isUncertain,
    )

/**
 * Framework-free reservation transaction state.
 *
 * Active entries retain the complete target only until a commit terminates,
 * focus invalidates it, or the runtime releases it. Terminal entries retain
 * only a typed result so duplicate commit requests cannot execute twice.
 */
internal class TargetReservationStore(
    private val maxActiveReservations: Int = DEFAULT_MAX_ACTIVE_RESERVATIONS,
    private val maxTerminalResults: Int = DEFAULT_MAX_TERMINAL_RESULTS,
) {

    init {
        require(maxActiveReservations > 0)
        require(maxTerminalResults > 0)
    }

    enum class ReserveRejection {
        INELIGIBLE,
        CAPACITY_EXCEEDED,
        TARGET_CHANGED,
        SESSION_TERMINAL,
    }

    sealed interface ReserveDecision {
        data class Reserved(val newlyCreated: Boolean) : ReserveDecision
        data class Rejected(val reason: ReserveRejection) : ReserveDecision
    }

    sealed interface CommitDecision {
        data class Execute(val target: TargetSnapshot) : CommitDecision
        data object InFlight : CommitDecision
        data class Terminal(val terminal: TerminalResult) : CommitDecision
    }

    data class TerminalResult(
        val result: InsertionResult,
        val commitStartedAtNanos: Long?,
        val commitCompletedAtNanos: Long? = null,
    )

    enum class ReleaseDecision {
        RELEASED,
        COMMIT_IN_FLIGHT,
        ALREADY_TERMINAL_OR_ABSENT,
    }

    private enum class Phase {
        RESERVED,
        COMMITTING,
    }

    private enum class Validation {
        CURRENT,
        STALE,
        NOT_SAFE,
    }

    private data class ActiveReservation(
        val target: TargetSnapshot,
        var phase: Phase = Phase.RESERVED,
        var commitStartedAtNanos: Long? = null,
    )

    private val active = LinkedHashMap<SessionId, ActiveReservation>()
    private val terminal = LinkedHashMap<SessionId, TerminalResult>()

    @Synchronized
    fun reserve(
        sessionId: SessionId,
        target: TargetSnapshot?,
        currentTarget: ReservationTargetIdentity?,
    ): ReserveDecision {
        if (terminal.containsKey(sessionId)) {
            return ReserveDecision.Rejected(ReserveRejection.SESSION_TERMINAL)
        }

        val existing = active[sessionId]
        if (existing != null) {
            return when (validate(existing.target, currentTarget)) {
                Validation.CURRENT -> ReserveDecision.Reserved(newlyCreated = false)
                Validation.STALE -> {
                    terminalize(sessionId, Validation.STALE, commitStartedAtNanos = null)
                    ReserveDecision.Rejected(ReserveRejection.TARGET_CHANGED)
                }
                Validation.NOT_SAFE -> {
                    terminalize(sessionId, Validation.NOT_SAFE, commitStartedAtNanos = null)
                    ReserveDecision.Rejected(ReserveRejection.INELIGIBLE)
                }
            }
        }

        if (target == null || target.sessionId != sessionId) {
            return ReserveDecision.Rejected(ReserveRejection.INELIGIBLE)
        }
        when (validate(target, currentTarget)) {
            Validation.STALE -> return ReserveDecision.Rejected(ReserveRejection.TARGET_CHANGED)
            Validation.NOT_SAFE -> return ReserveDecision.Rejected(ReserveRejection.INELIGIBLE)
            Validation.CURRENT -> Unit
        }
        if (active.size >= maxActiveReservations) {
            return ReserveDecision.Rejected(ReserveRejection.CAPACITY_EXCEEDED)
        }

        active[sessionId] = ActiveReservation(target)
        return ReserveDecision.Reserved(newlyCreated = true)
    }

    /**
     * Invalidates every not-yet-committing reservation that no longer points at
     * [currentTarget]. Committing entries are left for the single-shot gateway
     * validation and completion path; they are never made executable again.
     */
    @Synchronized
    fun invalidateAgainst(currentTarget: ReservationTargetIdentity?) {
        val invalid = active
            .filterValues { it.phase == Phase.RESERVED }
            .mapNotNull { (sessionId, reservation) ->
                validate(reservation.target, currentTarget)
                    .takeUnless { it == Validation.CURRENT }
                    ?.let { sessionId to it }
            }
        invalid.forEach { (sessionId, validation) ->
            terminalize(sessionId, validation, commitStartedAtNanos = null)
        }
    }

    /**
     * Atomically grants the only execution permit for [sessionId]. Every later
     * request observes either [CommitDecision.InFlight] or the cached terminal
     * result and therefore cannot invoke commitText again.
     */
    @Synchronized
    fun beginCommit(
        sessionId: SessionId,
        currentTarget: ReservationTargetIdentity?,
        commitStartedAtNanos: Long,
    ): CommitDecision {
        terminal[sessionId]?.let { return CommitDecision.Terminal(it) }

        val reservation = active[sessionId]
        if (reservation == null) {
            val missing = cacheTerminal(
                sessionId = sessionId,
                result = staleResult(),
                commitStartedAtNanos = null,
            )
            return CommitDecision.Terminal(missing)
        }
        if (reservation.phase == Phase.COMMITTING) return CommitDecision.InFlight

        val validation = validate(reservation.target, currentTarget)
        if (validation != Validation.CURRENT) {
            return CommitDecision.Terminal(
                terminalize(
                    sessionId = sessionId,
                    validation = validation,
                    commitStartedAtNanos = null,
                ),
            )
        }

        reservation.phase = Phase.COMMITTING
        reservation.commitStartedAtNanos = commitStartedAtNanos
        return CommitDecision.Execute(reservation.target)
    }

    /** Completes the granted commit once and releases the retained snapshot. */
    @Synchronized
    fun completeCommit(
        sessionId: SessionId,
        result: InsertionResult,
        commitCompletedAtNanos: Long? = null,
    ): TerminalResult {
        terminal[sessionId]?.let { return it }
        val reservation = active.remove(sessionId)
        if (reservation == null || reservation.phase != Phase.COMMITTING) {
            return cacheTerminal(
                sessionId = sessionId,
                result = staleResult(),
                commitStartedAtNanos = null,
            )
        }
        return cacheTerminal(
            sessionId = sessionId,
            result = result,
            commitStartedAtNanos = reservation.commitStartedAtNanos,
            commitCompletedAtNanos = commitCompletedAtNanos,
        )
    }

    /**
     * Releases a reservation before commit. A commit already in progress is not
     * cancelled because commitText may have executed; its terminal result must
     * settle the transaction without a blind retry.
     */
    @Synchronized
    fun release(sessionId: SessionId): ReleaseDecision {
        if (terminal.containsKey(sessionId)) {
            return ReleaseDecision.ALREADY_TERMINAL_OR_ABSENT
        }
        val reservation = active[sessionId]
            ?: return ReleaseDecision.ALREADY_TERMINAL_OR_ABSENT
        if (reservation.phase == Phase.COMMITTING) {
            return ReleaseDecision.COMMIT_IN_FLIGHT
        }

        active.remove(sessionId)
        cacheTerminal(
            sessionId = sessionId,
            result = staleResult(),
            commitStartedAtNanos = null,
        )
        return ReleaseDecision.RELEASED
    }

    @Synchronized
    fun clear() {
        active.clear()
        terminal.clear()
    }

    @Synchronized
    fun activeReservationCount(): Int = active.size

    @Synchronized
    fun terminalResultCount(): Int = terminal.size

    @Synchronized
    fun hasActiveSnapshot(sessionId: SessionId): Boolean = active.containsKey(sessionId)

    private fun validate(
        target: TargetSnapshot,
        currentTarget: ReservationTargetIdentity?,
    ): Validation {
        if (target.isSecure || target.isUncertain) return Validation.NOT_SAFE
        if (currentTarget == null) return Validation.STALE
        if (currentTarget.isSecure || currentTarget.isUncertain) return Validation.NOT_SAFE
        return if (target.reservationIdentity() == currentTarget) {
            Validation.CURRENT
        } else {
            Validation.STALE
        }
    }

    private fun terminalize(
        sessionId: SessionId,
        validation: Validation,
        commitStartedAtNanos: Long?,
    ): TerminalResult {
        active.remove(sessionId)
        val result = when (validation) {
            Validation.NOT_SAFE -> notSafeResult()
            Validation.STALE,
            Validation.CURRENT,
            -> staleResult()
        }
        return cacheTerminal(sessionId, result, commitStartedAtNanos)
    }

    private fun cacheTerminal(
        sessionId: SessionId,
        result: InsertionResult,
        commitStartedAtNanos: Long?,
        commitCompletedAtNanos: Long? = null,
    ): TerminalResult {
        terminal.remove(sessionId)
        while (terminal.size >= maxTerminalResults) {
            val eldest = terminal.entries.iterator()
            if (!eldest.hasNext()) break
            eldest.next()
            eldest.remove()
        }
        return TerminalResult(result, commitStartedAtNanos, commitCompletedAtNanos).also {
            terminal[sessionId] = it
        }
    }

    private fun staleResult(): InsertionResult =
        InsertionResult.Failed(InsertionDecision.targetStale())

    private fun notSafeResult(): InsertionResult =
        InsertionResult.Failed(InsertionDecision.targetNotSafe())

    private companion object {
        const val DEFAULT_MAX_ACTIVE_RESERVATIONS = 8
        const val DEFAULT_MAX_TERMINAL_RESULTS = 32
    }
}
