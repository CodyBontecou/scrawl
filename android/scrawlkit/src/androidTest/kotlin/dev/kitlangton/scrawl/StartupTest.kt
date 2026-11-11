package dev.kitlangton.scrawl

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class TestHostActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(FrameLayout(this))
    }
}

/** Stands in for the hardware: start completes only when the test says so. */
class SuspendedRecorder : ScrawlRecorder {
    override var startupTrace: StartupTrace? = null
    override val method = ScrawlCaptureMethod.AppFrames
    override val isAvailable = true
    override val recordsAudio = false
    override var onInterruption: (() -> Unit)? = null
    var completion: ((Result<RecordingStart>) -> Unit)? = null
    var starts = 0
    var stops = 0
    var didStart: (() -> Unit)? = null

    override fun start(microphone: Boolean, completion: (Result<RecordingStart>) -> Unit) {
        starts += 1
        this.completion = completion
        didStart?.invoke()
    }

    fun complete(result: Result<RecordingStart>) {
        val c = completion
        completion = null
        c?.invoke(result)
    }

    override fun stop(movie: File, completion: (Throwable?) -> Unit) {
        stops += 1
        completion(RecorderError.notRecording)
    }

    override fun cancel() {
        completion = null
    }

    override fun appDidEnterBackground() = Unit
}

/** Port of ScrawlKit's StartupTests, on a real Activity and overlay window. */
@RunWith(AndroidJUnit4::class)
class StartupTest {
    private lateinit var scenario: ActivityScenario<TestHostActivity>
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as Application

    @Before
    fun setUp() {
        scenario = ActivityScenario.launch(TestHostActivity::class.java)
    }

    private val made = mutableListOf<Scrawl>()

    @After
    fun tearDown() {
        instrumentation.runOnMainSync { made.forEach { it.detachForTesting(app) } }
        scenario.close()
    }

    private fun makeScrawl(recorder: SuspendedRecorder): Scrawl {
        lateinit var scrawl: Scrawl
        instrumentation.runOnMainSync {
            scrawl = Scrawl(app, ScrawlConfig.of(listOf("http://127.0.0.1:1"), "fixture")) { _, _ -> recorder }
            scrawl.attachForTesting(app)
            made += scrawl
        }
        // The lifecycle callbacks see the already-resumed activity.
        scenario.onActivity { scrawl.activityResumedForTesting(it) }
        return scrawl
    }

    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)

    @Test
    fun preparedOverlayIsPresentedBeforeHardwareStarts() {
        val recorder = SuspendedRecorder()
        val scrawl = makeScrawl(recorder)
        val prepared = CountDownLatch(1)
        val drawn = CountDownLatch(1)
        val order = mutableListOf<String>()
        scrawl.startupObserver = { event ->
            if (event.stage == "prewarmLayout") prepared.countDown()
            if (event.stage == "firstDraw") synchronized(order) { order += "firstDraw" }.also { drawn.countDown() }
        }
        main { scrawl.prepareToStart() }
        assertTrue(prepared.await(3, TimeUnit.SECONDS))
        val hardware = CountDownLatch(1)
        recorder.didStart = { synchronized(order) { order += "hardware" }; hardware.countDown() }
        main {
            scrawl.start()
            assertEquals(0, recorder.starts)
        }
        assertTrue(drawn.await(3, TimeUnit.SECONDS))
        assertTrue(hardware.await(3, TimeUnit.SECONDS))
        assertEquals(listOf("firstDraw", "hardware"), synchronized(order) { order.toList() })
        main { recorder.complete(Result.failure(RecorderError.notRecording)) }
    }

    @Test
    fun overlayDrawsBeforeAudioStartCompletesAndEarlyDrawingIsAccepted() {
        val recorder = SuspendedRecorder()
        val scrawl = makeScrawl(recorder)
        val drawn = CountDownLatch(1)
        scrawl.startupObserver = { if (it.stage == "firstDraw") drawn.countDown() }
        main {
            scrawl.start()
            assertTrue(scrawl.isStarting)
            assertFalse(scrawl.isRecording)
            scrawl.toggleDrawing()
            assertTrue(scrawl.isDrawing)
        }
        assertTrue(drawn.await(3, TimeUnit.SECONDS))
        waitFor { recorder.completion != null }
        main {
            assertNotNull("audio completion remains withheld until after presentation", recorder.completion)
            recorder.complete(Result.failure(RecorderError.notRecording))
            assertFalse(scrawl.isStarting)
            assertNull(scrawl.startedAt)
        }
    }

    @Test
    fun earlySendIsBufferedAndDuplicateStartDoesNotReplaceRecorder() {
        val recorder = SuspendedRecorder()
        val scrawl = makeScrawl(recorder)
        main {
            scrawl.start()
            scrawl.start()
        }
        waitFor { recorder.starts > 0 }
        main {
            assertEquals(1, recorder.starts)
            scrawl.stop()
            assertEquals(0, recorder.stops)
            recorder.complete(Result.success(RecordingStart.now()))
            assertEquals(1, recorder.stops)
            assertFalse(scrawl.isStarting)
            assertFalse(scrawl.isRecording)
        }
    }

    @Test
    fun earlyDiscardIsBuffered() {
        val recorder = SuspendedRecorder()
        val scrawl = makeScrawl(recorder)
        main { scrawl.start() }
        waitFor { recorder.starts > 0 }
        main {
            scrawl.discard()
            assertFalse(scrawl.isDiscardPending)
            recorder.complete(Result.success(RecordingStart.now()))
            assertEquals(1, recorder.stops)
            assertTrue(scrawl.isDiscardPending)
        }
    }

    @Test
    fun threeFingersHeldHalfASecondTriggerOnceAndPrewarm() {
        val recorder = SuspendedRecorder()
        val scrawl = makeScrawl(recorder)
        var window: android.view.Window? = null
        scenario.onActivity { window = it.window }
        val trigger = ThreeFingerTrigger(scrawl, window!!)
        val down = SystemClock.uptimeMillis()
        fun event(action: Int, count: Int): MotionEvent {
            val props = Array(count) { MotionEvent.PointerProperties().apply { id = it; toolType = MotionEvent.TOOL_TYPE_FINGER } }
            val coords = Array(count) { MotionEvent.PointerCoords().apply { x = 100f + it * 120; y = 400f } }
            return MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, count, props, coords, 0, 0, 1f, 1f, 0, 0, 0, 0)
        }
        main {
            trigger.observe(event(MotionEvent.ACTION_DOWN, 1))
            trigger.observe(event(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2))
            trigger.observe(event(MotionEvent.ACTION_POINTER_DOWN or (2 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 3))
            // The third finger prewarms the overlay.
            assertNotNull(scrawl.overlay)
            assertEquals(0, recorder.starts)
        }
        // Held: after 0.5 s the recording starts.
        waitFor(2_000) { recorder.starts == 1 }
        Thread.sleep(700)
        main { assertEquals("fires once", 1, recorder.starts) }
        main { recorder.complete(Result.failure(RecorderError.notRecording)) }
    }

    @Test
    fun threeFingersThatMoveDoNotTrigger() {
        val recorder = SuspendedRecorder()
        val scrawl = makeScrawl(recorder)
        var window: android.view.Window? = null
        scenario.onActivity { window = it.window }
        val trigger = ThreeFingerTrigger(scrawl, window!!)
        val down = SystemClock.uptimeMillis()
        val density = app.resources.displayMetrics.density
        fun event(action: Int, count: Int, dy: Float = 0f): MotionEvent {
            val props = Array(count) { MotionEvent.PointerProperties().apply { id = it; toolType = MotionEvent.TOOL_TYPE_FINGER } }
            val coords = Array(count) { MotionEvent.PointerCoords().apply { x = 100f + it * 120; y = 400f + dy } }
            return MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, count, props, coords, 0, 0, 1f, 1f, 0, 0, 0, 0)
        }
        main {
            trigger.observe(event(MotionEvent.ACTION_DOWN, 1))
            trigger.observe(event(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2))
            trigger.observe(event(MotionEvent.ACTION_POINTER_DOWN or (2 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 3))
            trigger.observe(event(MotionEvent.ACTION_MOVE, 3, dy = 20 * density))
        }
        Thread.sleep(900)
        main { assertEquals(0, recorder.starts) }
    }

    /**
     * Three real pointers through the system's input manager and dispatcher, so the event goes
     * through the window, ScrawlKit's wrapped window callback, and the trigger, as a finger would.
     */
    @Test
    fun threeFingersThroughTheInputDispatcherStartARecording() {
        val recorder = SuspendedRecorder()
        val scrawl = makeScrawl(recorder)
        var center = intArrayOf(0, 0)
        scenario.onActivity {
            val loc = IntArray(2)
            it.window.decorView.getLocationOnScreen(loc)
            center = intArrayOf(loc[0] + it.window.decorView.width / 2, loc[1] + it.window.decorView.height / 2)
        }
        val down = SystemClock.uptimeMillis()
        fun inject(action: Int, count: Int) {
            val props = Array(count) { MotionEvent.PointerProperties().apply { id = it; toolType = MotionEvent.TOOL_TYPE_FINGER } }
            val coords = Array(count) { MotionEvent.PointerCoords().apply { x = center[0] - 200f + it * 200; y = center[1].toFloat(); pressure = 1f; size = 1f } }
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, count, props, coords, 0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
            // Through the system's input manager and dispatcher into the window.
            instrumentation.sendPointerSync(event)
            event.recycle()
        }
        inject(MotionEvent.ACTION_DOWN, 1)
        inject(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2)
        inject(MotionEvent.ACTION_POINTER_DOWN or (2 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 3)
        Thread.sleep(800)
        inject(MotionEvent.ACTION_POINTER_UP or (2 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 3)
        inject(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2)
        inject(MotionEvent.ACTION_UP, 1)
        waitFor(3_000) { recorder.starts == 1 }
        main {
            assertEquals("the held trigger starts once", 1, recorder.starts)
            recorder.complete(Result.failure(RecorderError.notRecording))
        }
    }

    /** TalkBack's view of the strip: its controls as nodes with iOS's labels, and ACTION_CLICK works. */
    @Test
    fun stripControlsAreAccessibilityNodesThatActivate() {
        val recorder = SuspendedRecorder()
        val scrawl = makeScrawl(recorder)
        main { scrawl.start() }
        waitFor { recorder.starts == 1 }
        main { recorder.complete(Result.success(RecordingStart.now())) }
        // The strip fans out its controls.
        waitFor { scrawl.overlay?.strip?.liquid?.geometry?.rows?.all { it.opacity > 0.9 } == true }
        main {
            val strip = scrawl.overlay!!.strip
            val provider = strip.accessibilityHelper.getAccessibilityNodeProvider(strip)!!
            fun node(id: Int) = provider.createAccessibilityNodeInfo(id)!!
            assertTrue(node(0).contentDescription.toString().startsWith("Recording, 0 minutes"))
            assertEquals("Hold to show the time. Drag to move the controls.", node(0).hintText?.toString())
            assertEquals("Draw", node(1).contentDescription)
            assertEquals("Off", node(1).stateDescription)
            assertEquals("Discard recording", node(2).contentDescription)
            assertEquals("Send recording", node(3).contentDescription)
            assertTrue(provider.performAction(1, android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK, null))
            assertTrue(scrawl.isDrawing)
            assertEquals("On", node(1).stateDescription)
            assertTrue(provider.performAction(3, android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK, null))
            assertEquals("Send stops the recorder", 1, recorder.stops)
            assertFalse(scrawl.isRecording)
        }
    }

    /** HTTPS endpoints: a notice 10 s before the length cap, then the recording sends itself. */
    @Test
    fun hostedRecordingsWarnBeforeTheLengthCapThenSend() {
        val recorder = SuspendedRecorder()
        lateinit var scrawl: Scrawl
        instrumentation.runOnMainSync {
            scrawl = Scrawl(app, ScrawlConfig.of(listOf("https://127.0.0.1:1"), "fixture")) { _, _ -> recorder }
            scrawl.attachForTesting(app)
            made += scrawl
            scrawl.limitOverrideMs = Scrawl.LIMIT_WARNING_MS + 300
        }
        scenario.onActivity { scrawl.activityResumedForTesting(it) }
        main { scrawl.start() }
        waitFor { recorder.starts == 1 }
        main { recorder.complete(Result.success(RecordingStart.now())) }
        waitFor(3_000) { scrawl.currentNotice != null }
        main {
            assertEquals(StripNotice("Length limit", "Sending in 10 s"), scrawl.currentNotice)
            assertTrue(scrawl.isRecording)
        }
        waitFor(12_000) { recorder.stops == 1 }
        main {
            assertEquals("sent at the cap", 1, recorder.stops)
            assertFalse(scrawl.isRecording)
        }
    }

    private fun waitFor(timeoutMs: Long = 3_000, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            var ok = false
            main { ok = condition() }
            if (ok) return
            Thread.sleep(20)
        }
    }
}
