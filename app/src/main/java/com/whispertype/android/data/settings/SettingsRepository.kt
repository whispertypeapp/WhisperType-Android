package com.whispertype.android.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.whispertype.android.core.dictionary.DictionaryEntry
import com.whispertype.android.core.model.AudioSourcePreference
import com.whispertype.android.core.model.LanguageMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "settings",
)

private val dictionaryJson: Json = Json { ignoreUnknownKeys = true }

/**
 * [SettingsProvider] backed by DataStore preferences. Primary constructor takes
 * the [DataStore] directly so the behavior is unit-testable on the JVM with an
 * in-memory/preference DataStore; the Context constructor wires the real,
 * app-scoped singleton store.
 */
class SettingsRepository(private val dataStore: DataStore<Preferences>) : SettingsProvider {

    constructor(context: Context) : this(context.settingsDataStore)

    private object Keys {
        val speechMode = stringPreferencesKey("speech_mode")
        val historyEnabled = booleanPreferencesKey("history_enabled")
        val historyRetentionDays = intPreferencesKey("history_retention_days")
        val appEnabled = booleanPreferencesKey(SettingsRepository.KEY_APP_ENABLED)
        val onboardingCompleted = booleanPreferencesKey("onboarding_completed")
        val autoStopSeconds = intPreferencesKey("auto_stop_seconds")
        val audioSourcePreference = stringPreferencesKey("audio_source_preference")
        val dictionary = stringPreferencesKey("dictionary")
        val bubbleX = floatPreferencesKey("bubble_x")
        val bubbleY = floatPreferencesKey("bubble_y")
        val bubbleSizeDp = intPreferencesKey("bubble_size_dp")
        val bubbleOpacityPercent = intPreferencesKey("bubble_opacity_percent")
        val miniDotEnabled = booleanPreferencesKey("mini_dot_enabled")
        val miniDotDelaySeconds = intPreferencesKey("mini_dot_delay_seconds")
        val segmentAtSilence = booleanPreferencesKey("segment_at_silence")
        val a11yHasConnectedOnce = booleanPreferencesKey("a11y_has_connected_once")
        val darkMode = booleanPreferencesKey("dark_mode")
        val hotkeyKeycode = intPreferencesKey(SettingsRepository.KEY_HOTKEY_KEYCODE)
        val hotkeyModifiers = intPreferencesKey(SettingsRepository.KEY_HOTKEY_MODIFIERS)
    }

    override val speechMode: Flow<LanguageMode> =
        dataStore.data.map { prefs ->
            val stored = prefs[Keys.speechMode]
            LanguageMode.entries.firstOrNull { it.name == stored } ?: LanguageMode.HINGLISH
        }

    override val historyEnabled: Flow<Boolean> =
        dataStore.data.map { it[Keys.historyEnabled] ?: true }

    override val historyRetentionDays: Flow<Int> =
        dataStore.data.map { it[Keys.historyRetentionDays] ?: DEFAULT_RETENTION_DAYS }

    override val appEnabled: Flow<Boolean> =
        dataStore.data.map { it[Keys.appEnabled] ?: true }

    override val onboardingCompleted: Flow<Boolean> =
        dataStore.data.map { it[Keys.onboardingCompleted] ?: false }

    override val autoStopSeconds: Flow<Int> =
        dataStore.data.map { it[Keys.autoStopSeconds] ?: DEFAULT_AUTO_STOP_SECONDS }

    override val audioSourcePreference: Flow<AudioSourcePreference> =
        dataStore.data.map { prefs ->
            val stored = prefs[Keys.audioSourcePreference]
            AudioSourcePreference.entries.firstOrNull { it.name == stored } ?: DEFAULT_AUDIO_SOURCE_PREFERENCE
        }

    override val dictionary: Flow<List<DictionaryEntry>> =
        dataStore.data.map { decodeDictionary(it[Keys.dictionary]) }

    override val bubbleX: Flow<Float?> =
        dataStore.data.map { it[Keys.bubbleX] }

    override val bubbleY: Flow<Float?> =
        dataStore.data.map { it[Keys.bubbleY] }

    override val bubbleSizeDp: Flow<Int> =
        dataStore.data.map { (it[Keys.bubbleSizeDp] ?: DEFAULT_BUBBLE_SIZE_DP).coerceIn(MIN_BUBBLE_SIZE_DP, MAX_BUBBLE_SIZE_DP) }

    override val bubbleOpacityPercent: Flow<Int> =
        dataStore.data.map { (it[Keys.bubbleOpacityPercent] ?: DEFAULT_BUBBLE_OPACITY_PERCENT).coerceIn(MIN_BUBBLE_OPACITY_PERCENT, MAX_BUBBLE_OPACITY_PERCENT) }

    override val miniDotEnabled: Flow<Boolean> =
        dataStore.data.map { it[Keys.miniDotEnabled] ?: true }

    override val miniDotDelaySeconds: Flow<Int> =
        dataStore.data.map { (it[Keys.miniDotDelaySeconds] ?: DEFAULT_MINI_DOT_DELAY_SECONDS).coerceIn(MIN_MINI_DOT_DELAY_SECONDS, MAX_MINI_DOT_DELAY_SECONDS) }

    override val segmentAtSilence: Flow<Boolean> =
        dataStore.data.map { it[Keys.segmentAtSilence] ?: false }

    override val darkMode: Flow<Boolean> =
        dataStore.data.map { it[Keys.darkMode] ?: false }

    override val hotkeyKeycode: Flow<Int> =
        dataStore.data.map { it[Keys.hotkeyKeycode] ?: DEFAULT_HOTKEY_KEYCODE }

    override val hotkeyModifiers: Flow<Int> =
        dataStore.data.map { it[Keys.hotkeyModifiers] ?: DEFAULT_HOTKEY_MODIFIERS }

    override val a11yHasConnectedOnce: Flow<Boolean> =
        dataStore.data.map { it[Keys.a11yHasConnectedOnce] ?: false }

    suspend fun setA11yHasConnectedOnce(connected: Boolean) {
        dataStore.edit { it[Keys.a11yHasConnectedOnce] = connected }
    }

    suspend fun setSpeechMode(mode: LanguageMode) {
        dataStore.edit { it[Keys.speechMode] = mode.name }
    }

    suspend fun setHistoryEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.historyEnabled] = enabled }
    }

    suspend fun setHistoryRetentionDays(days: Int) {
        dataStore.edit { it[Keys.historyRetentionDays] = days }
    }

    suspend fun setAppEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.appEnabled] = enabled }
    }

    suspend fun setOnboardingCompleted(completed: Boolean) {
        dataStore.edit { it[Keys.onboardingCompleted] = completed }
    }

    suspend fun setAutoStopSeconds(seconds: Int) {
        dataStore.edit { it[Keys.autoStopSeconds] = seconds }
    }

    suspend fun setAudioSourcePreference(preference: AudioSourcePreference) {
        dataStore.edit { it[Keys.audioSourcePreference] = preference.name }
    }

    suspend fun addDictionaryEntry(entry: DictionaryEntry) {
        dataStore.edit { prefs ->
            val current = decodeDictionary(prefs[Keys.dictionary])
            prefs[Keys.dictionary] = encodeDictionary(current.filterNot { it.match == entry.match } + entry)
        }
    }

    suspend fun removeDictionaryEntry(match: String) {
        dataStore.edit { prefs ->
            val current = decodeDictionary(prefs[Keys.dictionary])
            prefs[Keys.dictionary] = encodeDictionary(current.filterNot { it.match == match })
        }
    }

    suspend fun clearDictionary() {
        dataStore.edit { it.remove(Keys.dictionary) }
    }

    suspend fun setBubblePosition(x: Float?, y: Float?) {
        dataStore.edit {
            if (x == null) it.remove(Keys.bubbleX) else it[Keys.bubbleX] = x
            if (y == null) it.remove(Keys.bubbleY) else it[Keys.bubbleY] = y
        }
    }

    suspend fun resetBubblePosition() {
        dataStore.edit {
            it.remove(Keys.bubbleX)
            it.remove(Keys.bubbleY)
        }
    }

    suspend fun setBubbleSizeDp(dp: Int) {
        dataStore.edit { it[Keys.bubbleSizeDp] = dp.coerceIn(MIN_BUBBLE_SIZE_DP, MAX_BUBBLE_SIZE_DP) }
    }

    suspend fun setBubbleOpacityPercent(percent: Int) {
        dataStore.edit { it[Keys.bubbleOpacityPercent] = percent.coerceIn(MIN_BUBBLE_OPACITY_PERCENT, MAX_BUBBLE_OPACITY_PERCENT) }
    }

    suspend fun setMiniDotEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.miniDotEnabled] = enabled }
    }

    suspend fun setSegmentAtSilence(enabled: Boolean) {
        dataStore.edit { it[Keys.segmentAtSilence] = enabled }
    }

    suspend fun setMiniDotDelaySeconds(seconds: Int) {
        dataStore.edit { it[Keys.miniDotDelaySeconds] = seconds.coerceIn(MIN_MINI_DOT_DELAY_SECONDS, MAX_MINI_DOT_DELAY_SECONDS) }
    }

    suspend fun setDarkMode(enabled: Boolean) {
        dataStore.edit { it[Keys.darkMode] = enabled }
    }

    suspend fun setHotkeyKeycode(keycode: Int) {
        dataStore.edit { it[Keys.hotkeyKeycode] = keycode }
    }

    suspend fun setHotkeyModifiers(modifiers: Int) {
        dataStore.edit { it[Keys.hotkeyModifiers] = modifiers }
    }

    /** Decodes the stored dictionary JSON; malformed or unset input yields an empty list. */
    private fun decodeDictionary(raw: String?): List<DictionaryEntry> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            dictionaryJson.decodeFromString(DictionaryPayload.serializer(), raw)
                .entries
                .map { DictionaryEntry(match = it.match, replace = it.replace) }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun encodeDictionary(entries: List<DictionaryEntry>): String =
        dictionaryJson.encodeToString(
            DictionaryPayload.serializer(),
            DictionaryPayload(entries = entries.map { EntryDto(match = it.match, replace = it.replace) }),
        )

    companion object {
        /** Stable wire key for the "App enabled" kill-switch setting; shared with [com.whispertype.android.core.settings.PreferencesFileReader]. */
        const val KEY_APP_ENABLED = "app_enabled"

        /** Stable wire key for the physical-keyboard hotkey; shared with [com.whispertype.android.core.settings.PreferencesFileReader]. */
        const val KEY_HOTKEY_KEYCODE = "hotkey_keycode"

        /** Stable wire key for the hotkey modifier mask; shared with [com.whispertype.android.core.settings.PreferencesFileReader]. */
        const val KEY_HOTKEY_MODIFIERS = "hotkey_modifiers"

        /** Physical-keyboard hotkey: the grave/backtick key toggles dictation. */
        val DEFAULT_HOTKEY_KEYCODE = android.view.KeyEvent.KEYCODE_GRAVE

        /** Hotkey default: no modifier required. */
        const val DEFAULT_HOTKEY_MODIFIERS = 0
        const val DEFAULT_RETENTION_DAYS = 30
        const val DEFAULT_AUTO_STOP_SECONDS = 60
        const val DEFAULT_BUBBLE_SIZE_DP = 38
        const val MIN_BUBBLE_SIZE_DP = 24
        const val MAX_BUBBLE_SIZE_DP = 72
        const val DEFAULT_BUBBLE_OPACITY_PERCENT = 80
        const val MIN_BUBBLE_OPACITY_PERCENT = 10
        const val MAX_BUBBLE_OPACITY_PERCENT = 100
        const val DEFAULT_MINI_DOT_DELAY_SECONDS = 5
        const val MIN_MINI_DOT_DELAY_SECONDS = 1
        const val MAX_MINI_DOT_DELAY_SECONDS = 15
        val DEFAULT_AUDIO_SOURCE_PREFERENCE = AudioSourcePreference.DEFAULT
    }
}

@Serializable
private data class DictionaryPayload(
    val entries: List<EntryDto> = emptyList(),
)

@Serializable
private data class EntryDto(
    val match: String,
    val replace: String,
)