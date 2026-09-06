package com.whispertype.android.platform.accessibility

import com.whispertype.android.core.model.DictationFailure
import com.whispertype.android.core.model.InsertionResult
import com.whispertype.android.core.model.SessionId
import com.whispertype.android.core.model.TargetSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetReservationStoreTest {

    @Test
    fun `reservation grants one commit and caches its terminal result`() {
        val store = TargetReservationStore()
        val target = target("session-1")

        assertEquals(
            TargetReservationStore.ReserveDecision.Reserved(newlyCreated = true),
            store.reserve(target.sessionId, target, target.reservationIdentity()),
        )

        val first = store.beginCommit(
            sessionId = target.sessionId,
            currentTarget = target.reservationIdentity(),
            commitStartedAtNanos = 30L,
        )
        assertEquals(TargetReservationStore.CommitDecision.Execute(target), first)
        assertEquals(
            TargetReservationStore.CommitDecision.InFlight,
            store.beginCommit(
                sessionId = target.sessionId,
                currentTarget = target.reservationIdentity(),
                commitStartedAtNanos = 40L,
            ),
        )

        val terminal = store.completeCommit(
            sessionId = target.sessionId,
            result = InsertionResult.Inserted,
            commitCompletedAtNanos = 35L,
        )
        assertEquals(InsertionResult.Inserted, terminal.result)
        assertEquals(30L, terminal.commitStartedAtNanos)
        assertEquals(35L, terminal.commitCompletedAtNanos)
        assertFalse(store.hasActiveSnapshot(target.sessionId))

        assertEquals(
            TargetReservationStore.CommitDecision.Terminal(terminal),
            store.beginCommit(
                sessionId = target.sessionId,
                currentTarget = target.reservationIdentity(),
                commitStartedAtNanos = 50L,
            ),
        )
    }

    @Test
    fun `all target identity changes invalidate the reservation`() {
        val original = target("session")
        val changedTargets = listOf(
            original.reservationIdentity().copy(packageName = "other.package") to "package",
            original.reservationIdentity().copy(displayId = 2) to "display",
            original.reservationIdentity().copy(windowId = 9) to "window",
            original.reservationIdentity().copy(editorIdentity = "other-editor") to "editor",
            original.reservationIdentity().copy(generation = 8L) to "generation",
        )

        changedTargets.forEachIndexed { index, (changed, label) ->
            val sessionTarget = original.copy(sessionId = SessionId("session-$index"))
            val store = TargetReservationStore()
            store.reserve(
                sessionId = sessionTarget.sessionId,
                target = sessionTarget,
                currentTarget = sessionTarget.reservationIdentity(),
            )

            store.invalidateAgainst(changed)

            val terminal = store.beginCommit(
                sessionId = sessionTarget.sessionId,
                currentTarget = changed,
                commitStartedAtNanos = 10L,
            ) as TargetReservationStore.CommitDecision.Terminal
            assertEquals(label, "insert_target_stale", failureCode(terminal.terminal.result))
            assertFalse(label, store.hasActiveSnapshot(sessionTarget.sessionId))
        }
    }

    @Test
    fun `security change fails closed and cannot commit`() {
        val target = target("secure-change")
        val store = TargetReservationStore()
        store.reserve(target.sessionId, target, target.reservationIdentity())

        val nowSecure = target.reservationIdentity().copy(isSecure = true)
        val decision = store.beginCommit(
            sessionId = target.sessionId,
            currentTarget = nowSecure,
            commitStartedAtNanos = 20L,
        ) as TargetReservationStore.CommitDecision.Terminal

        assertEquals("insert_target_not_safe", failureCode(decision.terminal.result))
        assertEquals(null, decision.terminal.commitStartedAtNanos)
        assertFalse(store.hasActiveSnapshot(target.sessionId))
    }

    @Test
    fun `duplicate reserve never rebinds a session to a new field`() {
        val store = TargetReservationStore()
        val first = target("same-session")
        val second = first.copy(
            windowId = first.windowId + 1,
            editorIdentity = "second-editor",
            generation = first.generation + 1,
        )
        store.reserve(first.sessionId, first, first.reservationIdentity())

        val duplicate = store.reserve(
            sessionId = first.sessionId,
            target = second,
            currentTarget = second.reservationIdentity(),
        )

        assertEquals(
            TargetReservationStore.ReserveDecision.Rejected(
                TargetReservationStore.ReserveRejection.TARGET_CHANGED,
            ),
            duplicate,
        )
        val terminal = store.beginCommit(
            sessionId = first.sessionId,
            currentTarget = second.reservationIdentity(),
            commitStartedAtNanos = 10L,
        ) as TargetReservationStore.CommitDecision.Terminal
        assertEquals("insert_target_stale", failureCode(terminal.terminal.result))
    }

    @Test
    fun `release drops snapshot and rejects a later commit`() {
        val store = TargetReservationStore()
        val target = target("released")
        store.reserve(target.sessionId, target, target.reservationIdentity())

        assertEquals(
            TargetReservationStore.ReleaseDecision.RELEASED,
            store.release(target.sessionId),
        )
        assertFalse(store.hasActiveSnapshot(target.sessionId))

        val terminal = store.beginCommit(
            sessionId = target.sessionId,
            currentTarget = target.reservationIdentity(),
            commitStartedAtNanos = 99L,
        ) as TargetReservationStore.CommitDecision.Terminal
        assertEquals("insert_target_stale", failureCode(terminal.terminal.result))
        assertEquals(null, terminal.terminal.commitStartedAtNanos)
    }

    @Test
    fun `release cannot erase an in-flight exactly-once outcome`() {
        val store = TargetReservationStore()
        val target = target("release-in-flight")
        store.reserve(target.sessionId, target, target.reservationIdentity())
        store.beginCommit(
            sessionId = target.sessionId,
            currentTarget = target.reservationIdentity(),
            commitStartedAtNanos = 7L,
        )

        assertEquals(
            TargetReservationStore.ReleaseDecision.COMMIT_IN_FLIGHT,
            store.release(target.sessionId),
        )
        assertTrue(store.hasActiveSnapshot(target.sessionId))

        val terminal = store.completeCommit(
            sessionId = target.sessionId,
            result = InsertionResult.Ambiguous,
            commitCompletedAtNanos = 9L,
        )
        assertEquals(InsertionResult.Ambiguous, terminal.result)
        assertFalse(store.hasActiveSnapshot(target.sessionId))
    }

    @Test
    fun `failed commit releases snapshot and duplicate receives same failure`() {
        val store = TargetReservationStore()
        val target = target("failed")
        store.reserve(target.sessionId, target, target.reservationIdentity())
        store.beginCommit(
            sessionId = target.sessionId,
            currentTarget = target.reservationIdentity(),
            commitStartedAtNanos = 12L,
        )
        val failure = InsertionResult.Failed(
            DictationFailure(
                code = "test_failure",
                message = "Typed test failure",
                recoverable = true,
            ),
        )

        val completed = store.completeCommit(
            sessionId = target.sessionId,
            result = failure,
            commitCompletedAtNanos = 18L,
        )
        val duplicate = store.beginCommit(
            sessionId = target.sessionId,
            currentTarget = target.reservationIdentity(),
            commitStartedAtNanos = 20L,
        ) as TargetReservationStore.CommitDecision.Terminal

        assertEquals(completed, duplicate.terminal)
        assertEquals(12L, duplicate.terminal.commitStartedAtNanos)
        assertEquals(18L, duplicate.terminal.commitCompletedAtNanos)
        assertFalse(store.hasActiveSnapshot(target.sessionId))
    }

    @Test
    fun `completion without an execution permit cannot claim insertion`() {
        val store = TargetReservationStore()
        val target = target("no-permit")
        store.reserve(target.sessionId, target, target.reservationIdentity())

        val terminal = store.completeCommit(target.sessionId, InsertionResult.Inserted)

        assertEquals("insert_target_stale", failureCode(terminal.result))
        assertFalse(store.hasActiveSnapshot(target.sessionId))
    }

    @Test
    fun `active and terminal storage stay bounded and clear on teardown`() {
        val store = TargetReservationStore(
            maxActiveReservations = 1,
            maxTerminalResults = 2,
        )
        val first = target("first")
        val second = target("second")

        store.reserve(first.sessionId, first, first.reservationIdentity())
        assertEquals(
            TargetReservationStore.ReserveDecision.Rejected(
                TargetReservationStore.ReserveRejection.CAPACITY_EXCEEDED,
            ),
            store.reserve(second.sessionId, second, second.reservationIdentity()),
        )
        assertEquals(1, store.activeReservationCount())

        store.release(first.sessionId)
        repeat(3) { index ->
            val unknown = SessionId("unknown-$index")
            store.beginCommit(
                sessionId = unknown,
                currentTarget = null,
                commitStartedAtNanos = index.toLong(),
            )
        }
        assertEquals(0, store.activeReservationCount())
        assertEquals(2, store.terminalResultCount())

        store.clear()
        assertEquals(0, store.activeReservationCount())
        assertEquals(0, store.terminalResultCount())
    }

    @Test
    fun `missing or unsafe capture is rejected without retaining a snapshot`() {
        val store = TargetReservationStore()
        val sessionId = SessionId("missing")

        assertEquals(
            TargetReservationStore.ReserveDecision.Rejected(
                TargetReservationStore.ReserveRejection.INELIGIBLE,
            ),
            store.reserve(sessionId, target = null, currentTarget = null),
        )
        assertEquals(0, store.activeReservationCount())

        val unsafe = target("unsafe").copy(isUncertain = true)
        assertEquals(
            TargetReservationStore.ReserveDecision.Rejected(
                TargetReservationStore.ReserveRejection.INELIGIBLE,
            ),
            store.reserve(unsafe.sessionId, unsafe, unsafe.reservationIdentity()),
        )
        assertEquals(0, store.activeReservationCount())
    }

    private fun failureCode(result: InsertionResult): String =
        (result as InsertionResult.Failed).failure.code

    private fun target(session: String): TargetSnapshot = TargetSnapshot(
        sessionId = SessionId(session),
        packageName = "com.example.editor",
        displayId = 0,
        windowId = 4,
        editorIdentity = "message",
        generation = 7L,
        inputTypeMask = SecurityClassifier.TYPE_CLASS_TEXT,
        isSecure = false,
        isUncertain = false,
        selectionStart = 3,
        selectionEnd = 3,
        capturedAtMillis = 100L,
    )
}
