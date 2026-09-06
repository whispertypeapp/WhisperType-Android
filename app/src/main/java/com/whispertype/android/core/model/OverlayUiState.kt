package com.whispertype.android.core.model

/** Pure rendering input: current eligibility plus current session state. */
data class OverlayUiState(
    val eligibility: TargetEligibility,
    val state: DictationState,
) {
    companion object {
        val Hidden = OverlayUiState(TargetEligibility.Ineligible, DictationState.Unavailable)
    }
}