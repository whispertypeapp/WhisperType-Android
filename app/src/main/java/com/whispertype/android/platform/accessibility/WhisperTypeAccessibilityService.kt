package com.whispertype.android.platform.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.InputMethod
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.util.size
import com.whispertype.android.core.model.HotkeyShortcut
import com.whispertype.android.core.model.InsertionResult
import com.whispertype.android.core.model.SessionId
import com.whispertype.android.core.settings.PreferencesFileReader
import com.whispertype.android.data.settings.SettingsRepository
import com.whispertype.android.platform.ipc.RuntimeIpc
import com.whispertype.android.platform.runtime.FlowRuntimeService
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The dedicated `:accessibility`-process service (locked decision §3). It sees
 * the screen only and owns focus / target / insertion — never the overlay, the
 * microphone, or Gemini. Focus and keyboard state are pushed to the main-process
 * [FlowRuntimeService] over typed IPC; the runtime reserves at tap time and later
 * commits or releases that reservation. No editor content or target snapshot is
 * ever transported over IPC.
 */
class WhisperTypeAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val tracker = EditorTracker()

    private val inputMethod = WhisperTypeInputMethod(this)

    private val gateway = AccessibilityTargetGateway(
        tracker = tracker,
        liveTargetProvider = { resolveLiveTarget() },
        inputConnectionProvider = { inputMethod.getCurrentInputConnection() },
    )

    private val targetReservations = TargetReservationStore()

    private val pendingCommitReplies =
        mutableMapOf<SessionId, MutableList<PendingCommitReply>>()

    @Volatile
    private var runtimeMessenger: Messenger? = null

    /** 0.5.4: app-enabled snapshot so a system restart of this accessibility
     *  service while the app is disabled does not resurrect the runtime. */
    @Volatile
    private var cachedAppEnabled: Boolean = true

    /** Whether [cachedAppEnabled] has been seeded by the poller yet. */
    private var appEnabledInitialized = false

    /** 0.6.0: physical-keyboard hotkey, polled from disk alongside
     *  [cachedAppEnabled] so a main-process Settings change is observed here. */
    @Volatile
    private var cachedHotkey: HotkeyShortcut = HotkeyShortcut(SettingsRepository.DEFAULT_HOTKEY_KEYCODE)

    private var legacyInsertionJob: Job? = null

    private val replyHandler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                RuntimeIpc.MSG_RESERVE_TARGET -> onReserveTargetRequest(msg)
                RuntimeIpc.MSG_COMMIT_RESERVED_TARGET -> onCommitReservedTargetRequest(msg)
                RuntimeIpc.MSG_RELEASE_RESERVED_TARGET -> onReleaseReservedTargetRequest(msg)
                RuntimeIpc.MSG_INSERT -> onLegacyInsertRequest(msg)
                else -> Log.w(TAG, "Unhandled runtime message ${msg.what}")
            }
        }
    }

    private val replyMessenger = Messenger(replyHandler)

    private val runtimeConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val remote = binder?.let { Messenger(it) }
            runtimeMessenger = remote
            if (remote == null) {
                Log.w(TAG, "Runtime service bound with null messenger")
                return
            }
            val reg = Message.obtain(null, RuntimeIpc.MSG_REGISTER_REPLY).apply {
                replyTo = replyMessenger
            }
            try {
                remote.send(reg)
            } catch (_: RemoteException) {
                runtimeMessenger = null
            }
            pushEligibility()
            Log.i(TAG, "Runtime service connected over IPC")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            runtimeMessenger = null
            legacyInsertionJob?.cancel()
            clearReservationState()
            Log.w(TAG, "Runtime service disconnected")
        }
    }

    /** Wispr-parity: return our own IME surface so cursor-aware commitText works. */
    override fun onCreateInputMethod(): InputMethod = inputMethod

    override fun onServiceConnected() {
        super.onServiceConnected()
        // 0.6.0: request hardware-key filtering so the physical-keyboard hotkey
        // can start/complete dictation (config flag + runtime flag both needed).
        serviceInfo = serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        }
        tracker.serviceConnected()
        // Focus + keyboard are (re)initialized on connect and on every window
        // change (§2.3), so a bubble decision is never made from a stale editor.
        refreshFocusedEditorAndKeyboard()

        // Push eligibility to the runtime on every change so the bubble tracks focus.
        scope.launch {
            tracker.eligibility.collect { pushEligibility() }
        }
        // 0.5.5: the kill-switch setting is re-read from disk every couple of
        // seconds instead of through the in-process DataStore flow — the flow
        // only sees writes made in THIS process, so a main-process toggle (the
        // Settings switch) left cachedAppEnabled permanently stale and the
        // bubble missing until a reboot. The poller observes every toggle
        // within ~APP_ENABLED_POLL_MS.
        scope.launch { runAppEnabledPoller() }
        Log.i(TAG, "Accessibility service connected (:accessibility process)")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // 0.5.4: while the app is disabled the accessibility process is fully
        // inert — no focus/keyboard tracking, no eligibility, nothing.
        if (!cachedAppEnabled) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                tracker.onViewFocused(event)
                invalidateReservationsAgainstCurrentFocus()
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            -> refreshFocusedTarget()
            // TYPE_WINDOWS_CHANGED refreshes BOTH keyboard visibility and the
            // focused-editor state, matching Wispr's observed event pipeline (§4.4).
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> refreshFocusedEditorAndKeyboard()
            else -> Unit
        }
    }

    override fun onInterrupt() {
        legacyInsertionJob?.cancel()
        clearReservationState()
    }

    /**
     * 0.6.0: physical-keyboard hotkey. A single key toggles dictation: the
     * runtime starts when Idle and completes (finalizes) when Listening. The key
     * is consumed (return true) only when it maps to the configured hotkey, so
     * normal typing keys are never swallowed. The runtime gates the actual
     * start/stop on its own state and the hotkey-relaxed eligibility.
     */
    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (!cachedAppEnabled) return false
        if (event.action != KeyEvent.ACTION_DOWN) return false
        if (event.repeatCount != 0) return false
        if (!cachedHotkey.matches(event.keyCode, event.metaState)) return false
        val remote = runtimeMessenger ?: return false
        return try {
            remote.send(Message.obtain(null, RuntimeIpc.MSG_HOTKEY_TOGGLE))
            Log.i(TAG, "Hotkey ${event.keyCode} toggled dictation")
            true
        } catch (_: RemoteException) {
            runtimeMessenger = null
            legacyInsertionJob?.cancel()
            clearReservationState()
            false
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        runtimeMessenger = null
        clearReservationState()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        legacyInsertionJob?.cancel()
        clearReservationState()
        try {
            unbindService(runtimeConnection)
        } catch (_: Throwable) {
            // never bound
        }
        scope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // Runtime binding / eligibility push
    // ------------------------------------------------------------------

    private fun bindToRuntime() {
        val intent = Intent(this, FlowRuntimeService::class.java)
        try {
            bindService(intent, runtimeConnection, BIND_AUTO_CREATE)
        } catch (t: Throwable) {
            Log.w(TAG, "Could not bind runtime service", t)
        }
    }

    /**
     * 0.5.5: polls the on-disk `app_enabled` value so a kill-switch toggle made
     * in the main process is observed here within ~[APP_ENABLED_POLL_MS] — the
     * in-process DataStore flow could never see the main process's writes. The
     * first pass seeds [cachedAppEnabled] and performs the initial runtime bind
     * (replacing the one-shot bind in [onServiceConnected]).
     */
    private suspend fun runAppEnabledPoller() {
        val settings = SettingsRepository(this@WhisperTypeAccessibilityService)
        val appEnabledFile = File(filesDir, PreferencesFileReader.DATASTORE_RELATIVE_PATH)
        while (true) {
            val enabled = PreferencesFileReader.readBoolean(appEnabledFile, SettingsRepository.KEY_APP_ENABLED)
                ?: settings.appEnabled.first() // fallback: DataStore default
            applyAppEnabled(enabled)
            // 0.6.0: hotkey changes made in the main-process Settings screen are
            // observed here from disk (same cross-process reason as app_enabled).
            val hotkeyKeycode = PreferencesFileReader.readInt(appEnabledFile, SettingsRepository.KEY_HOTKEY_KEYCODE)
                ?: cachedHotkey.keyCode
            val hotkeyModifiers = PreferencesFileReader.readInt(appEnabledFile, SettingsRepository.KEY_HOTKEY_MODIFIERS)
                ?: cachedHotkey.modifiers
            cachedHotkey = HotkeyShortcut(hotkeyKeycode, hotkeyModifiers)
            delay(APP_ENABLED_POLL_MS)
        }
    }

    private fun applyAppEnabled(enabled: Boolean) {
        val changed = !appEnabledInitialized || enabled != cachedAppEnabled
        cachedAppEnabled = enabled
        appEnabledInitialized = true
        tracker.setAppEnabled(enabled)
        if (enabled) {
            if (runtimeMessenger == null) bindToRuntime()
        } else if (changed) {
            runtimeMessenger = null
            legacyInsertionJob?.cancel()
            clearReservationState()
            try {
                unbindService(runtimeConnection)
            } catch (_: Throwable) {
                // never bound
            }
        }
    }

    private fun pushEligibility() {
        val remote = runtimeMessenger ?: return
        val eligibility = tracker.eligibility.value
        if (!eligibility.eligible) {
            // Phase 3 per-condition diagnostic so a hidden bubble is explainable (§2.3).
            Log.i(TAG, "Bubble hidden; reasons=${EligibilityExplanation.blockingReasons(eligibility)}")
        } else {
            Log.i(TAG, "STAGE: bubble shown (eligible target + keyboard)")
        }
        val m = Message.obtain(null, RuntimeIpc.MSG_ELIGIBILITY).apply {
            data = RuntimeIpc.packEligibility(eligibility)
        }
        try {
            remote.send(m)
        } catch (_: RemoteException) {
            runtimeMessenger = null
            legacyInsertionJob?.cancel()
            clearReservationState()
        }
    }

    // ------------------------------------------------------------------
    // Tap-time target reservation + exactly-once insertion
    // ------------------------------------------------------------------

    private fun onReserveTargetRequest(msg: Message) {
        val replyTo = msg.replyTo ?: run {
            Log.w(TAG, "Reserve request carried no reply messenger")
            return
        }
        val receivedAtNanos = monotonicNowNanos()
        val request = RuntimeIpc.unpackReserveTargetRequest(msg.data) ?: run {
            Log.w(TAG, "Rejected malformed reserve request")
            return
        }

        // Re-resolve immediately: this is the tap-time boundary, not the last
        // asynchronously observed accessibility event.
        refreshFocusedTarget()
        val target = gateway.captureTarget(request.sessionId)
        val decision = targetReservations.reserve(
            sessionId = request.sessionId,
            target = target,
            currentTarget = tracker.currentFocus?.reservationIdentity(),
        )
        val status = when (decision) {
            is TargetReservationStore.ReserveDecision.Reserved ->
                RuntimeIpc.ReservationStatus.RESERVED
            is TargetReservationStore.ReserveDecision.Rejected -> when (decision.reason) {
                TargetReservationStore.ReserveRejection.INELIGIBLE ->
                    RuntimeIpc.ReservationStatus.INELIGIBLE
                TargetReservationStore.ReserveRejection.CAPACITY_EXCEEDED ->
                    RuntimeIpc.ReservationStatus.CAPACITY_EXCEEDED
                TargetReservationStore.ReserveRejection.TARGET_CHANGED ->
                    RuntimeIpc.ReservationStatus.TARGET_CHANGED
                TargetReservationStore.ReserveRejection.SESSION_TERMINAL ->
                    RuntimeIpc.ReservationStatus.SESSION_TERMINAL
            }
        }
        val result = RuntimeIpc.ReserveTargetResult(
            sessionId = request.sessionId,
            status = status,
            requestedAtNanos = request.requestedAtNanos,
            receivedAtNanos = receivedAtNanos,
            repliedAtNanos = monotonicNowNanos(),
        )
        val reply = Message.obtain(null, RuntimeIpc.MSG_RESERVE_TARGET_RESULT).apply {
            data = RuntimeIpc.packReserveTargetResult(result)
        }
        try {
            replyTo.send(reply)
        } catch (_: RemoteException) {
            // The runtime cannot know it owns this reservation, so release it.
            targetReservations.release(request.sessionId)
            Log.w(TAG, "Could not deliver target-reservation result")
        }
    }

    private fun onCommitReservedTargetRequest(msg: Message) {
        val replyTo = msg.replyTo ?: run {
            Log.w(TAG, "Reserved commit carried no reply messenger")
            return
        }
        val receivedAtNanos = monotonicNowNanos()
        val request = RuntimeIpc.unpackCommitReservedTargetRequest(msg.data) ?: run {
            Log.w(TAG, "Rejected malformed reserved commit")
            return
        }
        val pending = PendingCommitReply(
            replyTo = replyTo,
            requestedAtNanos = request.requestedAtNanos,
            receivedAtNanos = receivedAtNanos,
        )

        // Re-resolve and invalidate before atomically granting the one commit.
        refreshFocusedTarget()
        when (val decision = targetReservations.beginCommit(
            sessionId = request.sessionId,
            currentTarget = tracker.currentFocus?.reservationIdentity(),
            commitStartedAtNanos = monotonicNowNanos(),
        )) {
            is TargetReservationStore.CommitDecision.Terminal -> {
                sendCommitResult(request.sessionId, pending, decision.terminal)
            }
            TargetReservationStore.CommitDecision.InFlight -> {
                if (!enqueueCommitReply(request.sessionId, pending)) {
                    sendCommitResult(
                        sessionId = request.sessionId,
                        pending = pending,
                        terminal = TargetReservationStore.TerminalResult(
                            result = InsertionResult.Ambiguous,
                            commitStartedAtNanos = null,
                        ),
                    )
                }
            }
            is TargetReservationStore.CommitDecision.Execute -> {
                enqueueCommitReply(request.sessionId, pending)
                scope.launch {
                    val result = try {
                        gateway.insert(decision.target, request.text)
                    } catch (failure: kotlinx.coroutines.CancellationException) {
                        throw failure
                    } catch (_: Throwable) {
                        InsertionResult.Failed(InsertionDecision.connectionUnavailable())
                    }
                    val terminal = targetReservations.completeCommit(
                        sessionId = request.sessionId,
                        result = result,
                        commitCompletedAtNanos = monotonicNowNanos(),
                    )
                    pendingCommitReplies.remove(request.sessionId)
                        .orEmpty()
                        .forEach { sendCommitResult(request.sessionId, it, terminal) }
                }
            }
        }
    }

    private fun onReleaseReservedTargetRequest(msg: Message) {
        val request = RuntimeIpc.unpackReleaseReservedTargetRequest(msg.data) ?: run {
            Log.w(TAG, "Rejected malformed target release")
            return
        }
        targetReservations.release(request.sessionId)
    }

    private fun enqueueCommitReply(
        sessionId: SessionId,
        pending: PendingCommitReply,
    ): Boolean {
        val replies = pendingCommitReplies.getOrPut(sessionId) { mutableListOf() }
        if (replies.size >= MAX_PENDING_COMMIT_REPLIES_PER_SESSION) return false
        replies += pending
        return true
    }

    private fun sendCommitResult(
        sessionId: SessionId,
        pending: PendingCommitReply,
        terminal: TargetReservationStore.TerminalResult,
    ) {
        val result = RuntimeIpc.CommitReservedTargetResult(
            sessionId = sessionId,
            result = terminal.result,
            requestedAtNanos = pending.requestedAtNanos,
            receivedAtNanos = pending.receivedAtNanos,
            commitStartedAtNanos = terminal.commitStartedAtNanos,
            commitCompletedAtNanos = terminal.commitCompletedAtNanos,
            repliedAtNanos = monotonicNowNanos(),
        )
        val reply = Message.obtain(null, RuntimeIpc.MSG_INSERT_RESULT).apply {
            data = RuntimeIpc.packCommitReservedTargetResult(result)
        }
        try {
            pending.replyTo.send(reply)
        } catch (_: RemoteException) {
            Log.w(TAG, "Could not deliver reserved-target commit result")
        }
    }

    /**
     * Migration-only insertion path. It intentionally preserves the old wire
     * message while new callers move to reserve -> commit/release.
     */
    private fun onLegacyInsertRequest(msg: Message) {
        val replyTo = msg.replyTo ?: run {
            Log.w(TAG, "Legacy insert carried no reply messenger")
            return
        }
        val receivedAtNanos = monotonicNowNanos()
        val request = RuntimeIpc.unpackCommitReservedTargetRequest(msg.data) ?: run {
            Log.w(TAG, "Rejected malformed legacy insert")
            return
        }
        legacyInsertionJob?.cancel()
        legacyInsertionJob = scope.launch {
            refreshFocusedTarget()
            val target = gateway.captureTarget(request.sessionId)
            val commitStartedAtNanos = if (target == null) null else monotonicNowNanos()
            val result = if (target == null) {
                val focus = tracker.currentFocus
                if (focus?.isSecure == true || focus?.isUncertain == true) {
                    InsertionResult.Failed(InsertionDecision.targetNotSafe())
                } else {
                    InsertionResult.Failed(
                        com.whispertype.android.core.model.DictationFailure(
                            code = "insert_target_ineligible",
                            message = "No safe text field is focused. Not a password or secure field.",
                            recoverable = true,
                        ),
                    )
                }
            } else {
                gateway.insert(target, request.text)
            }
            val commitCompletedAtNanos = if (target == null) null else monotonicNowNanos()
            sendCommitResult(
                sessionId = request.sessionId,
                pending = PendingCommitReply(
                    replyTo = replyTo,
                    requestedAtNanos = request.requestedAtNanos,
                    receivedAtNanos = receivedAtNanos,
                ),
                terminal = TargetReservationStore.TerminalResult(
                    result = result,
                    commitStartedAtNanos = commitStartedAtNanos,
                    commitCompletedAtNanos = commitCompletedAtNanos,
                ),
            )
        }
    }

    // ------------------------------------------------------------------
    // Live focus / keyboard
    // ------------------------------------------------------------------

    private fun refreshFocusedEditorAndKeyboard() {
        refreshFocusedTarget()
        refreshKeyboardVisible()
    }

    private fun refreshFocusedTarget() {
        tracker.refreshFromRoot(rootInActiveWindow)
        invalidateReservationsAgainstCurrentFocus()
    }

    private fun invalidateReservationsAgainstCurrentFocus() {
        targetReservations.invalidateAgainst(tracker.currentFocus?.reservationIdentity())
    }

    private fun refreshKeyboardVisible() {
        // 0.6.0: scan every display (API 33+, our minSdk) so an IME on a
        // secondary display (e.g. Samsung DeX) counts as keyboard-visible.
        val hasIme = try {
            val windowsByDisplay = windowsOnAllDisplays
            (0 until windowsByDisplay.size).any { i ->
                windowsByDisplay.valueAt(i).any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            }
        } catch (_: Throwable) {
            false
        }
        Log.d(TAG, "Keyboard window probe: hasIme=$hasIme")
        tracker.updateKeyboardVisible(hasIme)
    }

    private fun resolveLiveTarget(): LiveTarget? {
        val root = rootInActiveWindow ?: return null
        val focus = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: root
        if (!focus.isEditable) return null
        val tracked = tracker.currentFocus ?: return null
        val packageName = focus.packageName?.toString() ?: return null
        val displayId = focus.window?.displayId ?: -1
        val windowId = focus.window?.id ?: -1
        val editorIdentity = focus.viewIdResourceName.orEmpty()
        if (tracked.packageName != packageName ||
            tracked.displayId != displayId ||
            tracked.windowId != windowId ||
            tracked.editorIdentity.orEmpty() != editorIdentity
        ) {
            return null
        }
        return LiveTarget(
            packageName = packageName,
            displayId = displayId,
            windowId = windowId,
            editorIdentity = editorIdentity,
            generation = tracked.generation,
            isSecure = tracked.isSecure,
            isUncertain = tracked.isUncertain,
        )
    }

    private fun clearReservationState() {
        pendingCommitReplies.clear()
        targetReservations.clear()
    }

    private fun monotonicNowNanos(): Long = System.nanoTime()

    private data class PendingCommitReply(
        val replyTo: Messenger,
        val requestedAtNanos: Long?,
        val receivedAtNanos: Long,
    )

    private companion object {
        const val TAG = "WhisperTypeAccessibility"

        /** Poll cadence for re-reading the on-disk app-enabled kill switch. */
        const val APP_ENABLED_POLL_MS = 2000L

        /** Same-app IPC duplicates are bounded independently of reservation count. */
        const val MAX_PENDING_COMMIT_REPLIES_PER_SESSION = 8
    }
}

/**
 * The accessibility IME surface returned by [onCreateInputMethod] (Phase 3,
 * §2.6). It exposes the current [InputMethod.AccessibilityInputConnection] used
 * by the gateway for cursor-aware `commitText()` insertion.
 */
class WhisperTypeInputMethod(service: AccessibilityService) : InputMethod(service)
