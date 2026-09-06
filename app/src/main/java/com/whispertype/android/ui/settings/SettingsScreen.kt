package com.whispertype.android.ui.settings

import android.media.AudioManager
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.whispertype.android.BuildConfig
import com.whispertype.android.R
import com.whispertype.android.audio.AudioInputDevices
import com.whispertype.android.core.audio.AudioInputSelection
import com.whispertype.android.core.model.AudioSourcePreference
import com.whispertype.android.core.model.HotkeyShortcut
import com.whispertype.android.core.model.LanguageMode
import com.whispertype.android.data.secrets.KeyProvider
import com.whispertype.android.data.settings.SettingsRepository
import com.whispertype.android.platform.accessibility.SetupStatus
import com.whispertype.android.ui.theme.StudioColors
import com.whispertype.android.ui.theme.StudioCta
import com.whispertype.android.ui.theme.StudioGhostCta
import com.whispertype.android.ui.theme.StudioPageTitle
import com.whispertype.android.ui.theme.StudioSwitch
import com.whispertype.android.ui.theme.StudioType
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    settings: SettingsRepository,
    keyProvider: KeyProvider,
    scrollToGemini: Boolean = false,
    onGeminiScrollDone: () -> Unit = {},
    openSystemPage: Boolean = false,
    onSystemPageOpened: () -> Unit = {},
    systemGates: List<SetupStatus.SystemGate> = emptyList(),
    onFixGate: (String) -> Unit = {},
) {
    var page by remember { mutableStateOf(SettingsPage.Main) }
    LaunchedEffect(openSystemPage) {
        if (openSystemPage) {
            page = SettingsPage.System
            onSystemPageOpened()
        }
    }
    LaunchedEffect(scrollToGemini) {
        if (scrollToGemini) {
            page = SettingsPage.Gemini
            onGeminiScrollDone()
        }
    }
    if (page != SettingsPage.Main) {
        BackHandler { page = SettingsPage.Main }
    }
    when (page) {
        SettingsPage.System -> SystemStatusScreen(
            gates = systemGates,
            onBack = { page = SettingsPage.Main },
            onFixGate = onFixGate,
        )
        SettingsPage.Main -> SettingsMainScreen(
            settings = settings,
            keyProvider = keyProvider,
            systemGates = systemGates,
            onOpen = { page = it },
        )
        SettingsPage.Speech -> SpeechPage(settings) { page = SettingsPage.Main }
        SettingsPage.AutoStop -> AutoStopPage(settings) { page = SettingsPage.Main }
        SettingsPage.Microphone -> MicrophonePage(settings) { page = SettingsPage.Main }
        SettingsPage.Hotkey -> HotkeyPage(settings) { page = SettingsPage.Main }
        SettingsPage.Bubble -> BubblePage(settings) { page = SettingsPage.Main }
        SettingsPage.MiniDot -> MiniDotPage(settings) { page = SettingsPage.Main }
        SettingsPage.Gemini -> GeminiPage(keyProvider) { page = SettingsPage.Main }
        SettingsPage.History -> HistoryPrivacyPage(settings) { page = SettingsPage.Main }
    }
}

private enum class SettingsPage {
    Main, System, Speech, AutoStop, Microphone, Hotkey, Bubble, MiniDot, Gemini, History
}

@Composable
private fun SettingsMainScreen(
    settings: SettingsRepository,
    keyProvider: KeyProvider,
    systemGates: List<SetupStatus.SystemGate>,
    onOpen: (SettingsPage) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val appEnabled by settings.appEnabled.collectAsStateWithLifecycle(initialValue = true)
    val speechMode by settings.speechMode.collectAsStateWithLifecycle(initialValue = LanguageMode.ENGLISH)
    val autoStopSeconds by settings.autoStopSeconds
        .collectAsStateWithLifecycle(initialValue = SettingsRepository.DEFAULT_AUTO_STOP_SECONDS)
    val segmentAtSilence by settings.segmentAtSilence.collectAsStateWithLifecycle(initialValue = false)
    val audioSourcePreference by settings.audioSourcePreference
        .collectAsStateWithLifecycle(initialValue = SettingsRepository.DEFAULT_AUDIO_SOURCE_PREFERENCE)
    val hotkeyKeycode by settings.hotkeyKeycode
        .collectAsStateWithLifecycle(initialValue = SettingsRepository.DEFAULT_HOTKEY_KEYCODE)
    val hotkeyModifiers by settings.hotkeyModifiers
        .collectAsStateWithLifecycle(initialValue = SettingsRepository.DEFAULT_HOTKEY_MODIFIERS)
    val bubbleSizeDp by settings.bubbleSizeDp
        .collectAsStateWithLifecycle(initialValue = SettingsRepository.DEFAULT_BUBBLE_SIZE_DP)
    val bubbleOpacity by settings.bubbleOpacityPercent
        .collectAsStateWithLifecycle(initialValue = SettingsRepository.DEFAULT_BUBBLE_OPACITY_PERCENT)
    val miniDotEnabled by settings.miniDotEnabled.collectAsStateWithLifecycle(initialValue = true)
    val miniDotDelay by settings.miniDotDelaySeconds
        .collectAsStateWithLifecycle(initialValue = SettingsRepository.DEFAULT_MINI_DOT_DELAY_SECONDS)
    val historyEnabled by settings.historyEnabled.collectAsStateWithLifecycle(initialValue = true)
    val retentionDays by settings.historyRetentionDays
        .collectAsStateWithLifecycle(initialValue = SettingsRepository.DEFAULT_RETENTION_DAYS)
    val hasKey = remember { keyProvider.hasKey() }
    val issues = systemGates.count { !it.on }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        StudioPageTitle(stringResource(R.string.settings_title), modifier = Modifier.padding(top = 6.dp))

        SettingsGroupLabel(stringResource(R.string.settings_section_whispertype))
        SettingsGroupCard {
            SettingsNavRow(
                title = stringResource(R.string.settings_app_enabled),
                subtitle = stringResource(R.string.settings_app_enabled_short),
                showChevron = false,
                trailing = {
                    StudioSwitch(
                        checked = appEnabled,
                        onCheckedChange = { scope.launch { settings.setAppEnabled(it) } },
                    )
                },
            )
        }

        SettingsGroupLabel(stringResource(R.string.settings_section_dictation))
        SettingsGroupCard {
            SettingsNavRow(
                title = stringResource(R.string.settings_speech_mode),
                subtitle = stringResource(
                    if (speechMode == LanguageMode.HINGLISH) {
                        R.string.language_hinglish
                    } else {
                        R.string.language_english
                    },
                ),
                onClick = { onOpen(SettingsPage.Speech) },
            )
            SettingsDivider()
            SettingsNavRow(
                title = stringResource(R.string.settings_auto_stop),
                subtitle = autoStopFriendly(autoStopSeconds),
                onClick = { onOpen(SettingsPage.AutoStop) },
            )
            SettingsDivider()
            SettingsNavRow(
                title = stringResource(R.string.settings_audio_source),
                subtitle = stringResource(audioSourceLabelRes(audioSourcePreference)),
                onClick = { onOpen(SettingsPage.Microphone) },
            )
            SettingsDivider()
            SettingsNavRow(
                title = stringResource(R.string.settings_hotkey),
                subtitle = hotkeyLabel(hotkeyKeycode, hotkeyModifiers),
                onClick = { onOpen(SettingsPage.Hotkey) },
            )
            SettingsDivider()
            SettingsNavRow(
                title = stringResource(R.string.settings_segment_at_silence),
                subtitle = stringResource(R.string.settings_segment_experimental),
                showChevron = false,
                trailing = {
                    StudioSwitch(
                        checked = segmentAtSilence,
                        onCheckedChange = { scope.launch { settings.setSegmentAtSilence(it) } },
                    )
                },
            )
        }

        SettingsGroupLabel(stringResource(R.string.settings_section_bubble))
        SettingsGroupCard {
            SettingsNavRow(
                title = stringResource(R.string.settings_bubble_size_fade),
                subtitle = "${bubbleSizeLabel(bubbleSizeDp)} · $bubbleOpacity%",
                onClick = { onOpen(SettingsPage.Bubble) },
            )
            SettingsDivider()
            SettingsNavRow(
                title = stringResource(R.string.settings_mini_dot),
                subtitle = if (miniDotEnabled) {
                    pluralStringResource(R.plurals.settings_mini_dot_after, miniDotDelay, miniDotDelay)
                } else {
                    stringResource(R.string.status_off)
                },
                onClick = { onOpen(SettingsPage.MiniDot) },
            )
        }

        SettingsGroupLabel(stringResource(R.string.settings_section_account_privacy))
        SettingsGroupCard {
            SettingsNavRow(
                title = stringResource(R.string.settings_gemini_key),
                subtitle = stringResource(
                    if (hasKey) R.string.settings_key_on_device else R.string.settings_key_missing,
                ),
                onClick = { onOpen(SettingsPage.Gemini) },
            )
            SettingsDivider()
            SettingsNavRow(
                title = stringResource(R.string.settings_history),
                subtitle = if (historyEnabled) {
                    pluralStringResource(R.plurals.settings_history_keep_days, retentionDays, retentionDays)
                } else {
                    stringResource(R.string.status_off)
                },
                onClick = { onOpen(SettingsPage.History) },
            )
        }

        SettingsGroupLabel(stringResource(R.string.settings_section_system))
        SettingsGroupCard {
            SettingsNavRow(
                title = stringResource(R.string.settings_system_row),
                subtitle = if (issues == 0) {
                    stringResource(R.string.settings_system_all_set)
                } else {
                    stringResource(R.string.settings_system_needs_attention)
                },
                onClick = { onOpen(SettingsPage.System) },
            )
            SettingsDivider()
            SettingsNavRow(
                title = stringResource(R.string.settings_about),
                subtitle = BuildConfig.VERSION_NAME,
                showChevron = false,
            )
        }
    }
}

@Composable
private fun SpeechPage(settings: SettingsRepository, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val speechMode by settings.speechMode.collectAsStateWithLifecycle(initialValue = LanguageMode.ENGLISH)
    SettingsDrillScaffold(title = stringResource(R.string.settings_speech_mode), onBack = onBack) {
        SettingsGroupCard {
            LanguageMode.entries.forEachIndexed { index, mode ->
                if (index > 0) SettingsDivider()
                SettingsNavRow(
                    title = stringResource(
                        if (mode == LanguageMode.HINGLISH) R.string.language_hinglish else R.string.language_english,
                    ),
                    value = if (speechMode == mode) stringResource(R.string.status_on) else null,
                    showChevron = false,
                    onClick = { scope.launch { settings.setSpeechMode(mode) } },
                )
            }
        }
    }
}

@Composable
private fun AutoStopPage(settings: SettingsRepository, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val autoStopSeconds by settings.autoStopSeconds
        .collectAsStateWithLifecycle(initialValue = SettingsRepository.DEFAULT_AUTO_STOP_SECONDS)
    SettingsDrillScaffold(title = stringResource(R.string.settings_auto_stop), onBack = onBack) {
        SettingsGroupCard {
            AUTO_STOP_OPTIONS.forEachIndexed { index, seconds ->
                if (index > 0) SettingsDivider()
                SettingsNavRow(
                    title = autoStopFriendly(seconds),
                    value = if (autoStopSeconds == seconds) stringResource(R.string.status_on) else null,
                    showChevron = false,
                    onClick = { scope.launch { settings.setAutoStopSeconds(seconds) } },
                )
            }
        }
    }
}

@Composable
private fun MicrophonePage(settings: SettingsRepository, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val audioSourcePreference by settings.audioSourcePreference
        .collectAsStateWithLifecycle(initialValue = SettingsRepository.DEFAULT_AUDIO_SOURCE_PREFERENCE)
    SettingsDrillScaffold(title = stringResource(R.string.settings_audio_source), onBack = onBack) {
        SettingsGroupCard {
            AudioSourcePreference.entries.forEachIndexed { index, preference ->
                if (index > 0) SettingsDivider()
                SettingsNavRow(
                    title = stringResource(audioSourceLabelRes(preference)),
                    value = if (audioSourcePreference == preference) stringResource(R.string.status_on) else null,
                    showChevron = false,
                    onClick = { scope.launch { settings.setAudioSourcePreference(preference) } },
                )
            }
        }
        if (audioSourcePreference == AudioSourcePreference.BLUETOOTH) {
            val name = remember(context, audioSourcePreference) {
                context.getSystemService(AudioManager::class.java)?.let { audioManager ->
                    AudioInputSelection.selectBluetoothHeadset(
                        AudioInputDevices(audioManager).listAll(),
                    )?.name
                }
            }
            Text(
                text = if (name != null) {
                    stringResource(R.string.settings_audio_source_bluetooth_found, name)
                } else {
                    stringResource(R.string.settings_audio_source_bluetooth_missing)
                },
                style = StudioType.why,
                modifier = Modifier.padding(top = 12.dp, start = 4.dp),
            )
        }
    }
}

@Composable
private fun HotkeyPage(settings: SettingsRepository, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val hotkeyKeycode by settings.hotkeyKeycode
        .collectAsStateWithLifecycle(initialValue = SettingsRepository.DEFAULT_HOTKEY_KEYCODE)
    val hotkeyModifiers by settings.hotkeyModifiers
        .collectAsStateWithLifecycle(initialValue = SettingsRepository.DEFAULT_HOTKEY_MODIFIERS)
    var capturing by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(capturing) {
        if (capturing) focusRequester.requestFocus()
    }
    SettingsDrillScaffold(title = stringResource(R.string.settings_hotkey), onBack = onBack) {
        Text(
            text = hotkeyLabel(hotkeyKeycode, hotkeyModifiers),
            style = StudioType.heroSub,
            modifier = Modifier.padding(bottom = 12.dp),
        )
        Box(
            modifier = Modifier
                .focusRequester(focusRequester)
                .focusable()
                .onKeyEvent { event ->
                    if (!capturing) return@onKeyEvent false
                    val native = event.nativeKeyEvent
                    when (native.action) {
                        KeyEvent.ACTION_DOWN -> {
                            when (native.keyCode) {
                                KeyEvent.KEYCODE_ESCAPE -> {
                                    capturing = false
                                    true
                                }
                                in HOTKEY_MODIFIER_KEYS -> true
                                else -> {
                                    scope.launch {
                                        settings.setHotkeyKeycode(native.keyCode)
                                        settings.setHotkeyModifiers(
                                            native.metaState and HotkeyShortcut.MODIFIER_MASK,
                                        )
                                    }
                                    capturing = false
                                    true
                                }
                            }
                        }
                        else -> false
                    }
                },
        ) {
            if (capturing) {
                Text(stringResource(R.string.settings_hotkey_capture), style = StudioType.why)
            } else {
                StudioCta(
                    text = stringResource(R.string.settings_hotkey_capture_short),
                    onClick = { capturing = true },
                )
            }
        }
    }
}

@Composable
private fun BubblePage(settings: SettingsRepository, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val bubbleSizeDp by settings.bubbleSizeDp
        .collectAsStateWithLifecycle(initialValue = SettingsRepository.DEFAULT_BUBBLE_SIZE_DP)
    val bubbleOpacity by settings.bubbleOpacityPercent
        .collectAsStateWithLifecycle(initialValue = SettingsRepository.DEFAULT_BUBBLE_OPACITY_PERCENT)
    SettingsDrillScaffold(title = stringResource(R.string.settings_bubble_size_fade), onBack = onBack) {
        SettingsGroupCard {
            StudioSliderRow(
                title = stringResource(R.string.settings_bubble_size_friendly),
                description = bubbleSizeLabel(bubbleSizeDp),
                value = bubbleSizeDp.toFloat(),
                range = BUBBLE_SIZE_RANGE_DP_F,
                onValueChange = { scope.launch { settings.setBubbleSizeDp(it.roundToInt()) } },
            )
            SettingsDivider()
            StudioSliderRow(
                title = stringResource(R.string.settings_bubble_opacity_friendly),
                description = stringResource(R.string.settings_bubble_opacity_value, bubbleOpacity),
                value = bubbleOpacity.toFloat(),
                range = BUBBLE_OPACITY_RANGE_PERCENT_F,
                onValueChange = { scope.launch { settings.setBubbleOpacityPercent(it.roundToInt()) } },
            )
        }
        StudioGhostCta(
            text = stringResource(R.string.settings_bubble_reset),
            onClick = { scope.launch { settings.resetBubblePosition() } },
            modifier = Modifier.padding(top = 16.dp),
        )
    }
}

@Composable
private fun MiniDotPage(settings: SettingsRepository, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val miniDotEnabled by settings.miniDotEnabled.collectAsStateWithLifecycle(initialValue = true)
    val miniDotDelay by settings.miniDotDelaySeconds
        .collectAsStateWithLifecycle(initialValue = SettingsRepository.DEFAULT_MINI_DOT_DELAY_SECONDS)
    SettingsDrillScaffold(title = stringResource(R.string.settings_mini_dot), onBack = onBack) {
        SettingsGroupCard {
            SettingsNavRow(
                title = stringResource(R.string.settings_mini_dot),
                subtitle = stringResource(R.string.settings_mini_dot_desc),
                showChevron = false,
                trailing = {
                    StudioSwitch(
                        checked = miniDotEnabled,
                        onCheckedChange = { scope.launch { settings.setMiniDotEnabled(it) } },
                    )
                },
            )
            if (miniDotEnabled) {
                SettingsDivider()
                StudioSliderRow(
                    title = stringResource(R.string.settings_mini_dot_delay),
                    description = pluralStringResource(R.plurals.settings_mini_dot_after, miniDotDelay, miniDotDelay),
                    value = miniDotDelay.toFloat(),
                    range = MINI_DOT_DELAY_RANGE_SECONDS_F,
                    onValueChange = { scope.launch { settings.setMiniDotDelaySeconds(it.roundToInt()) } },
                )
            }
        }
    }
}

@Composable
private fun GeminiPage(keyProvider: KeyProvider, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var hasKey by remember { mutableStateOf(keyProvider.hasKey()) }
    var keyInput by remember { mutableStateOf("") }
    var keyFeedback by remember { mutableStateOf<String?>(null) }
    val keySavedMessage = stringResource(R.string.settings_key_saved)
    val keySaveFailedMessage = stringResource(R.string.settings_key_save_failed)
    val keyClearedMessage = stringResource(R.string.settings_key_cleared)
    SettingsDrillScaffold(title = stringResource(R.string.settings_gemini_key), onBack = onBack) {
        Text(
            text = stringResource(
                if (hasKey) R.string.settings_key_on_device else R.string.settings_key_missing,
            ),
            style = StudioType.why,
            modifier = Modifier.padding(bottom = 12.dp),
        )
        OutlinedTextField(
            value = keyInput,
            onValueChange = { keyInput = it },
            label = { Text(stringResource(R.string.settings_key_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        )
        StudioCta(
            text = stringResource(R.string.settings_key_save),
            onClick = {
                scope.launch {
                    val saved = keyProvider.storeKey(keyInput.trim())
                    keyFeedback = if (saved) {
                        hasKey = true
                        keyInput = ""
                        keySavedMessage
                    } else {
                        keySaveFailedMessage
                    }
                }
            },
        )
        if (hasKey) {
            StudioGhostCta(
                text = stringResource(R.string.settings_key_clear),
                onClick = {
                    scope.launch {
                        keyProvider.deleteKey()
                        hasKey = false
                        keyFeedback = keyClearedMessage
                    }
                },
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        keyFeedback?.let {
            Text(text = it, style = StudioType.rowDesc.copy(color = StudioColors.Accent), modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
private fun HistoryPrivacyPage(settings: SettingsRepository, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val historyEnabled by settings.historyEnabled.collectAsStateWithLifecycle(initialValue = true)
    val retentionDays by settings.historyRetentionDays
        .collectAsStateWithLifecycle(initialValue = SettingsRepository.DEFAULT_RETENTION_DAYS)
    SettingsDrillScaffold(title = stringResource(R.string.settings_history), onBack = onBack) {
        SettingsGroupCard {
            SettingsNavRow(
                title = stringResource(R.string.settings_history),
                subtitle = stringResource(R.string.settings_history_desc),
                showChevron = false,
                trailing = {
                    StudioSwitch(
                        checked = historyEnabled,
                        onCheckedChange = { scope.launch { settings.setHistoryEnabled(it) } },
                    )
                },
            )
            if (historyEnabled) {
                SettingsDivider()
                StudioSliderRow(
                    title = stringResource(R.string.settings_history_retention),
                    description = pluralStringResource(R.plurals.settings_history_keep_days, retentionDays, retentionDays),
                    value = retentionDays.toFloat(),
                    range = RETENTION_RANGE_DAYS_F,
                    onValueChange = { scope.launch { settings.setHistoryRetentionDays(it.roundToInt()) } },
                )
            }
        }
    }
}

private val BUBBLE_SIZE_RANGE_DP_F: ClosedFloatingPointRange<Float> =
    SettingsRepository.MIN_BUBBLE_SIZE_DP.toFloat()..SettingsRepository.MAX_BUBBLE_SIZE_DP.toFloat()

private val BUBBLE_OPACITY_RANGE_PERCENT_F: ClosedFloatingPointRange<Float> =
    SettingsRepository.MIN_BUBBLE_OPACITY_PERCENT.toFloat()..SettingsRepository.MAX_BUBBLE_OPACITY_PERCENT.toFloat()

private val MINI_DOT_DELAY_RANGE_SECONDS_F: ClosedFloatingPointRange<Float> =
    SettingsRepository.MIN_MINI_DOT_DELAY_SECONDS.toFloat()..SettingsRepository.MAX_MINI_DOT_DELAY_SECONDS.toFloat()

private val AUTO_STOP_OPTIONS: List<Int> = listOf(15, 30, 60, 120, 300)

private val RETENTION_RANGE_DAYS_F: ClosedFloatingPointRange<Float> = 7f..90f

private fun bubbleSizeLabel(dp: Int): String = when {
    dp <= 30 -> "Small"
    dp <= 44 -> "Medium"
    else -> "Large"
}

@Composable
private fun autoStopFriendly(seconds: Int): String = stringResource(
    when (seconds) {
        15 -> R.string.auto_stop_after_15s
        30 -> R.string.auto_stop_after_30s
        60 -> R.string.auto_stop_after_60s
        120 -> R.string.auto_stop_after_120s
        300 -> R.string.auto_stop_after_300s
        else -> R.string.auto_stop_after_60s
    },
)

@StringRes
private fun audioSourceLabelRes(preference: AudioSourcePreference): Int = when (preference) {
    AudioSourcePreference.DEFAULT -> R.string.audio_source_phone
    AudioSourcePreference.BLUETOOTH -> R.string.audio_source_bluetooth
}

@Composable
private fun hotkeyLabel(keycode: Int, modifiers: Int): String {
    if (keycode == 0) return stringResource(R.string.hotkey_off)
    val parts = buildList {
        if (modifiers and HotkeyShortcut.META_CTRL_ON != 0) add(stringResource(R.string.hotkey_mod_ctrl))
        if (modifiers and HotkeyShortcut.META_ALT_ON != 0) add(stringResource(R.string.hotkey_mod_alt))
        if (modifiers and HotkeyShortcut.META_SHIFT_ON != 0) add(stringResource(R.string.hotkey_mod_shift))
        if (modifiers and HotkeyShortcut.META_META_ON != 0) add(stringResource(R.string.hotkey_mod_meta))
        val res = KEY_LABEL_RES[keycode]
        add(if (res != null) stringResource(res) else keyFallbackLabel(keycode))
    }
    return parts.joinToString(" + ")
}

private val KEY_LABEL_RES: Map<Int, Int> = mapOf(
    android.view.KeyEvent.KEYCODE_GRAVE to R.string.hotkey_grave,
    android.view.KeyEvent.KEYCODE_F9 to R.string.hotkey_f9,
    android.view.KeyEvent.KEYCODE_F10 to R.string.hotkey_f10,
    android.view.KeyEvent.KEYCODE_F11 to R.string.hotkey_f11,
    android.view.KeyEvent.KEYCODE_SCROLL_LOCK to R.string.hotkey_scroll_lock,
    android.view.KeyEvent.KEYCODE_SPACE to R.string.hotkey_space,
    android.view.KeyEvent.KEYCODE_ENTER to R.string.hotkey_enter,
    android.view.KeyEvent.KEYCODE_TAB to R.string.hotkey_tab,
)

private fun keyFallbackLabel(keycode: Int): String =
    try {
        android.view.KeyEvent.keyCodeToString(keycode)?.removePrefix("KEYCODE_")
            ?.replace('_', ' ') ?: "Key $keycode"
    } catch (_: Throwable) {
        "Key $keycode"
    }

private val HOTKEY_MODIFIER_KEYS: Set<Int> = setOf(
    android.view.KeyEvent.KEYCODE_CTRL_LEFT,
    android.view.KeyEvent.KEYCODE_CTRL_RIGHT,
    android.view.KeyEvent.KEYCODE_ALT_LEFT,
    android.view.KeyEvent.KEYCODE_ALT_RIGHT,
    android.view.KeyEvent.KEYCODE_SHIFT_LEFT,
    android.view.KeyEvent.KEYCODE_SHIFT_RIGHT,
    android.view.KeyEvent.KEYCODE_META_LEFT,
    android.view.KeyEvent.KEYCODE_META_RIGHT,
)
