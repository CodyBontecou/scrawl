package dev.kitlangton.scrawl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

class InkTest {
    /** Deterministic white noise (SplitMix64) of ±0.8 pt standing in for finger jitter. */
    private fun jitter(i: Int): Double {
        var z = (i + 1).toULong() * 0x9E3779B97F4A7C15uL
        z = (z xor (z shr 30)) * 0xBF58476D1CE4E5B9uL
        z = (z xor (z shr 27)) * 0x94D049BB133111EBuL
        z = z xor (z shr 31)
        return ((z shr 11).toDouble() / (1uL shl 53).toDouble() * 2 - 1) * 0.8
    }

    /** The union of discs and joining quads: what the WINDING fill of [InkTrace.add] covers. */
    private fun contains(points: List<InkTrace.Point>, p: Vec): Boolean {
        var previous: InkTrace.Point? = null
        for (point in points) {
            if (hypot(p.x - point.point.x, p.y - point.point.y) <= point.radius) return true
            val a = previous
            if (a != null) {
                val dx = point.point.x - a.point.x
                val dy = point.point.y - a.point.y
                val d = hypot(dx, dy)
                if (d > 0.01) {
                    val nx = -dy / d
                    val ny = dx / d
                    val quad = listOf(
                        Vec(a.point.x + nx * a.radius, a.point.y + ny * a.radius),
                        Vec(point.point.x + nx * point.radius, point.point.y + ny * point.radius),
                        Vec(point.point.x - nx * point.radius, point.point.y - ny * point.radius),
                        Vec(a.point.x - nx * a.radius, a.point.y - ny * a.radius),
                    )
                    if (inConvex(quad, p)) return true
                }
            }
            previous = point
        }
        return false
    }

    private fun inConvex(poly: List<Vec>, p: Vec): Boolean {
        var sign = 0
        for (i in poly.indices) {
            val a = poly[i]
            val b = poly[(i + 1) % poly.size]
            val cross = (b.x - a.x) * (p.y - a.y) - (b.y - a.y) * (p.x - a.x)
            val s = if (cross > 1e-12) 1 else if (cross < -1e-12) -1 else 0
            if (s != 0) {
                if (sign == 0) sign = s else if (s != sign) return false
            }
        }
        return true
    }

    // Filter

    @Test
    fun oneEuroRemovesJitterFromASlowLine() {
        val filter = OneEuroFilter(3.0, 0.03)
        var rawError = 0.0
        var filteredError = 0.0
        for (i in 0 until 240) {
            val raw = Vec(i / 3.0, jitter(i))
            val filtered = filter.filter(raw, i / 120.0)
            if (i > 30) {
                rawError += abs(raw.y); filteredError += abs(filtered.y)
            }
        }
        assertTrue(filteredError < rawError * 0.5)
    }

    @Test
    fun oneEuroBarelyLagsAFastLine() {
        val filter = OneEuroFilter(3.0, 0.03)
        var value = Vec(0.0, 0.0)
        for (i in 0..60) value = filter.filter(Vec(i * 10.0, 0.0), i / 120.0)
        assertTrue(abs(value.x - 600) < 12)
    }

    @Test
    fun aDiagonalStaysOnItsLine() {
        val filter = OneEuroFilter(3.0, 0.03)
        for (i in 0..60) {
            val p = filter.filter(Vec(i * 4.0, i * 1.2), i / 120.0)
            assertTrue(abs(p.y - p.x * 0.3) < 1e-6)
        }
    }

    @Test
    fun oneEuroHoldsStillAndSurvivesRepeatedTimestamps() {
        val filter = OneEuroFilter(3.0, 0.03)
        repeat(10) { assertEquals(Vec(5.0, 7.0), filter.filter(Vec(5.0, 7.0), 1.0)) }
    }

    @Test
    fun theLineBehindTheTipIsSmoothedButItsEndsStayPut() {
        val wobbly = (0 until 80).map { Vec(it * 2.0, jitter(it)) }
        val trace = InkTrace().apply { append(samples(wobbly)) }
        val smoothed = trace.smoothedSamples
        assertEquals(wobbly.first(), smoothed.first().point)
        assertEquals(wobbly.last(), smoothed.last().point)
        val rawWobble = wobbly.drop(1).dropLast(1).sumOf { abs(it.y) }
        val smoothWobble = smoothed.drop(1).dropLast(1).sumOf { abs(it.point.y) }
        assertTrue(smoothWobble < rawWobble * 0.6)
        val straight = InkTrace().apply { append(samples((0 until 20).map { Vec(it * 3.0, it.toDouble()) })) }
        assertTrue(straight.smoothedSamples.all { abs(it.point.y - it.point.x / 3) < 1e-9 })
    }

    // Builder

    @Test
    fun samplesAreSpacedAndEndWhereTheFingerLifted() {
        val builder = InkStrokeBuilder()
        for (i in 0 until 200) builder.add(Vec(i * 0.1, 0.0), i / 240.0)
        builder.finish(Vec(20.0, 0.0), 1.0)
        val samples = builder.samples
        for ((a, b) in samples.zipWithNext()) {
            assertTrue(hypot(b.point.x - a.point.x, b.point.y - a.point.y) >= InkStrokeBuilder.MIN_SPACING - 1e-9)
            assertTrue(b.time >= a.time)
        }
        assertEquals(Vec(20.0, 0.0), samples.last().point)
    }

    @Test
    fun fastLinesAreThinnerButNeverPressureBased() {
        assertEquals(InkStrokeBuilder.MAX_WIDTH, InkStrokeBuilder.width(0.0), 0.0)
        assertTrue(InkStrokeBuilder.width(400.0) > InkStrokeBuilder.width(1200.0))
        assertEquals(InkStrokeBuilder.MAX_WIDTH * (1 - InkStrokeBuilder.THINNING), InkStrokeBuilder.width(10000.0), 1e-9)
    }

    @Test
    fun predictionsContinueTheSmoothedLineWithoutChangingIt() {
        val builder = InkStrokeBuilder()
        for (i in 0 until 60) builder.add(Vec(i * 2.0, 0.0), i / 120.0)
        val before = builder.samples.toList()
        val ahead = builder.predicting(listOf(Vec(122.0, 0.0) to 0.5, Vec(124.0, 0.0) to 0.51))
        assertEquals(before, builder.samples)
        assertTrue(ahead.isNotEmpty())
        assertTrue(ahead.all { it.point.x > before.last().point.x })
    }

    // Geometry

    private fun samples(points: List<Vec>, width: Double = 4.0) = points.mapIndexed { i, p -> InkSample(p, i / 120.0, width) }

    private fun circle(count: Int, radius: Double = 60.0) = (0 until count).map { i ->
        val a = i.toDouble() / count * 2 * PI * 1.1
        Vec(100 + radius * cos(a), 100 + radius * sin(a))
    }

    @Test
    fun theCurvePassesThroughEverySample() {
        val p = listOf(Vec(0.0, 0.0), Vec(10.0, 3.0), Vec(18.0, 12.0), Vec(21.0, 30.0))
        assertEquals(p[1], InkTrace.catmullRom(p[0], p[1], p[2], p[3], 0.0))
        val end = InkTrace.catmullRom(p[0], p[1], p[2], p[3], 1.0)
        assertTrue(abs(end.x - p[2].x) < 1e-9 && abs(end.y - p[2].y) < 1e-9)
    }

    @Test
    fun drawingItBitByBitGivesTheSameOutlineAsAllAtOnce() {
        val all = samples(circle(90))
        val trace = InkTrace()
        val drawn = mutableListOf<InkTrace.Point>()
        for (sample in all) {
            trace.append(listOf(sample))
            drawn += trace.freeze()
            val tip = trace.tail().firstOrNull()
            val last = drawn.lastOrNull()
            if (tip != null && last != null) assertEquals(last, tip)
        }
        assertTrue(trace.frozen > 60)
        val tail = trace.tail(finished = true)
        assertEquals(drawn.last(), tail.first())
        val joined = (drawn + tail.drop(1)).fold(mutableListOf<InkTrace.Point>()) { acc, p -> if (acc.lastOrNull() != p) acc += p; acc }
        assertEquals(trace.outline, joined)
    }

    @Test
    fun predictedPointsOnlyExtendTheTip() {
        val trace = InkTrace().apply { append(samples(circle(40))) }
        trace.freeze()
        val live = trace.tail(samples(listOf(Vec(180.0, 140.0), Vec(182.0, 150.0))))
        assertTrue(live.size > trace.tail().size)
        assertEquals(40, trace.samples.size)
    }

    @Test
    fun theOutlineCoversTheLineAndNothingFarFromIt() {
        val trace = InkTrace().apply { append(samples(circle(120))) }
        val points = trace.outline
        for (point in points) assertTrue(contains(points, point.point))
        assertFalse(contains(points, Vec(100.0, 100.0)))
        assertFalse(contains(points, Vec(100.0, 100.0 + 60 + 6)))
    }

    @Test
    fun hairpinsAndCrossingsLeaveNoHoles() {
        val line = (0 until 40).map { Vec(50.0, it * 2.0) } +
            (0 until 40).map { Vec(50.5, 78 - it * 2.0) } +
            (0 until 40).map { Vec(10 + it * 2.0, 40.0) }
        val trace = InkTrace().apply { append(samples(line, 4.0)) }
        val outline = trace.outline
        var y = 4.0
        while (y <= 74) {
            assertTrue(contains(outline, Vec(50.25, y))); y += 2
        }
        assertTrue(contains(outline, Vec(50.25, 40.0)))
        assertFalse(contains(outline, Vec(60.0, 60.0)))
    }

    @Test
    fun theEndsTaper() {
        val trace = InkTrace().apply { append(samples((0 until 60).map { Vec(it.toDouble(), 0.0) }, 4.0)) }
        val outline = trace.outline
        assertEquals(2 * InkTrace.TAPER_TIP, outline.first().radius, 1e-6)
        assertEquals(2 * InkTrace.TAPER_TIP, outline.last().radius, 1e-6)
        assertEquals(2.0, outline.maxOf { it.radius }, 0.0)
        assertEquals(2.0, trace.tail().last().radius, 0.0)
    }

    @Test
    fun aTapIsADot() {
        val trace = InkTrace().apply { append(samples(listOf(Vec(5.0, 5.0)))) }
        assertTrue(contains(trace.outline, Vec(5.0, 5.0)))
        assertFalse(contains(trace.outline, Vec(8.0, 5.0)))
    }

    // Fading

    @Test
    fun aStrokeFadesAfterTheHold() {
        val fade = InkFade()
        assertNull(fade.penDown(1, 0.0))
        assertEquals(1.0, fade.opacity(1, 5.0), 0.0)
        fade.penUp(5.0)
        assertEquals(5 + InkFade.HOLD, fade.fadeAt!!, 0.0)
        assertNull(fade.advance(5 + InkFade.HOLD - 0.01))
        assertEquals(1.0, fade.opacity(1, 5 + InkFade.HOLD - 0.01), 0.0)
        val start = 5 + InkFade.HOLD
        assertEquals(InkFade.Group(listOf(1), start), fade.advance(start))
        assertEquals(1.0, fade.opacity(1, start), 0.0)
        val a = fade.opacity(1, start + 0.1)
        val b = fade.opacity(1, start + 0.2)
        assertTrue(a < 1 && b < a && b > 0)
        assertTrue(a < 0.5)
        assertTrue(fade.opacity(1, start + InkFade.DURATION) < 1e-9)
        assertTrue(fade.removeFaded(start + InkFade.DURATION - 0.01).isEmpty())
        assertEquals(listOf(1), fade.removeFaded(start + InkFade.DURATION))
        assertTrue(fade.fading.isEmpty())
    }

    @Test
    fun strokesInQuickSuccessionFadeTogether() {
        val fade = InkFade()
        fade.penDown(1, 0.0)
        fade.penUp(0.8)
        assertNull(fade.penDown(2, 1.5))
        assertNull(fade.advance(4.0))
        assertEquals(1.0, fade.opacity(1, 4.0), 0.0)
        fade.penUp(4.2)
        assertEquals(InkFade.Group(listOf(1, 2), 4.2 + InkFade.HOLD), fade.advance(4.2 + InkFade.HOLD))
    }

    @Test
    fun aStrokeAfterTheGroupBeganFadingStartsANewGroup() {
        val fade = InkFade()
        fade.penDown(1, 0.0)
        fade.penUp(1.0)
        assertEquals(InkFade.Group(listOf(1), 1 + InkFade.HOLD), fade.penDown(2, 1 + InkFade.HOLD + 0.05))
        assertEquals(listOf(2), fade.held)
        fade.penUp(4.0)
        assertEquals(listOf(2), fade.advance(4 + InkFade.HOLD)?.strokes)
    }
}
