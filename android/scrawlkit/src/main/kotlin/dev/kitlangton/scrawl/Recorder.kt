package dev.kitlangton.scrawl

import android.os.Looper
import android.os.SystemClock
import java.io.File

/** How ScrawlKit records the screen. */
enum class ScrawlCaptureMethod(val raw: String) {
    /**
     * Draws the app's own windows into the movie ([AppFrameRecorder]). No screen-capture consent;
     * the microphone asks once. The strip and ink stay out of the video; the soft keyboard shows as
     * a labelled panel (it's another process's window); SurfaceView/TextureView content (video
     * players, camera previews, maps) may not appear.
     */
    AppFrames("appFrames"),

    /**
     * MediaProjection screen capture: the exact pixels on screen, overlay and keyboard included,
     * behind Android's "Start recording or casting?" consent (the ReplayKit counterpart).
     */
    MediaProjection("mediaProjection"),
}

/** The instant a movie's first frame was taken: wall clock (for `recordedAt`) and monotonic. */
data class RecordingStart(val wallMs: Long, val monoNs: Long) {
    companion object {
        fun now() = RecordingStart(System.currentTimeMillis(), System.nanoTime())
    }
}

/**
 * Something that records the app into a movie, on the clock the timeline uses: t=0 is the movie's
 * first frame. All calls and callbacks are on the main thread.
 */
interface ScrawlRecorder {
    var startupTrace: StartupTrace?
    val method: ScrawlCaptureMethod
    val isAvailable: Boolean

    /** Whether the finished movie has the microphone in it. Read after [stop]. */
    val recordsAudio: Boolean

    /** Called when the recorder can't go on (the app went to the background). */
    var onInterruption: (() -> Unit)?

    /** Starts recording. [completion] gets the instant of the movie's first frame, or an error. */
    fun start(microphone: Boolean, completion: (Result<RecordingStart>) -> Unit)

    /** Stops and writes the movie to [movie] (replacing any file there). */
    fun stop(movie: File, completion: (Throwable?) -> Unit)

    /** Stops and throws the recording away. */
    fun cancel()

    /** The app left the foreground: stop (as a send) or fail a start in progress, or pause. */
    fun appDidEnterBackground()

    /** The app came back to the foreground (a paused recorder resumes). */
    fun appWillEnterForeground() = Unit
}

class RecorderError(message: String) : Exception(message) {
    companion object {
        val noScene = RecorderError("No active window to record")
        val notRecording = RecorderError("Not recording")
        fun cannotWrite(reason: String) = RecorderError("Could not write the movie: $reason")
    }
}

/** Opt-in startup evidence. Only fixed stage names and monotonic timings, never capture content. */
data class ScrawlStartupEvent(val stage: String, val milliseconds: Double, val onMainThread: Boolean)

class StartupTrace(private val receive: (ScrawlStartupEvent) -> Unit) {
    private val start = SystemClock.elapsedRealtimeNanos()
    private val recorded = mutableSetOf<String>()

    init {
        mark("recognized")
    }

    fun mark(stage: String) {
        val first = synchronized(recorded) { recorded.add(stage) }
        if (!first) return
        val ms = (SystemClock.elapsedRealtimeNanos() - start) / 1e6
        // iOS's ScrawlStartup signpost event.
        android.util.Log.d("Scrawl", "ScrawlStartup stage=$stage ms=${"%.3f".format(ms)}")
        receive(ScrawlStartupEvent(stage, ms, Looper.myLooper() == Looper.getMainLooper()))
    }
}
