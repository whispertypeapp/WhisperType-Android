package com.whispertype.android.platform.accessibility

import com.whispertype.android.core.model.TargetEligibility

/**
 * Pure mapping from the tracked focus fields and configuration inputs to an
 * immutable [TargetEligibility]. Extracting this keeps the fail-closed
 * eligibility computation host-testable without any Android runtime
 * (see [EligibilityMapperTest]).
 */
object EligibilityMapper {

    /**
     * Builds a [TargetEligibility] from raw tracked state. Anything less than a
     * confident [Classification.SAFE] editor is reflected through
     * [TargetEligibility.editorSecure] / [TargetEligibility.editorUncertain],
     * which fails closed in [TargetEligibility.eligible].
     */
    fun toEligibility(
        serviceConnected: Boolean,
        editorFocused: Boolean,
        classification: Classification,
        keyboardVisible: Boolean,
        microphoneGranted: Boolean,
        apiKeyConfigured: Boolean,
        appEnabled: Boolean,
        sessionActive: Boolean,
        displayId: Int = TargetEligibility.DEFAULT_DISPLAY_ID,
    ): TargetEligibility = TargetEligibility(
        serviceConnected = serviceConnected,
        editorFocused = editorFocused,
        editorSecure = classification == Classification.SECURE,
        editorUncertain = classification == Classification.UNCERTAIN,
        keyboardVisible = keyboardVisible,
        microphoneGranted = microphoneGranted,
        apiKeyConfigured = apiKeyConfigured,
        appEnabled = appEnabled,
        sessionActive = sessionActive,
        displayId = displayId,
    )
}
