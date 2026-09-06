package com.whispertype.android.platform.runtime

import com.whispertype.android.core.model.DictationState
import com.whispertype.android.core.model.TargetEligibility

/**
 * Pure warm-pool eligibility rules shared by [FlowRuntimeService] and JVM tests.
 *
 * The pool stays eligible during [DictationState.Starting] (so a Ready socket can
 * be claimed before [DictationState.Listening]) and during retryable
 * [DictationState.Error] (so Retry can hit a rebuilt warm session). It is
 * suppressed only while a dictation turn actively owns the Live transport.
 */
object WarmPoolEligibility {

    /** True when an in-flight dictation owns the warm socket and prewarm must stop. */
    fun blocksWarmPool(state: DictationState, hasActiveHolder: Boolean): Boolean {
        if (!hasActiveHolder) return false
        return when (state) {
            is DictationState.Listening,
            is DictationState.Finalizing,
            is DictationState.Inserting,
            -> true
            is DictationState.Error -> !state.failure.retryAllowed
            else -> false
        }
    }

    /** True when a new warm session may be prewarmed for the current editor context. */
    fun compute(
        eligibility: TargetEligibility,
        blocksWarmPool: Boolean,
        appEnabled: Boolean,
        hasApiKey: Boolean,
    ): Boolean =
        eligibility.serviceConnected &&
            eligibility.editorFocused &&
            !eligibility.editorSecure &&
            !eligibility.editorUncertain &&
            eligibility.microphoneGranted &&
            appEnabled &&
            !blocksWarmPool &&
            hasApiKey
}
