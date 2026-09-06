package com.whispertype.android.core.contracts

import com.whispertype.android.core.model.OverlayIntent
import com.whispertype.android.core.model.OverlayUiState
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The persistent overlay host. Attached once per accessibility-service
 * lifetime on a display-specific context. The overlay renders
 * [uiState] and emits generic [OverlayIntent]s; it does not orchestrate
 * dictation.
 */
interface OverlayController {
    val uiState: StateFlow<OverlayUiState>
    val intents: SharedFlow<OverlayIntent>

    /** Adds the persistent window; idempotent. Surfaces failures via [uiState]. */
    fun attach()

    /** Removes the persistent window; idempotent. */
    fun detach()
}