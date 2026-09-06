package com.whispertype.android.platform.ipc

import android.os.Bundle
import com.whispertype.android.core.model.InsertionResult
import com.whispertype.android.core.model.SessionId
import com.whispertype.android.core.model.TargetEligibility

/**
 * Typed cross-process message contract between the main-process
 * [com.whispertype.android.platform.runtime.FlowRuntimeService] (overlay / session
 * owner) and the `:accessibility`-process
 * [com.whispertype.android.platform.accessibility.WhisperTypeAccessibilityService]
 * (focus / target / insertion). Locked rebuild decision §3: "Use typed IPC between
 * the runtime service and accessibility service. Use accessibility IPC for focus
 * state, target capture, insertion, and recovery only."
 *
 * Messages flow over a [android.os.Messenger] bound between the two processes;
 * every payload is packed / unpacked through the helpers below so the wire format
 * is a single source of truth.
 */
object RuntimeIpc {

    /** FlowRuntimeService binding action (same-app cross-process bind). */
    const val SERVICE_ACTION = "com.whispertype.android.action.BIND_RUNTIME"

    // Message.what codes
    const val MSG_REGISTER_REPLY = 1
    const val MSG_ELIGIBILITY = 2
    const val MSG_INSERT = 3
    const val MSG_INSERT_RESULT = 4

    /** 0.6.0: physical-keyboard hotkey press (start or complete dictation). */
    const val MSG_HOTKEY_TOGGLE = 5

    /** Main -> accessibility: pin the safe target that exists at tap time. */
    const val MSG_RESERVE_TARGET = 6

    /** Accessibility -> main: typed acknowledgement of [MSG_RESERVE_TARGET]. */
    const val MSG_RESERVE_TARGET_RESULT = 7

    /** Main -> accessibility: commit text once to the target pinned for this session. */
    const val MSG_COMMIT_RESERVED_TARGET = 8

    /** Main -> accessibility: discard a reservation after cancellation or failure. */
    const val MSG_RELEASE_RESERVED_TARGET = 9

    // Bundle keys. Insert text is sensitive dictated output; existing editor
    // content and target-snapshot identity fields are never transported.
    const val KEY_REPLY_MESSENGER = "reply_messenger"
    const val KEY_SESSION_ID = "session_id"
    const val KEY_INSERT_TEXT = "insert_text"
    const val KEY_RESERVATION_STATUS = "reservation_status"
    const val KEY_REQUESTED_AT_NANOS = "requested_at_nanos"
    const val KEY_RECEIVED_AT_NANOS = "received_at_nanos"
    const val KEY_COMMIT_STARTED_AT_NANOS = "commit_started_at_nanos"
    const val KEY_COMMIT_COMPLETED_AT_NANOS = "commit_completed_at_nanos"
    const val KEY_REPLIED_AT_NANOS = "replied_at_nanos"

    const val KEY_SERVICE_CONNECTED = "service_connected"
    const val KEY_EDITOR_FOCUSED = "editor_focused"
    const val KEY_EDITOR_SECURE = "editor_secure"
    const val KEY_EDITOR_UNCERTAIN = "editor_uncertain"
    const val KEY_KEYBOARD_VISIBLE = "keyboard_visible"
    const val KEY_MIC_GRANTED = "mic_granted"
    const val KEY_API_CONFIGURED = "api_configured"
    const val KEY_APP_ENABLED = "app_enabled"
    const val KEY_SESSION_ACTIVE = "session_active"
    const val KEY_DISPLAY_ID = "display_id"

    const val KEY_CONNECTION_PRESENT = "connection_present"
    const val KEY_FAILURE_CODE = "failure_code"
    const val KEY_FAILURE_MESSAGE = "failure_message"
    const val KEY_FAILURE_RECOVERABLE = "failure_recoverable"
    const val KEY_FAILURE_RETRY_ALLOWED = "failure_retry_allowed"

    /**
     * Content-free result of a target-reservation request. No package, window,
     * display, editor identity, selection, or other snapshot field crosses IPC.
     */
    enum class ReservationStatus(val wireValue: String) {
        RESERVED("reserved"),
        INELIGIBLE("ineligible"),
        CAPACITY_EXCEEDED("capacity_exceeded"),
        TARGET_CHANGED("target_changed"),
        SESSION_TERMINAL("session_terminal"),
        ;

        companion object {
            fun fromWireValue(value: String?): ReservationStatus? =
                entries.firstOrNull { it.wireValue == value }
        }
    }

    data class ReserveTargetRequest(
        val sessionId: SessionId,
        val requestedAtNanos: Long? = null,
    )

    data class ReserveTargetResult(
        val sessionId: SessionId,
        val status: ReservationStatus,
        val requestedAtNanos: Long?,
        val receivedAtNanos: Long?,
        val repliedAtNanos: Long?,
    ) {
        val reserved: Boolean
            get() = status == ReservationStatus.RESERVED
    }

    data class CommitReservedTargetRequest(
        val sessionId: SessionId,
        /** Settled dictation text only; never existing editor content. */
        val text: String,
        val requestedAtNanos: Long? = null,
    )

    data class CommitReservedTargetResult(
        val sessionId: SessionId,
        val result: InsertionResult,
        val requestedAtNanos: Long?,
        val receivedAtNanos: Long?,
        val commitStartedAtNanos: Long?,
        val commitCompletedAtNanos: Long?,
        val repliedAtNanos: Long?,
    )

    data class ReleaseReservedTargetRequest(
        val sessionId: SessionId,
        val requestedAtNanos: Long? = null,
    )

    /** Packs an eligibility snapshot into a Bundle (see [unpackEligibility]). */
    fun packEligibility(e: TargetEligibility): Bundle = Bundle().apply {
        putBoolean(KEY_SERVICE_CONNECTED, e.serviceConnected)
        putBoolean(KEY_EDITOR_FOCUSED, e.editorFocused)
        putBoolean(KEY_EDITOR_SECURE, e.editorSecure)
        putBoolean(KEY_EDITOR_UNCERTAIN, e.editorUncertain)
        putBoolean(KEY_KEYBOARD_VISIBLE, e.keyboardVisible)
        putBoolean(KEY_MIC_GRANTED, e.microphoneGranted)
        putBoolean(KEY_API_CONFIGURED, e.apiKeyConfigured)
        putBoolean(KEY_APP_ENABLED, e.appEnabled)
        putBoolean(KEY_SESSION_ACTIVE, e.sessionActive)
        putInt(KEY_DISPLAY_ID, e.displayId)
    }

    fun unpackEligibility(b: Bundle): TargetEligibility = TargetEligibility(
        serviceConnected = b.getBoolean(KEY_SERVICE_CONNECTED),
        editorFocused = b.getBoolean(KEY_EDITOR_FOCUSED),
        editorSecure = b.getBoolean(KEY_EDITOR_SECURE),
        editorUncertain = b.getBoolean(KEY_EDITOR_UNCERTAIN),
        keyboardVisible = b.getBoolean(KEY_KEYBOARD_VISIBLE),
        microphoneGranted = b.getBoolean(KEY_MIC_GRANTED),
        apiKeyConfigured = b.getBoolean(KEY_API_CONFIGURED),
        appEnabled = b.getBoolean(KEY_APP_ENABLED),
        sessionActive = b.getBoolean(KEY_SESSION_ACTIVE),
        displayId = b.getInt(KEY_DISPLAY_ID, TargetEligibility.DEFAULT_DISPLAY_ID),
    )

    fun packReserveTargetRequest(request: ReserveTargetRequest): Bundle = Bundle().apply {
        putString(KEY_SESSION_ID, request.sessionId.value)
        putOptionalLong(KEY_REQUESTED_AT_NANOS, request.requestedAtNanos)
    }

    fun unpackReserveTargetRequest(b: Bundle): ReserveTargetRequest? {
        val sessionId = b.sessionIdOrNull() ?: return null
        return ReserveTargetRequest(
            sessionId = sessionId,
            requestedAtNanos = b.optionalLong(KEY_REQUESTED_AT_NANOS),
        )
    }

    fun packReserveTargetResult(result: ReserveTargetResult): Bundle = Bundle().apply {
        putString(KEY_SESSION_ID, result.sessionId.value)
        putString(KEY_RESERVATION_STATUS, result.status.wireValue)
        putOptionalLong(KEY_REQUESTED_AT_NANOS, result.requestedAtNanos)
        putOptionalLong(KEY_RECEIVED_AT_NANOS, result.receivedAtNanos)
        putOptionalLong(KEY_REPLIED_AT_NANOS, result.repliedAtNanos)
    }

    fun unpackReserveTargetResult(b: Bundle): ReserveTargetResult? {
        val sessionId = b.sessionIdOrNull() ?: return null
        val status = ReservationStatus.fromWireValue(b.getString(KEY_RESERVATION_STATUS)) ?: return null
        return ReserveTargetResult(
            sessionId = sessionId,
            status = status,
            requestedAtNanos = b.optionalLong(KEY_REQUESTED_AT_NANOS),
            receivedAtNanos = b.optionalLong(KEY_RECEIVED_AT_NANOS),
            repliedAtNanos = b.optionalLong(KEY_REPLIED_AT_NANOS),
        )
    }

    fun packCommitReservedTargetRequest(request: CommitReservedTargetRequest): Bundle = Bundle().apply {
        putString(KEY_SESSION_ID, request.sessionId.value)
        putString(KEY_INSERT_TEXT, request.text)
        putOptionalLong(KEY_REQUESTED_AT_NANOS, request.requestedAtNanos)
    }

    fun unpackCommitReservedTargetRequest(b: Bundle): CommitReservedTargetRequest? {
        val sessionId = b.sessionIdOrNull() ?: return null
        val text = b.getString(KEY_INSERT_TEXT) ?: return null
        return CommitReservedTargetRequest(
            sessionId = sessionId,
            text = text,
            requestedAtNanos = b.optionalLong(KEY_REQUESTED_AT_NANOS),
        )
    }

    fun packReleaseReservedTargetRequest(request: ReleaseReservedTargetRequest): Bundle = Bundle().apply {
        putString(KEY_SESSION_ID, request.sessionId.value)
        putOptionalLong(KEY_REQUESTED_AT_NANOS, request.requestedAtNanos)
    }

    fun unpackReleaseReservedTargetRequest(b: Bundle): ReleaseReservedTargetRequest? {
        val sessionId = b.sessionIdOrNull() ?: return null
        return ReleaseReservedTargetRequest(
            sessionId = sessionId,
            requestedAtNanos = b.optionalLong(KEY_REQUESTED_AT_NANOS),
        )
    }

    /** Packs a typed insertion result into a Bundle (see [unpackInsertionResult]). */
    fun packInsertionResult(result: InsertionResult, sessionId: String? = null, b: Bundle = Bundle()): Bundle {
        if (sessionId != null) b.putString(KEY_SESSION_ID, sessionId)
        when (result) {
            InsertionResult.Inserted -> b.putBoolean(KEY_CONNECTION_PRESENT, true)
            InsertionResult.Ambiguous -> b.putBoolean(KEY_CONNECTION_PRESENT, false)
            is InsertionResult.Failed -> {
                b.putString(KEY_FAILURE_CODE, result.failure.code)
                b.putString(KEY_FAILURE_MESSAGE, result.failure.message)
                b.putBoolean(KEY_FAILURE_RECOVERABLE, result.failure.recoverable)
                b.putBoolean(KEY_FAILURE_RETRY_ALLOWED, result.failure.retryAllowed)
            }
        }
        return b
    }

    fun unpackInsertionResult(b: Bundle): InsertionResult {
        val code = b.getString(KEY_FAILURE_CODE)
        if (code != null) {
            return InsertionResult.Failed(
                com.whispertype.android.core.model.DictationFailure(
                    code = code,
                    message = b.getString(KEY_FAILURE_MESSAGE).orEmpty(),
                    recoverable = b.getBoolean(KEY_FAILURE_RECOVERABLE),
                    retryAllowed = b.getBoolean(KEY_FAILURE_RETRY_ALLOWED, true),
                ),
            )
        }
        return if (b.getBoolean(KEY_CONNECTION_PRESENT)) {
            InsertionResult.Inserted
        } else {
            InsertionResult.Ambiguous
        }
    }

    /**
     * Packs a reserved-target terminal response onto the legacy insert-result
     * message code. This keeps the migration backwards-compatible while adding
     * content-free monotonic timing metadata.
     */
    fun packCommitReservedTargetResult(result: CommitReservedTargetResult): Bundle {
        val b = packInsertionResult(result.result, sessionId = result.sessionId.value)
        b.putOptionalLong(KEY_REQUESTED_AT_NANOS, result.requestedAtNanos)
        b.putOptionalLong(KEY_RECEIVED_AT_NANOS, result.receivedAtNanos)
        b.putOptionalLong(KEY_COMMIT_STARTED_AT_NANOS, result.commitStartedAtNanos)
        b.putOptionalLong(KEY_COMMIT_COMPLETED_AT_NANOS, result.commitCompletedAtNanos)
        b.putOptionalLong(KEY_REPLIED_AT_NANOS, result.repliedAtNanos)
        return b
    }

    fun unpackCommitReservedTargetResult(b: Bundle): CommitReservedTargetResult? {
        val sessionId = b.sessionIdOrNull() ?: return null
        return CommitReservedTargetResult(
            sessionId = sessionId,
            result = unpackInsertionResult(b),
            requestedAtNanos = b.optionalLong(KEY_REQUESTED_AT_NANOS),
            receivedAtNanos = b.optionalLong(KEY_RECEIVED_AT_NANOS),
            commitStartedAtNanos = b.optionalLong(KEY_COMMIT_STARTED_AT_NANOS),
            commitCompletedAtNanos = b.optionalLong(KEY_COMMIT_COMPLETED_AT_NANOS),
            repliedAtNanos = b.optionalLong(KEY_REPLIED_AT_NANOS),
        )
    }

    private fun Bundle.sessionIdOrNull(): SessionId? =
        getString(KEY_SESSION_ID)
            ?.takeIf { it.isNotBlank() }
            ?.let(::SessionId)

    private fun Bundle.optionalLong(key: String): Long? =
        if (containsKey(key)) getLong(key) else null

    private fun Bundle.putOptionalLong(key: String, value: Long?) {
        if (value != null) putLong(key, value)
    }
}
