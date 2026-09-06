package com.whispertype.android.platform.accessibility

import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.whispertype.android.core.model.TargetEligibility
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Immutable snapshot of the currently focused editable node. Kept tiny and
 * free of secrets / editor content; the [generation] invalidates on every
 * relevant focus change so an inserted result is never routed to a stale field
 * (FR-8).
 */
data class FocusedEditor(
    val packageName: String,
    val displayId: Int,
    val windowId: Int,
    val editorIdentity: String?,
    val inputType: Int,
    val isPassword: Boolean,
    val contentInvalid: Boolean,
    val selectionStart: Int?,
    val selectionEnd: Int?,
    val generation: Long,
) {
    val isSecure: Boolean
        get() = SecurityClassifier.classify(inputType, isPassword, contentInvalid) == Classification.SECURE

    val isUncertain: Boolean
        get() = SecurityClassifier.classify(inputType, isPassword, contentInvalid) == Classification.UNCERTAIN
}

/**
 * Tracks the focused actionable editable node and publishes a [TargetEligibility]
 * flow. Framework-coupled (consumes [AccessibilityEvent] and
 * [AccessibilityNodeInfo]), but all classification / eligibility / insertion
 * decisions are delegated to pure, host-testable objects.
 */
class EditorTracker {

    private val _eligibility = MutableStateFlow(TargetEligibility.Ineligible)
    val eligibility: StateFlow<TargetEligibility> = _eligibility.asStateFlow()

    @Volatile
    private var connected: Boolean = false

    @Volatile
    var currentFocus: FocusedEditor? = null
        private set

    private var generation: Long = 0L

    // Config inputs. Microphone / api-key are NOT required for this static
    // spike, so they default to true to keep the bubble visible; appEnabled is
    // true and sessionActive remains false (no real session yet).
    @Volatile
    private var keyboardVisible: Boolean = false

    @Volatile
    private var microphoneGranted: Boolean = true

    @Volatile
    private var apiKeyConfigured: Boolean = true

    @Volatile
    private var appEnabled: Boolean = true

    @Volatile
    private var sessionActive: Boolean = false

    /** Marks the service connected; eligibility then reports it as such. */
    fun serviceConnected() {
        connected = true
        recompute()
    }

    /**
     * Keyboard heuristic (best effort): the soft input window presence is
     * derived by the service from the interactive-window list
     * (an AccessibilityWindowInfo of type TYPE_INPUT_METHOD present). On some
     * OEMs the IME window may vanish too
     * early/late, so this flag is only a coarse gate. Callers update it on
     * every TYPE_WINDOWS_CHANGED.
     */
    fun updateKeyboardVisible(visible: Boolean) {
        keyboardVisible = visible
        recompute()
    }

    fun setMicrophoneGranted(granted: Boolean) {
        microphoneGranted = granted
        recompute()
    }

    fun setApiKeyConfigured(configured: Boolean) {
        apiKeyConfigured = configured
        recompute()
    }

    fun setAppEnabled(enabled: Boolean) {
        appEnabled = enabled
        recompute()
    }

    fun setSessionActive(active: Boolean) {
        sessionActive = active
        recompute()
    }

    // ------------------------------------------------------------------
    // Accessibility events
    // ------------------------------------------------------------------

    /**
     * Processes a TYPE_VIEW_FOCUSED event. Every real focus event bumps the
     * generation, so two fields in the same package/window with missing
     * resource IDs are distinct (FR-8).
     */
    fun onViewFocused(event: AccessibilityEvent) {
        val source = event.source
        val editor = source?.let { toEditor(it, event.windowId) }
        if (editor == null) {
            clearCurrentFocus()
            return
        }
        synchronized(this) {
            generation += 1
            currentFocus = editor.copy(generation = generation)
        }
        recompute()
    }

    /**
     * Re-resolves the focused input node from the active window root
     * (window/content changed). Keeps the generation stable when the same
     * editor is still focused.
     */
    fun refreshFromRoot(root: AccessibilityNodeInfo?) {
        val node = root
        if (node == null) {
            clearCurrentFocus()
            return
        }
        val input = node.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: node
        val editor = toEditor(input, input.window?.id ?: -1)
        if (editor == null) {
            clearCurrentFocus()
            return
        }
        synchronized(this) {
            val prev = currentFocus
            if (prev != null &&
                prev.packageName == editor.packageName &&
                prev.windowId == editor.windowId &&
                prev.displayId == editor.displayId &&
                prev.editorIdentity == editor.editorIdentity &&
                prev.inputType == editor.inputType &&
                prev.isPassword == editor.isPassword &&
                prev.contentInvalid == editor.contentInvalid
            ) {
                currentFocus = editor.copy(generation = prev.generation)
            } else {
                generation += 1
                currentFocus = editor.copy(generation = generation)
            }
        }
        recompute()
    }

    private fun clearCurrentFocus() {
        synchronized(this) {
            if (currentFocus != null) generation += 1
            currentFocus = null
        }
        recompute()
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private fun recompute() {
        val focus = currentFocus
        val classification = focus?.let {
            SecurityClassifier.classify(it.inputType, it.isPassword, it.contentInvalid)
        } ?: Classification.UNCERTAIN
        _eligibility.value = EligibilityMapper.toEligibility(
            serviceConnected = connected,
            editorFocused = focus != null,
            classification = classification,
            keyboardVisible = keyboardVisible,
            microphoneGranted = microphoneGranted,
            apiKeyConfigured = apiKeyConfigured,
            appEnabled = appEnabled,
            sessionActive = sessionActive,
            displayId = focus?.displayId?.takeIf { it >= 0 } ?: TargetEligibility.DEFAULT_DISPLAY_ID,
        )
    }

    private fun toEditor(node: AccessibilityNodeInfo, windowId: Int): FocusedEditor? {
        if (!node.isEditable) return null
        val packageName = node.packageName?.toString() ?: return null

        val className = node.className?.toString().orEmpty().lowercase()
        val classBasedPassword = className.contains("password") || className.contains("pin")
        val password = node.isPassword || classBasedPassword

        var inputType = 0
        try {
            inputType = node.inputType
        } catch (_: Throwable) {
            inputType = 0
        }

        val selStart = node.textSelectionStart
        val selEnd = node.textSelectionEnd
        return FocusedEditor(
            packageName = packageName,
            displayId = node.window?.displayId ?: -1,
            windowId = windowId,
            editorIdentity = node.viewIdResourceName,
            inputType = inputType,
            isPassword = password,
            // inputType == 0 is a normal custom/web-editor signal, not a corrupt
            // capture; SecurityClassifier now treats it as ordinary text (§2.3). This
            // flag is reserved for genuinely unreadable editors.
            contentInvalid = false,
            selectionStart = if (selStart < 0) null else selStart,
            selectionEnd = if (selEnd < 0) null else selEnd,
            generation = 0L,
        )
    }
}

