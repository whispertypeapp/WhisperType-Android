package com.whispertype.android.platform.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.TextView
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.whispertype.android.core.contracts.OverlayController
import com.whispertype.android.core.model.DictationState
import com.whispertype.android.core.model.OverlayIntent
import com.whispertype.android.core.model.OverlayUiState
import com.whispertype.android.core.model.TargetEligibility
import com.whispertype.android.core.overlay.BubblePlacement
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * The one persistent production overlay window.
 *
 * Positioning is deliberately simple (0.4.1): the window always uses
 * `gravity = TOP|START` with an absolute pixel [currentPixel] as the single
 * source of truth. Drag deltas are coalesced into at most one
 * `updateViewLayout` per display frame and persisted once at drag end;
 * clamping uses the real measured view size. While dragging, a small "X" drop
 * target appears near the bottom; dropping the bubble on it hides the bubble
 * until the next eligible field is focused.
 *
 * The window is `TYPE_APPLICATION_OVERLAY` (SYSTEM_ALERT_WINDOW), WRAP_CONTENT,
 * translucent, non-focusable, touchable. A lifecycle-backed ComposeView renders
 * the content; a pure [OverlayHostStateMachine] drives attach/detach with a
 * bounded retry.
 */
class PersistentOverlayHost(
    private val serviceContext: Context,
    private val owners: OverlayOwners,
    sessionState: Flow<DictationState>,
    eligibility: Flow<TargetEligibility>,
    /** 0.6.0: display hosting the focused editor; the window follows it (DeX). */
    displayId: Flow<Int> = flowOf(TargetEligibility.DEFAULT_DISPLAY_ID),
    bubbleSizeDp: Flow<Int> = flowOf(OverlayAppearance.DEFAULT_BUBBLE_SIZE_DP),
    bubbleOpacityPercent: Flow<Int> = flowOf(100),
    miniDotEnabled: Flow<Boolean> = flowOf(true),
    miniDotDelaySeconds: Flow<Int> = flowOf(OverlayAppearance.DEFAULT_MINI_DOT_DELAY_SECONDS),
    /** 0.4.2 kill switch: when false, the idle bubble is hidden until re-enabled. */
    appEnabled: Flow<Boolean> = flowOf(true),
    private val placement: OverlayPlacement = OverlayPlacement(),
    private val maxRetries: Int = MAX_ATTACH_RETRIES,
    private val onBubblePositionChange: ((x: Float, y: Float) -> Unit)? = null,
) : OverlayController {

    private val _uiState = MutableStateFlow(OverlayUiState.Hidden)
    override val uiState: StateFlow<OverlayUiState> = _uiState

    private val _appearance = MutableStateFlow(OverlayAppearance())
    val appearance: StateFlow<OverlayAppearance> = _appearance

    private val _intents = MutableSharedFlow<OverlayIntent>(extraBufferCapacity = 4)
    override val intents: SharedFlow<OverlayIntent> = _intents

    private val _status = MutableStateFlow<OverlayHostStatus>(OverlayHostStatus.Detached)
    val status: StateFlow<OverlayHostStatus> = _status

    private val _lastFailure = MutableStateFlow<String?>(null)
    val lastFailure: StateFlow<String?> = _lastFailure

    private val machine = OverlayHostStateMachine()
    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var view: View? = null

    @Volatile
    private var windowManager: WindowManager? = null

    /** Display id the overlay window is currently attached to (0 = default). */
    @Volatile
    private var activeDisplayId: Int = TargetEligibility.DEFAULT_DISPLAY_ID

    /** Context bound to the active display; drives density/size math. */
    @Volatile
    private var activeContext: Context = serviceContext

    /** Saved bubble top-left position in dp (null = default right-center). */
    @Volatile
    private var bubblePositionDp: Pair<Float, Float>? = null

    /** Absolute top-left pixel position of the window - the single source of truth. */
    @Volatile
    private var currentPixel: Pair<Int, Int>? = null

    /** While true, the bubble stays hidden until the next eligibility cycle. */
    @Volatile
    private var dismissed = false

    @Volatile
    private var wasEligible = false

    /** startOwners() must run once per owner lifecycle — retries must not re-run it
     *  (SavedStateRegistryController.performAttach throws otherwise). */
    @Volatile
    private var ownersStarted = false

    @Volatile
    private var dropTargetView: View? = null

    @Volatile
    private var dropTargetBounds: Rect? = null

    /** Last visibility used to avoid re-anchoring on amplitude-only updates. */
    @Volatile
    private var renderedVisibility: OverlayVisibility = OverlayVisibility.Hidden

    /** Bubble center (px) captured before the pill anchor, so the bubble is
     *  restored to the same spot when the session returns to idle. */
    @Volatile
    private var storedBubbleCenter: Pair<Int, Int>? = null

    private var pendingAnchorView: View? = null
    private var pendingAnchorListener: ViewTreeObserver.OnPreDrawListener? = null

    private val dragFrames = DragFrameCoalescer()
    private var dragFrameView: View? = null
    private var dragActive = false
    private var dragEnding = false
    private val dragFrameCallback = Runnable {
        dragFrameView = null
        dragFrames.consume()?.let(::applyDragDelta)
        if (dragEnding) completeDrag()
    }

    private var retryCount = 0

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    init {
        scope.launch {
            combine(sessionState, eligibility, appEnabled) { state, target, enabled ->
                Triple(state, target, enabled)
            }
                .distinctUntilChanged()
                .collect { (state, target, enabled) ->
                    val eligible = target.eligible
                    // A fresh eligibility cycle (ineligible -> eligible) re-shows a
                    // dismissed bubble and re-attempts a failed overlay attach
                    // (e.g. after the user grants Display-over-other-apps).
                    if (eligible && !wasEligible) {
                        dismissed = false
                        // Re-attempt a failed overlay attach (e.g. after the user
                        // grants Display-over-other-apps). No-op when attached.
                        if (machine.status is OverlayHostStatus.AttachFailed) attach()
                    }
                    wasEligible = eligible
                    // 0.4.2 kill switch: with the app disabled, the idle bubble is
                    // hidden entirely (active sessions are not interrupted).
                    val ui = OverlayUiState(eligibility = target, state = state)
                    val effective =
                        if ((dismissed || !enabled) && state is DictationState.Idle) {
                            OverlayUiState.Hidden
                        } else {
                            ui
                        }
                    render(effective)
                }
        }
        // 0.4.2: combine the user-configurable bubble appearance settings.
        scope.launch {
            combine(bubbleSizeDp, bubbleOpacityPercent, miniDotEnabled, miniDotDelaySeconds) { size, opacity, dot, delay ->
                OverlayAppearance(
                    bubbleSizeDp = size.coerceIn(24, 72),
                    opacityPercent = opacity.coerceIn(10, 100),
                    miniDotEnabled = dot,
                    miniDotAutoMinimizeMs = delay.coerceIn(1, 15) * 1000L,
                )
            }
                .distinctUntilChanged()
                .collect { _appearance.value = it }
        }
        // 0.6.0: follow the display hosting the focused editor (Samsung DeX
        // secondary display). The overlay window is re-parented on change; a
        // pre-attach change is picked up by performAttach via activeDisplayId.
        scope.launch {
            displayId.distinctUntilChanged().collect { id ->
                if (id != activeDisplayId) {
                    activeDisplayId = id
                    handler.post { moveWindowToDisplay(activeDisplayId) }
                }
            }
        }
    }

    /** Publishes one render state and schedules anchoring only for surface changes. */
    private fun render(ui: OverlayUiState) {
        val nextVisibility = visibilityOf(ui)
        val previousVisibility = renderedVisibility
        val anchorMode = anchorModeFor(nextVisibility)
        if (anchorMode != null && storedBubbleCenter == null) {
            storedBubbleCenter = bubbleCenter()
        }
        renderedVisibility = nextVisibility
        _uiState.value = ui

        when {
            anchorMode != null && nextVisibility != previousVisibility ->
                schedulePillAnchor(nextVisibility, anchorMode)

            nextVisibility == OverlayVisibility.IdleBubble && storedBubbleCenter != null ->
                schedulePillAnchor(
                    visibility = nextVisibility,
                    mode = OverlayAnchorMode.Center,
                    clearStoredCenter = true,
                )

            anchorMode == null && storedBubbleCenter != null ->
                restoreBubblePosition()
        }
    }

    /** Remembers the saved bubble top-left position (dp), or clears it when either axis is null. */
    fun setBubblePosition(x: Float?, y: Float?) {
        bubblePositionDp = if (x != null && y != null) x to y else null
    }

    /** Adds the single persistent overlay window; idempotent, main-thread only. */
    override fun attach() {
        handler.post { performAttach() }
    }

    private fun performAttach() {
        if (!machine.attachRequested()) {
            _status.value = machine.status
            return
        }
        try {
            val context = windowContextFor(activeDisplayId) ?: serviceContext
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val container = buildWindow(context)
            if (!ownersStarted) {
                owners.startOwners()
                ownersStarted = true
            }
            activeContext = context
            windowManager = wm
            wm.addView(container, buildLayoutParams(context, placement))
            view = container
            scheduleCurrentAnchor()
            retryCount = 0
            machine.attachSucceeded()
        } catch (t: Throwable) {
            machine.attachFailed()
            _lastFailure.value = t::class.simpleName ?: "AttachFailure"
            Log.w(TAG, "Overlay attach failed", t)
            view = null
            windowManager = null
            activeContext = serviceContext
            scheduleRetry()
        }
        _status.value = machine.status
    }

    /** Builds the container + ComposeView window for [context]'s display. */
    private fun buildWindow(context: Context): View {
        val container = OverlayComposeContainer(context, owners)
        val composeView = ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                val current by uiState.collectAsState()
                val currentAppearance by appearance.collectAsState()
                WhisperTypeOverlayContent(
                    uiState = current,
                    appearance = currentAppearance,
                    onIntent = { _intents.tryEmit(it) },
                    onDragStart = { beginDrag() },
                    onDragBubble = { dx, dy -> moveBy(dx, dy) },
                    onDragEnd = { finishDrag() },
                )
            }
        }
        container.addView(composeView)
        return container
    }

    /**
     * 0.6.0: re-parents the overlay window onto [displayId]'s WindowManager
     * (Samsung DeX secondary display). The window is rebuilt on that display's
     * window context so its density/resources match. Owners stay started —
     * re-running startOwners() would re-attach SavedStateRegistryController,
     * which throws.
     */
    private fun moveWindowToDisplay(displayId: Int) {
        val currentView = view ?: return
        val oldWm = windowManager ?: return
        val context = windowContextFor(displayId) ?: serviceContext
        val newWm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (newWm === oldWm) return
        abortDrag()
        hideDropTarget()
        cancelPendingAnchor()
        try {
            oldWm.removeView(currentView)
        } catch (t: Throwable) {
            // The previous display may already be gone (e.g. DeX disconnected).
            Log.i(TAG, "Overlay window removed from previous display")
        }
        val container = buildWindow(context)
        activeContext = context
        windowManager = newWm
        try {
            newWm.addView(container, buildLayoutParams(context, placement))
            view = container
            scheduleCurrentAnchor()
        } catch (t: Throwable) {
            Log.w(TAG, "Overlay re-attach on display $displayId failed", t)
            view = null
            windowManager = null
            activeContext = serviceContext
        }
    }

    /**
     * Returns a context bound to [displayId], or null when the display is
     * unavailable (falls back to the default-display service context). Uses a
     * window context on secondary displays so the overlay window lands there.
     */
    private fun windowContextFor(displayId: Int): Context? {
        if (displayId == TargetEligibility.DEFAULT_DISPLAY_ID) return serviceContext
        val dm = serviceContext.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val display = dm.getDisplay(displayId) ?: return null
        return try {
            serviceContext.createWindowContext(
                display,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                null,
            )
        } catch (t: Throwable) {
            Log.w(TAG, "createWindowContext failed for display $displayId", t)
            null
        }
    }

    private fun scheduleRetry() {
        if (retryCount >= maxRetries) return
        retryCount += 1
        Log.i(TAG, "Scheduling bounded overlay attach retry $retryCount/$maxRetries")
        handler.postDelayed({ performAttach() }, ATTACH_RETRY_DELAY_MS)
    }

    private fun performDetach() {
        abortDrag()
        cancelPendingAnchor()
        hideDropTarget()
        if (!machine.detachRequested()) {
            _status.value = machine.status
            return
        }
        try {
            view?.let { windowManager?.removeView(it) }
        } catch (t: Throwable) {
            Log.w(TAG, "Overlay detach failed", t)
        } finally {
            view = null
            windowManager = null
            activeContext = serviceContext
        }
        owners.stopOwners()
        ownersStarted = false
        _status.value = machine.status
    }

    // ------------------------------------------------------------------
    // Positioning (the simple way): always TOP|START + absolute pixels
    // ------------------------------------------------------------------

    private fun density(): Float = activeContext.resources.displayMetrics.density

    private fun bubblePx(): Int = (placement.bubbleDp * density()).roundToInt()

    private fun displaySizePx(): Pair<Int, Int> {
        val wm = windowManager ?: serviceContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val bounds = wm.maximumWindowMetrics.bounds
        return bounds.width() to bounds.height()
    }

    /** Default placement: right edge, vertically centered (clamped on-screen). */
    private fun defaultPosition(sizePx: Int): Pair<Int, Int> {
        val marginPx = (placement.edgeMarginDp * density()).toInt()
        val (dw, dh) = displaySizePx()
        return BubblePlacement.clamp(dw - sizePx - marginPx, (dh - sizePx) / 2, sizePx, sizePx, dw, dh)
    }

    private fun overlayFlags(): Int = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN

    private fun buildLayoutParams(
        context: Context,
        placement: OverlayPlacement,
    ): WindowManager.LayoutParams {
        val sizePx = bubblePx()
        val saved = bubblePositionDp?.let {
            Pair((it.first * density()).roundToInt(), (it.second * density()).roundToInt())
        }
        val base = currentPixel ?: (saved ?: defaultPosition(sizePx))
        // Clamp against the active display so a position saved on another
        // display (different density/size) stays on-screen after a move.
        val (dw, dh) = displaySizePx()
        val clamped = BubblePlacement.clamp(base.first, base.second, sizePx, sizePx, dw, dh)
        currentPixel = clamped
        return windowParams(clamped.first, clamped.second)
    }

    private fun windowParams(x: Int, y: Int): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            overlayFlags(),
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
        }

    private fun beginDrag() {
        abortDrag()
        dragActive = true
        showDropTarget()
    }

    /**
     * Queues a pointer delta. All deltas received before the next display frame
     * are folded into one WindowManager update.
     */
    fun moveBy(dxPx: Float, dyPx: Float) {
        if (!dragActive) return
        val currentView = view ?: return
        if (dragFrames.enqueue(dxPx, dyPx)) {
            dragFrameView = currentView
            currentView.postOnAnimation(dragFrameCallback)
        }
    }

    /** Applies one frame's accumulated movement using the freshly measured bubble. */
    private fun applyDragDelta(delta: DragDelta) {
        val currentView = view ?: return
        val wm = windowManager ?: return
        val base = currentPixel ?: return
        val w = currentView.width.takeIf { it > 0 } ?: bubblePx()
        val h = currentView.height.takeIf { it > 0 } ?: bubblePx()
        val (dw, dh) = displaySizePx()
        val target = BubblePlacement.clamp(
            (base.first + delta.x).roundToInt(),
            (base.second + delta.y).roundToInt(),
            w,
            h,
            dw,
            dh,
        )
        if (target != base) {
            wm.updateViewLayout(currentView, windowParams(target.first, target.second))
        }
        currentPixel = target
    }

    /** Finishes after any queued frame, then persists exactly once for the drag. */
    private fun finishDrag() {
        if (!dragActive) {
            hideDropTarget()
            return
        }
        dragEnding = true
        if (!dragFrames.hasPendingFrame) completeDrag()
    }

    private fun completeDrag() {
        if (!dragActive) return
        dragActive = false
        dragEnding = false
        currentPixel?.let(::persistPosition)
        // Check before hiding because checkDropDismiss() reads dropTargetBounds.
        checkDropDismiss()
        hideDropTarget()
    }

    private fun abortDrag() {
        dragFrameView?.removeCallbacks(dragFrameCallback)
        dragFrameView = null
        dragFrames.clear()
        dragActive = false
        dragEnding = false
    }

    private fun persistPosition(pixel: Pair<Int, Int>) {
        val callback = onBubblePositionChange ?: return
        val xDp = pixel.first / density()
        val yDp = pixel.second / density()
        callback(xDp, yDp)
    }

    // ------------------------------------------------------------------
    // Recording-pill anchoring
    // ------------------------------------------------------------------

    /** The visible bubble's size in px (48dp touch floor, user size above). */
    private fun bubbleSizePx(): Int = (maxOf(48f, _appearance.value.bubbleSizeDp.toFloat()) * density()).roundToInt()

    /** The bubble's center (px) — the point the user tapped to start. */
    private fun bubbleCenter(): Pair<Int, Int>? {
        val base = currentPixel ?: return null
        val s = bubbleSizePx()
        return Pair(base.first + s / 2, base.second + s / 2)
    }

    private fun scheduleCurrentAnchor() {
        val mode = anchorModeFor(renderedVisibility) ?: return
        if (storedBubbleCenter == null) storedBubbleCenter = bubbleCenter()
        schedulePillAnchor(renderedVisibility, mode)
    }

    /**
     * Anchors on pre-draw, after Compose has measured the newly selected
     * surface. Replacing the pending listener prevents an older pill state from
     * applying stale dimensions after a fast transition.
     */
    private fun schedulePillAnchor(
        visibility: OverlayVisibility,
        mode: OverlayAnchorMode,
        clearStoredCenter: Boolean = false,
    ) {
        val center = storedBubbleCenter ?: return
        val anchorView = view ?: return
        cancelPendingAnchor()
        val listener = object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (pendingAnchorListener !== this) return true
                cancelPendingAnchor()
                if (
                    view !== anchorView ||
                    renderedVisibility != visibility ||
                    storedBubbleCenter != center
                ) {
                    return true
                }
                val width = anchorView.width
                val height = anchorView.height
                if (width <= 0 || height <= 0) return true
                val wm = windowManager ?: return true
                val (displayWidth, displayHeight) = displaySizePx()
                val target = anchoredTopLeft(
                    mode = mode,
                    bubbleCenter = center,
                    windowWidth = width,
                    windowHeight = height,
                    displayWidth = displayWidth,
                    displayHeight = displayHeight,
                    density = density(),
                )
                val positionChanged = target != currentPixel
                if (positionChanged) {
                    wm.updateViewLayout(anchorView, windowParams(target.first, target.second))
                    currentPixel = target
                }
                if (clearStoredCenter && storedBubbleCenter == center) {
                    storedBubbleCenter = null
                }
                // Skip this draw when the window moved so the stale anchor never flashes.
                return !positionChanged
            }
        }
        pendingAnchorView = anchorView
        pendingAnchorListener = listener
        anchorView.viewTreeObserver.addOnPreDrawListener(listener)
        anchorView.invalidate()
    }

    private fun cancelPendingAnchor() {
        val anchorView = pendingAnchorView
        val listener = pendingAnchorListener
        pendingAnchorView = null
        pendingAnchorListener = null
        if (anchorView != null && listener != null && anchorView.viewTreeObserver.isAlive) {
            anchorView.viewTreeObserver.removeOnPreDrawListener(listener)
        }
    }

    /** Restores the window to the bubble's top-left (the saved center minus half
     *  the bubble size), keeping it on-screen. */
    private fun restoreBubblePosition() {
        val center = storedBubbleCenter ?: return
        cancelPendingAnchor()
        storedBubbleCenter = null
        val s = bubbleSizePx()
        val (dw, dh) = displaySizePx()
        val target = BubblePlacement.clamp(
            center.first - s / 2,
            center.second - s / 2,
            s,
            s,
            dw,
            dh,
        )
        val currentView = view
        val wm = windowManager
        if (currentView != null && wm != null && target != currentPixel) {
            wm.updateViewLayout(currentView, windowParams(target.first, target.second))
        }
        currentPixel = target
    }

    // ------------------------------------------------------------------
    // Drag drop-target ("X")
    // ------------------------------------------------------------------

    private fun showDropTarget() {
        val wm = windowManager ?: return
        if (dropTargetView != null) return
        val size = (DROP_TARGET_DP * density()).roundToInt()
        val margin = (DROP_TARGET_MARGIN_DP * density()).roundToInt()
        val (dw, dh) = displaySizePx()
        val x = (dw - size) / 2
        val y = dh - size - margin
        val tv = TextView(serviceContext).apply {
            text = "\u2715"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 24f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xCCE8593C.toInt())
            }
        }
        val params = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            overlayFlags() or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
        }
        try {
            wm.addView(tv, params)
            dropTargetView = tv
            dropTargetBounds = Rect(x, y, x + size, y + size)
        } catch (t: Throwable) {
            Log.w(TAG, "Drop target add failed", t)
            dropTargetView = null
            dropTargetBounds = null
        }
    }

    private fun hideDropTarget() {
        val tv = dropTargetView ?: return
        dropTargetView = null
        dropTargetBounds = null
        try {
            windowManager?.removeView(tv)
        } catch (_: Throwable) {
        }
    }

    /** If the bubble was dropped on the X, hide it until the next eligible field. */
    private fun checkDropDismiss() {
        val bounds = dropTargetBounds ?: return
        val pos = currentPixel ?: return
        val vw = view?.width ?: 0
        val vh = view?.height ?: 0
        val cx = pos.first + vw / 2
        val cy = pos.second + vh / 2
        if (bounds.contains(cx, cy)) {
            dismissed = true
            val current = _uiState.value
            if (current.state is DictationState.Idle) {
                _uiState.value = OverlayUiState.Hidden
            }
        }
    }

    /** Removes the persistent overlay window; idempotent, main-thread only. */
    override fun detach() {
        handler.post { performDetach() }
    }

    private companion object {
        const val TAG = "PersistentOverlayHost"
        const val MAX_ATTACH_RETRIES = 3
        const val ATTACH_RETRY_DELAY_MS = 2000L
        const val DROP_TARGET_DP = 56f
        const val DROP_TARGET_MARGIN_DP = 24f
    }
}
