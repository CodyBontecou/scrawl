package dev.kitlangton.scrawl

import android.graphics.Path
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** A point in view coordinates (dp-independent "points", like iOS). */
data class Vec(val x: Double, val y: Double)

/**
 * One-euro filter (Casiez, Roussel & Vogel, CHI 2012) on points: a low-pass whose cutoff rises with
 * speed, so slow, careful lines lose their jitter while fast ones don't lag. One cutoff from the 2D
 * speed for both axes, so a straight line stays straight.
 */
class OneEuroFilter(val minCutoff: Double, val beta: Double, val derivativeCutoff: Double = 1.0) {
    private var value: Vec? = null
    private var vx = 0.0
    private var vy = 0.0
    private var time: Double? = null

    fun copy(): OneEuroFilter = OneEuroFilter(minCutoff, beta, derivativeCutoff).also {
        it.value = value; it.vx = vx; it.vy = vy; it.time = time
    }

    fun filter(point: Vec, t: Double): Vec {
        val value = value
        val time = time
        if (value == null || time == null) {
            this.value = point; this.time = t
            return point
        }
        val dt = max(t - time, 1.0 / 1000)
        val d = alpha(derivativeCutoff, dt)
        vx += d * ((point.x - value.x) / dt - vx)
        vy += d * ((point.y - value.y) / dt - vy)
        val a = alpha(minCutoff + beta * hypot(vx, vy), dt)
        val next = Vec(value.x + a * (point.x - value.x), value.y + a * (point.y - value.y))
        this.value = next; this.time = t
        return next
    }

    companion object {
        fun alpha(cutoff: Double, dt: Double): Double = 1 / (1 + 1 / (2 * PI * cutoff) / dt)
    }
}

/**
 * A point of a stroke as recorded: filtered position, the touch's own timestamp (seconds since
 * boot, as `MotionEvent.getEventTime()`), and the line's width there.
 */
data class InkSample(val point: Vec, val time: Double, val width: Double)

/**
 * Turns raw touch positions (every historical one) into the stroke's samples: one-euro filtered, at
 * least [MIN_SPACING] apart, with a width that thins as the finger speeds up (pressure plays no
 * part, like the Mac's ink).
 */
class InkStrokeBuilder private constructor(private var filter: OneEuroFilter, private var speed: Double, samples: List<InkSample>) {
    constructor() : this(OneEuroFilter(3.0, 0.03), 0.0, emptyList())

    private val _samples = samples.toMutableList()
    val samples: List<InkSample> get() = _samples

    fun add(point: Vec, time: Double) = append(filter.filter(point, time), time)

    /**
     * Samples for predicted touches, run through a copy of the filter so the live tip continues the
     * smoothed line. They're for drawing the tip only, never part of the stroke.
     */
    fun predicting(points: List<Pair<Vec, Double>>): List<InkSample> {
        if (points.isEmpty()) return emptyList()
        val copy = InkStrokeBuilder(filter.copy(), speed, listOfNotNull(_samples.lastOrNull()))
        for ((point, time) in points) copy.add(point, time)
        return copy.samples.drop(if (_samples.isEmpty()) 0 else 1)
    }

    /** The finger lifted at [point]: the stroke ends exactly there. */
    fun finish(point: Vec, time: Double) = append(point, time)

    private fun append(point: Vec, time: Double) {
        val last = _samples.lastOrNull()
        if (last != null) {
            val distance = hypot(point.x - last.point.x, point.y - last.point.y)
            if (distance < MIN_SPACING) return
            val dt = max(time - last.time, 1.0 / 1000)
            speed += (1 - exp(-dt / 0.04)) * (distance / dt - speed)
        }
        _samples += InkSample(point, time, width(speed))
    }

    companion object {
        /** The line's width when drawn slowly, in points. */
        const val MAX_WIDTH = 4.6

        /** How much thinner a fast line gets: 0.45 makes it 55% of [MAX_WIDTH]. */
        const val THINNING = 0.45

        /** Points a second at which a line is thinnest. */
        const val FAST_SPEED = 1600.0
        const val MIN_SPACING = 0.4

        fun width(speed: Double): Double {
            val t = (speed / FAST_SPEED).coerceIn(0.0, 1.0)
            return MAX_WIDTH * (1 - THINNING * t * t * (3 - 2 * t))
        }
    }
}

/**
 * A stroke's outline, built as it grows: a centripetal Catmull-Rom curve through the samples, drawn
 * as discs joined by quads (filled with the nonzero rule, so overlaps and hairpins never leave
 * holes). The start tapers in over [TAPER_LENGTH], and the end tapers once the finger lifts.
 *
 * Behind the tip, samples also get a centered smoothing ([smoothed]). A segment is final once its
 * smoothing can't change and it's more than [TAPER_LENGTH] from the end, so [freeze] hands each
 * segment out once for drawing into a layer that never changes; only [tail] is redrawn per touch.
 */
class InkTrace {
    data class Point(val point: Vec, val radius: Double)

    private val _samples = mutableListOf<InkSample>()
    private val _lengths = mutableListOf<Double>()
    val samples: List<InkSample> get() = _samples

    /** Length along the samples to each sample. */
    val lengths: List<Double> get() = _lengths

    /** Segments (sample i to i + 1) that are final. */
    var frozen = 0
        private set

    val length: Double get() = _lengths.lastOrNull() ?: 0.0

    /** The samples as drawn (smoothed), for the timeline once the stroke is finished. */
    val smoothedSamples: List<InkSample>
        get() = _samples.indices.map { k -> _samples[k].copy(point = smoothed(k, _samples.size) { _samples[it].point }) }

    fun append(new: Iterable<InkSample>) {
        for (sample in new) {
            val last = _samples.lastOrNull()
            val step = if (last == null) 0.0 else hypot(sample.point.x - last.point.x, sample.point.y - last.point.y)
            _lengths += length + step
            _samples += sample
        }
    }

    /** The curve points of segments that just became final, starting at the first one's start. */
    fun freeze(): List<Point> {
        val points = mutableListOf<Point>()
        val n = _samples.size
        while (frozen + 4 < n && length - _lengths[frozen + 1] >= TAPER_LENGTH) {
            points += segment(frozen, n, { _samples[it] }, { _lengths[it] }, null, points.isEmpty())
            frozen += 1
        }
        return points
    }

    /**
     * The rest of the stroke after the frozen segments: while drawing, through any [predicted]
     * samples (for the live tip only); once [finished], with the end tapered.
     */
    fun tail(predicted: List<InkSample> = emptyList(), finished: Boolean = false, from: Int = frozen): List<Point> {
        if (_samples.isEmpty()) return emptyList()
        val n = _samples.size + predicted.size
        if (n == 1) return listOf(Point(_samples[0].point, _samples[0].width / 2 * 0.8))
        val predictedLengths = mutableListOf<Double>()
        var previous = _samples.last().point
        for (sample in predicted) {
            predictedLengths += (predictedLengths.lastOrNull() ?: length) + hypot(sample.point.x - previous.x, sample.point.y - previous.y)
            previous = sample.point
        }
        val count = _samples.size
        val sample = { k: Int -> if (k < count) _samples[k] else predicted[k - count] }
        val lengthAt = { k: Int -> if (k < count) _lengths[k] else predictedLengths[k - count] }
        val points = mutableListOf<Point>()
        for (index in from until n - 1) {
            points += segment(index, n, sample, lengthAt, if (finished) length else null, points.isEmpty())
        }
        return points
    }

    /** The whole stroke, finished. */
    val outline: List<Point> get() = tail(finished = true, from = 0)

    private fun segment(
        i: Int,
        n: Int,
        sample: (Int) -> InkSample,
        length: (Int) -> Double,
        total: Double?,
        includeStart: Boolean,
    ): List<Point> {
        fun point(k: Int) = smoothed(k, n) { sample(it).point }
        val p1 = point(i)
        val p2 = point(i + 1)
        // Past the ends the curve continues straight.
        val p0 = if (i > 0) point(i - 1) else Vec(2 * p1.x - p2.x, 2 * p1.y - p2.y)
        val p3 = if (i + 2 < n) point(i + 2) else Vec(2 * p2.x - p1.x, 2 * p2.y - p1.y)
        val steps = max(1, min(12, ceil(hypot(p2.x - p1.x, p2.y - p1.y) / STEP).toInt()))
        val w1 = sample(i).width
        val w2 = sample(i + 1).width
        val l1 = length(i)
        val l2 = length(i + 1)
        return ((if (includeStart) 0 else 1)..steps).map { k ->
            val u = k.toDouble() / steps
            Point(catmullRom(p0, p1, p2, p3, u), (w1 + (w2 - w1) * u) / 2 * taper(l1 + (l2 - l1) * u, total))
        }
    }

    companion object {
        const val TAPER_LENGTH = 10.0

        /** The width at the very tip of a taper, as a fraction of the full width. */
        const val TAPER_TIP = 0.35

        /** About one curve point per this many points of length. */
        const val STEP = 1.5

        /**
         * Sample k's position after a centered 1-2-3-2-1 smoothing, narrower near the ends (which
         * stay exactly where they were).
         */
        fun smoothed(k: Int, n: Int, point: (Int) -> Vec): Vec {
            val r = minOf(2, k, n - 1 - k)
            if (r <= 0) return point(k)
            val weights = if (r == 1) doubleArrayOf(1.0, 2.0, 1.0) else doubleArrayOf(1.0, 2.0, 3.0, 2.0, 1.0)
            var x = 0.0
            var y = 0.0
            var total = 0.0
            for ((i, offset) in (-r..r).withIndex()) {
                val p = point(k + offset)
                x += p.x * weights[i]; y += p.y * weights[i]; total += weights[i]
            }
            return Vec(x / total, y / total)
        }

        fun taper(along: Double, total: Double?): Double {
            fun ramp(distance: Double): Double {
                val x = (distance / TAPER_LENGTH).coerceIn(0.0, 1.0)
                return TAPER_TIP + (1 - TAPER_TIP) * (1 - (1 - x) * (1 - x))
            }
            val start = ramp(along)
            return if (total == null) start else min(start, ramp(total - along))
        }

        /** Centripetal Catmull-Rom (Barry and Goldman's form) from [p1] (u = 0) to [p2] (u = 1). */
        fun catmullRom(p0: Vec, p1: Vec, p2: Vec, p3: Vec, u: Double): Vec {
            fun knot(a: Vec, b: Vec) = max(sqrt(hypot(b.x - a.x, b.y - a.y)), 1e-4)
            fun mix(a: Vec, b: Vec, ta: Double, tb: Double, t: Double): Vec {
                val w = (t - ta) / (tb - ta)
                return Vec(a.x + (b.x - a.x) * w, a.y + (b.y - a.y) * w)
            }
            val t0 = 0.0
            val t1 = t0 + knot(p0, p1)
            val t2 = t1 + knot(p1, p2)
            val t3 = t2 + knot(p2, p3)
            val t = t1 + (t2 - t1) * u
            val a1 = mix(p0, p1, t0, t1, t)
            val a2 = mix(p1, p2, t1, t2, t)
            val a3 = mix(p2, p3, t2, t3, t)
            val b1 = mix(a1, a2, t0, t2, t)
            val b2 = mix(a2, a3, t1, t3, t)
            return mix(b1, b2, t1, t2, t)
        }

        /**
         * Adds discs at [points] and quads between them to [path], all wound the same way, so the
         * nonzero (WINDING) fill is their union. [scale] maps points to pixels.
         */
        fun add(points: List<Point>, path: Path, scale: Float = 1f) {
            path.fillType = Path.FillType.WINDING
            var previous: Point? = null
            for (point in points) {
                val cx = (point.point.x * scale).toFloat()
                val cy = (point.point.y * scale).toFloat()
                val r = (point.radius * scale).toFloat()
                path.addCircle(cx, cy, r, Path.Direction.CW)
                val a = previous
                if (a != null) {
                    val dx = point.point.x - a.point.x
                    val dy = point.point.y - a.point.y
                    val distance = hypot(dx, dy)
                    if (distance > 0.01) {
                        val nx = -dy / distance
                        val ny = dx / distance
                        fun f(v: Double) = (v * scale).toFloat()
                        // Same winding as the circles (clockwise in screen space).
                        path.moveTo(f(a.point.x - nx * a.radius), f(a.point.y - ny * a.radius))
                        path.lineTo(f(point.point.x - nx * point.radius), f(point.point.y - ny * point.radius))
                        path.lineTo(f(point.point.x + nx * point.radius), f(point.point.y + ny * point.radius))
                        path.lineTo(f(a.point.x + nx * a.radius), f(a.point.y + ny * a.radius))
                        path.close()
                    }
                }
                previous = point
            }
        }
    }
}

/**
 * When strokes fade. A stroke stays until [HOLD] after the pen last lifted, then fades over
 * [DURATION]. Strokes drawn in quick succession are one group: putting the pen down again before
 * the group starts fading holds all of them, and they fade together.
 */
class InkFade {
    data class Group(val strokes: List<Int>, val start: Double)

    private val _held = mutableListOf<Int>()
    val held: List<Int> get() = _held

    /** When [held] starts fading; null while the pen is down. */
    var fadeAt: Double? = null
        private set
    private val _fading = mutableListOf<Group>()
    val fading: List<Group> get() = _fading
    private var penIsDown = false

    /** A stroke begins. Returns the earlier group if its time had already come. */
    fun penDown(stroke: Int, time: Double): Group? {
        val started = advance(time)
        _held += stroke
        penIsDown = true
        fadeAt = null
        return started
    }

    fun penUp(time: Double) {
        penIsDown = false
        fadeAt = if (_held.isEmpty()) null else time + HOLD
    }

    /** Starts the held group fading if its time has come, and returns it. */
    fun advance(time: Double): Group? {
        val fadeAt = fadeAt
        if (penIsDown || fadeAt == null || fadeAt > time || _held.isEmpty()) return null
        val group = Group(_held.toList(), fadeAt)
        _fading += group
        _held.clear()
        this.fadeAt = null
        return group
    }

    /** Drops groups that have finished fading and returns their strokes. */
    fun removeFaded(time: Double): List<Int> {
        val done = _fading.filter { it.start + DURATION <= time }
        _fading.removeAll { it.start + DURATION <= time }
        return done.flatMap { it.strokes }
    }

    /** 1 while held, easing out to 0 over [DURATION] once its group fades, 0 once gone. */
    fun opacity(stroke: Int, time: Double): Double {
        if (stroke in _held) {
            val fadeAt = fadeAt
            if (penIsDown || fadeAt == null || time < fadeAt) return 1.0
            return easeOut((time - fadeAt) / DURATION)
        }
        val group = _fading.firstOrNull { stroke in it.strokes } ?: return 0.0
        return easeOut((time - group.start) / DURATION)
    }

    companion object {
        const val HOLD = 1.5
        const val DURATION = 0.3
        private fun easeOut(progress: Double): Double {
            val p = progress.coerceIn(0.0, 1.0)
            return (1 - p) * (1 - p)
        }
    }
}
