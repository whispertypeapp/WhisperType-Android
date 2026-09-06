package com.whispertype.android.core.contracts

import com.whispertype.android.core.model.OverlayIntent

/**
 * Consumes generic overlay intents (forwarded by the accessibility service)
 * and maps them to session-scoped dictation commands. Implemented by the
 * dictation service.
 */
interface OverlayIntentHandler {
    fun onIntent(intent: OverlayIntent)
}