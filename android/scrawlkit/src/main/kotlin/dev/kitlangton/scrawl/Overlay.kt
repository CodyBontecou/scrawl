package dev.kitlangton.scrawl

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.FrameLayout
import java.lang.ref.WeakReference

/** UIKit's feedback generators, through the view system (no VIBRATE permission). */
internal class Haptics(private val view: () -> View?) {
    /** Sees each feedback by UIKit name ("success", "light", …), for tests. */
    var observer: ((String) -> Unit)? = null

    private fun perform(name: String, constant: Int) {
        observer?.invoke(name)
        view()?.performHapticFeedback(constant, HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING)
    }

    fun success() = perform("success", if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY)
    fun warning() = perform("warning", if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS)
    fun light() = perform("light", HapticFeedbackConstants.CLOCK_TICK)
    fun medium() = perform("medium", HapticFeedbackConstants.KEYBOARD_TAP)
    fun rigid() = perform("rigid", if (Build.VERSION.SDK_INT >= 23) HapticFeedbackConstants.CONTEXT_CLICK else HapticFeedbackConstants.VIRTUAL_KEY)
    fun soft() = perform("soft", if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.GESTURE_END else HapticFeedbackConstants.CLOCK_TICK)
}

/**
 * The window above the app for freehand red strokes and the recording strip. It only exists from
 * the start of a recording until the strip has animated away after it.
 *
 * It never takes touches itself (`FLAG_NOT_TOUCHABLE`): the app windows' touch routers send it the
 * touches that land on the strip or its bubbles (or every touch while drawing), and everything else
 * reaches the app. App-frame recordings leave it out; MediaProjection recordings include it.
 */
@SuppressLint("ViewConstructor")
internal class Overlay(context: Context, private val scrawl: Scrawl) {
    val root: OverlayRoot = OverlayRoot(context)
    val ink = InkView(context)
    val strip: StripView
    private var activity: WeakReference<Activity>? = null
    private var attached = false
    private val main = Handler(Looper.getMainLooper())
    private var afterPresentation: (() -> Unit)? = null
    private var presentationPending = false
    private var presentationTrace: StartupTrace? = null
    private var preparationStartNs: Long? = null

    /** The gesture the overlay owns, and for ink, the drawing finger. */
    private var routing: Routing = Routing.None

    private sealed class Routing {
        data object None : Routing()
        data object Strip : Routing()
        data class Ink(val pointerId: Int) : Routing()
        data object Swallow : Routing()
    }

    val isActivelyDrawing: Boolean get() = ink.isActivelyDrawing

    init {
        strip = StripView(context, scrawl.haptics)
        root.addView(ink, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(strip, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // Edge-to-edge windows don't relayout for the keyboard, so nothing else tells us it moved:
        // read it each frame the overlay draws (it redraws every frame while it's showing).
        root.viewTreeObserver.addOnPreDrawListener {
            scrawl.keyboard.update(scrawl.focusedRoot())
            true
        }
        ink.onStroke = { scrawl.recordStroke(it) }
        ink.onFade = { strokes, uptime -> scrawl.recordFade(strokes, uptime) }
        strip.actions = StripActions(
            toggleDrawing = { scrawl.toggleDrawing() },
            discard = { scrawl.discard() },
            send = { scrawl.stop() },
            undoDiscard = { scrawl.undoDiscard() },
            share = { scrawl.shareFailedRecording() },
        )
        strip.onLaidOut = {
            preparationStartNs?.let { start ->
                preparationStartNs = null
                scrawl.startupObserver?.invoke(ScrawlStartupEvent("prewarmLayout", (System.nanoTime() - start) / 1e6, true))
            }
        }
        root.onLaid = { strip.onLaidOut?.invoke() }
        strip.backdrop = { activity?.get()?.window?.decorView }
        strip.onGeometry = {
            syncAccessibleWindow()
            syncGestureExclusion()
        }
    }

    // Window

    private fun params(token: android.os.IBinder?) = WindowManager.LayoutParams().apply {
        type = WindowManager.LayoutParams.TYPE_APPLICATION
        this.token = token
        width = WindowManager.LayoutParams.MATCH_PARENT
        height = WindowManager.LayoutParams.MATCH_PARENT
        gravity = Gravity.TOP or Gravity.START
        format = PixelFormat.TRANSLUCENT
        flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        if (Build.VERSION.SDK_INT >= 28) {
            layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= 30) {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        title = TITLE
        windowAnimations = 0
    }

    /** Puts the overlay window over [activity] (moving it from another activity if needed). */
    fun attach(activity: Activity, alpha: Float = 1f): Boolean {
        // An application window over the activity: its WindowManager fills in the activity's token.
        if (activity.isFinishing || activity.isDestroyed) return false
        val token: android.os.IBinder? = null
        if (attached && this.activity?.get() === activity) {
            setAlpha(alpha)
            return true
        }
        detachWindow()
        return runCatching {
            activity.windowManager.addView(root, params(token).also { it.alpha = alpha })
            this.activity = WeakReference(activity)
            attached = true
            accessibility?.addTouchExplorationStateChangeListener(explorationListener)
            windowRect = android.graphics.Rect()
            syncAccessibleWindow()
            true
        }.getOrDefault(false)
    }

    private fun setAlpha(alpha: Float) {
        val params = root.layoutParams as? WindowManager.LayoutParams ?: return
        if (params.alpha == alpha) return
        params.alpha = alpha
        runCatching { activity?.get()?.windowManager?.updateViewLayout(root, params) }
    }

    fun detachWindow() {
        if (!attached) return
        if (Build.VERSION.SDK_INT >= 29) activity?.get()?.window?.decorView?.systemGestureExclusionRects = emptyList()
        excluded = emptyList()
        accessibility?.removeTouchExplorationStateChangeListener(explorationListener)
        runCatching { activity?.get()?.windowManager?.removeViewImmediate(root) }
        attached = false
        activity = null
    }

    /** Keeps the overlay above the app's newest window (a dialog shown after it). */
    fun raiseIfCovered() {
        if (!attached) return
        val roots = Windows.roots()
        val mine = roots.indexOfFirst { it.view === root }
        if (mine < 0) return
        val covered = roots.drop(mine + 1).any { it.view.isShown && it.params?.type?.let { t -> t < WindowManager.LayoutParams.FIRST_SYSTEM_WINDOW } != false }
        if (!covered) return
        val activity = activity?.get() ?: return
        val alpha = (root.layoutParams as? WindowManager.LayoutParams)?.alpha ?: 1f
        detachWindow()
        attach(activity, alpha)
    }

    /** A hidden window doesn't lay out: mount it transparently during the deliberate hold. */
    fun prepareLayout(activity: Activity) {
        preparationStartNs = System.nanoTime()
        attach(activity, alpha = 0f)
    }

    fun present(activity: Activity, trace: StartupTrace?) {
        val laidOut = strip.width > 0
        if (!attach(activity, alpha = 1f)) {
            // No window to present in: start anyway rather than wait for a frame that never comes.
            presentationPending = false
            return
        }
        if (laidOut) trace?.mark("stripLayout") else root.onLaid = {
            strip.onLaidOut?.invoke()
            trace?.mark("stripLayout")
        }
        presentationTrace = trace
        presentationPending = true
        // The next frame after this one is drawn presents the strip.
        root.viewTreeObserver.addOnDrawListener(object : ViewTreeObserver.OnDrawListener {
            override fun onDraw() {
                main.post { root.viewTreeObserver.removeOnDrawListener(this) }
                Choreographer.getInstance().postFrameCallback { presented() }
            }
        })
        root.invalidate()
    }

    private fun presented() {
        if (!presentationPending) return
        presentationPending = false
        if (attached && ((root.layoutParams as? WindowManager.LayoutParams)?.alpha ?: 0f) > 0f) presentationTrace?.mark("firstDraw")
        presentationTrace = null
        val action = afterPresentation
        afterPresentation = null
        // Let this frame commit before the encoder and microphone start.
        if (action != null) main.post(action)
    }

    fun afterFirstPresentation(action: () -> Unit) {
        if (presentationPending) afterPresentation = action else action()
    }

    fun dismiss() {
        presentationPending = false
        presentationTrace = null
        afterPresentation = null
        routing = Routing.None
        detachWindow()
    }

    val isAttached: Boolean get() = attached
    val hostActivity: Activity? get() = activity?.get()

    // State

    fun update(mode: StripMode?, notice: StripNotice?) {
        strip.apply(mode, notice)
        strip.keyboardTopPx = scrawl.keyboardTopIn(root)
    }

    fun clearInk(animated: Boolean) {
        routing = Routing.None
        ink.clear(animated)
    }

    // Touch routing (events in screen coordinates via the app window's offset)

    private fun local(event: MotionEvent): MotionEvent {
        val copy = MotionEvent.obtain(event)
        val location = IntArray(2)
        root.getLocationOnScreen(location)
        copy.offsetLocation(event.rawX - event.x - location[0] + root.scrollX, event.rawY - event.y - location[1] + root.scrollY)
        return copy
    }

    /**
     * Offers an app window's touch to the overlay. Returns true when the overlay takes it (the strip
     * or its bubble was hit, or drawing is on); the app then never sees that gesture.
     */
    fun route(event: MotionEvent): Boolean {
        if (!attached || accessible) return false
        return routeScreen(event)
    }

    /** Touches the overlay window received itself (accessible mode). */
    fun routeDirect(event: MotionEvent): Boolean = routeScreen(event)

    // Accessible mode: with TalkBack's touch exploration on, the overlay is a normal touchable window
    // sized to the strip and its bubble (everything while drawing), so TalkBack can reach the
    // controls; touches outside it still go straight to the app. Otherwise the window never takes
    // touches and the app windows' routers hand it the ones that belong to it.

    private val accessibility = context.getSystemService(android.view.accessibility.AccessibilityManager::class.java)
    private val accessible: Boolean get() = accessibility?.isTouchExplorationEnabled == true
    private var windowRect = android.graphics.Rect()
    private val explorationListener = android.view.accessibility.AccessibilityManager.TouchExplorationStateChangeListener {
        windowRect = android.graphics.Rect()
        syncAccessibleWindow()
    }

    /** The window's rect in screen px for accessible mode. */
    private fun accessibleRect(): android.graphics.Rect {
        val metrics = root.resources.displayMetrics
        val full = android.graphics.Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
        if ((scrawl.isRecording || scrawl.isStarting) && scrawl.isDrawing) return full
        val rects = strip.touchRects()
        if (rects.isEmpty()) return android.graphics.Rect(0, 0, 1, 1)
        val union = android.graphics.Rect(rects[0])
        rects.drop(1).forEach { union.union(it) }
        return union
    }

    private var excluded = emptyList<android.graphics.Rect>()

    /**
     * The strip sits a few dp from the screen's edge, inside Android's back-gesture zone: it claims
     * its own area on the app window it floats over, so dragging its dot moves it rather than going
     * back (iOS has no edge gesture competing with it).
     */
    private fun syncGestureExclusion() {
        if (Build.VERSION.SDK_INT < 29) return
        val decor = activity?.get()?.window?.decorView ?: return
        val rects = if (attached) strip.touchRects() else emptyList()
        if (rects == excluded) return
        excluded = rects
        val origin = IntArray(2).also { decor.getLocationOnScreen(it) }
        val overlayOrigin = IntArray(2).also { root.getLocationOnScreen(it) }
        decor.systemGestureExclusionRects = rects.map {
            android.graphics.Rect(it).apply { offset(overlayOrigin[0] - origin[0] - root.scrollX, overlayOrigin[1] - origin[1] - root.scrollY) }
        }
    }

    /** Resizes the window to the strip (accessible mode); a no-op otherwise. */
    fun syncAccessibleWindow() {
        val params = root.layoutParams as? WindowManager.LayoutParams ?: return
        val metrics = root.resources.displayMetrics
        if (!accessible) {
            if (params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE == 0 || params.width != WindowManager.LayoutParams.MATCH_PARENT) {
                params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                params.width = WindowManager.LayoutParams.MATCH_PARENT
                params.height = WindowManager.LayoutParams.MATCH_PARENT
                params.x = 0; params.y = 0
                root.content(metrics.widthPixels, metrics.heightPixels, 0, 0, fixed = false)
                runCatching { activity?.get()?.windowManager?.updateViewLayout(root, params) }
            }
            return
        }
        val rect = accessibleRect()
        if (rect == windowRect && params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE == 0) return
        windowRect = rect
        params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        params.x = rect.left; params.y = rect.top
        params.width = rect.width(); params.height = rect.height()
        root.content(metrics.widthPixels, metrics.heightPixels, rect.left, rect.top, fixed = true)
        runCatching { activity?.get()?.windowManager?.updateViewLayout(root, params) }
    }

    private fun routeScreen(event: MotionEvent): Boolean {
        val action = event.actionMasked
        if (action == MotionEvent.ACTION_DOWN) {
            val local = local(event)
            routing = when {
                ((root.layoutParams as? WindowManager.LayoutParams)?.alpha ?: 0f) <= 0f -> Routing.None
                strip.wantsTouch(local.x, local.y) -> Routing.Strip
                (scrawl.isRecording || scrawl.isStarting) && scrawl.isDrawing -> Routing.Ink(event.getPointerId(0))
                else -> Routing.None
            }
            local.recycle()
        }
        val current = routing
        if (current == Routing.None) return false
        val local = local(event)
        try {
            when (current) {
                Routing.Strip -> strip.dispatchTouchEvent(local)
                is Routing.Ink -> {
                    val endsDrawing = (action == MotionEvent.ACTION_POINTER_UP && event.getPointerId(event.actionIndex) == current.pointerId) ||
                        action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL
                    if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE || endsDrawing) {
                        if (action != MotionEvent.ACTION_POINTER_DOWN) ink.touch(local, current.pointerId)
                    }
                    // Other fingers are ignored until the drawing finger lifts; the rest of the
                    // gesture is still the overlay's.
                    if (endsDrawing && action == MotionEvent.ACTION_POINTER_UP) routing = Routing.Swallow
                }
                Routing.Swallow, Routing.None -> Unit
            }
        } finally {
            local.recycle()
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) routing = Routing.None
        return true
    }

    /** The overlay window's root: reports its first layout. */
    inner class OverlayRoot(context: Context) : FrameLayout(context) {
        var onLaid: (() -> Unit)? = null

        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            if (!accessible) return false
            routeDirect(event)
            return true
        }

        /** Keeps the children screen-sized and scrolled to the window's origin (accessible mode). */
        fun content(width: Int, height: Int, left: Int, top: Int, fixed: Boolean) {
            for (i in 0 until childCount) {
                val child = getChildAt(i)
                val lp = child.layoutParams
                val w = if (fixed) width else ViewGroup.LayoutParams.MATCH_PARENT
                val h = if (fixed) height else ViewGroup.LayoutParams.MATCH_PARENT
                if (lp.width != w || lp.height != h) {
                    lp.width = w; lp.height = h; child.layoutParams = lp
                }
            }
            scrollTo(left, top)
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            super.onLayout(changed, left, top, right, bottom)
            if (width > 0 && height > 0) {
                val laid = onLaid
                onLaid = null
                laid?.invoke()
            }
        }
    }

    companion object {
        const val TITLE = "ScrawlKit"
    }
}

