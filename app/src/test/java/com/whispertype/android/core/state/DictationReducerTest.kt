package com.whispertype.android.core.state

import com.whispertype.android.core.model.CancelReason
import com.whispertype.android.core.model.DictationCommand
import com.whispertype.android.core.model.DictationFailure
import com.whispertype.android.core.model.DictationState
import com.whispertype.android.core.model.InsertionResult
import com.whispertype.android.core.model.LanguageMode
import com.whispertype.android.core.model.ResultCandidate
import com.whispertype.android.core.model.SessionId
import com.whispertype.android.core.model.TargetSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for [DictationReducer] covering the dictation state machine. */
class DictationReducerTest {

    private val reducer = DictationReducer()

    private fun session(id: String): SessionId = SessionId(id)

    private fun target(id: String): TargetSnapshot = TargetSnapshot(
        sessionId = session(id),
        packageName = "com.example.test",
        displayId = 0,
        windowId = 0,
        editorIdentity = "editor-$id",
        generation = 1L,
        inputTypeMask = 0,
        isSecure = false,
        isUncertain = false,
        selectionStart = 0,
        selectionEnd = 0,
        capturedAtMillis = 0L,
    )

    private fun candidate(raw: String, cleaned: String? = null): ResultCandidate =
        ResultCandidate(raw = raw, cleaned = cleaned, language = LanguageMode.ENGLISH)

    private fun failure(code: String, recoverable: Boolean = false): DictationFailure =
        DictationFailure(code = code, message = "failure: $code", recoverable = recoverable)

    /** Drives Idle -> Starting -> Listening for session [id]. */
    private fun startAndListen(id: String): SessionId {
        val sid = session(id)
        reducer.reduce(DictationCommand.Start(target(id)))
        assertEquals(DictationState.Starting(sid, target(id)), reducer.state)
        reducer.reduce(DictationCommand.AmplitudeTick(sid, 0.1f))
        assertEquals(DictationState.Listening(sid, 0.1f, 0L), reducer.state)
        return sid
    }

    // ------------------------------------------------------------------
    // Happy path: Idle -> Starting -> Listening -> Finalizing -> Inserting -> Success
    // ------------------------------------------------------------------

    @Test
    fun `idle without any command stays idle`() {
        assertEquals(DictationState.Idle, reducer.state)
    }

    @Test
    fun `happy path full sequence ends in Success`() {
        reducer.reduce(DictationCommand.Start(target("s1")))
        val sid = session("s1")
        assertEquals(DictationState.Starting(sid, target("s1")), reducer.state)

        reducer.reduce(DictationCommand.AmplitudeTick(sid, 0.25f))
        assertEquals(DictationState.Listening(sid, 0.25f, 0L), reducer.state)

        // Candidate arrives during Listening -> recorded, no state change.
        reducer.reduce(DictationCommand.TranscriptCandidates(sid, listOf(candidate("hello"))))
        assertEquals(DictationState.Listening(sid, 0.25f, 0L), reducer.state)

        reducer.reduce(DictationCommand.Stop(sid))
        assertEquals(DictationState.Finalizing(sid), reducer.state)

        // Candidate arrives while Finalizing -> drives Finalizing -> Inserting.
        reducer.reduce(DictationCommand.TranscriptCandidates(sid, listOf(candidate("hello world"))))
        assertEquals(DictationState.Inserting(sid), reducer.state)

        reducer.reduce(DictationCommand.InsertOutcome(sid, InsertionResult.Inserted))
        assertEquals(DictationState.Success(sid), reducer.state)
    }


    @Test
    fun `Start from Idle clears previous candidate`() {
        val sid = session("s1")
        reducer.reduce(DictationCommand.Start(target("s1")))
        reducer.reduce(DictationCommand.AmplitudeTick(sid, 0.2f))
        reducer.reduce(DictationCommand.TranscriptCandidates(sid, listOf(candidate("first"))))
        reducer.reduce(DictationCommand.Stop(sid))
        reducer.reduce(DictationCommand.TranscriptCandidates(sid, listOf(candidate("first"))))
        reducer.reduce(DictationCommand.InsertOutcome(sid, InsertionResult.Ambiguous))
        assertEquals(DictationState.CopyAvailable(sid, candidate("first")), reducer.state)

        // New start clears the candidate; ambiguous without a candidate -> Error.
        reducer.reduce(DictationCommand.Start(target("s2")))
        val s2 = session("s2")
        reducer.reduce(DictationCommand.AmplitudeTick(s2, 0.3f))
        reducer.reduce(DictationCommand.Stop(s2))
        reducer.reduce(DictationCommand.TranscriptCandidates(s2, emptyList()))
        reducer.reduce(DictationCommand.InsertOutcome(s2, InsertionResult.Ambiguous))
        val errorState = reducer.state as DictationState.Error
        assertEquals(s2, errorState.sessionId)
        assertEquals("AMBIGUOUS_INSERTION", errorState.failure.code)
    }

    // ------------------------------------------------------------------
    // Cancel from each active state + stale barrier
    // ------------------------------------------------------------------

    @Test
    fun `Cancel from Starting produces Cancelled`() {
        reducer.reduce(DictationCommand.Start(target("s1")))
        val sid = session("s1")
        reducer.reduce(DictationCommand.Cancel(sid, CancelReason.USER))
        assertEquals(DictationState.Cancelled(sid, CancelReason.USER), reducer.state)
    }

    @Test
    fun `Cancel from Listening produces Cancelled`() {
        val sid = startAndListen("s1")
        reducer.reduce(DictationCommand.Cancel(sid, CancelReason.FOCUS_CHANGED))
        assertEquals(DictationState.Cancelled(sid, CancelReason.FOCUS_CHANGED), reducer.state)
    }

    @Test
    fun `Cancel from Finalizing produces Cancelled`() {
        val sid = startAndListen("s1")
        reducer.reduce(DictationCommand.Stop(sid))
        reducer.reduce(DictationCommand.Cancel(sid, CancelReason.APP_SWITCHED))
        assertEquals(DictationState.Cancelled(sid, CancelReason.APP_SWITCHED), reducer.state)
    }

    @Test
    fun `Cancel from Inserting produces Cancelled`() {
        val sid = startAndListen("s1")
        reducer.reduce(DictationCommand.Stop(sid))
        reducer.reduce(DictationCommand.TranscriptCandidates(sid, listOf(candidate("x"))))
        reducer.reduce(DictationCommand.Cancel(sid, CancelReason.MIC_LOST))
        assertEquals(DictationState.Cancelled(sid, CancelReason.MIC_LOST), reducer.state)
    }

    @Test
    fun `Cancel is ignored from a non-active terminal state`() {
        val sid = startAndListen("s1")
        reducer.reduce(DictationCommand.Stop(sid))
        reducer.reduce(DictationCommand.TranscriptCandidates(sid, listOf(candidate("x"))))
        reducer.reduce(DictationCommand.InsertOutcome(sid, InsertionResult.Inserted))
        assertEquals(DictationState.Success(sid), reducer.state)

        reducer.reduce(DictationCommand.Cancel(sid, CancelReason.USER))
        assertEquals(DictationState.Success(sid), reducer.state)
    }

    @Test
    fun `stale events after Cancel are ignored`() {
        val sid = startAndListen("s1")
        reducer.reduce(DictationCommand.Cancel(sid, CancelReason.USER))
        val cancelled = DictationState.Cancelled(sid, CancelReason.USER)
        assertEquals(cancelled, reducer.state)

        // Old-session events must be ignored (stale-session barrier).
        reducer.reduce(DictationCommand.Stop(sid))
        assertEquals(cancelled, reducer.state)
        reducer.reduce(DictationCommand.AmplitudeTick(sid, 0.9f))
        assertEquals(cancelled, reducer.state)
        reducer.reduce(DictationCommand.TranscriptCandidates(sid, listOf(candidate("late"))))
        assertEquals(cancelled, reducer.state)
        reducer.reduce(DictationCommand.InsertOutcome(sid, InsertionResult.Inserted))
        assertEquals(cancelled, reducer.state)
    }

    @Test
    fun `Cancel with a different session id is ignored`() {
        startAndListen("s1")
        val cancelled = reducer.state
        reducer.reduce(DictationCommand.Cancel(session("other"), CancelReason.USER))
        assertEquals(cancelled, reducer.state)
    }

    // ------------------------------------------------------------------
    // Stale-session rejection (wrong SessionId)
    // ------------------------------------------------------------------

    @Test
    fun `Stop with a stale session id is ignored`() {
        val sid = startAndListen("s1")
        reducer.reduce(DictationCommand.Stop(session("other")))
        assertEquals(DictationState.Listening(sid, 0.1f, 0L), reducer.state)
    }

    @Test
    fun `AmplitudeTick with a stale session id is ignored`() {
        val sid = startAndListen("s1")
        reducer.reduce(DictationCommand.AmplitudeTick(session("other"), 0.99f))
        assertEquals(DictationState.Listening(sid, 0.1f, 0L), reducer.state)
    }

    @Test
    fun `TranscriptCandidates with a stale session id is ignored and not recorded`() {
        val sid = startAndListen("s1")
        reducer.reduce(DictationCommand.TranscriptCandidates(session("other"), listOf(candidate("stale"))))
        assertEquals(DictationState.Listening(sid, 0.1f, 0L), reducer.state)

        // Verify the stale candidate was not recorded: a later ambiguous outcome
        // must produce Error (no candidate) rather than CopyAvailable.
        reducer.reduce(DictationCommand.Stop(sid))
        reducer.reduce(DictationCommand.TranscriptCandidates(sid, emptyList()))
        reducer.reduce(DictationCommand.InsertOutcome(sid, InsertionResult.Ambiguous))
        val errorState = reducer.state as DictationState.Error
        assertEquals("AMBIGUOUS_INSERTION", errorState.failure.code)
    }

    @Test
    fun `InsertOutcome with a stale session id is ignored`() {
        val sid = startAndListen("s1")
        reducer.reduce(DictationCommand.Stop(sid))
        reducer.reduce(DictationCommand.TranscriptCandidates(sid, listOf(candidate("x"))))
        assertEquals(DictationState.Inserting(sid), reducer.state)

        reducer.reduce(DictationCommand.InsertOutcome(session("other"), InsertionResult.Inserted))
        assertEquals(DictationState.Inserting(sid), reducer.state)
    }

    // ------------------------------------------------------------------
    // One active session globally
    // ------------------------------------------------------------------

    @Test
    fun `Start is ignored while active`() {
        startAndListen("s1")
        val active = reducer.state
        reducer.reduce(DictationCommand.Start(target("s2")))
        assertEquals(active, reducer.state)
    }

    @Test
    fun `Start is ignored while Finalizing and Inserting`() {
        val sid = startAndListen("s1")
        reducer.reduce(DictationCommand.Stop(sid))
        val finalizing = reducer.state
        reducer.reduce(DictationCommand.Start(target("s2")))
        assertEquals(finalizing, reducer.state)

        reducer.reduce(DictationCommand.TranscriptCandidates(sid, listOf(candidate("x"))))
        val inserting = reducer.state
        reducer.reduce(DictationCommand.Start(target("s2")))
        assertEquals(inserting, reducer.state)
    }

    // ------------------------------------------------------------------
    // AmplitudeTick behaviour
    // ------------------------------------------------------------------

    @Test
    fun `AmplitudeTick updates amplitude only in Listening with matching session`() {
        // Transitions Starting -> Listening, then updates amplitude.
        reducer.reduce(DictationCommand.Start(target("s1")))
        val sid = session("s1")
        reducer.reduce(DictationCommand.AmplitudeTick(sid, 0.55f))
        assertEquals(DictationState.Listening(sid, 0.55f, 0L), reducer.state)

        reducer.reduce(DictationCommand.AmplitudeTick(sid, 0.66f))
        reducer.reduce(DictationCommand.AmplitudeTick(sid, 0.77f))
        assertEquals(DictationState.Listening(sid, 0.77f, 0L), reducer.state)
    }

    @Test
    fun `AmplitudeTick is ignored after leaving Listening`() {
        val sid = startAndListen("s1")
        reducer.reduce(DictationCommand.Stop(sid))
        val finalizing = reducer.state
        reducer.reduce(DictationCommand.AmplitudeTick(sid, 0.99f))
        assertEquals(finalizing, reducer.state)
    }


    // ------------------------------------------------------------------
    // InsertOutcome -> CopyAvailable / Error
    // ------------------------------------------------------------------

    @Test
    fun `InsertOutcome Ambiguous with candidate yields CopyAvailable`() {
        val sid = startAndListen("s1")
        reducer.reduce(DictationCommand.TranscriptCandidates(sid, listOf(candidate("the answer"))))
        reducer.reduce(DictationCommand.Stop(sid))
        reducer.reduce(DictationCommand.TranscriptCandidates(sid, listOf(candidate("the answer"))))
        reducer.reduce(DictationCommand.InsertOutcome(sid, InsertionResult.Ambiguous))
        assertEquals(DictationState.CopyAvailable(sid, candidate("the answer")), reducer.state)
    }

    @Test
    fun `InsertOutcome Ambiguous without candidate yields AMBIGUOUS_INSERTION Error`() {
        val sid = startAndListen("s1")
        reducer.reduce(DictationCommand.Stop(sid))
        reducer.reduce(DictationCommand.TranscriptCandidates(sid, emptyList()))
        reducer.reduce(DictationCommand.InsertOutcome(sid, InsertionResult.Ambiguous))
        val errorState = reducer.state as DictationState.Error
        assertEquals(sid, errorState.sessionId)
        assertEquals("AMBIGUOUS_INSERTION", errorState.failure.code)
        assertTrue(errorState.failure.recoverable)
    }

    @Test
    fun `InsertOutcome Failed with candidate yields CopyAvailable`() {
        val sid = startAndListen("s1")
        val c = candidate("copy me", "copy me cleaned")
        reducer.reduce(DictationCommand.TranscriptCandidates(sid, listOf(c)))
        reducer.reduce(DictationCommand.Stop(sid))
        reducer.reduce(DictationCommand.TranscriptCandidates(sid, listOf(c)))
        reducer.reduce(DictationCommand.InsertOutcome(sid, InsertionResult.Failed(failure("INSERT_FAILED"))))
        assertEquals(DictationState.CopyAvailable(sid, c), reducer.state)
    }

    @Test
    fun `InsertOutcome Failed without candidate yields Error with failure`() {
        val sid = startAndListen("s1")
        val original = failure("INSERT_FAILED", recoverable = true)
        reducer.reduce(DictationCommand.Stop(sid))
        reducer.reduce(DictationCommand.TranscriptCandidates(sid, emptyList()))
        reducer.reduce(DictationCommand.InsertOutcome(sid, InsertionResult.Failed(original)))
        assertEquals(DictationState.Error(sid, original), reducer.state)
    }

    @Test
    fun `InsertOutcome is accepted from Finalizing as well as Inserting`() {
        // Direct from Finalizing (no candidates) -> still handled.
        val sid = startAndListen("s1")
        reducer.reduce(DictationCommand.Stop(sid))
        assertEquals(DictationState.Finalizing(sid), reducer.state)
        reducer.reduce(DictationCommand.InsertOutcome(sid, InsertionResult.Inserted))
        assertEquals(DictationState.Success(sid), reducer.state)
    }

    // ------------------------------------------------------------------
    // Terminal result consumed at most once
    // ------------------------------------------------------------------

    @Test
    fun `a second InsertOutcome after terminal state is ignored`() {
        val sid = startAndListen("s1")
        reducer.reduce(DictationCommand.TranscriptCandidates(sid, listOf(candidate("x"))))
        reducer.reduce(DictationCommand.Stop(sid))
        reducer.reduce(DictationCommand.TranscriptCandidates(sid, listOf(candidate("x"))))
        reducer.reduce(DictationCommand.InsertOutcome(sid, InsertionResult.Inserted))
        val success = reducer.state
        assertEquals(DictationState.Success(sid), success)

        // Further outcomes (even different ones) must be ignored.
        reducer.reduce(DictationCommand.InsertOutcome(sid, InsertionResult.Ambiguous))
        assertEquals(success, reducer.state)
        reducer.reduce(DictationCommand.InsertOutcome(sid, InsertionResult.Failed(failure("LATE"))))
        assertEquals(success, reducer.state)
    }

    @Test
    fun `an InsertOutcome arriving before Inserting or Finalizing is ignored`() {
        val sid = startAndListen("s1")
        val listening = reducer.state
        reducer.reduce(DictationCommand.InsertOutcome(sid, InsertionResult.Inserted))
        assertEquals(listening, reducer.state)
    }

    // ------------------------------------------------------------------
    // DismissCopy / DismissError
    // ------------------------------------------------------------------

    @Test
    fun `DismissCopy returns to Idle`() {
        val sid = startAndListen("s1")
        reducer.reduce(DictationCommand.TranscriptCandidates(sid, listOf(candidate("x"))))
        reducer.reduce(DictationCommand.Stop(sid))
        reducer.reduce(DictationCommand.TranscriptCandidates(sid, listOf(candidate("x"))))
        reducer.reduce(DictationCommand.InsertOutcome(sid, InsertionResult.Ambiguous))
        assertEquals(DictationState.CopyAvailable(sid, candidate("x")), reducer.state)

        reducer.reduce(DictationCommand.DismissCopy)
        assertEquals(DictationState.Idle, reducer.state)
    }

    @Test
    fun `DismissError returns to Idle`() {
        val sid = startAndListen("s1")
        reducer.reduce(DictationCommand.Stop(sid))
        reducer.reduce(DictationCommand.TranscriptCandidates(sid, emptyList()))
        reducer.reduce(DictationCommand.InsertOutcome(sid, InsertionResult.Ambiguous))
        assertTrue(reducer.state is DictationState.Error)

        reducer.reduce(DictationCommand.DismissError)
        assertEquals(DictationState.Idle, reducer.state)
    }


    // ------------------------------------------------------------------
    // Fresh start from terminal states clears old candidate
    // ------------------------------------------------------------------

    @Test
    fun `Start from CopyAvailable begins a fresh session and clears candidate`() {
        val sid = startAndListen("s1")
        reducer.reduce(DictationCommand.TranscriptCandidates(sid, listOf(candidate("old"))))
        reducer.reduce(DictationCommand.Stop(sid))
        reducer.reduce(DictationCommand.TranscriptCandidates(sid, listOf(candidate("old"))))
        reducer.reduce(DictationCommand.InsertOutcome(sid, InsertionResult.Ambiguous))
        assertEquals(DictationState.CopyAvailable(sid, candidate("old")), reducer.state)

        reducer.reduce(DictationCommand.Start(target("s2")))
        val s2 = session("s2")
        reducer.reduce(DictationCommand.AmplitudeTick(s2, 0.1f))
        reducer.reduce(DictationCommand.Stop(s2))
        reducer.reduce(DictationCommand.TranscriptCandidates(s2, emptyList()))
        reducer.reduce(DictationCommand.InsertOutcome(s2, InsertionResult.Ambiguous))
        val errorState = reducer.state as DictationState.Error
        assertEquals(s2, errorState.sessionId)
        assertEquals("AMBIGUOUS_INSERTION", errorState.failure.code)
    }

    @Test
    fun `Start from Error begins a fresh session`() {
        val sid = startAndListen("s1")
        reducer.reduce(DictationCommand.Stop(sid))
        reducer.reduce(DictationCommand.TranscriptCandidates(sid, emptyList()))
        reducer.reduce(DictationCommand.InsertOutcome(sid, InsertionResult.Failed(failure("E1"))))
        assertTrue(reducer.state is DictationState.Error)

        reducer.reduce(DictationCommand.Start(target("s2")))
        assertEquals(DictationState.Starting(session("s2"), target("s2")), reducer.state)
    }

    @Test
    fun `Start from Success begins a fresh session`() {
        val sid = startAndListen("s1")
        reducer.reduce(DictationCommand.Stop(sid))
        reducer.reduce(DictationCommand.TranscriptCandidates(sid, listOf(candidate("x"))))
        reducer.reduce(DictationCommand.InsertOutcome(sid, InsertionResult.Inserted))
        assertEquals(DictationState.Success(sid), reducer.state)

        reducer.reduce(DictationCommand.Start(target("s2")))
        assertEquals(DictationState.Starting(session("s2"), target("s2")), reducer.state)
    }

    @Test
    fun `Start from Cancelled begins a fresh session`() {
        val sid = startAndListen("s1")
        reducer.reduce(DictationCommand.Cancel(sid, CancelReason.USER))
        assertEquals(DictationState.Cancelled(sid, CancelReason.USER), reducer.state)

        reducer.reduce(DictationCommand.Start(target("s2")))
        assertEquals(DictationState.Starting(session("s2"), target("s2")), reducer.state)
    }

    @Test
    fun `new session ignores old session late events`() {
        // Old session reaches CopyAvailable, then a new session starts.
        val sid = startAndListen("s1")
        reducer.reduce(DictationCommand.TranscriptCandidates(sid, listOf(candidate("old"))))
        reducer.reduce(DictationCommand.Stop(sid))
        reducer.reduce(DictationCommand.TranscriptCandidates(sid, listOf(candidate("old"))))
        reducer.reduce(DictationCommand.InsertOutcome(sid, InsertionResult.Ambiguous))
        assertEquals(DictationState.CopyAvailable(sid, candidate("old")), reducer.state)

        reducer.reduce(DictationCommand.Start(target("s2")))
        val s2 = session("s2")
        reducer.reduce(DictationCommand.AmplitudeTick(s2, 0.1f))
        val listening = DictationState.Listening(s2, 0.1f, 0L)
        assertEquals(listening, reducer.state)

        // A late InsertOutcome from the old session must be ignored.
        reducer.reduce(DictationCommand.InsertOutcome(sid, InsertionResult.Inserted))
        assertEquals(listening, reducer.state)
    }

    @Test
    fun `a non-applicable command returns the state unchanged`() {
        // DismissCopy / DismissError in Idle do nothing.
        assertEquals(DictationState.Idle, reducer.state)
        reducer.reduce(DictationCommand.DismissCopy)
        assertEquals(DictationState.Idle, reducer.state)
        reducer.reduce(DictationCommand.DismissError)
        assertEquals(DictationState.Idle, reducer.state)

        // Stop in Idle does nothing.
        reducer.reduce(DictationCommand.Stop(session("s1")))
        assertEquals(DictationState.Idle, reducer.state)
    }
}

