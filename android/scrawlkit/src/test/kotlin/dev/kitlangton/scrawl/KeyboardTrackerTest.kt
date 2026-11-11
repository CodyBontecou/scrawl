package dev.kitlangton.scrawl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardTrackerTest {
    private var recording = false
    private var seconds = 0.0
    private val context = ScrawlAppContext { if (recording) seconds else null }
    private val tracker = KeyboardTracker(context) { recording }
    private val ms = 1_000_000L

    @Test
    fun theKeyboardRisesWithAnEaseAndSaysVisible() {
        tracker.setTracking(true)
        tracker.update(0, 0f, 1080f, 2400f, 0)
        assertNull(tracker.top(0))
        assertNull(context.currentValues["keyboard"])

        tracker.update(800, 0f, 1080f, 2400f, 1_000 * ms)
        assertTrue(tracker.isVisible)
        assertEquals("visible", context.currentValues["keyboard"])
        // It slides up from the bottom: part way after half the animation, ease-out ahead of linear.
        val mid = tracker.top(1_000 * ms + 142 * ms)!!
        assertTrue(mid < 2400f && mid > 1600f)
        assertTrue("ease-out is past halfway at half time", 2400f - mid > 400f)
        assertEquals(1600f, tracker.top(1_000 * ms + 400 * ms)!!, 0.5f)
        assertEquals(0f, tracker.left, 0f)
        assertEquals(1080f, tracker.right, 0f)
        assertEquals(2400f, tracker.bottom, 0f)
    }

    @Test
    fun goingDownMidRecordingSaysHiddenAndNothingIsDrawnOnceItsGone() {
        tracker.setTracking(true)
        tracker.update(800, 0f, 1080f, 2400f, 0)
        context.begin()
        recording = true
        seconds = 2.5
        tracker.update(0, 0f, 1080f, 2400f, 1_000 * ms)
        assertFalse(tracker.isVisible)
        assertEquals("hidden", context.currentValues["keyboard"])
        // Still sliding away, then gone.
        assertTrue(tracker.top(1_000 * ms + 100 * ms) != null)
        assertNull(tracker.top(1_000 * ms + 500 * ms))
        val info = context.end()
        val changes = info["contextChanges"].toString()
        assertTrue(changes.contains("\"hidden\"") && changes.contains("2.5"))
    }

    @Test
    fun aKeyboardThatIsDownWhenNotRecordingLeavesNoContext() {
        tracker.setTracking(true)
        tracker.update(800, 0f, 1080f, 2400f, 0)
        tracker.update(0, 0f, 1080f, 2400f, 1_000 * ms)
        assertNull(context.currentValues["keyboard"])
        // Untracked (unpaired): forgets everything.
        tracker.update(800, 0f, 1080f, 2400f, 2_000 * ms)
        tracker.setTracking(false)
        assertNull(tracker.top(3_000 * ms))
        assertNull(context.currentValues["keyboard"])
    }
}
