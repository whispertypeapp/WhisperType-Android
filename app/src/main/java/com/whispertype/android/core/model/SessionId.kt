package com.whispertype.android.core.model

import java.util.UUID

/**
 * Canonical identity for one dictation session. One [SessionId] flows from
 * target capture through the dictation service, Gemini, insertion, and the
 * terminal result. Every asynchronous event is ignored if it belongs to an
 * older session.
 */
@JvmInline
value class SessionId(val value: String) {
    companion object {
        fun new(): SessionId = SessionId(UUID.randomUUID().toString())
    }
}