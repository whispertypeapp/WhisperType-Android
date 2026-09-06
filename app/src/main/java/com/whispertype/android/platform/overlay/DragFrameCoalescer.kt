package com.whispertype.android.platform.overlay

/** Aggregate pointer deltas applied by one WindowManager update per frame. */
internal data class DragDelta(val x: Float, val y: Float)

/**
 * Framework-free scheduling gate used by [PersistentOverlayHost]. [enqueue]
 * returns true only when the host needs to post a new frame callback.
 */
internal class DragFrameCoalescer {
    private var pendingX = 0f
    private var pendingY = 0f

    var hasPendingFrame: Boolean = false
        private set

    fun enqueue(dx: Float, dy: Float): Boolean {
        if (!dx.isFinite() || !dy.isFinite() || (dx == 0f && dy == 0f)) return false
        pendingX += dx
        pendingY += dy
        if (hasPendingFrame) return false
        hasPendingFrame = true
        return true
    }

    fun consume(): DragDelta? {
        if (!hasPendingFrame) return null
        hasPendingFrame = false
        val delta = DragDelta(pendingX, pendingY)
        pendingX = 0f
        pendingY = 0f
        return delta.takeUnless { it.x == 0f && it.y == 0f }
    }

    fun clear() {
        pendingX = 0f
        pendingY = 0f
        hasPendingFrame = false
    }
}
