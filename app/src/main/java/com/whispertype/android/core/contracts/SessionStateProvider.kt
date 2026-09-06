package com.whispertype.android.core.contracts

import com.whispertype.android.core.model.DictationState
import kotlinx.coroutines.flow.Flow

/** Provides the current dictation session state to observers (overlay, UI). */
interface SessionStateProvider {
    val state: Flow<DictationState>
}