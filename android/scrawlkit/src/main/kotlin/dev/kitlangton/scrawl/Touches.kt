package dev.kitlangton.scrawl

import android.graphics.Rect as AndroidRect
import android.os.Handler
import android.os.Looper
import android.view.ActionMode
import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.SearchEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import kotlin.math.hypot
import kotlin.math.max

/**
 * Wraps an app window's `Window.Callback` to see every touch without taking part: the overlay
 * gets the touches that land on the strip (or every touch while drawing); everything else goes to
 * the app unchanged, after being logged for the timeline and watched for the three-finger trigger.
 */
internal class TouchRouter(
    val original: Window.Callback,
    private val window: Window,
    val scrawl: Scrawl,
) : Window.Callback by original {
    private val trigger = ThreeFingerTrigger(scrawl, window)
    private var overlayGesture = false

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val overlay = scrawl.overlay
        if (event.actionMasked == MotionEvent.ACTION_DOWN) overlayGesture = overlay?.route(event) == true
        else if (overlayGesture) overlay?.route(event)
        if (overlayGesture) {
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) overlayGesture = false
            return true
        }
        if (scrawl.isConfigured) {
            trigger.observe(event)
            scrawl.recordTouches(event, window.decorView)
        }
        return original.dispatchTouchEvent(event)
    }

    // Explicit overrides for members added after API 21 so older delegates aren't required to have them.
    override fun onSearchRequested(searchEvent: SearchEvent?): Boolean = original.onSearchRequested(searchEvent)
    override fun onWindowStartingActionMode(callback: ActionMode.Callback?, type: Int): ActionMode? =
        original.onWindowStartingActionMode(callback, type)

    override fun onProvideKeyboardShortcuts(data: MutableList<KeyboardShortcutGroup>?, menu: Menu?, deviceId: Int) =
        original.onProvideKeyboardShortcuts(data, menu, deviceId)

    override fun onPointerCaptureChanged(hasCapture: Boolean) = original.onPointerCaptureChanged(hasCapture)
    override fun dispatchKeyEvent(event: KeyEvent?): Boolean = original.dispatchKeyEvent(event)
    override fun dispatchPopulateAccessibilityEvent(event: AccessibilityEvent?): Boolean = original.dispatchPopulateAccessibilityEvent(event)
    override fun onMenuItemSelected(featureId: Int, item: MenuItem): Boolean = original.onMenuItemSelected(featureId, item)
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        original.onWindowFocusChanged(hasFocus)
        // A dialog took focus (or gave it back): wrap any new window and keep the overlay on top.
        scrawl.windowsChanged()
    }

    override fun onAttachedToWindow() {
        original.onAttachedToWindow()
        scrawl.windowsChanged()
    }

    override fun onDetachedFromWindow() = original.onDetachedFromWindow()
    override fun onWindowAttributesChanged(attrs: WindowManager.LayoutParams?) = original.onWindowAttributesChanged(attrs)
}

/**
 * Hold three fingers on the screen for half a second to start or stop (UIKit's long press:
 * exactly three touches, 0.5 s, 10 dp of allowable movement, fires once). The third finger landing
 * prewarms the overlay.
 */
internal class ThreeFingerTrigger(private val scrawl: Scrawl, private val window: Window) {
    private val main = Handler(Looper.getMainLooper())
    private var starts = mutableMapOf<Int, Pair<Float, Float>>()
    private var armed = false
    private var fired = false
    private val fire = Runnable {
        if (armed && !fired && starts.size == 3) {
            fired = true
            scrawl.triggerRecognized()
        }
    }

    fun observe(event: MotionEvent) {
        if (scrawl.trigger != ScrawlTrigger.ThreeFingerLongPress) return
        val density = window.decorView.resources.displayMetrics.density
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = event.actionIndex
                starts[event.getPointerId(i)] = event.getX(i) to event.getY(i)
                main.removeCallbacks(fire)
                armed = starts.size == 3 && !fired
                if (starts.size == 3) {
                    scrawl.prepareToStart()
                    if (armed) main.postDelayed(fire, PRESS_MS)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (!armed) return
                for (i in 0 until event.pointerCount) {
                    val start = starts[event.getPointerId(i)] ?: continue
                    if (hypot(event.getX(i) - start.first, event.getY(i) - start.second) / density > ALLOWABLE_MOVEMENT_DP) {
                        armed = false
                        main.removeCallbacks(fire)
                    }
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                starts.remove(event.getPointerId(event.actionIndex))
                armed = false
                main.removeCallbacks(fire)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                starts.clear()
                armed = false
                fired = false
                main.removeCallbacks(fire)
            }
        }
    }

    companion object {
        private const val PRESS_MS = 500L
        private const val ALLOWABLE_MOVEMENT_DP = 10f
    }
}

/** Finds a concise human-readable label for what's under a touch. */
internal object HitLabels {
    /** [x], [y] in [root]'s px. */
    fun label(root: View, x: Float, y: Float): String? {
        val density = root.resources.displayMetrics.density
        val path = hitPath(root, x, y)
        // From the deepest view up, at most 7 levels.
        for (view in path.asReversed().take(7)) {
            // Compose screens (and WebViews) keep their elements below one View.
            val rootLoc = IntArray(2).also { root.getLocationInWindow(it) }
            ComposeLabels.label(view, x + rootLoc[0], y + rootLoc[1])?.let { return it }
            virtualLabel(view, root, x, y)?.let { return it }
            cleaned(view.contentDescription)?.let { return it }
            if (view is Button) cleaned(view.text)?.let { return it }
            // A text field reads as its placeholder (as UIKit's does), then what's typed.
            if (view is EditText) (cleaned(view.hint) ?: cleaned(view.text))?.let { return it }
            if (view is TextView) cleaned(view.text)?.let { return it }
            // Shallow labels inside compact controls/rows (<= 110 dp tall).
            if (view.height / density <= 110) nearestChildLabel(view, root, x, y, 3)?.let { return it }
        }
        return null
    }

    /** The views under ([x], [y]) from [root] down to the deepest one that would get the touch. */
    private fun hitPath(root: View, x: Float, y: Float): List<View> {
        val path = mutableListOf<View>()
        var view: View? = root
        val rootLoc = IntArray(2).also { root.getLocationInWindow(it) }
        val loc = IntArray(2)
        while (view != null) {
            path += view
            val group = view as? ViewGroup ?: break
            var next: View? = null
            for (i in group.childCount - 1 downTo 0) {
                val child = group.getChildAt(i)
                if (child.visibility != View.VISIBLE || child.alpha <= 0.05f) continue
                child.getLocationInWindow(loc)
                val left = loc[0] - rootLoc[0]
                val top = loc[1] - rootLoc[1]
                if (x >= left && x < left + child.width * child.scaleX && y >= top && y < top + child.height * child.scaleY) {
                    next = child
                    break
                }
            }
            view = next
        }
        return path
    }

    private fun nearestChildLabel(container: View, root: View, x: Float, y: Float, depth: Int): String? {
        if (container.visibility != View.VISIBLE || container.alpha <= 0.05f) return null
        var best: Pair<Double, String>? = null
        val rootLoc = IntArray(2).also { root.getLocationInWindow(it) }
        val loc = IntArray(2)
        fun walk(view: View, remaining: Int) {
            if (remaining < 0 || view.visibility != View.VISIBLE || view.alpha <= 0.05f) return
            if (view is TextView) {
                cleaned(view.text)?.let { text ->
                    view.getLocationInWindow(loc)
                    val left = (loc[0] - rootLoc[0]).toDouble()
                    val top = (loc[1] - rootLoc[1]).toDouble()
                    val dx = max(max(left - x, 0.0), x - (left + view.width))
                    val dy = max(max(top - y, 0.0), y - (top + view.height))
                    val dist = hypot(dx, dy)
                    if (best == null || dist < best!!.first) best = dist to text
                }
            }
            if (view is ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i), remaining - 1)
        }
        walk(container, depth)
        return best?.second
    }

    /**
     * Views with a virtual hierarchy (Compose, WebView) publish it through their accessibility node
     * provider: the deepest node under the touch with a description or text, or the nearest text in
     * a compact node around it.
     */
    private fun virtualLabel(view: View, root: View, x: Float, y: Float): String? {
        val provider = view.accessibilityNodeProvider ?: return null
        val screen = IntArray(2).also { root.getLocationOnScreen(it) }
        val sx = (x + screen[0]).toInt()
        val sy = (y + screen[1]).toInt()
        val host = runCatching { provider.createAccessibilityNodeInfo(View.NO_ID) }.getOrNull() ?: return null
        val bounds = AndroidRect()
        var node: AccessibilityNodeInfo? = host
        val chain = mutableListOf<AccessibilityNodeInfo>()
        var guard = 0
        while (node != null && guard++ < 64) {
            chain += node
            var next: AccessibilityNodeInfo? = null
            for (i in node.childCount - 1 downTo 0) {
                val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
                child.getBoundsInScreen(bounds)
                if (child.isVisibleToUser && bounds.contains(sx, sy)) {
                    next = child; break
                }
            }
            node = next
        }
        val density = root.resources.displayMetrics.density
        for (n in chain.asReversed().take(7)) {
            cleaned(n.contentDescription)?.let { return it }
            cleaned(n.text)?.let { return it }
            n.getBoundsInScreen(bounds)
            if (bounds.height() / density <= 110) {
                var best: Pair<Double, String>? = null
                fun walk(m: AccessibilityNodeInfo, remaining: Int) {
                    if (remaining < 0) return
                    val text = cleaned(m.text) ?: cleaned(m.contentDescription)
                    if (text != null) {
                        m.getBoundsInScreen(bounds)
                        val dx = max(max(bounds.left - sx, 0), sx - bounds.right).toDouble()
                        val dy = max(max(bounds.top - sy, 0), sy - bounds.bottom).toDouble()
                        val dist = hypot(dx, dy)
                        if (best == null || dist < best!!.first) best = dist to text
                    }
                    for (i in 0 until m.childCount) runCatching { m.getChild(i) }.getOrNull()?.let { walk(it, remaining - 1) }
                }
                walk(n, 3)
                best?.second?.let { return it }
            }
        }
        return null
    }

    fun cleaned(raw: CharSequence?): String? {
        val compact = raw?.toString()?.split(Regex("\\s+"))?.filter { it.isNotEmpty() }?.joinToString(" ") ?: return null
        if (compact.isEmpty()) return null
        return if (compact.length > 48) compact.take(48) + "…" else compact
    }
}
