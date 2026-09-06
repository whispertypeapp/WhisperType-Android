package com.whispertype.android.core.contracts

import com.whispertype.android.core.model.CancelReason
import com.whispertype.android.core.model.SessionId
import com.whispertype.android.core.model.StartResult
import com.whispertype.android.core.model.TargetSnapshot

/**
 * Session-scoped dictation commands implemented by the dictation service
 * (the sole active-session owner). Implementations must reject commands whose
 * [SessionId] does not match the current session.
 */
interface DictationCommandSink {
    suspend fun start(target: TargetSnapshot): StartResult
    suspend fun stop(sessionId: SessionId): Boolean
    suspend fun cancel(sessionId: SessionId, reason: CancelReason): Boolean
}