package com.whispertype.android.platform.overlay

import android.content.Context
import com.whispertype.android.core.contracts.OverlayController
import com.whispertype.android.core.model.DictationState
import com.whispertype.android.core.model.TargetEligibility
import kotlinx.coroutines.flow.Flow

/**
 * Creates the persistent overlay host. Owned by the main-process runtime
 * workstream; consumed by [com.whispertype.android.platform.runtime.FlowRuntimeService],
 * which attaches it once per service lifetime on its own service context and
 * stable owners (§4.1 Wispr FlowService parity). The accessibility process no
 * longer owns the overlay window.
 */
interface OverlayHostFactory {
    fun create(
        serviceContext: Context,
        owners: OverlayOwners,
        sessionState: Flow<DictationState>,
        eligibility: Flow<TargetEligibility>,
    ): OverlayController
}