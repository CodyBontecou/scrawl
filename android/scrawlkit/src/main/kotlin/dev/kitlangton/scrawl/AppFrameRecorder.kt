package dev.kitlangton.scrawl

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Trace
import android.util.Log
import android.view.Choreographer
import android.view.View
import android.view.WindowManager
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** What the app-frame recorder needs from [Scrawl]. All on the main thread. */
internal interface CaptureHost {
    /** Monotonic time (ns) of the last touch move, for interaction pacing. */
    val lastTouchMoveNs: Long?

    /** Whether the user is scrolling, dragging, or drawing ink right now. */
    val isInteractingForCapture: Boolean

    /** ScrawlKit's own overlay root, never drawn into the movie. */
    fun isOverlay(root: View): Boolean
    val keyboard: KeyboardTracker

    /** Re-reads where the soft keyboard is (each capture tick). */
    fun refreshKeyboard() = keyboard.update(focusedRoot())

    /** The window touches are measured in (the foreground activity's decor view). */
    fun focusedRoot(): View?

    /** Whether the app may record the microphone; [request] asks the user when it may not. */
    fun hasMicrophonePermission(): Boolean
    fun requestMicrophonePermission(completion: (Boolean) -> Unit)
}

/**
 * Records the app's own windows, not the screen, with the microphone into an H.264 `.mp4`.
 *
 * No screen-capture consent is needed: the microphone asks once. If it's denied, the movie is video
 * only. ScrawlKit's overlay is left out, so the strip, ink, and toasts never appear in the movie;
 * Scrawl on the Mac draws touches and strokes from the timeline.
 *
 * Frames are drawn on the main thread into the video encoder's input surface. [FramePacer] keeps
 * capture at 30 fps when idle, backs off to 10–12 fps while scrolling or dragging, and captures a
 * settled frame once motion stops. Video and audio share the monotonic clock, and the movie starts
 * at the first frame, so movie time 0 is the timeline's t=0.
 */
internal class AppFrameRecorder(
    private val host: CaptureHost,
    private val videoBitRate: Int = VIDEO_BIT_RATE,
    private val temporaryDirectory: File,
) : ScrawlRecorder {
    private enum class State { Idle, Starting, Recording, Finished }

    override var onInterruption: (() -> Unit)? = null
    override var startupTrace: StartupTrace? = null
    override var recordsAudio = false
        private set
    override val method = ScrawlCaptureMethod.AppFrames
    override val isAvailable = true
    var pacer = FramePacer()
        private set

    private var state = State.Idle
    private var startCompletion: ((Result<RecordingStart>) -> Unit)? = null
    private var audioReady = false
    private var capturedFirstFrame = false
    private var start = RecordingStart.now()
    private var writer: MovieWriter? = null
    private var canvasScale = 1f
    private var screenWidthPx = 0
    private var screenHeightPx = 0
    private val main = Handler(Looper.getMainLooper())
    private val temporaryMovie = File(temporaryDirectory, "scrawl-${java.util.UUID.randomUUID()}.mp4")
    private val frameCallback = Choreographer.FrameCallback { frameNs -> tick(frameNs) }
    private var frameIntervalNs = 16_666_667L

    override fun start(microphone: Boolean, completion: (Result<RecordingStart>) -> Unit) {
        if (state != State.Idle) return
        state = State.Starting
        start = RecordingStart.now()
        startCompletion = completion
        // The writer and the microphone have independent startup paths, as on iOS: the encoder is
        // set up off the main thread while the permission is checked here.
        begin()
        if (state != State.Starting && state != State.Recording) return
        startupTrace?.mark("permissionBegin")
        if (!microphone) {
            startupTrace?.mark("permissionEnd")
            audio = Audio.None
            applyAudio()
            return
        }
        if (host.hasMicrophonePermission()) {
            startupTrace?.mark("permissionEnd")
            startMicrophone()
        } else {
            host.requestMicrophonePermission { granted ->
                main.post {
                    startupTrace?.mark("permissionEnd")
                    if (state != State.Starting && state != State.Recording) return@post
                    if (granted) {
                        startMicrophone()
                    } else {
                        audio = Audio.None
                        applyAudio()
                    }
                }
            }
        }
    }

    private enum class Audio { Undecided, None, Microphone }
    private var audio = Audio.Undecided

    private fun startMicrophone() {
        audio = Audio.Microphone
        applyAudio()
    }

    /** Hands the audio decision to the writer once both exist (either can come first). */
    private fun applyAudio() {
        val writer = writer ?: return
        when (audio) {
            Audio.Undecided -> Unit
            Audio.None -> {
                writer.noAudio()
                audioReady = true
                completeStartIfReady()
            }
            Audio.Microphone -> startMicrophone(writer)
        }
    }

    private fun startMicrophone(writer: MovieWriter) {
        writer.startAudio(startupTrace) { running ->
            main.post {
                if (state != State.Starting && state != State.Recording) return@post
                if (!running) startupTrace?.mark("microphoneUnavailable")
                audioReady = true
                completeStartIfReady()
            }
        }
    }

    private fun begin() {
        val root = host.focusedRoot()
        if (root == null || root.width == 0 || root.height == 0) {
            failStart(RecorderError.noScene)
            return
        }
        val density = root.resources.displayMetrics.density
        screenWidthPx = root.width
        screenHeightPx = root.height
        val pointsW = root.width / density.toDouble()
        val pointsH = root.height / density.toDouble()
        val (w, h) = canvasSize(pointsW, pointsH)
        Log.i(TAG, "CaptureSize ${w}x$h from ${pointsW}x$pointsH pt")
        canvasScale = (w / root.width.toDouble()).toFloat()
        frameIntervalNs = (1e9 / (root.display?.refreshRate ?: 60f)).toLong()
        pacer = FramePacer()
        val trace = startupTrace
        val begin = start
        val movie = temporaryMovie
        val bitRate = videoBitRate
        writerThread.execute {
            trace?.mark("writerBegin")
            val result = runCatching {
                temporaryDirectory.mkdirs()
                MovieWriter(movie, w, h, bitRate, begin.monoNs)
            }
            if (result.isSuccess) trace?.mark("writerEnd")
            main.post {
                val writer = result.getOrElse {
                    movie.delete()
                    if (state == State.Starting) failStart(it)
                    return@post
                }
                if (state != State.Starting) {
                    writer.cancel(); return@post // cancelled while the writer was set up
                }
                this.writer = writer
                state = State.Recording
                applyAudio()
                // Capture on the next display turn, after the overlay's initial layout can commit.
                Choreographer.getInstance().postFrameCallback(frameCallback)
            }
        }
    }

    private fun completeStartIfReady() {
        if (state != State.Recording || !audioReady || !capturedFirstFrame) return
        val completion = startCompletion ?: return
        startCompletion = null
        completion(Result.success(start))
    }

    private fun failStart(error: Throwable) {
        val completion = startCompletion
        cancel()
        completion?.invoke(Result.failure(error))
    }

    override fun stop(movie: File, completion: (Throwable?) -> Unit) {
        val writer = writer
        if (state != State.Recording || writer == null) {
            completion(RecorderError.notRecording)
            return
        }
        // The last frame holds until now: it's appended again at the stop instant, as iOS does.
        repeatLastFrame(System.nanoTime()) || renderFrame(System.nanoTime()) != null
        state = State.Finished
        lastMetrics = pacer.metrics
        val m = pacer.metrics
        Log.i(TAG, "CaptureSummary frames=${m.framesCaptured} skippedCadence=${m.ticksSkippedCadence} skippedBudget=${m.ticksSkippedBudget} skippedBusy=${m.ticksSkippedWriterBusy} meanDrawMs=${"%.2f".format(m.meanDrawMs)} maxDrawMs=${"%.2f".format(m.maxDrawMs)} hitches120=${m.estimatedHitches120Hz}")
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        writer.finish { error ->
            main.post {
                var failure = error
                if (failure == null) {
                    movie.delete()
                    movie.parentFile?.mkdirs()
                    if (!temporaryMovie.renameTo(movie)) {
                        failure = runCatching {
                            temporaryMovie.copyTo(movie, overwrite = true)
                            temporaryMovie.delete()
                        }.exceptionOrNull()
                    }
                }
                if (failure != null) temporaryMovie.delete()
                recordsAudio = writer.audioSampleCount.get() > 0
                this.writer = null
                surfaces.clear()
        releaseLastFrame()
                releaseLastFrame()
                completion(failure)
            }
        }
    }

    override fun cancel() {
        state = State.Finished
        startCompletion = null
        lastMetrics = pacer.metrics
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        writer?.cancel()
        writer = null
        surfaces.clear()
        temporaryMovie.delete()
    }

    /** The app left the foreground: frames stop, so the recording finishes there. */
    override fun appDidEnterBackground() {
        if (state != State.Starting && state != State.Recording) return
        if (startCompletion != null) failStart(RecorderError.notRecording) else onInterruption?.invoke()
    }

    private fun tick(frameTimeNs: Long) {
        val writer = writer
        if (state != State.Recording || writer == null) return
        Choreographer.getInstance().postFrameCallback(frameCallback)
        val now = System.nanoTime()
        host.lastTouchMoveNs?.let { pacer.noteInteractionMovement(it / 1e9) }
        host.refreshKeyboard()
        val interacting = host.isInteractingForCapture
        val decision = pacer.decide(
            linkTimestamp = frameTimeNs / 1e9,
            targetTimestamp = (frameTimeNs + frameIntervalNs) / 1e9,
            now = now / 1e9,
            isInteracting = interacting,
            writerReady = writer.isReadyForVideo,
        )
        if (decision !is FramePacer.Decision.Capture) {
            // iOS's CaptureSkip signpost event (cadence skips are routine and left out there too).
            if (decision is FramePacer.Decision.Skip && decision.reason != FramePacer.SkipReason.Cadence) {
                Log.d(TAG, "CaptureSkip ${decision.reason}")
            }
            return
        }
        val turnElapsed = max(0L, now - frameTimeNs) / 1e9
        val drawDuration = renderFrame(now) ?: return
        pacer.recordCapture(now / 1e9, drawDuration, turnElapsed, interacting)
        if (!capturedFirstFrame) {
            capturedFirstFrame = true
            completeStartIfReady()
        }
    }

    /** Draws every visible app window, back to front; returns the draw time in seconds. */
    private fun renderFrame(nowNs: Long): Double? {
        val writer = writer ?: return null
        if (state != State.Recording) return null
        val roots = recordedRoots()
        if (roots.isEmpty()) return null
        val t0 = System.nanoTime()
        val canvas: Canvas = try {
            writer.lockCanvas()
        } catch (e: Exception) {
            return null
        }
        // iOS's CaptureDraw and VideoAppend signpost intervals, as Perfetto/systrace sections.
        Trace.beginSection(if (host.isInteractingForCapture) "CaptureDraw interacting=1" else "CaptureDraw interacting=0")
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                // Recorded into a display list too, so the stop can append exactly this frame again.
                val node = (lastFrame as? android.graphics.RenderNode) ?: android.graphics.RenderNode("ScrawlFrame").also { lastFrame = it }
                node.setPosition(0, 0, writer.width, writer.height)
                val recording = node.beginRecording(writer.width, writer.height)
                try {
                    draw(recording, roots, writer.width, writer.height)
                } finally {
                    node.endRecording()
                }
                canvas.drawRenderNode(node)
            } else {
                draw(canvas, roots, writer.width, writer.height)
            }
        } finally {
            Trace.endSection()
            Trace.beginSection("VideoAppend")
            try {
                writer.post(canvas, nowNs)
            } finally {
                Trace.endSection()
            }
        }
        if (!capturedFirstFrame) startupTrace?.mark("firstCapture")
        return max(0L, System.nanoTime() - t0) / 1e9
    }

    private val surfaces = SurfaceContent()

    /** The last captured frame's display list (API 29+). */
    private var lastFrame: Any? = null

    private fun releaseLastFrame() {
        if (Build.VERSION.SDK_INT >= 29) (lastFrame as? android.graphics.RenderNode)?.discardDisplayList()
        lastFrame = null
    }

    /** Appends the last captured frame again at [nowNs]; false where there's none to repeat. */
    private fun repeatLastFrame(nowNs: Long): Boolean {
        if (Build.VERSION.SDK_INT < 29 || !capturedFirstFrame) return false
        val node = lastFrame as? android.graphics.RenderNode ?: return false
        val writer = writer ?: return false
        val canvas = runCatching { writer.lockCanvas() }.getOrNull() ?: return false
        canvas.drawRenderNode(node)
        writer.post(canvas, nowNs)
        return true
    }

    /** Sets up encoders off the main thread (iOS: a detached task). */
    private val writerThread = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "dev.kitlangton.scrawl.writer").apply { isDaemon = true }
    }

    private fun draw(canvas: Canvas, roots: List<Windows.Root>, width: Int, height: Int) {
        // The scene fits the movie (it only differs from the start if the device rotates).
        val focused = host.focusedRoot()
        val sceneW = focused?.width?.takeIf { it > 0 } ?: screenWidthPx
        val sceneH = focused?.height?.takeIf { it > 0 } ?: screenHeightPx
        val scale = min(width / sceneW.toFloat(), height / sceneH.toFloat())
        val offsetX = (width - sceneW * scale) / 2
        val offsetY = (height - sceneH * scale) / 2
        val origin = IntArray(2)
        focused?.getLocationOnScreen(origin)
        // No window can be trusted to paint every pixel.
        canvas.drawColor(Color.BLACK)
        val location = IntArray(2)
        for (root in roots) {
            val params = root.params
            if (params != null && params.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND != 0) {
                canvas.drawColor(Color.argb((params.dimAmount.coerceIn(0f, 1f) * 255).roundToInt(), 0, 0, 0))
            }
            root.view.getLocationOnScreen(location)
            canvas.save()
            canvas.translate(offsetX + (location[0] - origin[0]) * scale, offsetY + (location[1] - origin[1]) * scale)
            canvas.scale(scale, scale)
            runCatching { root.view.draw(canvas) }
            runCatching { surfaces.draw(canvas, root.view, scale) }
            canvas.restore()
        }
        // The keyboard is another process's window: a labelled panel stands in for it.
        host.keyboard.frame()?.let { keyboard ->
            val rect = RectF(
                offsetX + (keyboard.left - origin[0]) * scale,
                offsetY + (keyboard.top - origin[1]) * scale,
                offsetX + (keyboard.right - origin[0]) * scale,
                offsetY + (keyboard.bottom - origin[1]) * scale,
            )
            val density = (focused?.resources?.displayMetrics?.density ?: 1f) * scale
            KeyboardPanel.draw(canvas, rect, focused?.let(KeyboardPanel::isDark) ?: false, density)
        }
    }

    /** Every app window that may show something, back to front; never ScrawlKit's own. */
    private fun recordedRoots(): List<Windows.Root> =
        Windows.roots().filter { shouldRecord(it) }

    private fun shouldRecord(root: Windows.Root): Boolean {
        val view = root.view
        if (host.isOverlay(view)) return false
        if (!view.isAttachedToWindow || view.visibility != View.VISIBLE) return false
        if (view.width <= 0 || view.height <= 0 || view.alpha <= 0.01f) return false
        val params = root.params
        if (params != null && params.alpha <= 0.01f) return false
        return true
    }

    companion object {
        private const val TAG = "Scrawl"

        /** Movie pixels per dp: 1.5× (capped at [MAX_LONG_EDGE]). */
        const val PIXELS_PER_POINT = 1.5
        const val MAX_LONG_EDGE = 1600.0

        /** Average H.264 bitrate for recordings sent to a Mac. */
        const val VIDEO_BIT_RATE = 6_000_000

        /** For HTTPS endpoints, whose uploads must stay under 95 MB. */
        const val HOSTED_VIDEO_BIT_RATE = 3_000_000

        var lastMetrics: FramePacer.Metrics? = null
            private set

        fun canvasSize(
            width: Double,
            height: Double,
            pixelsPerPoint: Double = PIXELS_PER_POINT,
            maxLongEdge: Double = MAX_LONG_EDGE,
        ): Pair<Int, Int> {
            val longest = max(width, height)
            val scale = if (longest > 0) min(pixelsPerPoint, maxLongEdge / longest) else pixelsPerPoint
            return even(width * scale) to even(height * scale)
        }

        /** H.264 wants even dimensions. */
        private fun even(value: Double): Int = max(2, ((value / 2).roundToInt() * 2))
    }
}
