package com.whispertype.android.platform.overlay

/**
 * Typed host lifecycle status. [AttachPending] is the transient
 * window while a main-thread [android.view.WindowManager.addView] is in flight;
 * it is included so a detach issued during an in-flight attach is safe.
 */
sealed interface OverlayHostStatus {
    data object Detached : OverlayHostStatus
    data object AttachPending : OverlayHostStatus
    data object Attached : OverlayHostStatus
    data object AttachFailed : OverlayHostStatus
    data object Recovering : OverlayHostStatus
}

/**
 * Pure attach/detach lifecycle machine, independent of WindowManager so it is
 * unit-testable on the JVM host. The [PersistentOverlayHost] runs it on the
 * main thread and performs the actual window operations.
 *
 * Invariants:
 *  - [attachRequested] is idempotent: a second attach while Attached /
 *    AttachPending / Recovering is a no-op.
 *  - The host only reports [OverlayHostStatus.Attached] after [attachSucceeded],
 *    which is called strictly after `addView()` returns without throwing.
 *  - [detachRequested] is idempotent and safe during [AttachPending] (the view
 *    has not been added yet, so it merely cancels the pending attach).
 */
class OverlayHostStateMachine {
    var status: OverlayHostStatus = OverlayHostStatus.Detached
        private set

    /** Returns true when the caller should proceed to add the window. */
    fun attachRequested(): Boolean = when (status) {
        OverlayHostStatus.Detached,
        OverlayHostStatus.AttachFailed -> {
            status = OverlayHostStatus.AttachPending
            true
        }
        OverlayHostStatus.AttachPending,
        OverlayHostStatus.Attached,
        OverlayHostStatus.Recovering -> false
    }

    /** Records that `addView()` returned successfully. */
    fun attachSucceeded() {
        if (status == OverlayHostStatus.AttachPending) status = OverlayHostStatus.Attached
    }

    /** Records that `addView()` threw; the host surfaces the typed diagnostic. */
    fun attachFailed() {
        if (status == OverlayHostStatus.AttachPending) status = OverlayHostStatus.AttachFailed
    }

    /** Enters [OverlayHostStatus.Recovering]; no-op when already detached. */
    fun recovering() {
        if (status != OverlayHostStatus.Detached) status = OverlayHostStatus.Recovering
    }

    /** Returns true when the caller should remove the window. */
    fun detachRequested(): Boolean = when (status) {
        OverlayHostStatus.Attached,
        OverlayHostStatus.Recovering -> {
            status = OverlayHostStatus.Detached
            true
        }
        // In-flight attach: the view is not yet added, so just cancel it.
        OverlayHostStatus.AttachPending -> {
            status = OverlayHostStatus.Detached
            false
        }
        OverlayHostStatus.Detached,
        OverlayHostStatus.AttachFailed -> false
    }
}
