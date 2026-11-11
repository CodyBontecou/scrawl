package dev.kitlangton.scrawl

import kotlin.math.max
import kotlin.math.min

/**
 * Decides which vsync ticks [AppFrameRecorder] captures and which it skips so recording yields to
 * 120 Hz scrolling and animations on the main thread.
 *
 * Time is passed in (seconds on the `Choreographer` / `System.nanoTime` clock), making the pacer a
 * deterministic value that can be unit-tested without a display.
 */
class FramePacer(
    /** Target interval when the UI is not actively scrolling or being dragged (30 fps). */
    var idleInterval: Double = DEFAULT_IDLE_INTERVAL,
    /** Target interval while scrolling, dragging, or drawing (12 fps). */
    var interactionInterval: Double = DEFAULT_INTERACTION_INTERVAL,
    var maxAdaptiveInterval: Double = DEFAULT_MAX_ADAPTIVE_INTERVAL,
    var maxStarvationInterval: Double = DEFAULT_MAX_STARVATION_INTERVAL,
    var minSettledInterval: Double = DEFAULT_MIN_SETTLED_INTERVAL,
    var settleDelay: Double = DEFAULT_SETTLE_DELAY,
) {
    enum class CaptureReason { First, Cadence, Settled, StarvationCap }
    enum class SkipReason { Cadence, BudgetTight, WriterBusy }

    sealed class Decision {
        data class Capture(val reason: CaptureReason) : Decision()
        data class Skip(val reason: SkipReason) : Decision()
    }

    data class Metrics(
        var framesCaptured: Int = 0,
        var settledCatchUps: Int = 0,
        var ticksSkippedCadence: Int = 0,
        var ticksSkippedBudget: Int = 0,
        var ticksSkippedWriterBusy: Int = 0,
        var totalDrawMs: Double = 0.0,
        var maxDrawMs: Double = 0.0,
        var estimatedHitches120Hz: Int = 0,
        var estimatedHitches60Hz: Int = 0,
    ) {
        val totalTicks: Int get() = framesCaptured + ticksSkippedCadence + ticksSkippedBudget + ticksSkippedWriterBusy
        val meanDrawMs: Double get() = if (framesCaptured > 0) totalDrawMs / framesCaptured else 0.0
    }

    var lastCaptureTime: Double? = null
        private set
    var lastInteractionTime: Double? = null
        private set
    var smoothedDrawDuration: Double? = null
        private set
    var lastCaptureWasInteracting = false
        private set
    var needsSettledFrame = false
        private set
    val metrics = Metrics()

    /** Records raw touch-drag or ink movement at [now], holding interaction pacing for [settleDelay]. */
    fun noteInteractionMovement(now: Double) {
        lastInteractionTime = now
    }

    /** Whether interaction pacing is in effect at [now]. */
    fun isInteractionActive(explicit: Boolean, now: Double): Boolean {
        if (explicit) return true
        val last = lastInteractionTime ?: return false
        return now >= last && (now - last) < settleDelay
    }

    /** Current target interval between captures, including adaptive draw-cost backoff. */
    fun targetInterval(interacting: Boolean): Double {
        val base = if (interacting) interactionInterval else idleInterval
        val draw = smoothedDrawDuration
        if (draw == null || draw <= 0) return base
        // Keep main-thread capture duty cycle under ~12.5% while scrolling and ~25% when idle.
        val multiplier = if (interacting) 8.0 else 4.0
        return min(max(base, draw * multiplier), maxAdaptiveInterval)
    }

    /** Evaluates a vsync tick and decides whether to capture or skip. */
    fun decide(
        linkTimestamp: Double,
        targetTimestamp: Double,
        now: Double,
        isInteracting: Boolean,
        writerReady: Boolean = true,
    ): Decision {
        if (!writerReady) {
            metrics.ticksSkippedWriterBusy += 1
            return Decision.Skip(SkipReason.WriterBusy)
        }
        val active = isInteractionActive(isInteracting, now)
        val last = lastCaptureTime ?: return Decision.Capture(CaptureReason.First)
        if (!active && lastCaptureWasInteracting) needsSettledFrame = true
        val elapsed = max(0.0, now - last)

        if (!active && needsSettledFrame && elapsed >= minSettledInterval - PHASE_TOLERANCE) {
            if (elapsed >= maxStarvationInterval || !isBudgetTight(linkTimestamp, targetTimestamp, now)) {
                return Decision.Capture(CaptureReason.Settled)
            }
            metrics.ticksSkippedBudget += 1
            return Decision.Skip(SkipReason.BudgetTight)
        }

        val required = targetInterval(active) - PHASE_TOLERANCE
        if (elapsed < required) {
            metrics.ticksSkippedCadence += 1
            return Decision.Skip(SkipReason.Cadence)
        }
        if (elapsed >= maxStarvationInterval) return Decision.Capture(CaptureReason.StarvationCap)
        if (isBudgetTight(linkTimestamp, targetTimestamp, now)) {
            metrics.ticksSkippedBudget += 1
            return Decision.Skip(SkipReason.BudgetTight)
        }
        return Decision.Capture(CaptureReason.Cadence)
    }

    /** Records a completed frame draw and updates adaptive cost estimates and hitch counters. */
    fun recordCapture(now: Double, drawDuration: Double, turnElapsed: Double = 0.0, isInteracting: Boolean) {
        val active = isInteractionActive(isInteracting, now)
        lastCaptureTime = now
        if (active) {
            lastCaptureWasInteracting = true
        } else {
            if (needsSettledFrame) metrics.settledCatchUps += 1
            lastCaptureWasInteracting = false
            needsSettledFrame = false
        }
        val clamped = max(0.0, drawDuration)
        smoothedDrawDuration = smoothedDrawDuration?.let { it * 0.65 + clamped * 0.35 } ?: clamped
        metrics.framesCaptured += 1
        val drawMs = clamped * 1000
        metrics.totalDrawMs += drawMs
        metrics.maxDrawMs = max(metrics.maxDrawMs, drawMs)
        val frameWork = max(0.0, turnElapsed) + clamped
        if (frameWork > PRO_MOTION_BUDGET) metrics.estimatedHitches120Hz += 1
        if (frameWork > SIXTY_HZ_BUDGET) metrics.estimatedHitches60Hz += 1
    }

    /**
     * True when the main thread has already spent significant time in this vsync turn (`> 1.8 ms`)
     * and adding an estimated draw would exceed the 120 Hz frame budget or [targetTimestamp].
     */
    fun isBudgetTight(linkTimestamp: Double, targetTimestamp: Double, now: Double): Boolean {
        val turnElapsed = max(0.0, now - linkTimestamp)
        if (turnElapsed <= 0.0018) return false
        val estimatedDraw = smoothedDrawDuration ?: 0.004
        if (turnElapsed + estimatedDraw > PRO_MOTION_BUDGET) return true
        if (targetTimestamp > linkTimestamp && (targetTimestamp - now) < estimatedDraw) return true
        return false
    }

    companion object {
        const val DEFAULT_IDLE_INTERVAL = 1.0 / 30.0
        const val DEFAULT_INTERACTION_INTERVAL = 1.0 / 12.0

        /** Adaptive backoff never drops the target cadence below 10 fps (100 ms). */
        const val DEFAULT_MAX_ADAPTIVE_INTERVAL = 1.0 / 10.0

        /** Maximum gap allowed when skipping tight-budget ticks before forcing a capture anyway. */
        const val DEFAULT_MAX_STARVATION_INTERVAL = 0.14

        /** Minimum spacing before a settled catch-up frame after motion stops. */
        const val DEFAULT_MIN_SETTLED_INTERVAL = 1.0 / 60.0

        /** Hold window after touch-drag movement ends so momentum/spring settle isn't hitched. */
        const val DEFAULT_SETTLE_DELAY = 0.22

        /** Vsync jitter allowance so a 30 Hz tick at ~29.5–33.5 ms never skips every other tick. */
        const val PHASE_TOLERANCE = 0.004
        const val PRO_MOTION_BUDGET = 1.0 / 120.0
        const val SIXTY_HZ_BUDGET = 1.0 / 60.0
    }
}
