package com.whispertype.android.platform.accessibility

import com.whispertype.android.core.model.InsertionResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure host tests for [InsertionDecision] (FR-8 exactly-once insertion contract). */
class InsertionDecisionTest {

    @Test
    fun `successful commit returns Inserted`() {
        assertEquals(
            InsertionResult.Inserted,
            InsertionDecision.evaluate(
                connectionPresent = true,
                targetCurrent = true,
                targetSecureOrUncertain = false,
                commitAccepted = true,
            ),
        )
    }

    @Test
    fun `commit returning false is ambiguous not retried`() {
        val result = InsertionDecision.evaluate(
            connectionPresent = true,
            targetCurrent = true,
            targetSecureOrUncertain = false,
            commitAccepted = false,
        )
        assertEquals(InsertionResult.Ambiguous, result)
    }

    @Test
    fun `missing connection fails with connection code and is recoverable`() {
        val result = InsertionDecision.evaluate(
            connectionPresent = false,
            targetCurrent = false,
            targetSecureOrUncertain = false,
            commitAccepted = null,
        ) as InsertionResult.Failed
        assertEquals("insert_connection_unavailable", result.failure.code)
        assertTrue(result.failure.recoverable)
    }

    @Test
    fun `stale target fails even with a live connection and no commit`() {
        val result = InsertionDecision.evaluate(
            connectionPresent = true,
            targetCurrent = false,
            targetSecureOrUncertain = false,
            commitAccepted = null,
        ) as InsertionResult.Failed
        assertEquals("insert_target_stale", result.failure.code)
    }

    @Test
    fun `secure or uncertain target never commits`() {
        // A secure/uncertain target is rejected before any commit attempt.
        repeat(2) {
            val result = InsertionDecision.evaluate(
                connectionPresent = true,
                targetCurrent = true,
                targetSecureOrUncertain = true,
                commitAccepted = null,
            ) as InsertionResult.Failed
            assertEquals("insert_target_not_safe", result.failure.code)
        }
    }

    @Test
    fun `secure target wins over missing connection to prevent clipboard fallback`() {
        val result = InsertionDecision.evaluate(
            connectionPresent = false,
            targetCurrent = false,
            targetSecureOrUncertain = true,
            commitAccepted = null,
        ) as InsertionResult.Failed

        assertEquals("insert_target_not_safe", result.failure.code)
    }

    @Test
    fun `ambiguous failure forbids retry`() {
        val failure = InsertionDecision.ambiguous()
        assertEquals("insert_ambiguous", failure.code)
        assertEquals(false, failure.retryAllowed)
        assertTrue(failure.recoverable)
    }
}
