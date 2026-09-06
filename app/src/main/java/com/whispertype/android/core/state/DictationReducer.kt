package com.whispertype.android.core.state

import com.whispertype.android.core.model.DictationCommand
import com.whispertype.android.core.model.DictationFailure
import com.whispertype.android.core.model.DictationState
import com.whispertype.android.core.model.InsertionResult
import com.whispertype.android.core.model.ResultCandidate
import com.whispertype.android.core.model.SessionId

/**
 * Sole owner of the dictation session state machine.
 *
 * The reducer serializes one [DictationCommand] at a time and holds the current
 * [DictationState] (exposed via [state]). It is intentionally stateful because
 * the [DictationState.CopyAvailable] terminal requires the selected
 * [ResultCandidate], which is not carried inside [InsertionResult] and therefore
 * must be remembered across commands (see [selectedCandidate]).
 *
 * Staleness rules enforced by this reducer:
 *  - Every session-scoped asynchronous event (Stop, Cancel, AmplitudeTick,
 *    TranscriptCandidates, InsertOutcome) whose [SessionId] does not match the
 *    current *active* session is IGNORED (state unchanged).
 *  - Exactly one active session exists globally; [DictationCommand.Start] is
 *    ignored while the current state is active (Starting/Listening/Finalizing/
 *    Inserting).
 *  - A terminal result is consumed at most once: after [InsertOutcome] produces a
 *    terminal state (Success/CopyAvailable/Error) the session is no longer
 *    active and any further [InsertOutcome] / stale event for it is ignored.
 *
 * Thread-safety: all mutation happens inside a [synchronized] block, so simple
 * single/multi-threaded command feeding is safe.
 */
class DictationReducer {

    private val lock = Any()

    @Volatile
    private var currentState: DictationState = DictationState.Idle

    /**
     * Latest valid candidate recorded for the current session. Cleared on
     * [DictationCommand.Start] so a fresh session never inherits a stale copy.
     */
    private var selectedCandidate: ResultCandidate? = null

    val state: DictationState
        get() = currentState

    /** Applies [command] to the current state, possibly mutating it. */
    fun reduce(command: DictationCommand) {
        synchronized(lock) {
            currentState = transition(currentState, command)
        }
    }

    private fun transition(state: DictationState, command: DictationCommand): DictationState = when (command) {
        is DictationCommand.Start -> {
            // One active session globally: ignore a Start while active.
            if (state.isActive()) {
                state
            } else {
                selectedCandidate = null
                DictationState.Starting(command.target.sessionId, command.target)
            }
        }

        is DictationCommand.Stop -> {
            // Only from Starting or Listening with a matching session.
            val active = state.activeSessionId()
            if (active != null &&
                active == command.sessionId &&
                (state is DictationState.Starting || state is DictationState.Listening)
            ) {
                DictationState.Finalizing(active)
            } else {
                state
            }
        }

        is DictationCommand.Cancel -> {
            // Only from an active state with a matching session. Establishes the
            // stale-session barrier: any subsequent event for this session is
            // ignored because the state is no longer active.
            val active = state.activeSessionId()
            if (active != null && active == command.sessionId) {
                DictationState.Cancelled(active, command.reason)
            } else {
                state
            }
        }

        is DictationCommand.AmplitudeTick -> {
            // From Starting -> Listening (audio began), and updates amplitude in
            // Listening. IGNORED in every other state or on a stale session.
            when (state) {
                is DictationState.Starting ->
                    if (state.sessionId == command.sessionId) {
                        DictationState.Listening(state.sessionId, command.amplitude)
                    } else {
                        state
                    }

                is DictationState.Listening ->
                    if (state.sessionId == command.sessionId) {
                        // copy() preserves elapsedMillis.
                        state.copy(amplitude = command.amplitude)
                    } else {
                        state
                    }

                else -> state
            }
        }

        is DictationCommand.TranscriptCandidates -> {
            val active = state.activeSessionId()
            if (active == null || active != command.sessionId) {
                // Stale or no active session: record nothing, change nothing.
                state
            } else {
                // Record the latest valid candidate (may arrive during Listening
                // or Finalizing; the state itself is unchanged by recording).
                command.candidates.lastOrNull()?.let { selectedCandidate = it }

                // BeginInsert logic: there is no BeginInsert command in the
                // committed command set, so arrival of candidates while Finalizing
                // is what drives Finalizing -> Inserting. During Listening we stay
                // in Listening while still recording the candidate.
                if (state is DictationState.Finalizing) {
                    DictationState.Inserting(active)
                } else {
                    state
                }
            }
        }

        is DictationCommand.InsertOutcome -> {
            // Only when the current state is Inserting or Finalizing with a
            // matching session; any other state (including every terminal) is
            // ignored, which consumes a terminal result at most once.
            val active = state.activeSessionId()
            if (active == null || active != command.sessionId ||
                (state !is DictationState.Inserting && state !is DictationState.Finalizing)
            ) {
                state
            } else {
                val candidate = selectedCandidate
                when (command.outcome) {
                    is InsertionResult.Inserted -> DictationState.Success(active)

                    is InsertionResult.Ambiguous ->
                        if (candidate != null) {
                            DictationState.CopyAvailable(active, candidate)
                        } else {
                            DictationState.Error(
                                sessionId = active,
                                failure = DictationFailure(
                                    code = "AMBIGUOUS_INSERTION",
                                    message = "Insertion could not be confirmed; no candidate is available to copy.",
                                    recoverable = true,
                                ),
                            )
                        }

                    is InsertionResult.Failed ->
                        if (candidate != null) {
                            DictationState.CopyAvailable(active, candidate)
                        } else {
                            DictationState.Error(active, command.outcome.failure)
                        }
                }
            }
        }

        is DictationCommand.DismissCopy ->
            if (state is DictationState.CopyAvailable) DictationState.Idle else state

        is DictationCommand.DismissError ->
            if (state is DictationState.Error) DictationState.Idle else state
    }

    private fun DictationState.isActive(): Boolean = when (this) {
        is DictationState.Starting,
        is DictationState.Listening,
        is DictationState.Finalizing,
        is DictationState.Inserting,
        -> true

        else -> false
    }

    private fun DictationState.activeSessionId(): SessionId? = when (this) {
        is DictationState.Starting -> sessionId
        is DictationState.Listening -> sessionId
        is DictationState.Finalizing -> sessionId
        is DictationState.Inserting -> sessionId
        else -> null
    }
}

