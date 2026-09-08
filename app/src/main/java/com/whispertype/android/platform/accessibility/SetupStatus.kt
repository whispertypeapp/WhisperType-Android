package com.whispertype.android.platform.accessibility

import com.whispertype.android.core.model.TargetEligibility

/**
 * Filters eligibility and runtime flags to **setup failures only** — the ones
 * worth a banner on Home. Expected in-app context reasons (no focus, keyboard
 * hidden, secure field, session active) are omitted.
 */
object SetupStatus {

    /** Reason codes that should interrupt the user on Home. */
    private val HOME_SETUP_REASONS = setOf(
        EligibilityExplanation.REASON_SERVICE_NOT_CONNECTED,
        EligibilityExplanation.REASON_MICROPHONE_NOT_GRANTED,
        EligibilityExplanation.REASON_API_KEY_MISSING,
        EligibilityExplanation.REASON_APP_DISABLED,
    )

    /** Maps a setup reason to a short banner title resource, or 0 if unknown. */
    fun homeBannerReasons(
        eligibility: TargetEligibility,
        overlayGranted: Boolean,
        runtimeRunning: Boolean,
        notificationsGranted: Boolean,
    ): List<String> = buildList {
        addAll(
            EligibilityExplanation.blockingReasons(eligibility).filter { it in HOME_SETUP_REASONS },
        )
        if (!overlayGranted) add(REASON_OVERLAY_NOT_GRANTED)
        if (!runtimeRunning) add(REASON_RUNTIME_NOT_RUNNING)
    }

    /** All gates for Settings → System (includes setup + contextual, except
     *  focus/keyboard while diagnosing from inside the app). */
    fun systemGateReasons(
        eligibility: TargetEligibility,
        overlayGranted: Boolean,
        runtimeRunning: Boolean,
        notificationsGranted: Boolean,
        apiKeyConfigured: Boolean,
    ): List<SystemGate> = listOf(
        SystemGate("overlay", overlayGranted),
        SystemGate("runtime", runtimeRunning),
        SystemGate(
            "accessibility",
            eligibility.serviceConnected,
        ),
        SystemGate("gemini_key", apiKeyConfigured),
        SystemGate("microphone", eligibility.microphoneGranted),
    )

    data class SystemGate(val id: String, val on: Boolean)

    const val REASON_OVERLAY_NOT_GRANTED = "overlay_not_granted"
    const val REASON_RUNTIME_NOT_RUNNING = "runtime_not_running"
    const val REASON_NOTIFICATIONS_NOT_GRANTED = "notifications_not_granted"
}
