package dev.kitlangton.scrawl

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.MotionPredictor
import android.view.View

/**
 * The red ink drawn over the app while recording, in the overlay under the strip.
 *
 * Every historical touch sample is used (the main thread is often busy drawing the recording's
 * frames, so one sample per event made strokes jagged). Samples are smoothed and given a width by
 * [InkStrokeBuilder], and drawn by [InkTrace] as a curve. Finished stretches go into chunk paths
 * that never change, and only the live tip (the last few points, through Android's predicted
 * motion on API 34+) is rebuilt on each touch. Predicted points are dropped on the next event and
 * never recorded.
 *
 * Strokes fade [InkFade.HOLD] after the pen last lifted, together with any drawn just before.
 */
@SuppressLint("ViewConstructor")
internal class InkView(context: Context) : View(context) {
    /** Records a finished stroke (smoothed, unpredicted samples, in dp); returns its timeline index. */
    var onStroke: (List<InkSample>) -> Int? = { null }

    /** Strokes (timeline indices) started fading at `uptime` seconds. */
    var onFade: (List<Int>, Double) -> Unit = { _, _ -> }

    private class Stroke {
        val builder = InkStrokeBuilder()
        val trace = InkTrace()
        val chunks = mutableListOf<Path>()
        var open = Path()
        var openCount = 0
        var live = Path()
        var logIndex: Int? = null
        var clearStart: Double? = null
        var clearFrom = 1.0
    }

    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = RED
        style = Paint.Style.FILL
    }
    private val strokes = linkedMapOf<Int, Stroke>()
    private var active: Pair<Int, Stroke>? = null
    private var nextID = 1
    private var fade = InkFade()
    private val main = Handler(Looper.getMainLooper())
    private val advanceFade = Runnable { advance() }
    private var predictor: Any? = null
    private var clearing = false

    val isActivelyDrawing: Boolean get() = active != null

    /** Whether Android's motion prediction draws the live tip (API 34+). */
    internal val predictsTip: Boolean get() = predictor != null

    init {
        setWillNotDraw(false)
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        if (Build.VERSION.SDK_INT >= 34) predictor = MotionPredictor(context)
    }

    // Touches (in this view's coordinates)

    /** Feeds one pointer's event; [pointerId] is the drawing finger. */
    fun touch(event: MotionEvent, pointerId: Int) {
        val index = event.findPointerIndex(pointerId)
        if (index < 0) return
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> began(event, index)
            MotionEvent.ACTION_MOVE -> moved(event, index)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> ended(event, index)
        }
    }

    private fun began(event: MotionEvent, index: Int) {
        val id = nextID++
        val stroke = Stroke()
        strokes[id] = stroke
        active = id to stroke
        main.removeCallbacks(advanceFade)
        fade.penDown(id, time(event.eventTime))?.let { startFading(it) }
        add(event, index, stroke)
        draw(stroke, event)
    }

    private fun moved(event: MotionEvent, index: Int) {
        val stroke = active?.second ?: return
        add(event, index, stroke)
        draw(stroke, event)
    }

    private fun ended(event: MotionEvent, index: Int) {
        val (_, stroke) = active ?: return
        active = null
        add(event, index, stroke)
        stroke.builder.finish(point(event.getX(index), event.getY(index)), time(event.eventTime))
        freeze(stroke)
        stroke.live = path(stroke.open, stroke.trace.tail(finished = true))
        stroke.logIndex = onStroke(stroke.trace.smoothedSamples)
        fade.penUp(time(event.eventTime))
        scheduleFade()
        invalidate()
    }

    /** Removes all ink (a new recording, or this one finished), fading it quickly if [animated]. */
    fun clear(animated: Boolean) {
        main.removeCallbacks(advanceFade)
        val now = uptime()
        for ((id, stroke) in strokes) stroke.clearFrom = if (stroke === active?.second) 1.0 else fade.opacity(id, now)
        active = null
        fade = InkFade()
        if (!animated) {
            strokes.clear()
            clearing = false
            invalidate()
            return
        }
        strokes.values.forEach { it.clearStart = now }
        clearing = true
        postInvalidateOnAnimation()
    }

    // Drawing

    private fun point(x: Float, y: Float) = Vec(x / density.toDouble(), y / density.toDouble())

    private fun add(event: MotionEvent, index: Int, stroke: Stroke) {
        for (h in 0 until event.historySize) {
            stroke.builder.add(point(event.getHistoricalX(index, h), event.getHistoricalY(index, h)), time(event.getHistoricalEventTime(h)))
        }
        stroke.builder.add(point(event.getX(index), event.getY(index)), time(event.eventTime))
    }

    private fun draw(stroke: Stroke, event: MotionEvent) {
        freeze(stroke)
        val ahead = stroke.builder.predicting(predicted(event))
        stroke.live = path(stroke.open, stroke.trace.tail(ahead))
        invalidate()
    }

    @SuppressLint("NewApi")
    private fun predicted(event: MotionEvent): List<Pair<Vec, Double>> {
        // Checked first: on older Android even the cast below fails to resolve MotionPredictor.
        if (Build.VERSION.SDK_INT < 34) return emptyList()
        val predictor = predictor as? MotionPredictor ?: return emptyList()
        return runCatching {
            predictor.record(event)
            val p = predictor.predict(SystemClock.uptimeNanos() + 16_000_000L) ?: return emptyList()
            try {
                (0 until p.historySize).map { point(p.getHistoricalX(0, it), p.getHistoricalY(0, it)) to time(p.getHistoricalEventTime(it)) } +
                    listOf(point(p.x, p.y) to time(p.eventTime))
            } finally {
                p.recycle()
            }
        }.getOrDefault(emptyList())
    }

    /** Hands new samples to the trace and moves what became final out of the live path. */
    private fun freeze(stroke: Stroke) {
        stroke.trace.append(stroke.builder.samples.drop(stroke.trace.samples.size))
        val frozen = stroke.trace.freeze()
        if (frozen.isEmpty()) return
        InkTrace.add(frozen, stroke.open, density)
        stroke.openCount += frozen.size
        if (stroke.openCount < CHUNK_POINTS) return
        stroke.chunks += stroke.open
        stroke.open = Path()
        stroke.openCount = 0
    }

    private fun path(open: Path, tail: List<InkTrace.Point>): Path = Path(open).also { InkTrace.add(tail, it, density) }

    override fun onDraw(canvas: Canvas) {
        val now = uptime()
        var animating = false
        val gone = mutableListOf<Int>()
        for ((id, stroke) in strokes) {
            var opacity = if (stroke === active?.second) 1.0 else fade.opacity(id, now)
            stroke.clearStart?.let { start ->
                val p = ((now - start) / CLEAR_DURATION).coerceIn(0.0, 1.0)
                opacity = stroke.clearFrom * (1 - p) * (1 - p)
                if (p >= 1) gone += id else animating = true
            }
            if (fade.fading.any { id in it.strokes }) animating = true
            if (opacity <= 0.001) continue
            paint.alpha = (opacity * 255).toInt()
            for (chunk in stroke.chunks) canvas.drawPath(chunk, paint)
            canvas.drawPath(stroke.live, paint)
        }
        gone.forEach { strokes.remove(it) }
        if (clearing && strokes.isEmpty()) clearing = false
        if (animating) postInvalidateOnAnimation()
    }

    // Fading

    private fun scheduleFade() {
        main.removeCallbacks(advanceFade)
        val at = fade.fadeAt ?: return
        // Rounded up: a callback a fraction of a millisecond early would find the hold not over.
        val delayMs = kotlin.math.ceil((at - uptime()) * 1000).toLong().coerceAtLeast(0)
        main.postDelayed(advanceFade, delayMs)
    }

    private fun advance() {
        val group = fade.advance(uptime())
        if (group != null) startFading(group) else scheduleFade() // woke early: try again
    }

    private fun startFading(group: InkFade.Group) {
        val fading = group.strokes.mapNotNull { strokes[it] }
        onFade(fading.mapNotNull { it.logIndex }, group.start)
        postInvalidateOnAnimation()
        main.postDelayed({
            for (id in fade.removeFaded(uptime())) strokes.remove(id)
            invalidate()
        }, ((InkFade.DURATION + 0.05) * 1000).toLong())
    }

    companion object {
        /** #FF3B30, Scrawl's ink. */
        val RED: Int = Color.rgb(255, 59, 48)

        /** Frozen curve points per chunk path: the live path redraws at most this many plus the tip. */
        private const val CHUNK_POINTS = 48
        private const val CLEAR_DURATION = 0.2

        /** Seconds since boot, the clock touch event times use. */
        fun uptime(): Double = SystemClock.uptimeMillis() / 1000.0
        fun time(eventTimeMs: Long): Double = eventTimeMs / 1000.0
    }
}
