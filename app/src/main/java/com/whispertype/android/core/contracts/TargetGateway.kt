package com.whispertype.android.core.contracts

import com.whispertype.android.core.model.InsertionResult
import com.whispertype.android.core.model.SessionId
import com.whispertype.android.core.model.TargetEligibility
import com.whispertype.android.core.model.TargetSnapshot
import kotlinx.coroutines.flow.Flow

/**
 * The accessibility service's editor gateway. Target capture is explicit and
 * immutable; insertion returns a typed result, never a Boolean with hidden
 * causes. Consumers never fabricate a target token.
 */
interface TargetGateway {
    fun currentEligibility(): Flow<TargetEligibility>

    /** Captures the current focused editor for [sessionId], or null if ineligible. */
    fun captureTarget(sessionId: SessionId): TargetSnapshot?

    /** Validates the target snapshot immediately before committing text once. */
    suspend fun insert(target: TargetSnapshot, text: String): InsertionResult
}