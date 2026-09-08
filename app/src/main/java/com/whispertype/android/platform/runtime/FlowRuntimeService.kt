package com.whispertype.android.platform.runtime

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.provider.Settings
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import com.whispertype.android.R
import com.whispertype.android.audio.AudioCapture
import com.whispertype.android.audio.AudioPipeline
import com.whispertype.android.audio.AudioStartResult
import com.whispertype.android.audio.audioSourceFactory
import com.whispertype.android.core.dictionary.DictionaryCorrections
import com.whispertype.android.core.dictionary.DictionaryEntry
import com.whispertype.android.core.model.AudioSourcePreference
import com.whispertype.android.core.model.DictationFailure
import com.whispertype.android.core.model.DictationState
import com.whispertype.android.core.model.InsertionResult
import com.whispertype.android.core.model.LanguageMode
import com.whispertype.android.core.model.MutableSessionMetrics
import com.whispertype.android.core.model.OverlayIntent
import com.whispertype.android.core.model.SessionId
import com.whispertype.android.core.model.TargetEligibility
import com.whispertype.android.core.model.TranscriptionMode
import com.whispertype.android.core.model.WarmClaimResult
import com.whispertype.android.data.history.EncryptedHistoryRepository
import com.whispertype.android.data.history.HistoryRepository
import com.whispertype.android.data.secrets.AndroidKeystoreKeyStore
import com.whispertype.android.data.secrets.FileBlobStore
import com.whispertype.android.data.secrets.JavaxAesGcmCipher
import com.whispertype.android.data.secrets.KeystoreKeyProvider
import com.whispertype.android.data.secrets.KeyProvider
import com.whispertype.android.data.secrets.SensitiveClipboard
import com.whispertype.android.data.secrets.SystemSensitiveClipboard
import com.whispertype.android.data.settings.SettingsRepository
import com.whispertype.android.platform.gemini.GeminiLiveException
import com.whispertype.android.platform.gemini.GeminiSessionConfig
import com.whispertype.android.platform.gemini.GeminiSessionFactory
import com.whispertype.android.platform.gemini.WarmLiveSessionManager
import com.whispertype.android.platform.gemini.WarmSessionClaim
import com.whispertype.android.platform.gemini.WarmSessionClaimMissReason
import com.whispertype.android.platform.gemini.WarmSessionProfile
import com.whispertype.android.platform.ipc.RuntimeIpc
import com.whispertype.android.platform.overlay.OverlayOwners
import com.whispertype.android.platform.overlay.PersistentOverlayHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/**
 * The main-process foreground service that owns the overlay runtime: overlay
 * lifecycle, session state, and the Gemini Live connection. It implements the
 * stable Compose owners ([OverlayOwners]) and hosts the one persistent overlay.
 *
 * Live dictation orchestration is delegated to a host-testable
 * [DictationCoordinator]; this service is its Android-facing [DictationHost]
 * (IPC insertion, key/settings resolution, microphone/foreground capture,
 * overlay state publication). The static-insertion fallback (no API key) stays
 * here for the keyless field-matrix gates.
 */
class FlowRuntimeService : Service(), OverlayOwners, DictationHost {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val viewModelStoreHolder = ViewModelStore()

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry
    override val viewModelStore: ViewModelStore get() = viewModelStoreHolder

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _eligibility = MutableStateFlow(TargetEligibility.Ineligible)
    val eligibility: StateFlow<TargetEligibility> = _eligibility.asStateFlow()

    private val _sessionState = MutableStateFlow<DictationState>(DictationState.Idle)
    val sessionState: StateFlow<DictationState> = _sessionState.asStateFlow()

    @SuppressLint("InlinedApi")
    private var currentFgsType: Int = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE

    private var overlayHost: PersistentOverlayHost? = null

    /** Reply messenger registered by the accessibility process. */
    private var a11yReply: Messenger? = null

    // Context-dependent; lazy so they initialize on first use (in onCreate),
    // never during the Service constructor when the base Context is unattached.
    private val keyProvider: KeyProvider by lazy { KeystoreKeyProvider(this) }
    private val settings: SettingsRepository by lazy { SettingsRepository(this) }

    /** 0.5.8: sensitive-marked clipboard write for the no-target fallback. */
    private val sensitiveClipboard: SensitiveClipboard by lazy { SystemSensitiveClipboard(this) }

    /** One long-lived OkHttpClient shared by every session (Release D3). Its
     *  dispatcher/connection pool must never be shut down per session. */
    private val sharedOkHttpClient: OkHttpClient by lazy { GeminiSessionFactory.defaultClient() }

    /** Opt-in encrypted transcript history (0.4.0). */
    private val historyRepository: HistoryRepository by lazy {
        EncryptedHistoryRepository(
            keystore = AndroidKeystoreKeyStore(EncryptedHistoryRepository.DEFAULT_KEY_ALIAS),
            cipher = JavaxAesGcmCipher(),
            blobStore = FileBlobStore(this, EncryptedHistoryRepository.DEFAULT_FILE_NAME),
            retentionDays = { cachedHistoryRetentionDays },
        )
    }

    /** Live-session orchestration (Release C). Auto-stop knobs read the cached
     *  settings value lazily so the product default (60 s) applies immediately. */
    private val coordinator = DictationCoordinator(
        scope,
        this,
        config = DictationCoordinator.Config(
            autoStopSeconds = { cachedAutoStopSeconds.toLong() },
            maxRecordingSeconds = { cachedAutoStopSeconds.toLong() },
            segmentAtSilence = { cachedSegmentAtSilence },
        ),
    )

    /** Eligibility- and profile-driven warm Live session pool (Release F3). A
     *  profile mismatch (polish level, language, or echo setting changed) never
     *  reuses a stale warm session. */
    private val warmManager = WarmLiveSessionManager.profiled(
        scope,
        createSession = { profile -> createColdSession(profile) },
    )

    // Eager in-memory runtime-settings snapshot (Release D2): collected once at
    // service scope so the tap path never does sequential DataStore first() reads.
    @Volatile
    private var cachedSpeechMode: LanguageMode = LanguageMode.ENGLISH

    @Volatile
    private var cachedTranscriptionMode: TranscriptionMode = SettingsRepository.DEFAULT_TRANSCRIPTION_MODE

    @Volatile
    private var cachedHistoryEnabled: Boolean = false

    @Volatile
    private var cachedHistoryRetentionDays: Int = SettingsRepository.DEFAULT_RETENTION_DAYS

    @Volatile
    private var cachedAutoStopSeconds: Int = SettingsRepository.DEFAULT_AUTO_STOP_SECONDS

    /** 0.6.0 experimental: split the recording at pauses (off by default). */
    @Volatile
    private var cachedSegmentAtSilence: Boolean = false

    /** 0.6.0: recording input device (phone mic unless Bluetooth is selected). */
    @Volatile
    private var cachedAudioSourcePreference: AudioSourcePreference = SettingsRepository.DEFAULT_AUDIO_SOURCE_PREFERENCE

    @Volatile
    private var cachedDictionary: List<DictionaryEntry> = emptyList()

    @Volatile
    private var cachedBubbleX: Float? = null

    @Volatile
    private var cachedBubbleY: Float? = null

    @Volatile
    private var cachedAppEnabled: Boolean = true

    /** 0.5.2: true while the accessibility-drop notification is showing. */
    @Volatile
    private var a11yDropNotified: Boolean = false

    /** 0.5.4: true once the kill switch has shut the runtime down; prevents
     *  re-entrant shutdown while the service drains to onDestroy. */
    @Volatile
    private var killSwitchFired: Boolean = false

    private val incomingHandler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                RuntimeIpc.MSG_REGISTER_REPLY -> {
                    a11yReply = msg.replyTo
                    Log.i(TAG, "Accessibility process registered its reply messenger")
                }
                RuntimeIpc.MSG_ELIGIBILITY -> {
                    val b = msg.data
                    if (b != null) {
                        val e = RuntimeIpc.unpackEligibility(b)
                        _eligibility.value = e
                        // 0.5.2: mirror for the app UI's bubble-hidden diagnostics,
                        // and remember that the service once connected so the
                        // watchdog only nags after a real drop, not on fresh install.
                        currentEligibility = e
                        if (e.serviceConnected) {
                            scope.launch { settings.setA11yHasConnectedOnce(true) }
                        }
                        refreshWarmEligibility()
                    }
                }
                RuntimeIpc.MSG_INSERT_RESULT -> {
                    val b = msg.data
                    if (b != null) onInsertionResult(
                        SessionId(b.getString(RuntimeIpc.KEY_SESSION_ID) ?: return@handleMessage),
                        RuntimeIpc.unpackInsertionResult(b),
                    )
                }
                RuntimeIpc.MSG_HOTKEY_TOGGLE -> onHotkeyToggle()
                else -> Log.w(TAG, "Unhandled IPC message ${msg.what}")
            }
        }
    }

    private val incomingMessenger = Messenger(incomingHandler)

    override fun onBind(intent: Intent?): IBinder = incomingMessenger.binder

    /**
     * 1.0.1: the Quick Settings tile starts this service with
     * startForegroundService; a delivery racing a dying or still-alive instance
     * must promote to foreground within the per-start obligation window or the
     * system raises ForegroundServiceDidNotStartInTimeException. onCreate alone
     * does not cover redelivery to an existing instance.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_DICTATION -> {
                Log.i(TAG, "Notification action: Stop dictation")
                coordinator.stop()
                return START_NOT_STICKY
            }
            ACTION_CANCEL_DICTATION -> {
                Log.i(TAG, "Notification action: Cancel dictation")
                coordinator.cancel()
                return START_NOT_STICKY
            }
        }
        startForeground(NOTIFICATION_ID, buildNotification(_sessionState.value), currentFgsType)
        return START_NOT_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createNotificationChannel()
        createA11yWatchdogChannel()
        // Start as a special-use FGS (no runtime permission required) so the
        // overlay service can run before RECORD_AUDIO is granted. On API 33 the
        // specialUse bit is inert but accepted (manifest-declared); the service
        // is promoted to include the microphone type during dictation.
        currentFgsType = overlayFgsTypes()
        startForeground(NOTIFICATION_ID, buildNotification(_sessionState.value), currentFgsType)
        startOverlay()
        // Collect the runtime settings snapshot eagerly (Release D2) so the tap
        // path reads in-memory values instead of blocking on DataStore.
        scope.launch { settings.speechMode.collect { cachedSpeechMode = it } }
        scope.launch { settings.transcriptionMode.collect { cachedTranscriptionMode = it } }
        scope.launch { settings.historyEnabled.collect { cachedHistoryEnabled = it } }
        scope.launch { settings.historyRetentionDays.collect { cachedHistoryRetentionDays = it } }
        scope.launch { settings.autoStopSeconds.collect { cachedAutoStopSeconds = it } }
        scope.launch { settings.segmentAtSilence.collect { cachedSegmentAtSilence = it } }
        scope.launch { settings.audioSourcePreference.collect { cachedAudioSourcePreference = it } }
        scope.launch { settings.dictionary.collect { cachedDictionary = it } }
        scope.launch { settings.bubbleX.collect { cachedBubbleX = it } }
        scope.launch { settings.bubbleY.collect { cachedBubbleY = it } }
        // 0.4.2 kill switch: cache the app-enabled setting in this (main)
        // process so the overlay can hide the bubble immediately and reliably.
        // 0.5.4: disabling is now a genuine kill switch — the runtime stops
        // itself (overlay, notifications, mic, Gemini) until re-enabled.
        scope.launch {
            settings.appEnabled.collect { enabled ->
                cachedAppEnabled = enabled
                if (!enabled) shutdownForKillSwitch()
            }
        }
        // 0.5.2: surface a silent accessibility-service drop instead of an
        // unexplained missing bubble.
        scope.launch { runA11yWatchdog() }
        Log.i(TAG, "FlowRuntimeService started (main process)")
    }

    private fun startOverlay() {
        val host = PersistentOverlayHost(
            serviceContext = this,
            owners = this,
            sessionState = sessionState,
            eligibility = eligibility,
            // 0.6.0: the bubble follows the display hosting the focused editor
            // (the DeX display when dictating in Samsung DeX mode).
            displayId = eligibility.map { it.displayId },
            bubbleSizeDp = settings.bubbleSizeDp,
            bubbleOpacityPercent = settings.bubbleOpacityPercent,
            miniDotEnabled = settings.miniDotEnabled,
            miniDotDelaySeconds = settings.miniDotDelaySeconds,
            appEnabled = settings.appEnabled,
            onBubblePositionChange = { x, y ->
                scope.launch { settings.setBubblePosition(x, y) }
            },
        )
        host.setBubblePosition(cachedBubbleX, cachedBubbleY)
        overlayHost = host
        host.attach()
        scope.launch {
            host.intents.collect { intent -> onOverlayIntent(intent) }
        }
    }

    private fun onOverlayIntent(intent: OverlayIntent) {
        when (intent) {
            OverlayIntent.START_DICTATION -> startDictation()
            OverlayIntent.STOP -> coordinator.stop()
            OverlayIntent.CANCEL -> coordinator.cancel()
            OverlayIntent.RETRY -> coordinator.retry()
            OverlayIntent.DISMISS -> coordinator.dismiss()
            OverlayIntent.COPY -> Unit
        }
    }

    /**
     * 0.5.4: the "App enabled" kill switch. When disabled, this genuinely
     * terminates the runtime: any active dictation is aborted, the warm Live
     * session is closed, the overlay window is removed (which also clears the
     * system "displaying over other apps" notification), the foreground
     * notification is removed, and the service is stopped. The service is both
     * started and bound, so it only fully dies once the accessibility process
     * releases its binding; on re-enable the activity restarts it.
     */
    private fun shutdownForKillSwitch() {
        if (killSwitchFired) return
        killSwitchFired = true
        Log.i(TAG, "Kill switch: stopping runtime")
        coordinator.cancel()
        warmManager.onEligibilityChanged(false)
        dismissA11yDropNotification()
        overlayHost?.detach()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * Phase 6 routing: every accepted tap runs the live Gemini loop. A missing
     * or unreadable API key surfaces the configured no-key failure from
     * [resolveSession]; there is no separate hasKey-then-provideKey tap path
     * (Release D1).
     */
    private fun startDictation() {
        coordinator.start()
    }

    /**
     * 0.6.0 physical-keyboard hotkey: one toggle key. Completes an active turn
     * (Listening -> Finalizing) via the same [DictationCoordinator.stop] as the
     * overlay STOP; otherwise starts dictation, gated on the hotkey-relaxed
     * eligibility (safe focused editor, no soft-IME requirement).
     */
    private fun onHotkeyToggle() {
        if (coordinator.isActive) {
            coordinator.stop()
            return
        }
        if (!_eligibility.value.eligibleForHotkey) {
            Log.i(TAG, "Hotkey ignored; no safe focused editor")
            return
        }
        startDictation()
    }

    // ------------------------------------------------------------------
    // DictationHost
    // ------------------------------------------------------------------

    override fun publish(state: DictationState) {
        _sessionState.value = state
        // Dictation activity changes warm-prewarm eligibility; re-evaluate.
        refreshWarmEligibility()
        updateForegroundNotification(state)
    }

    override suspend fun resolveSession(metrics: MutableSessionMetrics): SessionResolve {
        metrics.mark(MutableSessionMetrics.Event.KeyLoadStarted)
        val profile = warmProfile()
        // Prefer a warm (preconnected) session whose exact profile matches the
        // current settings; wait for an in-flight prewarm before cold connect.
        when (val claim = warmManager.claimOrAwait(profile)) {
            is WarmSessionClaim.Hit -> {
                recordWarmClaim(metrics, claim)
                metrics.mark(MutableSessionMetrics.Event.KeyLoaded)
                metrics.mark(MutableSessionMetrics.Event.SettingsReady)
                metrics.mark(MutableSessionMetrics.Event.SocketCreated)
                val lease = claim.lease
                return SessionResolve.Ok(
                    SessionResolution(
                        session = lease.session,
                        language = lease.profile.language,
                        ready = true,
                    ),
                )
            }
            is WarmSessionClaim.Miss -> recordWarmClaim(metrics, claim)
        }
        val key = withContext(Dispatchers.IO) { keyProvider.provideKey() }
        metrics.mark(MutableSessionMetrics.Event.KeyLoaded)
        if (key.isNullOrEmpty()) {
            return SessionResolve.Failed(
                DictationFailure(
                    code = "runtime_no_api_key",
                    message = "Add your Gemini API key in Settings first.",
                    recoverable = true,
                ),
            )
        }
        val language = profile.language
        val model = profile.model
        metrics.mark(MutableSessionMetrics.Event.SettingsReady)
        metrics.mark(MutableSessionMetrics.Event.SocketCreated)
        val session = GeminiSessionFactory.create(
            apiKey = key,
            config = GeminiSessionConfig(
                model = model,
                apiVersion = profile.apiVersion,
                language = language,
                transcriptionMode = profile.transcriptionMode,
                transcriptionLanguageCode = transcriptionLanguageCode(language),
                automaticActivityDetectionDisabled = profile.automaticActivityDetectionDisabled,
                activityHandlingNoInterruption = profile.activityHandlingNoInterruption,
                inputAudioTranscription = profile.inputAudioTranscription,
                // Release B production protocol: manual activity signaling
                // (automaticActivityDetection disabled by default) and no text
                // prime. Text shaping runs server-side according to the selected
                // transcriptionMode; the language hint biases code-mixing.
            ),
            client = sharedOkHttpClient,
            metrics = metrics,
        )
        return SessionResolve.Ok(
            SessionResolution(
                session = session,
                language = language,
                ready = false,
            ),
        )
    }

    /** Cold session construction shared by the live path and the warm pool.
     *  The exact [profile] (language, activity signaling) shapes the session so
     *  a warm session is never reused under different settings. */
    private suspend fun createColdSession(profile: WarmSessionProfile): com.whispertype.android.core.contracts.GeminiLiveSession {
        val key = withContext(Dispatchers.IO) { keyProvider.provideKey() }
            ?: throw GeminiLiveException(
                DictationFailure(
                    code = "runtime_no_api_key",
                    message = "Add your Gemini API key in Settings first.",
                    recoverable = true,
                ),
            )
        return GeminiSessionFactory.create(
            apiKey = key,
            config = GeminiSessionConfig(
                model = profile.model,
                apiVersion = profile.apiVersion,
                language = profile.language,
                transcriptionMode = profile.transcriptionMode,
                transcriptionLanguageCode = transcriptionLanguageCode(profile.language),
                automaticActivityDetectionDisabled = profile.automaticActivityDetectionDisabled,
                activityHandlingNoInterruption = profile.activityHandlingNoInterruption,
                inputAudioTranscription = profile.inputAudioTranscription,
            ),
            client = sharedOkHttpClient,
        )
    }

    /** The exact session configuration a warm pool entry must match. */
    private fun warmProfile(): WarmSessionProfile {
        val language = cachedSpeechMode
        return WarmSessionProfile(
            model = GeminiSessionFactory.LIVE_MODEL,
            apiVersion = GeminiSessionConfig.DEFAULT_API_VERSION,
            language = language,
            transcriptionMode = cachedTranscriptionMode.wireValue,
            automaticActivityDetectionDisabled = true,
            activityHandlingNoInterruption = cachedSegmentAtSilence,
            inputAudioTranscription = true,
            credentialRevision = 0L,
        )
    }

    /** 0.10.0: BCP-47 language hint for the transcribe model's automatic
     *  language detection / code-mixing. Null omits the field. */
    private fun transcriptionLanguageCode(language: LanguageMode): String? = when (language) {
        LanguageMode.ENGLISH -> "en-US"
        LanguageMode.HINGLISH -> "hi-IN"
    }

    /** Release F2: prewarm only while every eligibility condition holds.
     *  [keyProvider.hasKey] reads the key blob from disk, so it runs last and
     *  only when the in-memory conditions already pass. The pool stays eligible
     *  during [DictationState.Starting] so a Ready socket can be claimed. */
    private fun computeWarmEligibility(): Boolean =
        WarmPoolEligibility.compute(
            eligibility = _eligibility.value,
            blocksWarmPool = coordinator.blocksWarmPool(),
            appEnabled = cachedAppEnabled,
            hasApiKey = keyProvider.hasKey(),
        )

    private fun recordWarmClaim(metrics: MutableSessionMetrics, claim: WarmSessionClaim) {
        val ageNanos = claim.ageMs?.times(1_000_000L)
        when (claim) {
            is WarmSessionClaim.Hit ->
                metrics.recordWarmClaim(WarmClaimResult.HIT, ageNanos = ageNanos)
            is WarmSessionClaim.Miss ->
                metrics.recordWarmClaim(claim.reason.toWarmClaimResult(), ageNanos = ageNanos)
        }
    }

    private fun WarmSessionClaimMissReason.toWarmClaimResult(): WarmClaimResult = when (this) {
        WarmSessionClaimMissReason.SHUT_DOWN -> WarmClaimResult.SHUT_DOWN
        WarmSessionClaimMissReason.INELIGIBLE -> WarmClaimResult.INELIGIBLE
        WarmSessionClaimMissReason.MISSING_PROFILE -> WarmClaimResult.MISSING_PROFILE
        WarmSessionClaimMissReason.CONNECTING -> WarmClaimResult.CONNECTING
        WarmSessionClaimMissReason.BACKING_OFF -> WarmClaimResult.BACKING_OFF
        WarmSessionClaimMissReason.NO_READY_SESSION -> WarmClaimResult.NO_READY_SESSION
        WarmSessionClaimMissReason.PROFILE_MISMATCH -> WarmClaimResult.PROFILE_MISMATCH
        WarmSessionClaimMissReason.TERMINATED -> WarmClaimResult.TERMINATED
    }

    private fun refreshWarmEligibility() {
        warmManager.onEligibilityChanged(computeWarmEligibility(), warmProfile())
    }

    override suspend fun startCapture(metrics: MutableSessionMetrics): CaptureStart {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return CaptureStart.Failed(
                DictationFailure(
                    code = "runtime_mic_permission",
                    message = "Microphone permission was revoked.",
                    recoverable = true,
                ),
            )
        }
        // Promote to microphone foreground mode before AudioRecord, only now that
        // RECORD_AUDIO is confirmed granted. specialUse stays in the type set so
        // the overlay service keeps running after dictation.
        currentFgsType = dictationFgsTypes()
        startForeground(
            NOTIFICATION_ID,
            buildNotification(DictationState.Listening(metrics.sessionId)),
            dictationFgsTypes(),
        )
        // AudioRecord construction and startRecording run off the main thread
        // (Release D4); failures surface as typed mic-init failures.
        metrics.mark(MutableSessionMetrics.Event.CaptureStartRequested)
        return withContext(Dispatchers.IO) {
            // 0.6.0: the capture source honors the selected recording device
            // (phone mic by default; the connected headset only when explicitly
            // chosen), falling back to the phone mic when no headset is usable.
            val audioManager = getSystemService(AudioManager::class.java)
            val cap = AudioCapture(sourceFactory = audioSourceFactory(audioManager, cachedAudioSourcePreference))
            when (val start = cap.start()) {
                is AudioStartResult.Failed -> {
                    withContext(Dispatchers.Main) {
                        currentFgsType = overlayFgsTypes()
                        startForeground(
                            NOTIFICATION_ID,
                            buildNotification(_sessionState.value),
                            overlayFgsTypes(),
                        )
                    }
                    CaptureStart.Failed(start.failure)
                }
                AudioStartResult.Started -> {
                    metrics.mark(MutableSessionMetrics.Event.CaptureStarted)
                    CaptureStart.Started(cap)
                }
            }
        }
    }

    override fun sendInsertion(sessionId: SessionId, text: String): Boolean {
        val reply = a11yReply ?: return false
        // Custom-dictionary correction rules (client-side) applied to the final text.
        val corrected = DictionaryCorrections.apply(text, cachedDictionary)
        return sendInsert(sessionId, corrected, reply)
    }

    /** 0.5.8: clipboard fallback when the transcript could not be committed to a
     *  focused field. Sensitive-marked write; returns true only on a confirmed
     *  copy. */
    override suspend fun copyToClipboard(sessionId: SessionId, text: String): Boolean =
        withContext(Dispatchers.IO) { sensitiveClipboard.copySensitive(text) }

    override fun onSessionFinished(state: DictationState, metrics: MutableSessionMetrics, transcript: String?) {
        // Aggregate per-session outcome + stage latencies. Never transcript or audio.
        Log.i(TAG, "SESSION DONE outcome=${state::class.simpleName} ${metrics.summary()}")
        // Opt-in history: record the settled transcript when enabled.
        if (cachedHistoryEnabled && !transcript.isNullOrBlank()) {
            val durationMs = metrics.capturedFrames * 20L // 20 ms per captured chunk
            scope.launch {
                historyRepository.record(
                    HistoryRepository.HistoryEntry(
                        id = "",
                        timestampMillis = System.currentTimeMillis(),
                        text = transcript,
                        language = cachedSpeechMode.name,
                        charCount = transcript.length,
                        outcome = state::class.simpleName ?: "Unknown",
                        durationMs = durationMs,
                    ),
                )
            }
        }
    }

    // ------------------------------------------------------------------
    // Foreground-service types (Android 13+)
    // ------------------------------------------------------------------

    /** Persistent overlay FGS type: specialUse (honored API 34+, inert on API 33
     *  where the bit is accepted because it is manifest-declared). */
    @SuppressLint("InlinedApi")
    private fun overlayFgsTypes(): Int = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE

    /** Dictation FGS type: specialUse + microphone (RECORD_AUDIO is confirmed
     *  granted before this is used). */
    @SuppressLint("InlinedApi")
    private fun dictationFgsTypes(): Int =
        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE

    // ------------------------------------------------------------------
    // Shared IPC + failure helpers
    // ------------------------------------------------------------------

    /** Returns false when the insert request could not be handed to accessibility. */
    private fun sendInsert(sessionId: SessionId, text: String, reply: Messenger): Boolean {
        val m = Message.obtain(null, RuntimeIpc.MSG_INSERT).apply {
            data = Bundle().apply {
                putString(RuntimeIpc.KEY_SESSION_ID, sessionId.value)
                putString(RuntimeIpc.KEY_INSERT_TEXT, text)
            }
            replyTo = incomingMessenger
        }
        return try {
            reply.send(m)
            true
        } catch (e: RemoteException) {
            Log.w(TAG, "Could not deliver insert request: ${e.message}")
            false
        }
    }

    private fun onInsertionResult(sessionId: SessionId, result: InsertionResult) {
        coordinator.onInsertionResult(sessionId, result)
    }

    // ------------------------------------------------------------------
    // OverlayOwners (lifecycle / saved-state / view-model store)
    // ------------------------------------------------------------------

    override fun startOwners() {
        savedStateRegistryController.performAttach()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    override fun stopOwners() {
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    override fun onDestroy() {
        isRunning = false
        overlayHost?.detach()
        overlayHost = null
        scope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // Foreground notification
    // ------------------------------------------------------------------

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.notification_channel_bubble),
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }

    private fun updateForegroundNotification(state: DictationState) {
        if (!isRunning || killSwitchFired) return
        val isListening = state is DictationState.Listening
        val targetFgsType = if (isListening) dictationFgsTypes() else overlayFgsTypes()
        val notification = buildNotification(state)
        if (targetFgsType != currentFgsType) {
            currentFgsType = targetFgsType
            startForeground(NOTIFICATION_ID, notification, targetFgsType)
        } else {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(state: DictationState = _sessionState.value): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val contentIntent = if (launchIntent != null) {
            PendingIntent.getActivity(
                this,
                NOTIFICATION_REQUEST_CODE_CONTENT,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        } else null

        val builder = Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)

        if (contentIntent != null) {
            builder.setContentIntent(contentIntent)
        }

        when (state) {
            is DictationState.Listening -> {
                val stopIntent = Intent(this, FlowRuntimeService::class.java).apply {
                    action = ACTION_STOP_DICTATION
                }
                val stopPendingIntent = PendingIntent.getService(
                    this,
                    NOTIFICATION_REQUEST_CODE_STOP,
                    stopIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                val cancelIntent = Intent(this, FlowRuntimeService::class.java).apply {
                    action = ACTION_CANCEL_DICTATION
                }
                val cancelPendingIntent = PendingIntent.getService(
                    this,
                    NOTIFICATION_REQUEST_CODE_CANCEL,
                    cancelIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )

                builder.setContentTitle(getString(R.string.notification_recording_title))
                    .setContentText(getString(R.string.notification_recording_text))
                    .addAction(
                        Notification.Action.Builder(
                            null,
                            getString(R.string.notification_action_stop),
                            stopPendingIntent,
                        ).build(),
                    )
                    .addAction(
                        Notification.Action.Builder(
                            null,
                            getString(R.string.notification_action_cancel),
                            cancelPendingIntent,
                        ).build(),
                    )
            }
            is DictationState.Finalizing, is DictationState.Inserting -> {
                builder.setContentTitle(getString(R.string.notification_finishing_title))
                    .setContentText(getString(R.string.notification_finishing_text))
            }
            else -> {
                builder.setContentTitle(getString(R.string.notification_bubble_ready_title))
                    .setContentText(getString(R.string.notification_bubble_ready_text))
            }
        }

        return builder.build()
    }

    // ------------------------------------------------------------------
    // Accessibility watchdog (0.5.2)
    // ------------------------------------------------------------------

    /**
     * 0.5.2: Android silently clears an app's accessibility service when the app
     * is force-stopped, which makes the bubble vanish with no in-app signal. This
     * loop posts a low-importance notification (tap -> accessibility settings)
     * whenever the service was once connected but is no longer reporting, and
     * dismisses it as soon as the service reconnects.
     */
    private suspend fun CoroutineScope.runA11yWatchdog() {
        delay(A11Y_WATCHDOG_START_DELAY_MS)
        while (isActive) {
            val e = currentEligibility
            val connected = e.serviceConnected
            if (settings.a11yHasConnectedOnce.first() && !connected) {
                postA11yDropNotification()
            } else {
                dismissA11yDropNotification()
            }
            delay(A11Y_WATCHDOG_INTERVAL_MS)
        }
    }

    private fun createA11yWatchdogChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            A11Y_WATCHDOG_CHANNEL_ID,
            getString(R.string.a11y_watchdog_channel),
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }

    private fun postA11yDropNotification() {
        if (a11yDropNotified) return
        a11yDropNotified = true
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        val contentIntent = PendingIntent.getActivity(
            this,
            A11Y_WATCHDOG_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, A11Y_WATCHDOG_CHANNEL_ID)
            .setContentTitle(getString(R.string.a11y_watchdog_title))
            .setContentText(getString(R.string.a11y_watchdog_text))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(A11Y_WATCHDOG_NOTIFICATION_ID, notification)
    }

    private fun dismissA11yDropNotification() {
        if (!a11yDropNotified) return
        a11yDropNotified = false
        getSystemService(NotificationManager::class.java).cancel(A11Y_WATCHDOG_NOTIFICATION_ID)
    }

    companion object {
        const val TAG = "FlowRuntimeService"
        const val NOTIFICATION_ID = 1001
        const val NOTIFICATION_CHANNEL_ID = "whispertype_runtime"

        const val ACTION_STOP_DICTATION = "com.whispertype.android.action.STOP_DICTATION"
        const val ACTION_CANCEL_DICTATION = "com.whispertype.android.action.CANCEL_DICTATION"
        private const val NOTIFICATION_REQUEST_CODE_CONTENT = 101
        private const val NOTIFICATION_REQUEST_CODE_STOP = 102
        private const val NOTIFICATION_REQUEST_CODE_CANCEL = 103

        /** 0.5.2: low-importance channel/id for the accessibility-drop watchdog. */
        const val A11Y_WATCHDOG_CHANNEL_ID = "whispertype_a11y_watchdog"
        const val A11Y_WATCHDOG_NOTIFICATION_ID = 1002
        private const val A11Y_WATCHDOG_REQUEST_CODE = 0
        private const val A11Y_WATCHDOG_START_DELAY_MS = 30_000L
        private const val A11Y_WATCHDOG_INTERVAL_MS = 60_000L

        /**
         * 0.5.2: eligibility mirror for the app UI's bubble-hidden diagnostics.
         * Companion-level so the activity can read it without IPC.
         */
        @Volatile
        var currentEligibility: TargetEligibility = TargetEligibility.Ineligible

        /** Process-local service-liveness flag for the app UI (set in onCreate/onDestroy). */
        @Volatile
        var isRunning: Boolean = false
    }
}
