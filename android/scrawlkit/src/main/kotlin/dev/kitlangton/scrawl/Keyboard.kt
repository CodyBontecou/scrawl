package dev.kitlangton.scrawl

import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.util.UUID
import kotlin.math.max
import kotlin.math.pow

/**
 * Follows the soft keyboard from the root window insets: where it is at any moment (including
 * mid-slide) for the app-frame recorder's keyboard panel, and whether it's up, as the `keyboard`
 * context value (`visible`, or `hidden` once it goes down mid-recording).
 *
 * It only follows while Scrawl is configured, from before any recording, so a keyboard that is
 * already up when recording starts is known. When the app animates its insets the tracker follows
 * them frame by frame; otherwise it eases from the old frame to the new one (cubic ease-out over
 * the system's ~285 ms), as iOS does from the keyboard's frame notifications.
 */
internal class KeyboardTracker(private val context: ScrawlAppContext, private val isRecording: () -> Boolean) {
    private val contextToken = UUID.randomUUID()
    private var tracking = false

    /** Keyboard top edge in screen px, from and to, and the screen height (null when hidden). */
    private var fromTop: Float? = null
    private var toTop: Float? = null
    private var screenBottom = 0f
    private var screenLeft = 0f
    private var screenRight = 0f
    private var animationStartNs = 0L
    var isVisible = false
        private set

    fun setTracking(tracking: Boolean) {
        if (tracking == this.tracking) return
        this.tracking = tracking
        if (!tracking) {
            fromTop = null; toTop = null
            setVisible(false)
            context.setContext(CONTEXT_KEY, null, contextToken, promote = false)
        }
    }

    /** A recording is about to start: a keyboard that's down isn't worth a `hidden` in its context. */
    fun recordingWillBegin() {
        if (!isVisible) context.setContext(CONTEXT_KEY, null, contextToken, promote = false)
    }

    /** Reads the IME inset of [root] (the focused app window); call on layout and each capture tick. */
    fun update(root: View?) {
        if (!tracking || root == null) return
        val insets = ViewCompat.getRootWindowInsets(root) ?: return
        val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
        val location = IntArray(2)
        root.getLocationOnScreen(location)
        update(ime, location[0].toFloat(), (location[0] + root.width).toFloat(), (location[1] + root.height).toFloat(), System.nanoTime())
    }

    /** The window's IME inset ([ime] px) and its screen edges at [now] (ns). */
    fun update(ime: Int, left: Float, right: Float, bottom: Float, now: Long) {
        if (!tracking) return
        screenBottom = bottom
        screenLeft = left
        screenRight = right
        val top = if (ime > 0) bottom - ime else null
        if (top == toTop) return
        val current = currentTop(now)
        val following = toTop != null && top != null && now - animationStartNs < FOLLOW_NS
        fromTop = if (following) null else (current ?: if (top != null) bottom else null)
        toTop = top ?: if (current != null) bottom else null
        if (top == null && current == null) toTop = null
        animationStartNs = now
        setVisible(top != null)
        onTargetChange?.invoke()
    }

    /** Called when the keyboard starts moving to a new place (up, down, resized). */
    var onTargetChange: (() -> Unit)? = null

    /** Where the keyboard's top edge is going (screen px), or null when it's going away; the strip
     * rests above this, as iOS's strip follows the keyboard's end frame. */
    val targetTop: Float?
        get() = if (isVisible) toTop?.takeIf { it < screenBottom } else null

    /** The keyboard's frame in screen px right now, or null when none of it is on screen. */
    fun frame(nowNs: Long = System.nanoTime()): RectF? {
        val top = top(nowNs) ?: return null
        return RectF(screenLeft, top, screenRight, screenBottom)
    }

    /** The keyboard's top edge in screen px right now (its frame spans the window's width to its
     * bottom), or null when none of it is on screen. */
    fun top(nowNs: Long = System.nanoTime()): Float? {
        val top = currentTop(nowNs) ?: return null
        return if (screenBottom - top < 1) null else top
    }

    val left: Float get() = screenLeft
    val right: Float get() = screenRight
    val bottom: Float get() = screenBottom

    private fun currentTop(nowNs: Long): Float? {
        val to = toTop ?: return null
        val from = fromTop ?: return to
        val progress = ((nowNs - animationStartNs) / DURATION_NS.toDouble()).coerceIn(0.0, 1.0)
        val eased = 1 - (1 - progress).pow(3)
        val top = (from + (to - from) * eased).toFloat()
        if (progress >= 1 && to >= screenBottom) {
            toTop = null; fromTop = null
            return null
        }
        return top
    }

    private fun setVisible(visible: Boolean) {
        if (visible == isVisible) return
        isVisible = visible
        val value = if (visible) "visible" else if (isRecording()) "hidden" else null
        context.setContext(CONTEXT_KEY, value, contextToken, promote = false)
    }

    companion object {
        private const val CONTEXT_KEY = "keyboard"
        private const val DURATION_NS = 285_000_000L

        /** Inset changes this close together are the app animating them: follow without easing. */
        private const val FOLLOW_NS = 40_000_000L
    }
}

/**
 * The stand-in drawn where the keyboard is in app-frame recordings: the keyboard is another
 * process's window, so the app's own drawing can't capture its keys.
 */
internal object KeyboardPanel {
    private class Style(val background: Int, val separator: Int, val ink: Int)

    private val light = Style(Color.rgb(209, 213, 219), Color.argb(20, 0, 0, 0), Color.argb(97, 0, 0, 0))
    private val dark = Style(Color.rgb(43, 43, 43), Color.argb(20, 255, 255, 255), Color.argb(115, 255, 255, 255))
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    fun isDark(view: View): Boolean =
        view.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    /** Draws the panel into [canvas] at [rect] (canvas units), [density] canvas px per dp. */
    fun draw(canvas: Canvas, rect: RectF, dark: Boolean, density: Float) {
        val style = if (dark) this.dark else light
        fill.color = style.background
        canvas.drawRect(rect, fill)
        fill.color = style.separator
        canvas.drawRect(rect.left, rect.top, rect.right, rect.top + max(1f, 0.5f * density), fill)

        text.textSize = 15 * density
        text.color = style.ink
        val label = "Keyboard"
        val labelWidth = text.measureText(label)
        val glyphW = 20 * density
        val glyphH = 14 * density
        val spacing = 6 * density
        val total = glyphW + spacing + labelWidth
        val height = max(glyphH, text.fontSpacing)
        if (rect.height() <= height + 4 * density) return
        val left = rect.centerX() - total / 2
        val cy = rect.centerY()
        // A keyboard glyph: a rounded outline with three rows of keys.
        glyph.color = style.ink
        glyph.strokeWidth = 1.4f * density
        val body = RectF(left, cy - glyphH / 2, left + glyphW, cy + glyphH / 2)
        canvas.drawRoundRect(body, 2.5f * density, 2.5f * density, glyph)
        fill.color = style.ink
        val key = 1.6f * density
        for (row in 0 until 2) {
            for (col in 0 until 5) {
                val x = body.left + 3.2f * density + col * 3.2f * density
                val y = body.top + 3.2f * density + row * 3.2f * density
                canvas.drawRect(x, y, x + key, y + key, fill)
            }
        }
        canvas.drawRect(body.left + 6 * density, body.bottom - 4 * density, body.right - 6 * density, body.bottom - 2.4f * density, fill)
        val baseline = cy - (text.descent() + text.ascent()) / 2
        canvas.drawText(label, left + glyphW + spacing, baseline, text)
    }
}
