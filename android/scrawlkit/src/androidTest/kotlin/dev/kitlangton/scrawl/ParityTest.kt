package dev.kitlangton.scrawl

import android.app.Application
import android.app.Instrumentation
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.Window
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
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
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Writes a small movie file instead of recording (the delivery tests don't need real video). */
class FileRecorder : ScrawlRecorder {
    override var startupTrace: StartupTrace? = null
    override val method = ScrawlCaptureMethod.AppFrames
    override var isAvailable = true
    override val recordsAudio = false
    override var onInterruption: (() -> Unit)? = null
    var cancels = 0

    override fun start(microphone: Boolean, completion: (Result<RecordingStart>) -> Unit) =
        completion(Result.success(RecordingStart.now()))

    override fun stop(movie: File, completion: (Throwable?) -> Unit) {
        movie.parentFile?.mkdirs()
        movie.writeBytes(ByteArray(4096) { it.toByte() })
        completion(null)
    }

    override fun cancel() {
        cancels += 1
    }

    override fun appDidEnterBackground() = Unit
}

/** A Mac's ingest server on the device: answers /health and /ingest, keeps what it was sent. */
class FakeMac(private val reply: String = """{"ok":true,"delivered":true,"state":"sent","sessionTitle":"Test Mac"}""") : AutoCloseable {
    data class Request(val line: String, val headers: Map<String, String>, val body: ByteArray)

    private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    val requests = CopyOnWriteArrayList<Request>()
    val url: String get() = "http://127.0.0.1:${socket.localPort}"

    init {
        thread(isDaemon = true) {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                thread(isDaemon = true) {
                    client.use { c ->
                        val input = c.getInputStream().buffered()
                        fun line(): String {
                            val b = StringBuilder()
                            while (true) {
                                val ch = input.read()
                                if (ch < 0 || ch == '\n'.code) break
                                if (ch != '\r'.code) b.append(ch.toChar())
                            }
                            return b.toString()
                        }
                        val request = line()
                        val headers = mutableMapOf<String, String>()
                        while (true) {
                            val h = line()
                            if (h.isEmpty()) break
                            val i = h.indexOf(':')
                            if (i > 0) headers[h.substring(0, i).trim().lowercase()] = h.substring(i + 1).trim()
                        }
                        val n = headers["content-length"]?.toIntOrNull() ?: 0
                        val body = ByteArray(n)
                        var read = 0
                        while (read < n) {
                            val r = input.read(body, read, n - read)
                            if (r < 0) break
                            read += r
                        }
                        requests += Request(request, headers, body)
                        val out = if (request.startsWith("GET")) """{"ok":true}""" else reply
                        val bytes = out.toByteArray()
                        c.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            write(bytes)
                            flush()
                        }
                    }
                }
            }
        }
    }

    override fun close() = socket.close()
}

/** Parity checks that need Android: one per checklist group (see PARITY.md §15). */
@RunWith(AndroidJUnit4::class)
class ParityTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as Application
    private lateinit var scenario: ActivityScenario<TestHostActivity>
    private val made = mutableListOf<Scrawl>()
    private val haptics = CopyOnWriteArrayList<String>()

    @Before
    fun setUp() {
        ScrawlConfig.delete(app)
        scenario = ActivityScenario.launch(TestHostActivity::class.java)
    }

    @After
    fun tearDown() {
        main { made.forEach { it.detachForTesting(app) } }
        scenario.close()
        ScrawlConfig.delete(app)
    }

    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)

    private fun waitFor(timeoutMs: Long = 4_000, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            var ok = false
            main { ok = condition() }
            if (ok) return true
            Thread.sleep(25)
        }
        return false
    }

    private fun make(urls: List<String>?, recorder: ScrawlRecorder): Scrawl {
        lateinit var scrawl: Scrawl
        main {
            scrawl = Scrawl(app, urls?.let { ScrawlConfig.of(it, "fixture") }) { _, _ -> recorder }
            scrawl.attachForTesting(app)
            scrawl.haptics.observer = { haptics += it }
            made += scrawl
        }
        scenario.onActivity { scrawl.activityResumedForTesting(it) }
        return scrawl
    }

    private fun window(): Window {
        var window: Window? = null
        scenario.onActivity { window = it.window }
        return window!!
    }

    private fun center(): IntArray {
        var c = intArrayOf(0, 0)
        scenario.onActivity {
            val loc = IntArray(2).also { l -> it.window.decorView.getLocationOnScreen(l) }
            c = intArrayOf(loc[0] + it.window.decorView.width / 2, loc[1] + it.window.decorView.height / 2)
        }
        return c
    }

    /** Sends a pointer gesture through the system's input dispatcher. */
    private fun touch(points: List<Pair<Float, Float>>, holdMs: Long = 0) {
        val down = SystemClock.uptimeMillis()
        fun send(action: Int, p: Pair<Float, Float>) {
            val e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, p.first, p.second, 0)
            e.source = InputDevice.SOURCE_TOUCHSCREEN
            instrumentation.sendPointerSync(e)
            e.recycle()
        }
        send(MotionEvent.ACTION_DOWN, points.first())
        if (holdMs > 0) Thread.sleep(holdMs)
        for (p in points.drop(1)) {
            send(MotionEvent.ACTION_MOVE, p); Thread.sleep(16)
        }
        send(MotionEvent.ACTION_UP, points.last())
    }

    private fun startRecording(scrawl: Scrawl) {
        main { scrawl.start() }
        assertTrue("recording", waitFor { scrawl.isRecording })
    }

    // 7–13: setup links, storage, haptics, listeners.
    @Test
    fun setupLinksAreStoredPrivatelyAndAnnouncedOnce() {
        val scrawl = make(null, FileRecorder())
        val changed = CountDownLatch(1)
        main {
            assertFalse(scrawl.isConfigured)
            scrawl.addConfigurationListener { changed.countDown() }
            val link = "scrawltest://scrawl?url=http%3A%2F%2F127.0.0.1%3A1&token=abc"
            assertTrue(scrawl.handleSetupURL(link))
            assertTrue(scrawl.handleSetupURL(link)) // the same link delivered twice
            assertTrue(scrawl.isConfigured)
        }
        assertTrue("listener on the next main turn", changed.await(2, TimeUnit.SECONDS))
        val stored = File(app.noBackupFilesDir, "dev.kitlangton.scrawl.config.json")
        assertTrue(stored.exists())
        // Sealed with a Keystore key: the token isn't in the file, and loads back.
        assertTrue(stored.readText().startsWith(SealedStore.PREFIX))
        assertFalse("token not in plain text", stored.readText().contains("abc"))
        assertEquals("abc", ScrawlConfig.load(app)!!.token)
        assertEquals("one success haptic for a duplicate link", listOf("success"), haptics.toList())
        main { assertTrue(scrawl.handleSetupURL("scrawltest://scrawl?reset=1")) }
        assertFalse(stored.exists())
        main { assertFalse(scrawl.isConfigured) }
        assertEquals("warning", haptics.last())
        main { assertFalse(scrawl.handleSetupURL("https://example.com/not-scrawl")) }
    }

    // 14–16: losing the configuration mid-recording, team endpoints, and inertness when unpaired.
    @Test
    fun unpairedDevicesAreInertAndUnpairingCancelsARecording() {
        val recorder = FileRecorder()
        val scrawl = make(null, recorder)
        Thread.sleep(300)
        assertFalse("no window wrapping before pairing", window().callback is TouchRouter)
        main {
            scrawl.prepareToStart()
            assertNull(scrawl.overlay)
            scrawl.setTeamEndpoint("https://team.example.com/api", "team")
            assertEquals(listOf("https://team.example.com/api"), scrawl.serverURLs)
            scrawl.setTeamEndpoint(null, null)
            assertTrue(scrawl.serverURLs.isEmpty())
            scrawl.handleSetupURL("scrawltest://scrawl?url=http%3A%2F%2F127.0.0.1%3A1&token=abc")
        }
        assertTrue("wrapped once paired", waitFor { window().callback is TouchRouter })
        startRecording(scrawl)
        main { scrawl.handleSetupURL("scrawltest://scrawl?reset=1") }
        main {
            assertFalse(scrawl.isRecording)
            assertEquals(1, recorder.cancels)
            assertNull(scrawl.overlay)
        }
        assertTrue("unwrapped once unpaired", waitFor { window().callback !is TouchRouter })
    }

    // 21, 23: no trigger, and the editing-gesture switch (a no-op on Android).
    @Test
    fun withoutATriggerThreeFingersDoNothing() {
        val recorder = SuspendedRecorder()
        val scrawl = make(listOf("http://127.0.0.1:1"), recorder)
        main {
            scrawl.trigger = ScrawlTrigger.None
            assertTrue(scrawl.suppressesSystemEditingGestures)
            scrawl.suppressesSystemEditingGestures = false
        }
        waitFor { window().callback is TouchRouter }
        val c = center()
        val down = SystemClock.uptimeMillis()
        fun inject(action: Int, count: Int) {
            val props = Array(count) { MotionEvent.PointerProperties().apply { id = it; toolType = MotionEvent.TOOL_TYPE_FINGER } }
            val coords = Array(count) { MotionEvent.PointerCoords().apply { x = c[0] - 200f + it * 200; y = c[1].toFloat(); pressure = 1f; size = 1f } }
            val e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, count, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            instrumentation.sendPointerSync(e); e.recycle()
        }
        inject(MotionEvent.ACTION_DOWN, 1)
        inject(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2)
        inject(MotionEvent.ACTION_POINTER_DOWN or (2 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 3)
        Thread.sleep(800)
        inject(MotionEvent.ACTION_POINTER_UP or (2 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 3)
        inject(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2)
        inject(MotionEvent.ACTION_UP, 1)
        Thread.sleep(300)
        main {
            assertEquals(0, recorder.starts)
            assertNull("no prewarm either", scrawl.overlay)
        }
    }

    // 28, 29: the timeline supplement and the in-process submit hook.
    @Test
    fun theSupplementLandsInTheTimelineAndASubmittedRecordingIsDeleted() {
        val scrawl = make(listOf("http://127.0.0.1:1"), FileRecorder())
        var timeline: String? = null
        var files: Pair<File, File>? = null
        main {
            scrawl.timelineSupplement = { mapOf("build" to "debug-42") }
            scrawl.onRecordingSubmitted = { t, m ->
                timeline = t.readText(); files = t to m; true
            }
        }
        startRecording(scrawl)
        main { scrawl.stop() }
        assertTrue(waitFor { timeline != null })
        val json = JSONObject(timeline!!)
        assertEquals("debug-42", json.getString("build"))
        assertEquals("appFrames", json.getString("capture"))
        assertTrue(timeline!!.indexOf("\"app\"") < timeline!!.indexOf("\"build\""))
        assertTrue(waitFor { !files!!.first.exists() && !files!!.second.exists() })
        main { assertEquals(Scrawl.UploadStatus.Idle, scrawl.uploadStatus) }
    }

    // 34, 35, 128: start failure, an unavailable recorder, and a failed stop all end quietly.
    @Test
    fun failuresToStartOrStopEndQuietly() {
        val failing = SuspendedRecorder()
        val scrawl = make(listOf("http://127.0.0.1:1"), failing)
        main { scrawl.start() }
        waitFor { failing.starts == 1 }
        main {
            failing.complete(Result.failure(RecorderError.notRecording))
            assertFalse(scrawl.isStarting)
            assertNull(scrawl.overlay)
        }
        val unavailable = FileRecorder().apply { isAvailable = false }
        val other = make(listOf("http://127.0.0.1:1"), unavailable)
        main {
            other.start()
            assertFalse(other.isStarting)
            assertNull(other.overlay)
        }
        // SuspendedRecorder's stop fails: idle, and the strip leaves after its exit.
        val stopping = SuspendedRecorder()
        val third = make(listOf("http://127.0.0.1:1"), stopping)
        main { third.start() }
        waitFor { stopping.starts == 1 }
        main {
            stopping.complete(Result.success(RecordingStart.now()))
            third.stop()
        }
        assertTrue(waitFor { third.uploadStatus == Scrawl.UploadStatus.Idle })
        assertTrue("overlay gone after the linger", waitFor(3_000) { third.overlay == null })
    }

    // 98–107, 100: LAN delivery to a Mac, statuses, markers, recordings list, haptics.
    @Test
    fun aRecordingIsPostedToTheMacAndTheStripSaysSent() {
        FakeMac().use { mac ->
            val scrawl = make(listOf(mac.url), FileRecorder())
            startRecording(scrawl)
            main { assertTrue(scrawl.stripModeForTesting is StripMode.Recording) }
            main { scrawl.mark(); scrawl.toggleDrawing(); scrawl.toggleDrawing() }
            main { scrawl.stop() }
            assertTrue("posted", waitFor(10_000) { mac.requests.any { it.line.startsWith("POST /ingest") } })
            val post = mac.requests.first { it.line.startsWith("POST /ingest") }
            assertTrue(mac.requests.any { it.line.startsWith("GET /health") })
            assertEquals("Bearer fixture", post.headers["authorization"])
            assertEquals("45", post.headers["x-scrawl-wait"]) // the address that answered at start
            val n = java.nio.ByteBuffer.wrap(post.body, 0, 4).int
            val timeline = JSONObject(String(post.body, 4, n))
            assertEquals(1, timeline.getJSONArray("markers").length())
            assertEquals(4096, post.body.size - 4 - n)
            assertTrue(waitFor { scrawl.uploadStatus == Scrawl.UploadStatus.Sent(IngestReply.Delivered("Test Mac")) })
            main {
                assertEquals(StripMode.Sent(IngestReply.Delivered("Test Mac")), scrawl.stripModeForTesting)
                assertEquals(1, scrawl.recordings.size)
                val rec = scrawl.recordings.first()
                assertTrue(rec.movie.exists() && rec.timeline.exists())
                val dir = rec.movie.parentFile!!
                assertFalse(File(dir, rec.movie.nameWithoutExtension + ".ready").exists())
            }
            // "Sent to …" for 2.2 s, then idle and gone.
            assertTrue(waitFor(4_000) { scrawl.uploadStatus == Scrawl.UploadStatus.Idle })
            assertTrue(waitFor(3_000) { scrawl.overlay == null })
            assertEquals(listOf("medium", "light", "light", "success"), haptics.filter { it != "rigid" })
        }
    }

    // 105, 127: no Mac and no USB pickup: "Mac didn't answer", and Share follows sharesWhenDone.
    @Test
    fun anUnreachableMacKeepsTheRecordingAndShareFollowsSharesWhenDone() {
        val scrawl = make(listOf("http://127.0.0.1:1"), FileRecorder())
        val chooser = instrumentation.addMonitor(IntentFilter(Intent.ACTION_CHOOSER), null, true)
        startRecording(scrawl)
        main { scrawl.stop() }
        val t0 = SystemClock.uptimeMillis()
        val seen = mutableListOf<Scrawl.UploadStatus>()
        val failed = waitFor(60_000) { scrawl.uploadStatus.also { if (seen.lastOrNull() != it) seen += it } == Scrawl.UploadStatus.Failed }
        assertTrue("after the 30 s USB window: saw $seen in ${SystemClock.uptimeMillis() - t0} ms", failed)
        main {
            assertEquals(StripMode.Failed(StripFailure.Unreachable), scrawl.stripModeForTesting)
            val rec = scrawl.recordings.first()
            assertTrue("kept for USB pickup", File(rec.movie.parentFile, rec.movie.nameWithoutExtension + ".ready").exists())
            scrawl.sharesWhenDone = false
            scrawl.shareFailedRecording()
            assertEquals(Scrawl.UploadStatus.Idle, scrawl.uploadStatus)
        }
        Thread.sleep(500)
        assertEquals("no share sheet", 0, chooser.hits)
        assertEquals("warning", haptics.last())
        instrumentation.removeMonitor(chooser)
    }

    // 125: an older entry's failure is shown only when nothing else is on the strip.
    @Test
    fun olderFailuresWaitForAnIdleStrip() {
        val scrawl = make(listOf("https://127.0.0.1:1"), FileRecorder())
        startRecording(scrawl)
        main {
            scrawl.handleForTesting(Outbox.Event.Failed("older", Outbox.Failure.Rejected))
            assertTrue(scrawl.stripModeForTesting is StripMode.Recording)
        }
        main { scrawl.discard() }
        assertTrue(waitFor(7_000) { !scrawl.isDiscardPending && scrawl.uploadStatus == Scrawl.UploadStatus.Idle })
        main { scrawl.handleForTesting(Outbox.Event.Failed("older", Outbox.Failure.Rejected)) }
        assertTrue(waitFor { scrawl.stripModeForTesting == StripMode.Failed(StripFailure.Rejected) })
    }

    // 57, 58, 63, 64: touches, the hold tick, and touches on the strip staying off the timeline.
    @Test
    fun touchesAreLoggedWithAHoldTickButNotOnTheStrip() {
        val scrawl = make(listOf("http://127.0.0.1:1"), FileRecorder())
        startRecording(scrawl)
        waitFor { scrawl.overlay?.strip?.liquid?.geometry?.rows?.all { it.opacity > 0.9 } == true }
        val c = center()
        val p = c[0].toFloat() to c[1].toFloat()
        touch(listOf(p), holdMs = 600)
        main {
            val touches = scrawl.touchesForTesting
            assertEquals(listOf("down", "up"), touches.map { it.getString("phase") })
            assertEquals(1, touches.first().getInt("id"))
            val density = app.resources.displayMetrics.density
            assertEquals(c[0] / density.toDouble(), touches.first().getDouble("x"), 50.0)
        }
        assertEquals(1, haptics.count { it == "rigid" })
        // Moving more than 12 dp before the hold: no tick.
        val far = (c[0] + 60 * app.resources.displayMetrics.density) to c[1].toFloat()
        touch(listOf(p, far, far), holdMs = 0)
        Thread.sleep(600)
        assertEquals(1, haptics.count { it == "rigid" })
        // A tap on the strip is the overlay's, not the app's.
        var target = intArrayOf(0, 0)
        main {
            val r = scrawl.overlay!!.strip.touchRects().first()
            val loc = IntArray(2).also { scrawl.overlay!!.root.getLocationOnScreen(it) }
            target = intArrayOf(loc[0] + r.centerX(), loc[1] + r.top + (24 * app.resources.displayMetrics.density).toInt())
        }
        val before = scrawl.touchesForTesting.size
        touch(listOf(target[0].toFloat() to target[1].toFloat()))
        main { assertEquals(before, scrawl.touchesForTesting.size) }
    }

    // 74–81: drawing takes the touches, strokes go on the timeline, and fade.
    @Test
    fun aDrawnStrokeIsRecordedAndFades() {
        val scrawl = make(listOf("http://127.0.0.1:1"), FileRecorder())
        startRecording(scrawl)
        main { scrawl.toggleDrawing() }
        if (Build.VERSION.SDK_INT >= 34) main { assertTrue(scrawl.overlay!!.ink.predictsTip) }
        val c = center()
        val stroke = (0..12).map { (c[0] - 150f + it * 25f) to (c[1] - 100f + it * 15f) }
        touch(stroke)
        main {
            assertTrue("drawing keeps touches off the app", scrawl.touchesForTesting.isEmpty())
            val strokes = scrawl.strokesForTesting
            assertEquals(1, strokes.size)
            val s = strokes.first()
            assertTrue(s.getDouble("t1") >= s.getDouble("t0"))
            assertTrue(s.getJSONArray("points").length() >= 5)
            assertEquals(1, scrawl.completedStrokes.size)
        }
        assertTrue("fades after the 1.5 s hold", waitFor(6_000) { scrawl.strokesForTesting.first().has("fadeAt") })
        main { scrawl.stop() }
        main { assertTrue(scrawl.completedStrokes.isEmpty()) }
    }

    // 39, 44–46: the movie itself; and a cancel during setup never completes (iOS StartupTests).
    @Test
    fun anImmediateStopKeepsTheFirstFrameAndCancelIsSilent() {
        EncoderProbe.assume()
        lateinit var decor: View
        scenario.onActivity { decor = it.window.decorView }
        val host = object : CaptureHost {
            override val lastTouchMoveNs: Long? = null
            override val isInteractingForCapture = false
            override fun isOverlay(root: View) = false
            override val keyboard = KeyboardTracker(ScrawlAppContext { null }) { false }
            override fun focusedRoot(): View = decor
            override fun hasMicrophonePermission() = false
            override fun requestMicrophonePermission(completion: (Boolean) -> Unit) = completion(false)
        }
        val temp = File(app.cacheDir, "ParityTemp")
        val recorder = AppFrameRecorder(host, AppFrameRecorder.VIDEO_BIT_RATE, temp)
        val started = CountDownLatch(1)
        main { recorder.start(false) { started.countDown() } }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        val movie = File(app.cacheDir, "immediate.mp4")
        val stopped = CountDownLatch(1)
        var error: Throwable? = null
        main { recorder.stop(movie) { error = it; stopped.countDown() } }
        assertTrue(stopped.await(20, TimeUnit.SECONDS))
        assertNull(error)
        val extractor = MediaExtractor().apply { setDataSource(movie.path) }
        assertEquals(1, extractor.trackCount)
        val format = extractor.getTrackFormat(0)
        assertEquals(MediaFormat.MIMETYPE_VIDEO_AVC, format.getString(MediaFormat.KEY_MIME))
        assertEquals(0, format.getInteger(MediaFormat.KEY_WIDTH) % 2)
        extractor.selectTrack(0)
        // A stop right after start may hold just the first frame (no duration key then).
        assertEquals("first sample at t=0", 0L, extractor.sampleTime)
        assertTrue("a key frame", extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0)
        extractor.release()

        val cancelled = AppFrameRecorder(host, AppFrameRecorder.VIDEO_BIT_RATE, temp)
        val completed = CountDownLatch(1)
        main {
            cancelled.start(false) { completed.countDown() }
            cancelled.cancel()
        }
        assertFalse("a cancelled start never completes", completed.await(700, TimeUnit.MILLISECONDS))
    }

    // 49: interaction slows capture (12 fps) so scrolling keeps its frame budget.
    @Test
    fun interactionSlowsCapture() {
        EncoderProbe.assume()
        lateinit var decor: View
        scenario.onActivity { decor = it.window.decorView }
        fun record(interacting: Boolean): FramePacer.Metrics {
            val host = object : CaptureHost {
                override val lastTouchMoveNs: Long? get() = if (interacting) System.nanoTime() else null
                override val isInteractingForCapture = interacting
                override fun isOverlay(root: View) = false
                override val keyboard = KeyboardTracker(ScrawlAppContext { null }) { false }
                override fun focusedRoot(): View = decor
                override fun hasMicrophonePermission() = false
                override fun requestMicrophonePermission(completion: (Boolean) -> Unit) = completion(false)
            }
            val recorder = AppFrameRecorder(host, AppFrameRecorder.VIDEO_BIT_RATE, File(app.cacheDir, "ParityTemp"))
            val started = CountDownLatch(1)
            var startNs = 0L
            main { recorder.start(false) { startNs = it.getOrThrow().monoNs; started.countDown() } }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            Thread.sleep(1_500)
            val stopped = CountDownLatch(1)
            val movie = File(app.cacheDir, "pace-$interacting.mp4")
            var stopNs = 0L
            main { stopNs = System.nanoTime(); recorder.stop(movie) { stopped.countDown() } }
            assertTrue(stopped.await(20, TimeUnit.SECONDS))
            // The last frame is repeated at the stop instant: the movie is as long as the recording.
            val extractor = MediaExtractor().apply { setDataSource(movie.path) }
            extractor.selectTrack(0)
            var lastUs = 0L
            while (extractor.sampleTime >= 0) { lastUs = maxOf(lastUs, extractor.sampleTime); extractor.advance() }
            extractor.release()
            val elapsedUs = (stopNs - startNs) / 1000
            assertEquals("last frame at stop ($lastUs vs $elapsedUs µs)", elapsedUs.toDouble(), lastUs.toDouble(), 150_000.0)
            return AppFrameRecorder.lastMetrics!!
        }
        val idle = record(false)
        val busy = record(true)
        assertTrue("interacting skips for cadence: ${busy.ticksSkippedCadence}", busy.ticksSkippedCadence > 0)
        // 12 fps while interacting: at most ~18 frames in the 1.5 s (+ the first and the stop frame),
        // however fast the device would otherwise go (a loaded host can be slower than 12 fps idle).
        assertTrue("≤ 12 fps while interacting: ${busy.framesCaptured} (idle ${idle.framesCaptured})", busy.framesCaptured <= 21)
    }

    // 43: the microphone comes back after its input goes away.
    @Test
    fun theMicrophoneRestartsAfterItsInputGoesAway() {
        EncoderProbe.assume()
        val granted = runCatching {
            // One UiAutomation connection for the whole run: switching flags unregisters the other.
            instrumentation.uiAutomation.grantRuntimePermission(app.packageName, android.Manifest.permission.RECORD_AUDIO)
        }.isSuccess
        assertTrue("RECORD_AUDIO granted for the test", granted || app.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == 0)
        lateinit var decor: View
        scenario.onActivity { decor = it.window.decorView }
        val host = object : CaptureHost {
            override val lastTouchMoveNs: Long? = null
            override val isInteractingForCapture = false
            override fun isOverlay(root: View) = false
            override val keyboard = KeyboardTracker(ScrawlAppContext { null }) { false }
            override fun focusedRoot(): View = decor
            override fun hasMicrophonePermission() = true
            override fun requestMicrophonePermission(completion: (Boolean) -> Unit) = completion(true)
        }
        val recorder = AppFrameRecorder(host, AppFrameRecorder.VIDEO_BIT_RATE, File(app.cacheDir, "ParityTemp"))
        Microphone.restartsForTesting = 0
        val started = CountDownLatch(1)
        main { recorder.start(true) { started.countDown() } }
        assertTrue(started.await(8, TimeUnit.SECONDS))
        Thread.sleep(500)
        Microphone.failReadsForTesting = 1
        Thread.sleep(1_500)
        val stopped = CountDownLatch(1)
        main { recorder.stop(File(app.cacheDir, "mic.mp4")) { stopped.countDown() } }
        assertTrue(stopped.await(20, TimeUnit.SECONDS))
        assertEquals(1, Microphone.restartsForTesting)
        assertTrue("audio kept recording", recorder.recordsAudio)
        val extractor = MediaExtractor().apply { setDataSource(File(app.cacheDir, "mic.mp4").path) }
        val audio = (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }.first { it.getString(MediaFormat.KEY_MIME)!!.startsWith("audio/") }
        assertEquals(48_000, audio.getInteger(MediaFormat.KEY_SAMPLE_RATE))
        assertEquals(1, audio.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
        assertTrue("audio runs past the restart", audio.getLong(MediaFormat.KEY_DURATION) > 1_200_000)
        extractor.release()
    }

    // 39, 46: the encoder's settings, no B-frames, and nothing encoded is an error.
    @Test
    fun theMovieIsHighProfileH264WithoutBFramesAndAnEmptyOneFails() {
        EncoderProbe.assume()
        val file = File(app.cacheDir, "format.mp4")
        lateinit var writer: MovieWriter
        main { writer = MovieWriter(file, 618, 1372, AppFrameRecorder.VIDEO_BIT_RATE, System.nanoTime()) }
        val f = writer.videoFormat
        assertEquals(6_000_000, f.getInteger(MediaFormat.KEY_BIT_RATE))
        assertEquals(3_000_000, AppFrameRecorder.HOSTED_VIDEO_BIT_RATE)
        assertEquals(30, f.getInteger(MediaFormat.KEY_FRAME_RATE))
        assertEquals(1, f.getInteger(MediaFormat.KEY_I_FRAME_INTERVAL))
        assertEquals(android.media.MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR, f.getInteger(MediaFormat.KEY_BITRATE_MODE))
        assertEquals(MediaFormat.COLOR_STANDARD_BT709, f.getInteger(MediaFormat.KEY_COLOR_STANDARD))
        if (Build.VERSION.SDK_INT >= 29) assertEquals(0, f.getInteger(MediaFormat.KEY_MAX_B_FRAMES))
        // Nothing drawn: "no frames were recorded", like iOS.
        val done = CountDownLatch(1)
        var error: Throwable? = null
        writer.noAudio()
        writer.finish { error = it; done.countDown() }
        assertTrue(done.await(10, TimeUnit.SECONDS))
        assertTrue("$error", error?.message?.contains("no frames were recorded") == true)

        // A real recording: High profile in the SPS when the encoder took it, and decode order is
        // presentation order (no B-frames).
        lateinit var decor: View
        scenario.onActivity { decor = it.window.decorView }
        val host = object : CaptureHost {
            override val lastTouchMoveNs: Long? = null
            override val isInteractingForCapture = false
            override fun isOverlay(root: View) = false
            override val keyboard = KeyboardTracker(ScrawlAppContext { null }) { false }
            override fun focusedRoot(): View = decor
            override fun hasMicrophonePermission() = false
            override fun requestMicrophonePermission(completion: (Boolean) -> Unit) = completion(false)
        }
        val recorder = AppFrameRecorder(host, AppFrameRecorder.VIDEO_BIT_RATE, File(app.cacheDir, "ParityTemp"))
        val started = CountDownLatch(1)
        main { recorder.start(false) { started.countDown() } }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        main { decor.invalidate() }
        Thread.sleep(1_200)
        val movie = File(app.cacheDir, "profile.mp4")
        val stopped = CountDownLatch(1)
        main { recorder.stop(movie) { stopped.countDown() } }
        assertTrue(stopped.await(20, TimeUnit.SECONDS))
        val extractor = MediaExtractor().apply { setDataSource(movie.path) }
        val format = extractor.getTrackFormat(0)
        val sps = format.getByteBuffer("csd-0")!!
        val bytes = ByteArray(sps.remaining()).also { sps.get(it) }
        val nal = (0 until bytes.size - 4).first { bytes[it] == 0.toByte() && bytes[it + 1] == 0.toByte() && bytes[it + 2] == 1.toByte() } + 3
        assertEquals("an SPS", 7, bytes[nal].toInt() and 0x1f)
        val profile = bytes[nal + 1].toInt() and 0xff
        val encoder = android.media.MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val levels = encoder.codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).profileLevels.map { it.profile }
        encoder.release()
        android.util.Log.i("ScrawlParity", "requestedHigh=${writer.videoFormat.containsKey(MediaFormat.KEY_PROFILE)} advertisesHigh=${android.media.MediaCodecInfo.CodecProfileLevel.AVCProfileHigh in levels} spsProfile=$profile")
        if (android.media.MediaCodecInfo.CodecProfileLevel.AVCProfileHigh in levels) {
            assertEquals("High profile", 100, profile)
        } else {
            // The emulator's software encoder: its own profile, not a High request it ignores.
            assertFalse(writer.videoFormat.containsKey(MediaFormat.KEY_PROFILE))
            assertTrue("profile $profile", profile == 66 || profile == 77)
        }
        extractor.selectTrack(0)
        var previous = -1L
        var samples = 0
        while (extractor.sampleTime >= 0) {
            assertTrue("pts rise in decode order", extractor.sampleTime > previous)
            previous = extractor.sampleTime; samples++; extractor.advance()
        }
        extractor.release()
        assertTrue(samples >= 2)
    }

    // 88: the strip rests in the safe area, 6 dp off the side, and above the keyboard.
    @Test
    fun theStripRestsInTheSafeAreaAndAboveTheKeyboard() {
        val scrawl = make(listOf("http://127.0.0.1:1"), FileRecorder())
        startRecording(scrawl)
        assertTrue(waitFor { scrawl.overlay?.strip?.liquid?.isAnimating == false && scrawl.overlay?.strip?.liquid?.geometry?.controlsLive == true })
        val d = app.resources.displayMetrics.density.toDouble()
        var keyboardTop = 0f
        main {
            val strip = scrawl.overlay!!.strip
            val insets = androidx.core.view.ViewCompat.getRootWindowInsets(strip)!!
                .getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or androidx.core.view.WindowInsetsCompat.Type.displayCutout())
            val body = strip.liquid.geometry.body
            val right = (strip.width - insets.right) / d
            assertEquals("6 dp off the trailing edge", right - 6, body.maxX, 1.0)
            assertTrue("below the status bar", body.minY >= insets.top / d + 8 - 0.5)
            keyboardTop = strip.height * 0.55f
            strip.keyboardTopPx = keyboardTop
        }
        assertTrue(waitFor { scrawl.overlay?.strip?.liquid?.isAnimating == false })
        main {
            val body = scrawl.overlay!!.strip.liquid.geometry.body
            assertTrue("above the keyboard: ${body.maxY} vs ${keyboardTop / d}", body.maxY <= keyboardTop / d - 8 + 0.5)
            scrawl.overlay!!.strip.keyboardTopPx = null
        }
    }

    // 84–86: the strip is Liquid Glass on Android 13+ (iOS 26's glassEffect), material below.
    @Test
    fun theStripIsLiquidGlassWithALitRim() {
        scenario.onActivity { activity ->
            // Stripes behind the strip, so the lens has something to bend.
            activity.window.decorView.background = object : android.graphics.drawable.Drawable() {
                private val paint = android.graphics.Paint()
                override fun draw(canvas: android.graphics.Canvas) {
                    val w = bounds.width().toFloat()
                    var x = 0f; var i = 0
                    while (x < w) {
                        paint.color = if (i % 2 == 0) android.graphics.Color.rgb(40, 110, 220) else android.graphics.Color.rgb(250, 250, 250)
                        canvas.drawRect(x, 0f, x + 18 * app.resources.displayMetrics.density, bounds.height().toFloat(), paint)
                        x += 18 * app.resources.displayMetrics.density; i++
                    }
                }
                override fun setAlpha(alpha: Int) = Unit
                override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) = Unit
                @Suppress("OVERRIDE_DEPRECATION") override fun getOpacity() = android.graphics.PixelFormat.OPAQUE
            }
        }
        val scrawl = make(listOf("http://127.0.0.1:1"), FileRecorder())
        startRecording(scrawl)
        assertTrue(waitFor { scrawl.overlay?.strip?.liquid?.isAnimating == false && scrawl.overlay?.strip?.liquid?.geometry?.controlsLive == true })
        Thread.sleep(300)
        var glass = false
        var body = Rect(0.0, 0.0, 0.0, 0.0)
        val origin = IntArray(2)
        main {
            val strip = scrawl.overlay!!.strip
            glass = strip.drawsLiquidGlass
            body = strip.liquid.geometry.body
            strip.getLocationOnScreen(origin)
        }
        if (Build.VERSION.SDK_INT < 33) {
            assertFalse(glass); return
        }
        assertTrue("Liquid Glass on API ${Build.VERSION.SDK_INT}", glass)
        val shot = instrumentation.uiAutomation.takeScreenshot()!!
        File(app.getExternalFilesDir(null), "liquid-glass.png").outputStream().use { shot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        val d = app.resources.displayMetrics.density
        fun luma(x: Double, y: Double): Double {
            val c = shot.getPixel((origin[0] + x * d).toInt(), (origin[1] + y * d).toInt())
            return 0.299 * android.graphics.Color.red(c) + 0.587 * android.graphics.Color.green(c) + 0.114 * android.graphics.Color.blue(c)
        }
        // The top-left rim (lit) against the interior, both inside the tinted glass. Rim is ~1 dp in.
        val rim = luma(body.midX - body.width * 0.25, body.minY + body.width / 2 * 0.15 + 0.8)
        val inside = luma(body.midX, body.midY)
        assertTrue("lit rim $rim brighter than the interior $inside", rim > inside + 15)
        // The tint darkens the white/blue stripes: the interior is well below the backdrop's luma.
        assertTrue("tinted interior $inside", inside < 175)
    }

    // 92 below Android 12: the strip is the app behind, blurred on the CPU, under the tint.
    @Test
    fun beforeAndroid12TheStripBlursTheAppOnTheCpu() {
        val scrawl = make(listOf("http://127.0.0.1:1"), FileRecorder())
        scenario.onActivity { it.window.decorView.setBackgroundColor(android.graphics.Color.WHITE) }
        startRecording(scrawl)
        assertTrue(waitFor { scrawl.overlay?.strip?.liquid?.isAnimating == false && scrawl.overlay?.strip?.liquid?.geometry?.controlsLive == true })
        main { scrawl.overlay!!.strip.forcesSoftwareBlur = true; scrawl.overlay!!.strip.invalidate() }
        Thread.sleep(400)
        var body = Rect(0.0, 0.0, 0.0, 0.0)
        val origin = IntArray(2)
        main {
            val strip = scrawl.overlay!!.strip
            assertTrue(strip.drawsSoftwareBlur)
            body = strip.liquid.geometry.body
            strip.getLocationOnScreen(origin)
        }
        val d = app.resources.displayMetrics.density
        fun interior(): Int {
            val shot = instrumentation.uiAutomation.takeScreenshot()!!
            // Between the Draw and Discard rows: glass, no glyph.
            return shot.getPixel((origin[0] + (body.minX + 6) * d).toInt(), (origin[1] + body.midY * d).toInt())
        }
        val overWhite = interior()
        scenario.onActivity { it.window.decorView.setBackgroundColor(android.graphics.Color.rgb(20, 60, 230)) }
        Thread.sleep(500)
        val overBlue = interior()
        assertTrue("tinted white ${Integer.toHexString(overWhite)}", android.graphics.Color.red(overWhite) in 100..180)
        assertTrue("the blue shows through ${Integer.toHexString(overBlue)}",
            android.graphics.Color.blue(overBlue) > android.graphics.Color.red(overBlue) + 50)
        main { scrawl.overlay!!.strip.forcesSoftwareBlur = false }
    }

    // 88 with a real IME: the strip rests above the keyboard while it's up, and goes back after.
    @Test
    fun theStripMovesAboveARealKeyboardAndBack() {
        val ui = instrumentation.uiAutomation
        fun shell(cmd: String) = ui.executeShellCommand(cmd).use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes().toString(Charsets.UTF_8).trim() }
        val hardKeyboardSetting = shell("settings get secure show_ime_with_hard_keyboard")
        shell("settings put secure show_ime_with_hard_keyboard 1")
        try {
            lateinit var field: android.widget.EditText
            scenario.onActivity { activity ->
                field = android.widget.EditText(activity).apply { hint = "note" }
                val frame = android.widget.FrameLayout(activity)
                frame.addView(field, android.widget.FrameLayout.LayoutParams(-1, -2, android.view.Gravity.TOP))
                activity.setContentView(frame)
            }
            val scrawl = make(listOf("http://127.0.0.1:1"), FileRecorder())
            startRecording(scrawl)
            // Rest low on the trailing edge, where a keyboard would cover it.
            main { scrawl.overlay!!.strip.restForTesting(StripSide.Trailing, 0.95) }
            assertTrue(waitFor { scrawl.overlay?.strip?.liquid?.isAnimating == false && scrawl.overlay?.strip?.liquid?.geometry?.controlsLive == true })
            var lowMaxY = 0.0
            main { lowMaxY = scrawl.overlay!!.strip.liquid.geometry.body.maxY }
            scenario.onActivity {
                field.requestFocus()
                (it.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                    .showSoftInput(field, android.view.inputmethod.InputMethodManager.SHOW_FORCED)
            }
            val up = waitFor(8_000) { scrawl.keyboard.isVisible }
            var probe = ""
            main {
                val root = scrawl.focusedRoot()
                scrawl.keyboard.update(root)
                probe = "focusedRoot=${root?.javaClass?.simpleName} visibleAfterManualUpdate=${scrawl.keyboard.isVisible} overlay=${scrawl.overlay != null}"
            }
            var imeInset = -1
            scenario.onActivity { imeInset = androidx.core.view.ViewCompat.getRootWindowInsets(it.window.decorView)?.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())?.bottom ?: -2 }
            assertTrue("the keyboard came up ($probe; ime inset $imeInset; ${shell("dumpsys input_method").lines().filter { "mInputShown" in it || "isInputViewShown" in it || "mServedView" in it }.joinToString(" | ").take(400)})", up)
            val d = app.resources.displayMetrics.density
            assertTrue("strip above the keyboard", waitFor(4_000) {
                val o = scrawl.overlay!!
                val top = scrawl.keyboardTopIn(o.root)
                top != null && !o.strip.liquid.isAnimating && o.strip.liquid.geometry.body.maxY <= top / d - 8 + 0.5
            })
            scenario.onActivity {
                (it.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                    .hideSoftInputFromWindow(field.windowToken, 0)
            }
            assertTrue("keyboard down", waitFor(8_000) { !scrawl.keyboard.isVisible })
            assertTrue("strip back where it rests", waitFor(4_000) {
                val g = scrawl.overlay!!.strip.liquid
                !g.isAnimating && kotlin.math.abs(g.geometry.body.maxY - lowMaxY) < 1.0
            })
        } finally {
            shell("settings put secure show_ime_with_hard_keyboard ${if (hardKeyboardSetting == "null") "0" else hardKeyboardSetting}")
        }
    }

    // 57–58: a touch in a dialog is logged where it is on the app's screen, and `screen` is the app's.
    @Test
    fun touchesInADialogAreInTheAppWindowsCoordinates() {
        val scrawl = make(listOf("http://127.0.0.1:1"), FileRecorder())
        lateinit var dialog: android.app.Dialog
        lateinit var content: View
        scenario.onActivity { activity ->
            content = View(activity).apply { setBackgroundColor(android.graphics.Color.LTGRAY); minimumWidth = 600; minimumHeight = 400 }
            dialog = android.app.Dialog(activity).apply { setContentView(content); show() }
        }
        assertTrue(waitFor { content.isShown && content.width > 0 && window().callback is TouchRouter })
        main { scrawl.windowsChanged() }
        assertTrue("dialog window wrapped", waitFor { dialog.window?.callback is TouchRouter })
        startRecording(scrawl)
        var tap = intArrayOf(0, 0)
        var decorOrigin = intArrayOf(0, 0)
        var decorSize = intArrayOf(0, 0)
        main {
            val loc = IntArray(2).also { content.getLocationOnScreen(it) }
            tap = intArrayOf(loc[0] + content.width / 2, loc[1] + content.height / 2)
            scenario.onActivity { a ->
                a.window.decorView.getLocationOnScreen(decorOrigin)
                decorSize = intArrayOf(a.window.decorView.width, a.window.decorView.height)
            }
        }
        touch(listOf(tap[0].toFloat() to tap[1].toFloat()))
        val d = app.resources.displayMetrics.density.toDouble()
        main {
            val down = scrawl.touchesForTesting.first()
            assertEquals("x on the app's screen", (tap[0] - decorOrigin[0]) / d, down.getDouble("x"), 2.0)
            assertEquals("y on the app's screen", (tap[1] - decorOrigin[1]) / d, down.getDouble("y"), 2.0)
        }
        var timeline: String? = null
        main {
            scrawl.onRecordingSubmitted = { t, _ -> timeline = t.readText(); true }
            scrawl.stop()
        }
        assertTrue(waitFor { timeline != null })
        val screen = JSONObject(timeline!!).getJSONObject("screen")
        assertEquals(decorSize[0] / d, screen.getDouble("width"), 0.5)
        assertEquals(decorSize[1] / d, screen.getDouble("height"), 0.5)
        main { dialog.dismiss() }
    }

    // 71–73: screens and context set before install() are kept (iOS's singleton always exists).
    @Test
    fun contextSetBeforeInstallIsKept() {
        Scrawl.context("preInstall", "kept")
        Scrawl.screen("Before install")
        lateinit var installed: Scrawl
        main { installed = Scrawl.install(app, isEnabled = false) }
        main {
            assertEquals("kept", installed.appContext.currentValues["preInstall"])
            assertEquals("Before install", installed.appContext.currentScreen)
        }
        Scrawl.context("preInstall", null)
        Scrawl.screen(null)
    }

    // 10: a config stored in plain text (before sealing) still loads, and is re-saved sealed.
    @Test
    fun aPlainTextConfigIsMigratedToTheSealedStore() {
        val file = File(app.noBackupFilesDir, "dev.kitlangton.scrawl.config.json")
        file.writeText("""{"urls":["http://127.0.0.1:1"],"token":"plain-token"}""")
        assertEquals("plain-token", ScrawlConfig.load(app)!!.token)
        assertTrue(file.readText().startsWith(SealedStore.PREFIX))
        assertEquals("plain-token", ScrawlConfig.load(app)!!.token)
    }

    // 45: the stop appends the last captured frame again at the stop instant (iOS), not a new one.
    @Test
    fun theStopRepeatsTheLastCapturedFrame() {
        EncoderProbe.assume()
        org.junit.Assume.assumeTrue("display-list repeat needs API 29", Build.VERSION.SDK_INT >= 29)
        lateinit var decor: View
        scenario.onActivity { decor = it.window.decorView.apply { setBackgroundColor(android.graphics.Color.RED) } }
        val host = object : CaptureHost {
            override val lastTouchMoveNs: Long? = null
            override val isInteractingForCapture = false
            override fun isOverlay(root: View) = false
            override val keyboard = KeyboardTracker(ScrawlAppContext { null }) { false }
            override fun focusedRoot(): View = decor
            override fun hasMicrophonePermission() = false
            override fun requestMicrophonePermission(completion: (Boolean) -> Unit) = completion(false)
        }
        val recorder = AppFrameRecorder(host, AppFrameRecorder.VIDEO_BIT_RATE, File(app.cacheDir, "ParityTemp"))
        val started = CountDownLatch(1)
        main { recorder.start(false) { started.countDown() } }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        Thread.sleep(800)
        val movie = File(app.cacheDir, "repeat.mp4")
        val stopped = CountDownLatch(1)
        // Changed and stopped in one main-thread turn: no capture can see the blue before the stop.
        main {
            decor.setBackgroundColor(android.graphics.Color.BLUE)
            recorder.stop(movie) { stopped.countDown() }
        }
        assertTrue(stopped.await(20, TimeUnit.SECONDS))
        val extractor = MediaExtractor().apply { setDataSource(movie.path) }
        extractor.selectTrack(0)
        var lastUs = 0L
        while (extractor.sampleTime >= 0) { lastUs = maxOf(lastUs, extractor.sampleTime); extractor.advance() }
        extractor.release()
        val retriever = android.media.MediaMetadataRetriever().apply { setDataSource(movie.path) }
        val frame = retriever.getFrameAtTime(lastUs, android.media.MediaMetadataRetriever.OPTION_CLOSEST)!!
        retriever.release()
        val c = frame.getPixel(frame.width / 2, frame.height / 2)
        assertTrue("the last frame repeats the red one, was ${Integer.toHexString(c)}",
            android.graphics.Color.red(c) > 180 && android.graphics.Color.blue(c) < 80)
    }
}
