package com.whispertype.android.data.settings

import com.whispertype.android.core.dictionary.DictionaryEntry
import com.whispertype.android.core.model.AudioSourcePreference
import com.whispertype.android.core.model.LanguageMode
import com.whispertype.android.core.model.TranscriptionMode
import kotlinx.coroutines.flow.Flow

/**
 * Settings consumed by working runtime consumers only (PRD FR-10). No setting
 * exists here without an implementation and a test.
 */
interface SettingsProvider {
    val speechMode: Flow<LanguageMode>
    val transcriptionMode: Flow<TranscriptionMode>
    val historyEnabled: Flow<Boolean>
    val historyRetentionDays: Flow<Int>
    val appEnabled: Flow<Boolean>
    val onboardingCompleted: Flow<Boolean>
    val autoStopSeconds: Flow<Int>

    /** 0.6.0: recording input device; [AudioSourcePreference.DEFAULT] is the phone mic. */
    val audioSourcePreference: Flow<AudioSourcePreference>
    val dictionary: Flow<List<DictionaryEntry>>
    val bubbleX: Flow<Float?>
    val bubbleY: Flow<Float?>

    /** 0.4.2 dark mode for the app screens. */
    val darkMode: Flow<Boolean>

    /** 0.4.2 bubble visual settings (Wispr-style). */
    val bubbleSizeDp: Flow<Int>
    val bubbleOpacityPercent: Flow<Int>
    val miniDotEnabled: Flow<Boolean>

    /** 0.4.2: seconds of idle before the bubble auto-minimizes to the mini dot. */
    val miniDotDelaySeconds: Flow<Int>

    /** 0.6.0 experimental: split the recording at pauses so the model echoes
     *  each segment while the user keeps talking. Default off; requires
     *  on-device validation. */
    val segmentAtSilence: Flow<Boolean>

    /**
     * 0.5.2: records that the accessibility service has connected at least once.
     * Internal watchdog gate so the "service dropped" notification is never shown
     * to a fresh install that simply never enabled the service.
     */
    val a11yHasConnectedOnce: Flow<Boolean>

    /**
     * Physical-keyboard hotkey keycode (android.view.KeyEvent key code) that
     * toggles dictation start/complete. 0 disables the hotkey.
     */
    val hotkeyKeycode: Flow<Int>

    /**
     * Modifier mask for the hotkey (see [com.whispertype.android.core.model.HotkeyShortcut]).
     * 0 = no modifier required.
     */
    val hotkeyModifiers: Flow<Int>
}