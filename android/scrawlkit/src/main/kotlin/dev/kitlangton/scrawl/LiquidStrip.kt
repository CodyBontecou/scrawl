package dev.kitlangton.scrawl

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * SwiftUI's `Spring(duration:bounce:)`: mass 1, stiffness (2π/d)², damping 4π(1−b)/d. [update]
 * advances a value toward its target by [dt] seconds (semi-implicit Euler; callers substep).
 */
data class Spring(val duration: Double, val bounce: Double) {
    companion object {
        /** Measured with SwiftUI on macOS for the strip's springs (StripMotion). */
        private val SWIFTUI_SETTLING = mapOf(
            (0.425 to 0.0) to 0.7000, // exitFade
            (0.42 to 0.18) to 0.7739, // change
            (0.21 to 0.0) to 0.4000, // quick
            (0.4704 to 0.18) to 0.8572, // stretchLag
            (0.483 to 0.18) to 0.8779, // pourLag
            (0.462 to 0.38) to 1.0854, // unsquash
            (0.231 to 0.35) to 0.5563, // throwStretch
            (0.189 to 0.55) to 0.6469, // shake
            (0.336 to 0.0) to 0.5000, // rowIn
        )
    }

    val stiffness: Double = (2 * PI / duration).pow(2)
    val damping: Double = 4 * PI * (1 - bounce) / duration

    fun step(value: Double, velocity: Double, target: Double, dt: Double): Pair<Double, Double> {
        val acceleration = -stiffness * (value - target) - damping * velocity
        val v = velocity + acceleration * dt
        return (value + v * dt) to v
    }

    /**
     * SwiftUI's `settlingDuration` for this spring. Apple's rule isn't public, so the strip's springs
     * use the values SwiftUI reports on macOS (`Spring(duration:bounce:).settlingDuration`); any
     * other spring falls back to settling a unit displacement (|v| < 0.01 and |x| < 0.001).
     */
    val settlingDuration: Double get() = SWIFTUI_SETTLING[duration to bounce] ?: simulatedSettling

    private val simulatedSettling: Double by lazy {
        var x = 1.0
        var v = 0.0
        var t = 0.0
        val dt = 0.001
        while (t < 10) {
            val (nx, nv) = step(x, v, 0.0, dt)
            x = nx; v = nv; t += dt
            if (abs(v) < 0.01 && abs(x) < 0.001) break
        }
        t
    }
}

/** Every spring and timing of the strip's "Liquid morph" motion. */
object StripMotion {
    val change = Spring(0.42, 0.18)
    val quick = Spring(0.21, 0.0)
    val exit = Spring(0.34, 0.0)
    val exitFade = Spring(0.425, 0.0)
    val stretchLag = Spring(0.4704, 0.18)
    val pourLag = Spring(0.483, 0.18)
    val unsquash = Spring(0.462, 0.38)
    val throwStretch = Spring(0.231, 0.35)
    val shake = Spring(0.189, 0.55)
    val rowIn = Spring(0.336, 0.0)
    val rowGather = Spring(0.294, 0.0)
    val rowOut = Spring(0.168, 0.0)
    val iconIn = Spring(0.294, 0.0)
    val iconPop = Spring(0.336, 0.18)
    val bubbleBud = Spring(0.441, 0.38)
    val bubbleWidth = Spring(0.42, 0.18)
    val bubbleHeight = Spring(0.378, 0.18)
    val bubbleTextIn = Spring(0.525, 0.0)
    val bubbleTextOut = Spring(0.147, 0.0)
    val bubbleAbsorb = Spring(0.378, 0.0)

    const val STAGGER = 0.045
    const val FAN_DELAY = 0.04
    const val ICON_SWAP_DELAY = 0.12
    const val BUBBLE_DELAY_AFTER_POUR = 0.3
    const val BUBBLE_DELAY = 0.18

    /** "Sending…" waits this long, so a quick upload goes straight to "Sent to …". */
    const val SENDING_BUBBLE_DELAY = 1.2
    const val SHAKE_DELAY = 0.06
    const val SHAKE_VELOCITY = 900.0
    const val GULP_VELOCITY = 1.4
    const val EXIT_DELAY = 0.22
    const val STRETCH_SPEED = 2600.0
    const val MAX_STRETCH = 0.3
    const val REDUCED_FADE = 0.11

    /** How long the overlay stays after the strip is told to leave. */
    val overlayLingerMs: Long get() = (((EXIT_DELAY + exitFade.settlingDuration) + 0.05) * 1000).toLong()
}

/** The strip's metrics in points (dp). */
object StripMetrics {
    const val WIDTH = 42.0
    const val HIT = 44.0
    const val ROW = 44.0
    const val DOT_ROW = WIDTH
    const val DIVIDER = 9.0
    const val EXPANDED_HEIGHT = DOT_ROW + ROW * 3 + DIVIDER
    const val DRAG_THRESHOLD = 8.0

    /** Each row's centre relative to the strip's centre: dot, draw, divider, discard, send. */
    val rowCenters: List<Double> = run {
        var top = -EXPANDED_HEIGHT / 2
        listOf(DOT_ROW, ROW, DIVIDER, ROW, ROW).map { h -> (top + h / 2).also { top += h } }
    }
    const val DOT_INDEX = 0
    const val DISCARD_INDEX = 3
    const val SEND_INDEX = 4
    const val HIDDEN_BUBBLE = 28.0
    const val HIDDEN_BUBBLE_GAP = -35.0
    const val BUBBLE_GAP = 8.0
    const val NECK_REACH = 18.0
    const val GLASS_TINT = 0.45
}

/** The strip's state from [Scrawl]. */
sealed class StripMode {
    enum class Phase { Recording, Sending, Sent, Queued, Failed, Discarded }

    data class Recording(val startedAtMs: Long, val isDrawing: Boolean) : StripMode()
    data class Sending(val progress: Double?, val stalled: Boolean = false) : StripMode()
    data class Sent(val reply: IngestReply) : StripMode()
    data object Queued : StripMode()
    data class Failed(val failure: StripFailure) : StripMode()
    data object Discarded : StripMode()

    val phase: Phase
        get() = when (this) {
            is Recording -> Phase.Recording
            is Sending -> Phase.Sending
            is Sent -> Phase.Sent
            Queued -> Phase.Queued
            is Failed -> Phase.Failed
            Discarded -> Phase.Discarded
        }
}

/** A short message in a bubble: one line, or two with [detail]. */
data class StripNotice(val title: String, val detail: String? = null) {
    companion object {
        val linkExpired get() = StripFailure.LinkExpired.notice
    }
}

/** Why a recording didn't send; every failure keeps Share. */
enum class StripFailure {
    Unreachable, LinkExpired, TooLarge, Rejected, NotSaved;

    val notice: StripNotice
        get() = when (this) {
            Unreachable -> StripNotice("Mac didn't answer")
            LinkExpired -> StripNotice("Scrawl link expired", "Ask Kit for a new one")
            TooLarge -> StripNotice("Too large to send")
            Rejected -> StripNotice("Couldn't send", "The server rejected it")
            NotSaved -> StripNotice("Couldn't save to send")
        }

    companion object {
        fun of(failure: Outbox.Failure) = when (failure) {
            Outbox.Failure.Unauthorized -> LinkExpired
            Outbox.Failure.TooLarge -> TooLarge
            Outbox.Failure.Rejected -> Rejected
        }
    }
}

enum class StripSide { Leading, Trailing }

data class Rect(val x: Double, val y: Double, val width: Double, val height: Double) {
    val minX get() = x
    val minY get() = y
    val maxX get() = x + width
    val maxY get() = y + height
    val midX get() = x + width / 2
    val midY get() = y + height / 2
    val isEmpty get() = width <= 0 || height <= 0
    fun inset(d: Double) = Rect(x + d, y + d, width - 2 * d, height - 2 * d)
    fun contains(px: Double, py: Double) = px >= minX && px <= maxX && py >= minY && py <= maxY
    fun union(o: Rect): Rect {
        val nx = min(minX, o.minX)
        val ny = min(minY, o.minY)
        return Rect(nx, ny, max(maxX, o.maxX) - nx, max(maxY, o.maxY) - ny)
    }

    companion object {
        val ZERO = Rect(0.0, 0.0, 0.0, 0.0)
    }
}

/**
 * The strip's springs, in the overlay's coordinates (points). A frame callback steps them only
 * while something is moving, and [geometry] is what the strip view draws. A hand-run spring set,
 * because the motion needs per-edge springs, staggered per-row springs, velocity kicks (shake,
 * throw), and stretch that follows a drag's speed.
 */
class LiquidStrip {
    data class Row(val opacity: Double = 0.0, val offset: Double = 0.0, val scale: Double = 1.0)

    data class Geometry(
        val alpha: Double = 0.0,
        val body: Rect = Rect.ZERO,
        val column: Vec = Vec(0.0, 0.0),
        val rows: List<Row> = List(5) { Row() },
        val controlsLive: Boolean = false,
        val icon: Vec = Vec(0.0, 0.0),
        val iconScale: Double = 1.0,
        val iconOpacity: Double = 0.0,
        val bubble: Rect? = null,
        val bubbleCenter: Vec = Vec(0.0, 0.0),
        val bubbleGap: Double = 0.0,
        val bubbleOpacity: Double = 0.0,
        val bubbleInteractive: Boolean = false,
        /** +1 on the right edge, −1 on the left. */
        val direction: Double = 1.0,
    )

    sealed class Bubble {
        data object Elapsed : Bubble()
        data class Sent(val reply: IngestReply) : Bubble()
        data object Queued : Bubble()
        data class Failed(val failure: StripFailure) : Bubble()
        data object Discarded : Bubble()
        data class Sending(val stalled: Boolean) : Bubble()

        /** A message while recording (link expired, the length cap). */
        data class Notice(val notice: StripNotice) : Bubble()

        /** Only bubbles with a button (Share, Undo) take touches. */
        val isInteractive get() = this is Failed || this == Discarded

        /** Bubbles beside the dot rather than the status circle. */
        val isRecording get() = this == Elapsed || this is Notice
    }

    private enum class Key {
        Alpha, Slide, Scale, Top, Bottom, SquashX, SquashY, ThrowX, ThrowY, Shake,
        Gather, IconAlpha, IconScale, Row0, Row1, Row2, Row3, Row4,
        BubbleWidth, BubbleHeight, BubbleGap, BubbleAlpha, PlaceX, PlaceY,
    }

    private class Value(var value: Double) {
        var velocity = 0.0
        var target = value
        var spring = StripMotion.change
        val isSettled get() = velocity == 0.0 && value == target

        fun step(dt: Double) {
            if (isSettled) return
            // Small substeps keep stiff springs stable on long frames.
            val steps = max(1, ceil(dt / 0.004).toInt())
            repeat(steps) {
                val (v, vel) = spring.step(value, velocity, target, dt / steps)
                value = v; velocity = vel
            }
            if (abs(velocity) < 0.01 && abs(value - target) < 0.001) {
                value = target; velocity = 0.0
            }
        }
    }

    private class Timer(val at: Double, val run: (LiquidStrip) -> Unit)
    private class Fade(val from: Double, val to: Double, val start: Double, val done: ((LiquidStrip) -> Unit)?)

    var geometry = Geometry()
        private set
    var statusIcon: StripMode.Phase? = null
        private set

    /** The bubble's text (kept while it's absorbed). */
    var bubble: Bubble? = null
        private set

    /** Whether the glass exists; false once an exit has faded it. */
    var isPresent = false
        private set
    var startedAtMs = 0L
        private set
    var isDrawing = false
        private set
    var sendProgress: Double? = null
        private set
    private var sendStalled = false

    var side = StripSide.Trailing
        set(value) {
            if (field != value) {
                field = value; wake()
            }
        }
    var reduceMotion = false

    /** Called whenever [geometry] or what's shown changes, and when the frame loop should run. */
    var onChange: () -> Unit = {}
    var requestFrames: () -> Unit = {}

    private val values = Key.entries.map { key ->
        when (key) {
            Key.Slide -> Value(16.0)
            Key.Scale, Key.SquashX, Key.SquashY, Key.ThrowX, Key.ThrowY, Key.IconScale -> Value(1.0)
            Key.Top -> Value(-21.0)
            Key.Bottom -> Value(21.0)
            Key.BubbleWidth, Key.BubbleHeight -> Value(StripMetrics.HIDDEN_BUBBLE)
            Key.BubbleGap -> Value(StripMetrics.HIDDEN_BUBBLE_GAP)
            else -> Value(0.0)
        }
    }

    private var phase: StripMode.Phase? = null
    private var clock = 0.0
    private val timers = mutableListOf<Timer>()
    private var fade: Fade? = null
    private var fadeLevel = 1.0
    private var instant = false
    private var exiting = false
    private var holding = false
    private var gatherY = 0.0
    private var bubbleActive = false
    private var notice: StripNotice? = null
    private var bubbleY = 0.0
    private var measured: Pair<Bubble, Pair<Double, Double>>? = null
    private var restPoint: Vec? = null
    private var held = false

    /** True while the frame loop has work: timers, a fade, or unsettled springs. */
    val isAnimating: Boolean get() = timers.isNotEmpty() || fade != null || values.any { !it.isSettled }

    // Inputs

    /** The strip's state from [Scrawl]. A new phase starts its transition. */
    fun apply(mode: StripMode?) {
        if (mode is StripMode.Recording) {
            startedAtMs = mode.startedAtMs
            isDrawing = mode.isDrawing
        }
        if (mode is StripMode.Sending) {
            sendProgress = mode.progress
            if (sendStalled != mode.stalled) {
                sendStalled = mode.stalled
                // Already saying "Sending…": the text changes in place.
                if (phase == StripMode.Phase.Sending && bubbleActive && bubble is Bubble.Sending) {
                    val change: (LiquidStrip) -> Unit = { it.showBubble(Bubble.Sending(it.sendStalled)) }
                    if (reduceMotion) applyInstant(change) else change(this)
                    wake()
                }
            }
        }
        onChange()
        val previous = phase
        val next = mode?.phase
        if (next == previous) return
        phase = next
        if (next != StripMode.Phase.Recording) {
            holding = false; held = false
        }
        val rest = restPoint
        if (next == StripMode.Phase.Recording && previous == null && rest != null) {
            set(Key.PlaceX, rest.x); set(Key.PlaceY, rest.y)
        }
        val transition: (LiquidStrip) -> Unit = { strip ->
            strip.timers.clear()
            strip.exiting = false
            when (next) {
                StripMode.Phase.Recording -> {
                    strip.toStrip(previous)
                    if (strip.notice != null) strip.after(StripMotion.BUBBLE_DELAY_AFTER_POUR) { it.showRecordingBubble() }
                }
                null -> strip.toGone(previous)
                else -> strip.toStatus(next, mode, previous)
            }
        }
        if (reduceMotion) reducedSwap(transition) else transition(this)
        wake()
    }

    /** The dot is held still (elapsed-time bubble) or not. */
    fun setHolding(isHolding: Boolean) {
        if (phase != StripMode.Phase.Recording || isHolding == holding) return
        holding = isHolding
        val change: (LiquidStrip) -> Unit = { strip ->
            if (isHolding) strip.showBubble(Bubble.Elapsed) else if (strip.bubbleActive) strip.showRecordingBubble()
        }
        if (reduceMotion) applyInstant(change) else change(this)
        wake()
    }

    /** A message beside the dot while recording; holding the dot shows the time over it. */
    fun setNotice(notice: StripNotice?) {
        if (notice == this.notice) return
        this.notice = notice
        if (phase != StripMode.Phase.Recording || holding) return
        val change: (LiquidStrip) -> Unit = { it.showRecordingBubble() }
        if (reduceMotion) applyInstant(change) else change(this)
        wake()
    }

    private fun showRecordingBubble() {
        if (phase != StripMode.Phase.Recording || holding) return
        val notice = notice
        if (notice != null) showBubble(Bubble.Notice(notice)) else if (bubbleActive) hideBubble()
    }

    /** The bubble's text measured at its natural size (points). */
    fun measuredBubble(width: Double, height: Double, bubble: Bubble) {
        measured = bubble to (width to height)
        if (!bubbleActive || this.bubble != bubble) return
        to(Key.BubbleWidth, width, StripMotion.bubbleWidth)
        to(Key.BubbleHeight, height, StripMotion.bubbleHeight)
        wake()
    }

    /** Where the strip rests: its edge and height in the current safe area. */
    fun rest(point: Vec, animated: Boolean) {
        val first = restPoint == null
        restPoint = point
        if (held) return
        if (animated && !first && !reduceMotion) {
            to(Key.PlaceX, point.x, StripMotion.change)
            to(Key.PlaceY, point.y, StripMotion.change)
        } else {
            set(Key.PlaceX, point.x); set(Key.PlaceY, point.y)
        }
        wake()
    }

    val place: Vec get() = Vec(this[Key.PlaceX], this[Key.PlaceY])

    /** Follows a drag exactly, keeping its velocity for the stretch. */
    fun drag(point: Vec, velocity: Vec) {
        held = true
        set(Key.PlaceX, point.x, velocity.x)
        set(Key.PlaceY, point.y, velocity.y)
        wake()
    }

    /** Lets go with the throw's velocity; call [rest] with where it lands. */
    fun release(velocity: Vec) {
        held = false
        values[Key.PlaceX.ordinal].velocity = velocity.x
        values[Key.PlaceY.ordinal].velocity = velocity.y
        wake()
    }

    // Transitions

    private fun toStrip(previous: StripMode.Phase?) {
        val fresh = previous == null || this[Key.Alpha] < 0.3
        hideBubble(now = fresh)
        if (fresh) {
            set(Key.Slide, 18.0)
            // The first frame already contains the droplet.
            set(Key.Alpha, 0.15)
            set(Key.Scale, 0.9)
            set(Key.Top, -21.0)
            set(Key.Bottom, 21.0)
            set(Key.SquashX, 1.2)
            set(Key.SquashY, 0.85)
            set(Key.Gather, 1.0)
            gatherY = 0.0
            for (key in rowKeys) set(key, 0.0)
            set(Key.IconAlpha, 0.0)
            statusIcon = null
            present(true)
        } else {
            gatherY = (this[Key.Top] + this[Key.Bottom]) / 2
            set(Key.Gather, 1.0)
            to(Key.IconAlpha, 0.0, StripMotion.quick)
        }
        to(Key.Alpha, 1.0, StripMotion.quick)
        to(Key.Slide, 0.0, StripMotion.change)
        to(Key.Scale, 1.0, StripMotion.change)
        to(Key.SquashX, 1.0, StripMotion.unsquash)
        to(Key.SquashY, 1.0, StripMotion.unsquash)
        to(Key.Top, -StripMetrics.EXPANDED_HEIGHT / 2, StripMotion.change)
        to(Key.Bottom, StripMetrics.EXPANDED_HEIGHT / 2, StripMotion.stretchLag)
        to(Key.Gather, 0.0, StripMotion.change)
        // Middle row first, then outward.
        val ring = listOf(2, 1, 0, 1, 2)
        for ((index, key) in rowKeys.withIndex()) {
            after(StripMotion.FAN_DELAY + ring[index] * StripMotion.STAGGER) { it.to(key, 1.0, StripMotion.rowIn) }
        }
    }

    private fun toStatus(status: StripMode.Phase, mode: StripMode?, previous: StripMode.Phase?) {
        val fromStrip = previous == StripMode.Phase.Recording
        val target = when {
            fromStrip -> StripMetrics.rowCenters[if (status == StripMode.Phase.Discarded) StripMetrics.DISCARD_INDEX else StripMetrics.SEND_INDEX]
            this[Key.Alpha] > 0.3 -> (values[Key.Top.ordinal].target + values[Key.Bottom.ordinal].target) / 2
            else -> StripMetrics.rowCenters[StripMetrics.SEND_INDEX]
        }
        if (bubbleActive) hideBubble()
        if (previous == null) {
            for (key in rowKeys) set(key, 0.0)
            present(true)
        }
        to(Key.Alpha, 1.0, StripMotion.quick)
        to(Key.Slide, 0.0, StripMotion.change)
        to(Key.Scale, 1.0, StripMotion.change)
        val radius = StripMetrics.WIDTH / 2
        to(Key.Top, target - radius, StripMotion.change)
        to(Key.Bottom, target + radius, StripMotion.pourLag)
        if (fromStrip) {
            gatherY = target
            to(Key.Gather, 1.0, StripMotion.rowGather)
            for (key in rowKeys) to(key, 0.0, StripMotion.rowOut)
        }
        val gulp = !fromStrip && previous != null
        val swap: (LiquidStrip) -> Unit = { strip ->
            strip.statusIcon = status
            strip.set(Key.IconAlpha, 0.0)
            strip.set(Key.IconScale, 0.5)
            strip.to(Key.IconAlpha, 1.0, StripMotion.iconIn)
            strip.to(Key.IconScale, 1.0, StripMotion.iconPop)
            if (gulp) strip.values[Key.Scale.ordinal].velocity += StripMotion.GULP_VELOCITY
        }
        if (fromStrip) after(StripMotion.ICON_SWAP_DELAY, swap) else swap(this)
        val bubble: Bubble? = when (mode) {
            is StripMode.Sent -> Bubble.Sent(mode.reply)
            StripMode.Queued -> Bubble.Queued
            is StripMode.Failed -> Bubble.Failed(mode.failure)
            StripMode.Discarded -> Bubble.Discarded
            else -> null
        }
        if (bubble != null) {
            after(if (fromStrip) StripMotion.BUBBLE_DELAY_AFTER_POUR else StripMotion.BUBBLE_DELAY) { it.showBubble(bubble) }
        } else if (status == StripMode.Phase.Sending) {
            after(StripMotion.SENDING_BUBBLE_DELAY) { it.showBubble(Bubble.Sending(it.sendStalled)) }
        }
        if (status == StripMode.Phase.Failed) {
            after(StripMotion.SHAKE_DELAY) { strip ->
                strip.values[Key.Shake.ordinal].velocity = -strip.direction * StripMotion.SHAKE_VELOCITY
            }
        }
    }

    private fun toGone(previous: StripMode.Phase?) {
        if (bubbleActive) hideBubble()
        val wait = if (previous == StripMode.Phase.Recording) 0.0 else StripMotion.EXIT_DELAY
        if (previous == StripMode.Phase.Discarded) {
            after(wait) { strip ->
                strip.exiting = true
                strip.to(Key.Scale, 0.35, StripMotion.exit)
                strip.to(Key.Alpha, 0.0, StripMotion.exit)
                strip.to(Key.IconAlpha, 0.0, StripMotion.rowOut)
            }
        } else {
            after(wait) { strip ->
                strip.exiting = true
                strip.to(Key.Slide, 90.0, StripMotion.exit)
                strip.to(Key.SquashX, 1.2, StripMotion.exit)
                strip.to(Key.SquashY, 0.86, StripMotion.exit)
                strip.to(Key.Alpha, 0.0, StripMotion.exitFade)
                strip.to(Key.IconAlpha, 0.0, StripMotion.exit)
            }
        }
    }

    private fun showBubble(kind: Bubble) {
        bubbleActive = true
        bubble = kind
        bubbleY = if (kind.isRecording) {
            StripMetrics.rowCenters[StripMetrics.DOT_INDEX]
        } else {
            (values[Key.Top.ordinal].target + values[Key.Bottom.ordinal].target) / 2
        }
        to(Key.BubbleGap, StripMetrics.BUBBLE_GAP, StripMotion.bubbleBud)
        val measured = measured
        if (measured != null && measured.first == kind) {
            to(Key.BubbleWidth, measured.second.first, StripMotion.bubbleWidth)
            to(Key.BubbleHeight, measured.second.second, StripMotion.bubbleHeight)
        }
        to(Key.BubbleAlpha, 1.0, StripMotion.bubbleTextIn)
        onChange()
    }

    private fun hideBubble(now: Boolean = false) {
        bubbleActive = false
        to(Key.BubbleAlpha, 0.0, StripMotion.bubbleTextOut)
        to(Key.BubbleGap, StripMetrics.HIDDEN_BUBBLE_GAP, StripMotion.bubbleAbsorb)
        to(Key.BubbleWidth, StripMetrics.HIDDEN_BUBBLE, StripMotion.bubbleAbsorb)
        to(Key.BubbleHeight, StripMetrics.HIDDEN_BUBBLE, StripMotion.bubbleAbsorb)
        if (now) {
            set(Key.BubbleAlpha, 0.0)
            set(Key.BubbleGap, StripMetrics.HIDDEN_BUBBLE_GAP)
            set(Key.BubbleWidth, StripMetrics.HIDDEN_BUBBLE)
            set(Key.BubbleHeight, StripMetrics.HIDDEN_BUBBLE)
        }
    }

    // Reduce Motion

    private fun reducedSwap(change: (LiquidStrip) -> Unit) {
        if (fade != null) {
            applyInstant(change); return
        }
        fade = Fade(1.0, 0.0, clock) { strip ->
            strip.applyInstant(change)
            strip.fade = Fade(0.0, 1.0, strip.clock, null)
        }
    }

    private fun applyInstant(change: (LiquidStrip) -> Unit) {
        instant = true
        try {
            change(this)
            while (timers.isNotEmpty()) {
                val pending = timers.sortedBy { it.at }
                timers.clear()
                for (timer in pending) timer.run(this)
            }
        } finally {
            instant = false
        }
    }

    // Springs and the frame loop

    private operator fun get(key: Key): Double = values[key.ordinal].value
    private val direction: Double get() = if (side == StripSide.Trailing) 1.0 else -1.0

    private fun to(key: Key, target: Double, spring: Spring) {
        val v = values[key.ordinal]
        v.target = target
        v.spring = spring
        if (instant) {
            v.value = target; v.velocity = 0.0
        }
    }

    private fun set(key: Key, value: Double, velocity: Double = 0.0) {
        val v = values[key.ordinal]
        v.value = value; v.target = value; v.velocity = velocity
    }

    private fun after(delay: Double, run: (LiquidStrip) -> Unit) {
        timers += Timer(clock + delay, run)
    }

    private fun present(present: Boolean) {
        if (isPresent != present) {
            isPresent = present; onChange()
        }
    }

    private fun wake() {
        advance(0.0)
        requestFrames()
    }

    /** Advances [dt] seconds (clamped like a display link); returns whether more frames are needed. */
    fun tick(dt: Double): Boolean {
        advance(dt.coerceIn(0.0, 1.0 / 20))
        return isAnimating
    }

    private fun advance(dt: Double) {
        clock += dt
        val now = clock
        val due = timers.filter { it.at <= now }.sortedBy { it.at }
        if (due.isNotEmpty()) {
            timers.removeAll { it.at <= now }
            for (timer in due) timer.run(this)
        }
        fade?.let { f ->
            val progress = ((clock - f.start) / StripMotion.REDUCED_FADE).coerceIn(0.0, 1.0)
            val eased = if (progress < 0.5) 2 * progress * progress else 1 - (-2 * progress + 2).pow(2) / 2
            fadeLevel = f.from + (f.to - f.from) * eased
            if (progress >= 1) {
                fade = null
                f.done?.invoke(this)
            }
        }
        // Stretch along the direction of a throw or drag.
        var sx = 1.0
        var sy = 1.0
        if (!reduceMotion && !exiting && phase == StripMode.Phase.Recording) {
            val speedX = min(abs(values[Key.PlaceX.ordinal].velocity) / StripMotion.STRETCH_SPEED, StripMotion.MAX_STRETCH)
            val speedY = min(abs(values[Key.PlaceY.ordinal].velocity) / StripMotion.STRETCH_SPEED, StripMotion.MAX_STRETCH)
            sx = 1 + speedX - speedY * 0.45
            sy = 1 + speedY - speedX * 0.45
        }
        if (values[Key.ThrowX.ordinal].target != sx) to(Key.ThrowX, sx, StripMotion.throwStretch)
        if (values[Key.ThrowY.ordinal].target != sy) to(Key.ThrowY, sy, StripMotion.throwStretch)
        for (v in values) v.step(dt)
        publish()
        if (exiting && isPresent && values[Key.Alpha.ordinal].target == 0.0 && this[Key.Alpha] < 0.3) present(false)
    }

    private fun publish() {
        val dir = direction
        val alpha = this[Key.Alpha].coerceIn(0.0, 1.0) * fadeLevel
        val ax = this[Key.PlaceX] + dir * (this[Key.Slide] + this[Key.Shake])
        val ay = this[Key.PlaceY]
        val scale = this[Key.Scale]
        val width = StripMetrics.WIDTH * this[Key.SquashX] * this[Key.ThrowX] * scale
        val height = max(0.0, (this[Key.Bottom] - this[Key.Top]) * this[Key.SquashY] * this[Key.ThrowY] * scale)
        val cy = ay + (this[Key.Top] + this[Key.Bottom]) / 2
        val gather = this[Key.Gather]
        val rows = rowKeys.mapIndexed { index, key ->
            Row(this[key] * alpha, gather * (gatherY - StripMetrics.rowCenters[index]) * 0.7, 1 - 0.35 * gather)
        }
        val gap = this[Key.BubbleGap]
        val bubbleWidth = max(0.0, this[Key.BubbleWidth])
        val bubbleHeight = max(0.0, this[Key.BubbleHeight])
        val inner = ax - dir * width / 2
        val near = inner - dir * gap
        val left = if (dir > 0) near - bubbleWidth else near
        val centerY = ay + bubbleY
        val bubbleRect = if (bubbleWidth > 1 && (this[Key.BubbleAlpha] > 0.01 || gap > StripMetrics.HIDDEN_BUBBLE_GAP + 1)) {
            Rect(left, centerY - bubbleHeight / 2, bubbleWidth, bubbleHeight)
        } else {
            null
        }
        val next = Geometry(
            alpha = alpha,
            body = Rect(ax - width / 2, cy - height / 2, width, height),
            column = Vec(ax, ay),
            rows = rows,
            controlsLive = phase == StripMode.Phase.Recording,
            icon = Vec(ax, cy),
            iconScale = max(0.0, this[Key.IconScale] * scale),
            iconOpacity = this[Key.IconAlpha].coerceIn(0.0, 1.0) * alpha,
            bubble = bubbleRect,
            bubbleCenter = Vec(left + bubbleWidth / 2, centerY),
            bubbleGap = gap,
            bubbleOpacity = this[Key.BubbleAlpha].coerceIn(0.0, 1.0) * alpha,
            bubbleInteractive = bubbleActive && this[Key.BubbleAlpha] > 0.5 && (bubble?.isInteractive ?: false),
            direction = dir,
        )
        if (next != geometry) {
            geometry = next
            onChange()
        }
    }

    companion object {
        private val rowKeys = listOf(Key.Row0, Key.Row1, Key.Row2, Key.Row3, Key.Row4)
    }
}
