package com.whispertype.android

import com.whispertype.android.core.contracts.DictationBridge
import com.whispertype.android.core.contracts.TargetGateway
import com.whispertype.android.data.history.HistoryRepository
import com.whispertype.android.data.secrets.KeyProvider
import com.whispertype.android.data.secrets.SensitiveClipboard
import com.whispertype.android.data.settings.SettingsProvider
import com.whispertype.android.platform.overlay.OverlayHostFactory

/**
 * Lead-owned wiring contract. Adapters are created once at application start;
 * the application singleton never owns an active microphone, socket, or editor
 * transaction (those belong to the foreground service and accessibility
 * service). ViewModels and services receive these interfaces.
 */
interface AppContainer {
    val keyProvider: KeyProvider
    val settingsProvider: SettingsProvider
    val sensitiveClipboard: SensitiveClipboard
    val historyRepository: HistoryRepository
    val dictation: DictationBridge
    val overlayHostFactory: OverlayHostFactory
    val targetGateway: TargetGateway
}