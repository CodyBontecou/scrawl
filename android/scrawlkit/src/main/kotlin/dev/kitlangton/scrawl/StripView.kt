package dev.kitlangton.scrawl

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.view.Choreographer
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.customview.widget.ExploreByTouchHelper
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/** What the strip's controls do. */
internal class StripActions(
    val toggleDrawing: () -> Unit = {},
    val discard: () -> Unit = {},
    val send: () -> Unit = {},
    val undoDiscard: () -> Unit = {},
    val share: () -> Unit = {},
)

/**
 * The recording controls: a slim vertical strip on one edge of the screen, drawn from
 * [LiquidStrip]'s geometry.
 *
 * - Dot: pulses while recording; its ring fills once a minute. Hold it to see the elapsed time,
 *   drag it to move the strip (a throw lands on the nearer edge; the side and height are kept).
 * - Draw (toggles drawing), Discard (with Undo), Send.
 *
 * Coordinates are in points (dp) throughout; drawing scales by the display density.
 */
@SuppressLint("ViewConstructor")
internal class StripView(context: Context, private val haptics: Haptics) : View(context) {
    val liquid = LiquidStrip()
    var actions = StripActions()
    private val density = resources.displayMetrics.density
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private var side = runCatching { StripSide.valueOf(prefs.getString(SIDE_KEY, null) ?: "Trailing") }.getOrDefault(StripSide.Trailing)
    private var position = prefs.getFloat(POSITION_KEY, 0.5f).toDouble()
    private var mode: StripMode? = null
    private var notice: StripNotice? = null
    private var isReady = false

    /** The keyboard's top edge in this view's px while it's showing. */
    var keyboardTopPx: Float? = null
        set(value) {
            if (field != value) {
                field = value; relayoutRest(animated = true)
            }
        }

    /** Called when the strip's geometry changes (the overlay resizes its window in accessible mode). */
    var onGeometry: (() -> Unit)? = null

    /** Called once the strip has content laid out (the startup trace's `stripLayout`). */
    var onLaidOut: (() -> Unit)? = null

    private val reduceMotion: Boolean
        get() = runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)

    init {
        setWillNotDraw(false)
        liquid.side = side
        liquid.onChange = { invalidate(); onGeometry?.invoke() }
        liquid.requestFrames = { startFrames() }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ViewCompat.setAccessibilityDelegate(this, access)
    }

    // Inputs

    fun apply(mode: StripMode?, notice: StripNotice?) {
        this.mode = mode
        liquid.reduceMotion = reduceMotion
        liquid.setNotice(notice.takeIf { mode is StripMode.Recording })
        this.notice = notice
        if (isReady) liquid.apply(mode)
        if (mode !is StripMode.Recording) {
            grip = null
        }
        access.invalidateRoot()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val first = !isReady
        relayoutRest(animated = !first)
        if (first && w > 0 && h > 0) {
            isReady = true
            liquid.side = side
            liquid.setNotice(notice.takeIf { mode is StripMode.Recording })
            liquid.apply(mode)
        }
    }

    /** Tests: rest at [side], [position] (0 top … 1 bottom) without saving it. */
    internal fun restForTesting(side: StripSide, position: Double) {
        this.side = side
        this.position = position
        liquid.side = side
        relayoutRest(animated = false)
    }

    private fun relayoutRest(animated: Boolean) {
        if (width == 0 || height == 0) return
        liquid.rest(area().center(side, position), animated)
    }

    // Frame loop

    private var framing = false
    private var lastFrameNs = 0L
    private val frame = Choreographer.FrameCallback { ns -> onFrame(ns) }

    private fun startFrames() {
        if (framing) return
        framing = true
        lastFrameNs = 0L
        Choreographer.getInstance().postFrameCallback(frame)
    }

    private fun onFrame(ns: Long) {
        val dt = if (lastFrameNs == 0L) 1.0 / 60 else (ns - lastFrameNs) / 1e9
        lastFrameNs = ns
        val more = liquid.tick(dt)
        invalidate()
        if (more) {
            Choreographer.getInstance().postFrameCallback(frame)
        } else {
            framing = false
        }
    }

    // Placement

    /** The space the strip's centre can move in: the safe area above the keyboard, 6 dp off the side
     * edges and 8 dp off the top and bottom. */
    private inner class Area(val left: Double, val top: Double, val right: Double, val bottom: Double) {
        private val minX = left + 6 + StripMetrics.WIDTH / 2
        private val maxX = max(minX, right - 6 - StripMetrics.WIDTH / 2)
        private val minY = top + 8 + StripMetrics.EXPANDED_HEIGHT / 2
        private val maxY = max(minY, bottom - 8 - StripMetrics.EXPANDED_HEIGHT / 2)
        val midX get() = (left + right) / 2
        fun center(side: StripSide, position: Double) =
            Vec(if (side == StripSide.Leading) minX else maxX, minY + (maxY - minY) * position.coerceIn(0.0, 1.0))

        fun clamp(p: Vec) = Vec(p.x.coerceIn(minX, maxX), p.y.coerceIn(minY, maxY))
        fun position(y: Double): Double {
            val span = maxY - minY
            return if (span <= 0) 0.5 else (y.coerceIn(minY, maxY) - minY) / span
        }
    }

    private fun area(): Area {
        val insets = ViewCompat.getRootWindowInsets(this)
            ?.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        val d = density.toDouble()
        val left = (insets?.left ?: 0) / d
        val top = (insets?.top ?: 0) / d
        val right = (width - (insets?.right ?: 0)) / d
        var bottom = (height - (insets?.bottom ?: 0)) / d
        keyboardTopPx?.let { bottom = min(bottom, it / d) }
        return Area(left, top, right, bottom)
    }

    // Touches (in this view's px), routed by the overlay

    /** Whether a touch at ([x], [y]) px belongs to the strip or its bubble (with 8 dp of slack). */
    fun wantsTouch(x: Float, y: Float): Boolean {
        val g = liquid.geometry
        val px = x / density.toDouble()
        val py = y / density.toDouble()
        if (g.alpha > 0.05 && g.controlsLive && !g.body.isEmpty && g.body.inset(-8.0).contains(px, py)) return true
        val bubble = g.bubble
        return g.bubbleInteractive && bubble != null && bubble.inset(-8.0).contains(px, py)
    }

    /** The strip's touchable rects in px (the body and an interactive bubble, 8 dp of slack). */
    fun touchRects(): List<android.graphics.Rect> {
        val g = liquid.geometry
        val d = density.toDouble()
        fun px(r: Rect) = r.inset(-8.0).let { android.graphics.Rect((it.minX * d).toInt(), (it.minY * d).toInt(), (it.maxX * d).toInt(), (it.maxY * d).toInt()) }
        val rects = mutableListOf<android.graphics.Rect>()
        if (g.alpha > 0.05 && g.controlsLive && !g.body.isEmpty) rects += px(g.body)
        val bubble = g.bubble
        if (g.bubbleInteractive && bubble != null) rects += px(bubble)
        return rects
    }

    private enum class Target { Dot, Draw, Discard, Send, Share, Undo }

    private class Grip(val origin: Vec, val down: Vec, var dragging: Boolean = false)

    private var grip: Grip? = null
    private var pressed: Target? = null
    private var velocity: VelocityTracker? = null

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val p = Vec(event.x / density.toDouble(), event.y / density.toDouble())
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                velocity?.recycle()
                velocity = VelocityTracker.obtain().apply { addMovement(event) }
                val target = target(p) ?: return false
                if (target == Target.Dot) {
                    grip = Grip(liquid.place, p)
                    liquid.setHolding(true)
                } else {
                    pressed = target
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                velocity?.addMovement(event)
                val grip = grip ?: return pressed != null
                val translation = Vec(p.x - grip.down.x, p.y - grip.down.y)
                if (!grip.dragging) {
                    if (hypot(translation.x, translation.y) <= StripMetrics.DRAG_THRESHOLD) return true
                    grip.dragging = true
                    liquid.setHolding(false)
                    haptics.light()
                }
                liquid.drag(area().clamp(Vec(grip.origin.x + translation.x, grip.origin.y + translation.y)), currentVelocity())
                return true
            }
            MotionEvent.ACTION_UP -> {
                velocity?.addMovement(event)
                val grip = grip
                if (grip != null) {
                    this.grip = null
                    liquid.setHolding(false)
                    if (grip.dragging) throwStrip(grip, p, currentVelocity())
                } else {
                    val target = pressed
                    pressed = null
                    invalidate()
                    if (target != null && target(p) == target) perform(target)
                }
                velocity?.recycle(); velocity = null
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                val grip = grip
                this.grip = null
                pressed = null
                liquid.setHolding(false)
                if (grip?.dragging == true) {
                    // A cancelled drag puts the strip back.
                    liquid.release(Vec(0.0, 0.0))
                    liquid.rest(area().center(side, position), true)
                }
                velocity?.recycle(); velocity = null
                invalidate()
                return true
            }
        }
        return true
    }

    private fun currentVelocity(): Vec {
        val tracker = velocity ?: return Vec(0.0, 0.0)
        tracker.computeCurrentVelocity(1000)
        return Vec(tracker.xVelocity / density.toDouble(), tracker.yVelocity / density.toDouble())
    }

    private fun throwStrip(grip: Grip, p: Vec, velocity: Vec) {
        val area = area()
        val dropped = area.clamp(Vec(grip.origin.x + p.x - grip.down.x, grip.origin.y + p.y - grip.down.y))
        // Where the throw would carry it decides the edge and height.
        val landingX = dropped.x + velocity.x * 0.3
        val landingY = dropped.y + velocity.y * 0.3 * 0.25
        side = if (landingX < area.midX) StripSide.Leading else StripSide.Trailing
        position = area.position(landingY)
        prefs.edit().putString(SIDE_KEY, side.name).putFloat(POSITION_KEY, position.toFloat()).apply()
        liquid.side = side
        liquid.release(velocity)
        liquid.rest(area.center(side, position), true)
        haptics.soft()
    }

    private fun perform(target: Target) {
        when (target) {
            Target.Draw -> actions.toggleDrawing()
            Target.Discard -> actions.discard()
            Target.Send -> {
                haptics.light(); actions.send()
            }
            Target.Share -> actions.share()
            Target.Undo -> actions.undoDiscard()
            Target.Dot -> Unit
        }
    }

    private fun rowRect(index: Int): Rect? {
        val g = liquid.geometry
        val row = g.rows[index]
        if (row.opacity <= 0.3 || !g.controlsLive) return null
        val h = if (index == StripMetrics.DOT_INDEX) StripMetrics.DOT_ROW else StripMetrics.ROW
        val cy = g.column.y + StripMetrics.rowCenters[index] + row.offset
        return Rect(g.column.x - StripMetrics.HIT / 2, cy - h / 2, StripMetrics.HIT, h)
    }

    private fun target(p: Vec): Target? {
        rowRect(0)?.takeIf { it.contains(p.x, p.y) }?.let { return Target.Dot }
        rowRect(1)?.takeIf { it.contains(p.x, p.y) }?.let { return Target.Draw }
        rowRect(3)?.takeIf { it.contains(p.x, p.y) }?.let { return Target.Discard }
        rowRect(4)?.takeIf { it.contains(p.x, p.y) }?.let { return Target.Send }
        val g = liquid.geometry
        if (g.bubbleInteractive) {
            // iOS: Share's tap area is 8 pt beyond it, Undo's 10.
            buttonRect?.let {
                val undo = liquid.bubble is LiquidStrip.Bubble.Discarded
                if (it.inset(if (undo) -10.0 else -8.0).contains(p.x, p.y)) return if (undo) Target.Undo else Target.Share
            }
        }
        return null
    }

    // Drawing

    private val glassFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(24, 24, 27) }
    private val glassEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(56, 255, 255, 255)
        strokeWidth = 1f
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 13 * density
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        fontFeatureSettings = "tnum"
    }
    private val caption = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(191, 255, 255, 255)
        textSize = 12 * density
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    /** The bubble's button (Share or Undo) in points, from the last layout. */
    private var buttonRect: Rect? = null
    private var measuredFor: LiquidStrip.Bubble? = null

    override fun onDraw(canvas: Canvas) {
        val g = liquid.geometry
        val d = density
        if (liquid.isPresent && g.alpha > 0.001) drawGlass(canvas, g)
        if (g.rows.any { it.opacity > 0.001 }) drawControls(canvas, g)
        liquid.statusIcon?.let { if (g.iconOpacity > 0.001) drawStatus(canvas, it, g) }
        liquid.bubble?.let { bubble ->
            val layout = layoutBubble(bubble)
            if (measuredFor != bubble) {
                measuredFor = bubble
                liquid.measuredBubble(layout.width, layout.height, bubble)
            }
            if (g.bubbleOpacity > 0.001) {
                canvas.save()
                canvas.translate(((g.bubbleCenter.x - layout.width / 2) * d).toFloat(), ((g.bubbleCenter.y - layout.height / 2) * d).toFloat())
                layout.draw(canvas, g.bubbleOpacity)
                canvas.restore()
            }
            buttonRect = layout.button?.let {
                Rect(g.bubbleCenter.x - layout.width / 2 + it.x, g.bubbleCenter.y - layout.height / 2 + it.y, it.width, it.height)
            }
        }
        if (mode is StripMode.Recording && g.rows[0].opacity > 0.01) postInvalidateOnAnimation()
        if (mode is StripMode.Sending && (mode as StripMode.Sending).progress == null && liquid.statusIcon == StripMode.Phase.Sending) postInvalidateOnAnimation()
    }

    /** The app window behind the strip, for the glass's blurred backdrop. */
    var backdrop: (() -> View?)? = null
    private var backdropNode: Any? = null

    private fun drawGlass(canvas: Canvas, g: LiquidStrip.Geometry) {
        val path = capsule(g.body)
        val bubble = g.bubble
        if (bubble != null) {
            path.op(capsule(bubble), Path.Op.UNION)
            neck(g, bubble)?.let { path.op(it, Path.Op.UNION) }
        }
        if (!forcesSoftwareBlur && android.os.Build.VERSION.SDK_INT >= 33 && drawLiquidGlass(canvas, g, bubble)) return
        val blurred = (!forcesSoftwareBlur && drawBlurredBackdrop(canvas, path, g.alpha)) || drawSoftwareBlur(canvas, path, g.alpha)
        drawsSoftwareBlur = blurred && (forcesSoftwareBlur || android.os.Build.VERSION.SDK_INT < 31)
        if (blurred) {
            // iOS's material: the blurred app under a black tint (`glassTint` 0.45).
            glassFill.alpha = (g.alpha * 255 * StripMetrics.GLASS_TINT).toInt()
        } else {
            // Nothing to blur (no app window behind): an opaque-enough dark fill instead.
            glassFill.alpha = (g.alpha * (255 * 0.86)).toInt()
        }
        canvas.drawPath(path, glassFill)
        glassEdge.alpha = (g.alpha * 56).toInt()
        canvas.drawPath(path, glassEdge)
    }

    private var liquidGlass: Any? = null
    private var softwareBlur: SoftwareBlur? = null

    /** Tests: draw the pre-Android-12 material on any version. */
    internal var forcesSoftwareBlur = false

    /** Whether the last frame used the CPU-blurred material (Android 8–11). */
    internal var drawsSoftwareBlur = false
        private set

    /** Android 8–11: the app behind, blurred on the CPU ([SoftwareBlur]). */
    private fun drawSoftwareBlur(canvas: Canvas, path: Path, alpha: Double): Boolean {
        val app = backdrop?.invoke()?.takeIf { it.isAttachedToWindow && it.width > 0 } ?: return false
        val bounds = android.graphics.RectF().also { path.computeBounds(it, true) }
        val blur = softwareBlur ?: SoftwareBlur(density).also { softwareBlur = it }
        if (!blur.draw(canvas, this, app, path, bounds, alpha)) return false
        // The app behind keeps moving: refresh while shown (the blur itself is throttled).
        postInvalidateOnAnimation()
        return true
    }

    /** Whether the strip is drawn as Liquid Glass (Android 13+, with a backdrop to refract). */
    internal var drawsLiquidGlass = false
        private set

    /**
     * Android 13+: iOS 26's Liquid Glass ([LiquidGlass]) over the app behind the strip. The shader
     * draws the merged shape, its tint and rim itself, so no clip or fill is needed.
     */
    @androidx.annotation.RequiresApi(33)
    private fun drawLiquidGlass(canvas: Canvas, g: LiquidStrip.Geometry, bubble: Rect?): Boolean {
        if (!canvas.isHardwareAccelerated) return false
        val app = backdrop?.invoke()?.takeIf { it.isAttachedToWindow && it.width > 0 } ?: return false
        val ok = runCatching {
            val glass = (liquidGlass as? LiquidGlass) ?: LiquidGlass(density).also { liquidGlass = it }
            val node = (backdropNode as? android.graphics.RenderNode) ?: android.graphics.RenderNode("ScrawlGlass").also { backdropNode = it }
            node.setPosition(0, 0, width, height)
            val recording = node.beginRecording(width, height)
            val appOrigin = IntArray(2).also { app.getLocationOnScreen(it) }
            val myOrigin = IntArray(2).also { getLocationOnScreen(it) }
            recording.translate((appOrigin[0] - myOrigin[0]).toFloat(), (appOrigin[1] - myOrigin[1]).toFloat())
            app.draw(recording)
            node.endRecording()
            node.setRenderEffect(glass.effect(g.body, bubble?.takeIf { g.bubbleOpacity > 0.001 }, g.alpha.toFloat(), 24 * density))
            node.alpha = 1f
            canvas.drawRenderNode(node)
            postInvalidateOnAnimation()
            true
        }.getOrDefault(false)
        drawsLiquidGlass = ok
        return ok
    }

    /**
     * The glass on Android 12: the app's own content under the strip, drawn from its display list
     * into a RenderNode, blurred on the GPU (RenderEffect), and clipped to the liquid shape, like
     * UIKit's material blurring what's behind it. Returns false where that isn't available.
     */
    private fun drawBlurredBackdrop(canvas: Canvas, path: Path, alpha: Double): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 31 || !canvas.isHardwareAccelerated) return false
        val app = backdrop?.invoke()?.takeIf { it.isAttachedToWindow && it.width > 0 } ?: return false
        return runCatching {
            val node = (backdropNode as? android.graphics.RenderNode) ?: android.graphics.RenderNode("ScrawlGlass").also { backdropNode = it }
            node.setPosition(0, 0, width, height)
            val recording = node.beginRecording(width, height)
            val appOrigin = IntArray(2).also { app.getLocationOnScreen(it) }
            val myOrigin = IntArray(2).also { getLocationOnScreen(it) }
            recording.translate((appOrigin[0] - myOrigin[0]).toFloat(), (appOrigin[1] - myOrigin[1]).toFloat())
            app.draw(recording)
            node.endRecording()
            val radius = 24 * density
            node.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(radius, radius, android.graphics.Shader.TileMode.CLAMP))
            node.alpha = alpha.toFloat().coerceIn(0f, 1f)
            canvas.save()
            canvas.clipPath(path)
            canvas.drawRenderNode(node)
            canvas.restore()
            // The app behind keeps moving (scrolling, animating): keep the glass live while shown.
            postInvalidateOnAnimation()
            true
        }.getOrDefault(false)
    }

    private fun capsule(r: Rect): Path {
        val path = Path()
        if (r.width <= 0.5 || r.height <= 0.5) return path
        val d = density
        val radius = (min(r.width, r.height) / 2 * d).toFloat()
        path.addRoundRect(RectF((r.minX * d).toFloat(), (r.minY * d).toFloat(), (r.maxX * d).toFloat(), (r.maxY * d).toFloat()), radius, radius, Path.Direction.CW)
        return path
    }

    /** Variant A's metaball neck: a waisted bridge that thins as the gap opens. */
    private fun neck(g: LiquidStrip.Geometry, bubble: Rect): Path? {
        val gap = g.bubbleGap
        if (gap >= StripMetrics.NECK_REACH || gap <= -bubble.height) return null
        val reach = (1 - gap / StripMetrics.NECK_REACH).coerceIn(0.0, 1.0)
        val bubbleRadius = min(bubble.width, bubble.height) / 2
        val body = g.body
        val bodyRadius = body.width / 2
        val bubbleCap = Triple(if (g.direction > 0) bubble.maxX - bubbleRadius else bubble.minX + bubbleRadius, bubble.midY, bubbleRadius)
        val bodyCap = Triple(body.midX, bubble.midY.coerceIn(body.minY + bodyRadius, max(body.minY + bodyRadius, body.maxY - bodyRadius)), bodyRadius)
        val half = min(bubbleRadius, bodyRadius) * sin(NECK_ANGLE) * (0.1 + 0.9 * reach.pow(3))
        val (left, right) = if (g.direction > 0) bubbleCap to bodyCap else bodyCap to bubbleCap
        val sine = sin(NECK_ANGLE)
        val cosine = cos(NECK_ANGLE)
        val startX = left.first + left.third * cosine
        val endX = right.first - right.third * cosine
        if (endX - startX < 0.5) return null
        val midX = (startX + endX) / 2
        val midY = (left.second + right.second) / 2
        val d = density
        fun f(v: Double) = (v * d).toFloat()
        return Path().apply {
            moveTo(f(startX), f(left.second - left.third * sine))
            quadTo(f(midX), f(midY - half), f(endX), f(right.second - right.third * sine))
            lineTo(f(endX), f(right.second + right.third * sine))
            quadTo(f(midX), f(midY + half), f(startX), f(left.second + left.third * sine))
            close()
        }
    }

    private fun drawControls(canvas: Canvas, g: LiquidStrip.Geometry) {
        val d = density
        for (index in 0 until 5) {
            val row = g.rows[index]
            if (row.opacity <= 0.001) continue
            val cx = g.column.x
            val cy = g.column.y + StripMetrics.rowCenters[index] + row.offset
            val target = when (index) {
                1 -> Target.Draw
                3 -> Target.Discard
                4 -> Target.Send
                else -> null
            }
            val press = target != null && pressed == target
            val scale = row.scale * if (press) 0.86 else 1.0
            val alpha = row.opacity * if (press) 0.7 else 1.0
            canvas.save()
            canvas.translate((cx * d).toFloat(), (cy * d).toFloat())
            canvas.scale(scale.toFloat(), scale.toFloat())
            when (index) {
                0 -> drawDot(canvas, alpha)
                1 -> {
                    if (liquid.isDrawing) {
                        fill.color = InkView.RED; fill.alpha = (alpha * 0.22 * 255).toInt()
                        canvas.drawCircle(0f, 0f, 16 * d, fill)
                    }
                    Icons.pencil(canvas, d, if (liquid.isDrawing) InkView.RED else Color.WHITE, if (liquid.isDrawing) alpha else alpha * 0.85, stroke, fill)
                }
                2 -> {
                    fill.color = Color.WHITE; fill.alpha = (alpha * 0.2 * 255).toInt()
                    canvas.drawRect(-9 * d, -0.5f, 9 * d, 0.5f, fill)
                }
                3 -> Icons.xmark(canvas, d, Color.WHITE, alpha * 0.85, stroke)
                4 -> {
                    fill.color = InkView.RED; fill.alpha = (alpha * 255).toInt()
                    canvas.drawCircle(0f, 0f, 16 * d, fill)
                    Icons.arrowUp(canvas, d, Color.WHITE, alpha, stroke)
                }
            }
            canvas.restore()
        }
    }

    /** A pulsing red dot inside a hairline ring that fills once per minute. */
    private fun drawDot(canvas: Canvas, alpha: Double) {
        val d = density
        val radius = 9 * d - 0.625f * d
        stroke.strokeWidth = 1.25f * d
        stroke.color = Color.WHITE
        stroke.alpha = (alpha * 0.16 * 255).toInt()
        canvas.drawCircle(0f, 0f, radius, stroke)
        val elapsed = elapsedSeconds()
        val sweep = ((elapsed % 60) / 60 * 360).toFloat()
        if (sweep > 0) {
            stroke.alpha = (alpha * 0.75 * 255).toInt()
            canvas.drawArc(-radius, -radius, radius, radius, -90f, sweep, false, stroke)
        }
        val pulse = if (reduceMotion) 1.0 else (0.5 + 0.5 * cos(2 * PI * (System.nanoTime() / 1e9) / 2.0)).let { t -> t * t * (3 - 2 * t) }
        val scale = 0.78 + 0.22 * pulse
        fill.color = InkView.RED
        fill.alpha = (alpha * (0.55 + 0.45 * pulse) * 255).toInt()
        canvas.drawCircle(0f, 0f, (4 * d * scale).toFloat(), fill)
    }

    private fun elapsedSeconds(): Double {
        val started = liquid.startedAtMs
        return if (started == 0L) 0.0 else max(0.0, (System.currentTimeMillis() - started) / 1000.0)
    }

    private fun drawStatus(canvas: Canvas, phase: StripMode.Phase, g: LiquidStrip.Geometry) {
        val d = density
        val alpha = g.iconOpacity
        canvas.save()
        canvas.translate((g.icon.x * d).toFloat(), (g.icon.y * d).toFloat())
        canvas.scale(g.iconScale.toFloat(), g.iconScale.toFloat())
        when (phase) {
            StripMode.Phase.Recording -> Unit
            StripMode.Phase.Sending -> {
                val progress = liquid.sendProgress
                stroke.strokeWidth = 2.5f * d
                stroke.color = Color.WHITE
                val r = 11 * d - 1.25f * d
                if (progress != null) {
                    stroke.alpha = (alpha * 0.22 * 255).toInt()
                    canvas.drawCircle(0f, 0f, r, stroke)
                    stroke.alpha = (alpha * 255).toInt()
                    canvas.drawArc(-r, -r, r, r, -90f, (max(progress, 0.02) * 360).toFloat(), false, stroke)
                } else {
                    // An indeterminate spinner.
                    val turn = ((System.nanoTime() / 1e9) % 1.0 * 360).toFloat()
                    stroke.strokeWidth = 2f * d
                    stroke.alpha = (alpha * 255).toInt()
                    canvas.drawArc(-8 * d, -8 * d, 8 * d, 8 * d, turn, 270f, false, stroke)
                }
            }
            StripMode.Phase.Sent -> Icons.checkmark(canvas, d, Color.rgb(52, 199, 89), alpha, stroke)
            StripMode.Phase.Queued -> Icons.trayUp(canvas, d, Color.WHITE, alpha * 0.85, stroke)
            StripMode.Phase.Failed -> Icons.warning(canvas, d, Color.rgb(255, 204, 0), alpha, fill)
            StripMode.Phase.Discarded -> Icons.trash(canvas, d, Color.WHITE, alpha * 0.6, stroke)
        }
        canvas.restore()
    }

    // Bubble layout

    private inner class BubbleLayout(
        val width: Double,
        val height: Double,
        val button: Rect?,
        val draw: (Canvas, Double) -> Unit,
    ) {
        fun draw(canvas: Canvas, opacity: Double) = draw.invoke(canvas, opacity)
    }

    private fun lines(notice: StripNotice): Pair<Double, Double> {
        val w1 = title.measureText(notice.title) / density
        val w2 = notice.detail?.let { caption.measureText(it) / density } ?: 0f
        val h = if (notice.detail != null) (title.fontSpacing + caption.fontSpacing) / density + 1 else title.fontSpacing / density
        return max(w1, w2).toDouble() to h.toDouble()
    }

    private fun drawNotice(canvas: Canvas, notice: StripNotice, x: Float, y: Float, opacity: Double) {
        title.alpha = (opacity * 255).toInt()
        canvas.drawText(notice.title, x, y - title.ascent(), title)
        notice.detail?.let {
            caption.alpha = (opacity * 191).toInt()
            canvas.drawText(it, x, y + title.fontSpacing + density - caption.ascent(), caption)
        }
    }

    private fun layoutBubble(bubble: LiquidStrip.Bubble): BubbleLayout {
        val d = density
        val padH = 12.0
        val padV = 8.0
        fun single(text: String) = StripNotice(text)
        val notice: StripNotice? = when (bubble) {
            LiquidStrip.Bubble.Elapsed -> single(elapsedText())
            is LiquidStrip.Bubble.Sent -> sentNotice(bubble.reply)
            is LiquidStrip.Bubble.Sending -> single(if (bubble.stalled) "Still sending…" else "Sending…")
            LiquidStrip.Bubble.Queued -> single("Queued · sends when online")
            is LiquidStrip.Bubble.Notice -> bubble.notice
            is LiquidStrip.Bubble.Failed -> bubble.failure.notice
            LiquidStrip.Bubble.Discarded -> null
        }
        return when (bubble) {
            is LiquidStrip.Bubble.Failed -> {
                val (w, h) = lines(notice!!)
                val shareText = "Share"
                val shareW = title.measureText(shareText) / d + 14 + 4 + 18.0
                val shareH = title.fontSpacing / d + 8.0
                val contentH = max(h, shareH)
                val width = padH * 2 + w + 8 + shareW
                val height = padV * 2 + contentH
                val button = Rect(padH + w + 8, (height - shareH) / 2, shareW, shareH)
                BubbleLayout(width, height, button) { canvas, o ->
                    drawNotice(canvas, notice, (padH * d).toFloat(), ((padV + (contentH - h) / 2) * d).toFloat(), o)
                    val b = button
                    fill.color = Color.WHITE; fill.alpha = (o * 0.18 * 255).toInt()
                    val rect = RectF((b.minX * d).toFloat(), (b.minY * d).toFloat(), (b.maxX * d).toFloat(), (b.maxY * d).toFloat())
                    canvas.drawRoundRect(rect, rect.height() / 2, rect.height() / 2, fill)
                    canvas.save()
                    canvas.translate(((b.minX + 9 + 7) * d).toFloat(), rect.centerY())
                    Icons.share(canvas, d, Color.WHITE, o, stroke)
                    canvas.restore()
                    title.alpha = (o * 255).toInt()
                    canvas.drawText(shareText, ((b.minX + 9 + 14 + 4) * d).toFloat(), rect.centerY() - (title.descent() + title.ascent()) / 2, title)
                }
            }
            LiquidStrip.Bubble.Discarded -> {
                val a = "Discarded"
                val dot = "·"
                val undo = "Undo"
                val wa = title.measureText(a) / d
                val wd = title.measureText(dot) / d
                val wu = title.measureText(undo) / d
                val h = title.fontSpacing / d.toDouble()
                val width = padH * 2 + wa + 6 + wd + 6 + wu
                val height = padV * 2 + h
                val button = Rect(padH + wa + 6 + wd + 6, padV, wu.toDouble(), h)
                BubbleLayout(width, height, button) { canvas, o ->
                    val baseline = (padV * d - title.ascent()).toFloat()
                    title.alpha = (o * 0.75 * 255).toInt()
                    canvas.drawText(a, (padH * d).toFloat(), baseline, title)
                    title.alpha = (o * 0.4 * 255).toInt()
                    canvas.drawText(dot, ((padH + wa + 6) * d).toFloat(), baseline, title)
                    title.alpha = (o * 255).toInt()
                    canvas.drawText(undo, ((padH + wa + 6 + wd + 6) * d).toFloat(), baseline, title)
                }
            }
            else -> {
                val (w, h) = lines(notice!!)
                BubbleLayout(padH * 2 + w, padV * 2 + h, null) { canvas, o ->
                    val shown = if (bubble == LiquidStrip.Bubble.Elapsed) single(elapsedText()) else notice
                    drawNotice(canvas, shown, (padH * d).toFloat(), (padV * d).toFloat(), o)
                }
            }
        }
    }

    private fun elapsedText(): String {
        val s = elapsedSeconds().toInt()
        return "%d:%02d".format(s / 60, s % 60)
    }

    /** Delivered reads as one line; on the Mac but not yet delivered takes two. */
    private fun sentNotice(reply: IngestReply): StripNotice = when (reply) {
        is IngestReply.Delivered -> StripNotice("Sent to ${shortened(reply.sessionTitle)}")
        is IngestReply.Queued -> StripNotice("On your Mac", "Sends to ${shortened(reply.sessionTitle, 24)} when it can")
        is IngestReply.Processing -> StripNotice("On your Mac", "Sending to ${shortened(reply.sessionTitle, 30)}…")
        IngestReply.Saved -> StripNotice("Saved on your Mac")
    }

    // Accessibility: the strip's controls as virtual views with iOS's labels.

    private val access = object : ExploreByTouchHelper(this) {
        override fun getVirtualViewAt(x: Float, y: Float): Int {
            val p = Vec(x / density.toDouble(), y / density.toDouble())
            return when (target(p)) {
                Target.Dot -> 0
                Target.Draw -> 1
                Target.Discard -> 2
                Target.Send -> 3
                Target.Share -> 5
                Target.Undo -> 6
                null -> if (liquid.bubble != null && liquid.geometry.bubble?.contains(p.x, p.y) == true) 4 else INVALID_ID
            }
        }

        override fun getVisibleVirtualViews(ids: MutableList<Int>) {
            val g = liquid.geometry
            if (g.controlsLive && g.rows[0].opacity > 0.3) ids += listOf(0, 1, 2, 3)
            if (liquid.bubble != null && g.bubbleOpacity >= 0.3) ids += 4
            if (g.bubbleInteractive) ids += if (liquid.bubble == LiquidStrip.Bubble.Discarded) 6 else 5
            if (liquid.statusIcon != null && liquid.statusIcon != StripMode.Phase.Recording && g.iconOpacity > 0.3) ids += 7
        }

        override fun onPopulateNodeForVirtualView(id: Int, node: AccessibilityNodeInfoCompat) {
            val g = liquid.geometry
            val rect = when (id) {
                0, 1, 2, 3 -> rowRect(listOf(0, 1, 3, 4)[id])
                4 -> g.bubble
                5, 6 -> buttonRect
                else -> Rect(g.icon.x - 21, g.icon.y - 21, 42.0, 42.0)
            } ?: Rect(0.0, 0.0, 1.0, 1.0)
            val d = density
            @Suppress("DEPRECATION")
            node.setBoundsInParent(android.graphics.Rect((rect.minX * d).toInt(), (rect.minY * d).toInt(), (rect.maxX * d).toInt(), (rect.maxY * d).toInt()))
            node.className = if (id in listOf(1, 2, 3, 5, 6)) "android.widget.Button" else "android.view.View"
            when (id) {
                0 -> {
                    val s = elapsedSeconds().toInt()
                    val m = s / 60
                    val sec = s % 60
                    node.contentDescription = "Recording, $m ${if (m == 1) "minute" else "minutes"} $sec ${if (sec == 1) "second" else "seconds"}"
                    node.hintText = "Hold to show the time. Drag to move the controls."
                }
                1 -> {
                    node.contentDescription = "Draw"; node.stateDescription = if (liquid.isDrawing) "On" else "Off"
                }
                2 -> node.contentDescription = "Discard recording"
                3 -> node.contentDescription = "Send recording"
                4 -> node.contentDescription = bubbleDescription()
                5 -> node.contentDescription = "Share"
                6 -> node.contentDescription = "Undo discard"
                7 -> node.contentDescription = when (liquid.statusIcon) {
                    StripMode.Phase.Sending -> "Sending recording" + (liquid.sendProgress?.let { ", ${(it * 100).toInt()}%" } ?: "")
                    StripMode.Phase.Sent -> "Sent"
                    StripMode.Phase.Queued -> "Recording queued"
                    StripMode.Phase.Failed -> "Not sent"
                    StripMode.Phase.Discarded -> "Recording discarded"
                    else -> ""
                }
            }
            if (id in listOf(1, 2, 3, 5, 6)) node.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK)
        }

        override fun onPerformActionForVirtualView(id: Int, action: Int, arguments: Bundle?): Boolean {
            if (action != AccessibilityNodeInfoCompat.ACTION_CLICK) return false
            when (id) {
                1 -> perform(Target.Draw)
                2 -> perform(Target.Discard)
                3 -> perform(Target.Send)
                5 -> perform(Target.Share)
                6 -> perform(Target.Undo)
                else -> return false
            }
            return true
        }
    }

    private fun bubbleDescription(): String = when (val b = liquid.bubble) {
        LiquidStrip.Bubble.Elapsed -> elapsedText()
        is LiquidStrip.Bubble.Sent -> sentNotice(b.reply).let { listOfNotNull(it.title, it.detail).joinToString(", ") }
        is LiquidStrip.Bubble.Sending -> if (b.stalled) "Still sending…" else "Sending…"
        LiquidStrip.Bubble.Queued -> "Queued · sends when online"
        is LiquidStrip.Bubble.Notice -> listOfNotNull(b.notice.title, b.notice.detail).joinToString(", ")
        is LiquidStrip.Bubble.Failed -> listOfNotNull(b.failure.notice.title, b.failure.notice.detail).joinToString(", ")
        LiquidStrip.Bubble.Discarded -> "Discarded"
        null -> ""
    }

    override fun dispatchHoverEvent(event: MotionEvent): Boolean = access.dispatchHoverEvent(event) || super.dispatchHoverEvent(event)

    /** The accessibility helper, for tests. */
    internal val accessibilityHelper: ExploreByTouchHelper get() = access

    companion object {
        private const val PREFS = "dev.kitlangton.scrawl"
        private const val SIDE_KEY = "dev.kitlangton.scrawl.strip.side"
        private const val POSITION_KEY = "dev.kitlangton.scrawl.strip.position"
        private const val NECK_ANGLE = 0.95

        fun shortened(target: String, length: Int = 36): String =
            if (target.length > length) target.take(length) + "…" else target
    }
}

/** The strip's icons, drawn centred on the origin (sizes in dp, like the SF Symbols they stand in for). */
internal object Icons {
    private fun alpha(a: Double) = (a.coerceIn(0.0, 1.0) * 255).toInt()

    fun pencil(canvas: Canvas, d: Float, color: Int, a: Double, stroke: Paint, fill: Paint) {
        stroke.color = color; stroke.alpha = alpha(a); stroke.strokeWidth = 1.7f * d
        canvas.save()
        canvas.rotate(45f)
        val body = RectF(-2.6f * d, -8.5f * d, 2.6f * d, 4.5f * d)
        canvas.drawRoundRect(body, 1f * d, 1f * d, stroke)
        val tip = Path().apply {
            moveTo(-2.6f * d, 4.5f * d); lineTo(0f, 8.6f * d); lineTo(2.6f * d, 4.5f * d)
        }
        canvas.drawPath(tip, stroke)
        fill.color = color; fill.alpha = alpha(a)
        canvas.drawCircle(0f, 7.6f * d, 0.9f * d, fill)
        canvas.restore()
    }

    fun xmark(canvas: Canvas, d: Float, color: Int, a: Double, stroke: Paint) {
        stroke.color = color; stroke.alpha = alpha(a); stroke.strokeWidth = 2f * d
        val r = 5.5f * d
        canvas.drawLine(-r, -r, r, r, stroke)
        canvas.drawLine(-r, r, r, -r, stroke)
    }

    fun arrowUp(canvas: Canvas, d: Float, color: Int, a: Double, stroke: Paint) {
        stroke.color = color; stroke.alpha = alpha(a); stroke.strokeWidth = 2.3f * d
        canvas.drawLine(0f, 6.5f * d, 0f, -6.5f * d, stroke)
        canvas.drawLine(-5.5f * d, -1f * d, 0f, -6.5f * d, stroke)
        canvas.drawLine(5.5f * d, -1f * d, 0f, -6.5f * d, stroke)
    }

    fun checkmark(canvas: Canvas, d: Float, color: Int, a: Double, stroke: Paint) {
        stroke.color = color; stroke.alpha = alpha(a); stroke.strokeWidth = 2.5f * d
        val path = Path().apply { moveTo(-6.5f * d, 0.5f * d); lineTo(-2f * d, 5f * d); lineTo(7f * d, -5f * d) }
        canvas.drawPath(path, stroke)
    }

    fun trayUp(canvas: Canvas, d: Float, color: Int, a: Double, stroke: Paint) {
        stroke.color = color; stroke.alpha = alpha(a); stroke.strokeWidth = 1.8f * d
        val tray = Path().apply {
            moveTo(-8f * d, 1.5f * d); lineTo(-8f * d, 7f * d); lineTo(8f * d, 7f * d); lineTo(8f * d, 1.5f * d)
            moveTo(-8f * d, 1.5f * d); lineTo(-3.5f * d, 1.5f * d); lineTo(-2.5f * d, 3.5f * d); lineTo(2.5f * d, 3.5f * d); lineTo(3.5f * d, 1.5f * d); lineTo(8f * d, 1.5f * d)
        }
        canvas.drawPath(tray, stroke)
        canvas.drawLine(0f, 0f, 0f, -8f * d, stroke)
        canvas.drawLine(-3.5f * d, -4.5f * d, 0f, -8f * d, stroke)
        canvas.drawLine(3.5f * d, -4.5f * d, 0f, -8f * d, stroke)
    }

    fun warning(canvas: Canvas, d: Float, color: Int, a: Double, fill: Paint) {
        fill.color = color; fill.alpha = alpha(a)
        val tri = Path().apply { moveTo(0f, -7.5f * d); lineTo(8.5f * d, 7f * d); lineTo(-8.5f * d, 7f * d); close() }
        canvas.drawPath(tri, fill)
        fill.color = Color.BLACK; fill.alpha = alpha(a * 0.85)
        canvas.drawRoundRect(-0.9f * d, -3f * d, 0.9f * d, 2.4f * d, 0.9f * d, 0.9f * d, fill)
        canvas.drawCircle(0f, 4.6f * d, 1f * d, fill)
    }

    fun trash(canvas: Canvas, d: Float, color: Int, a: Double, stroke: Paint) {
        stroke.color = color; stroke.alpha = alpha(a); stroke.strokeWidth = 1.7f * d
        canvas.drawLine(-7f * d, -5f * d, 7f * d, -5f * d, stroke)
        canvas.drawLine(-2f * d, -7.5f * d, 2f * d, -7.5f * d, stroke)
        val can = Path().apply { moveTo(-5.5f * d, -5f * d); lineTo(-4.5f * d, 7.5f * d); lineTo(4.5f * d, 7.5f * d); lineTo(5.5f * d, -5f * d) }
        canvas.drawPath(can, stroke)
    }

    fun share(canvas: Canvas, d: Float, color: Int, a: Double, stroke: Paint) {
        stroke.color = color; stroke.alpha = alpha(a); stroke.strokeWidth = 1.5f * d
        val box = Path().apply { moveTo(-2.5f * d, -1.5f * d); lineTo(-5f * d, -1.5f * d); lineTo(-5f * d, 6f * d); lineTo(5f * d, 6f * d); lineTo(5f * d, -1.5f * d); lineTo(2.5f * d, -1.5f * d) }
        canvas.drawPath(box, stroke)
        canvas.drawLine(0f, 2.5f * d, 0f, -6.5f * d, stroke)
        canvas.drawLine(-2.8f * d, -3.8f * d, 0f, -6.5f * d, stroke)
        canvas.drawLine(2.8f * d, -3.8f * d, 0f, -6.5f * d, stroke)
    }
}
