package com.whispertype.android.core.model

/**
 * Which input device dictation captures from. [DEFAULT] is always the phone
 * microphone; [BLUETOOTH] is strict opt-in — the connected bluetooth headset is
 * used only when the user explicitly selects it, and falls back to the phone mic
 * when no headset is connected.
 */
enum class AudioSourcePreference {
    /** Phone microphone — the default; never routes to another device. */
    DEFAULT,

    /** Prefer the connected bluetooth headset's mic when one is available. */
    BLUETOOTH,
}
