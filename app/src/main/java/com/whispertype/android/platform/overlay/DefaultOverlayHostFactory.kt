package com.whispertype.android.platform.overlay

import android.content.Context
import com.whispertype.android.core.contracts.OverlayController
import com.whispertype.android.core.model.DictationState
import com.whispertype.android.core.model.TargetEligibility
import kotlinx.coroutines.flow.Flow

/**
 * Default [OverlayHostFactory]; public so the runtime service can construct a
 * [PersistentOverlayHost] on its own service context and stable owners.
 */
class DefaultOverlayHostFactory : OverlayHostFactory {
    override fun create(
        serviceContext: Context,
        owners: OverlayOwners,
        sessionState: Flow<DictationState>,
        eligibility: Flow<TargetEligibility>,
    ): OverlayController = PersistentOverlayHost(
        serviceContext = serviceContext,
        owners = owners,
        sessionState = sessionState,
        eligibility = eligibility,
    )
}
