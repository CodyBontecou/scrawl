package dev.kitlangton.scrawl

import dev.kitlangton.scrawl.FramePacer.CaptureReason
import dev.kitlangton.scrawl.FramePacer.Decision
import dev.kitlangton.scrawl.FramePacer.SkipReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FramePacerTest {
    @Test
    fun capturesFirstFrameThenMaintains30FpsWhenIdle() {
        val pacer = FramePacer()
        val dt = 1.0 / 30.0
        for (step in 0 until 10) {
            val t = step * dt
            val decision = pacer.decide(t, t + dt, t + 0.0004, false)
            assertEquals(if (step == 0) Decision.Capture(CaptureReason.First) else Decision.Capture(CaptureReason.Cadence), decision)
            pacer.recordCapture(t + 0.0004, 0.0035, 0.0004, false)
        }
        assertEquals(10, pacer.metrics.framesCaptured)
        assertEquals(0, pacer.metrics.ticksSkippedCadence)
        assertEquals(0, pacer.metrics.ticksSkippedBudget)
        assertEquals(0, pacer.metrics.estimatedHitches120Hz)
    }

    @Test
    fun dropsToInteractionCadenceDuringScrollAndCapturesSettledFrameOnStop() {
        val pacer = FramePacer()
        val dt = 1.0 / 30.0
        assertEquals(Decision.Capture(CaptureReason.First), pacer.decide(0.0, dt, 0.0003, false))
        pacer.recordCapture(0.0003, 0.004, 0.0003, false)
        var captures = 0
        for (step in 1..12) {
            val t = step * dt
            val decision = pacer.decide(t, t + dt, t + 0.0005, true)
            if (decision is Decision.Capture) {
                captures++
                pacer.recordCapture(t + 0.0005, 0.004, 0.0005, true)
            }
        }
        // 12 ticks at 30 Hz = 400 ms -> 4 captures during scroll, 8 skipped for cadence.
        assertEquals(4, captures)
        assertEquals(8, pacer.metrics.ticksSkippedCadence)
        val stop = 13.0 * dt
        assertEquals(Decision.Capture(CaptureReason.Settled), pacer.decide(stop, stop + dt, stop + 0.0004, false))
        pacer.recordCapture(stop + 0.0004, 0.004, 0.0004, false)
        assertEquals(1, pacer.metrics.settledCatchUps)
    }

    @Test
    fun skipsTightBudgetTicksUntilStarvationCap() {
        val pacer = FramePacer()
        val dt = 1.0 / 30.0
        assertEquals(Decision.Capture(CaptureReason.First), pacer.decide(0.0, dt, 0.0003, false))
        pacer.recordCapture(0.0003, 0.005, 0.0003, false)
        for (step in 1..4) {
            val t = step * dt
            assertEquals(Decision.Skip(SkipReason.BudgetTight), pacer.decide(t, t + dt, t + 0.0045, false))
        }
        assertEquals(4, pacer.metrics.ticksSkippedBudget)
        val t5 = 5.0 * dt
        assertEquals(Decision.Capture(CaptureReason.StarvationCap), pacer.decide(t5, t5 + dt, t5 + 0.0045, false))
    }

    @Test
    fun adaptiveBackoffRespectsTenFpsFloorForKeyframeTolerance() {
        val pacer = FramePacer()
        pacer.decide(0.0, 1.0 / 30.0, 0.0, true)
        pacer.recordCapture(0.0, 0.018, 0.001, true)
        assertEquals(0.1, pacer.targetInterval(true), 1e-12)
        assertEquals(1, pacer.metrics.estimatedHitches120Hz)
        assertEquals(1, pacer.metrics.estimatedHitches60Hz)
    }

    @Test
    fun touchMovementHoldWindowCoversMomentumSettle() {
        val pacer = FramePacer()
        pacer.noteInteractionMovement(1.0)
        assertTrue(pacer.isInteractionActive(false, 1.10))
        assertTrue(pacer.isInteractionActive(false, 1.21))
        assertFalse(pacer.isInteractionActive(false, 1.25))
    }

    @Test
    fun skipsWhenWriterQueueHasFrameInFlight() {
        val pacer = FramePacer()
        assertEquals(Decision.Skip(SkipReason.WriterBusy), pacer.decide(0.0, 1.0 / 30.0, 0.0002, false, writerReady = false))
        assertEquals(1, pacer.metrics.ticksSkippedWriterBusy)
    }
}

class CanvasSizeTest {
    @Test
    fun canvasSizeUses1_5xAndCapsAt1600LongEdge() {
        // A 440×956 dp phone -> 660×1434 px at 1.5×.
        assertEquals(660 to 1434, AppFrameRecorder.canvasSize(440.0, 956.0))
        // A 1032×1376 dp tablet -> capped at 1600 px long edge with even width.
        assertEquals(1200 to 1600, AppFrameRecorder.canvasSize(1032.0, 1376.0))
    }
}
