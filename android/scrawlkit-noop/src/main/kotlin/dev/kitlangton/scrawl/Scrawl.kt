package dev.kitlangton.scrawl

import android.app.Application
import android.view.View
import java.io.File
import java.util.UUID

/*
 * ScrawlKit's public API with nothing behind it, for release builds:
 *
 *     debugImplementation("dev.kitlangton:scrawlkit")
 *     releaseImplementation("dev.kitlangton:scrawlkit-noop")
 *
 * Calls compile and do nothing: no permissions, activities, services, network, or files. (iOS has
 * no such split: ScrawlKit there is gated by `#if os(iOS)` and stays inert until paired; this module
 * lets Android release builds leave the permissions and components out of the manifest entirely.)
 */

enum class ScrawlTrigger { ThreeFingerLongPress, None }

enum class ScrawlCaptureMethod(val raw: String) { AppFrames("appFrames"), MediaProjection("mediaProjection") }

data class ScrawlStartupEvent(val stage: String, val milliseconds: Double, val onMainThread: Boolean)

data class RecordingStart(val wallMs: Long, val monoNs: Long)

class Scrawl private constructor() {
    data class Recording(val movie: File, val timeline: File)

    val isRecording = false
    val isDrawing = false
    val isStarting = false
    val isDiscardPending = false
    val startedAt: RecordingStart? = null
    val markers: List<Double> = emptyList()
    val recordings: List<Recording> = emptyList()
    var narration = true
    var captureMethod = ScrawlCaptureMethod.AppFrames
    var suppressesSystemEditingGestures = true
    var sharesWhenDone = true
    var trigger = ScrawlTrigger.ThreeFingerLongPress
    var serverURLs: List<String> = emptyList()
    var token: String? = null
    var sessionID: String? = null

    @Deprecated("The stroke being drawn isn't published any more.")
    val activeStroke: List<android.graphics.PointF> = emptyList()
    val isConfigured = false
    var startupObserver: ((ScrawlStartupEvent) -> Unit)? = null
    var timelineSupplement: (suspend (org.json.JSONObject) -> Map<String, Any?>)? = null
    var onRecordingSubmitted: (suspend (timeline: File, movie: File) -> Boolean)? = null

    fun toggle() = Unit
    fun start() = Unit
    fun stop() = Unit
    fun discard() = Unit
    fun mark() = Unit
    fun toggleDrawing() = Unit
    fun prepareToStart() = Unit
    fun setTeamEndpoint(url: String?, token: String?) = Unit
    fun handleSetupURL(url: String): Boolean = false
    fun addConfigurationListener(listener: () -> Unit) = Unit

    companion object {
        private val instance = Scrawl()

        @JvmStatic
        val shared: Scrawl get() = instance
        val isConfigured: Boolean get() = false

        @JvmStatic
        @JvmOverloads
        fun install(application: Application, trigger: ScrawlTrigger = ScrawlTrigger.ThreeFingerLongPress, isEnabled: Boolean = true): Scrawl = instance

        fun handleSetupURL(url: String): Boolean = false

        @JvmStatic
        fun event(label: String) = Unit

        @JvmStatic
        fun screen(name: String?) = Unit

        @JvmStatic
        fun context(key: String, value: String?) = Unit

        @JvmStatic
        fun setScreen(name: String?, token: UUID, promote: Boolean) = Unit

        fun hostedRecordingLimitMs(method: ScrawlCaptureMethod): Long = 0
    }
}

class ScrawlScreen private constructor() {
    fun update(name: String) = Unit

    companion object {
        fun attach(view: View, name: String): ScrawlScreen = ScrawlScreen()
    }
}

class ScrawlContextValue private constructor() {
    fun update(key: String, value: String?) = Unit

    companion object {
        fun attach(view: View, key: String, value: String?): ScrawlContextValue = ScrawlContextValue()
    }
}
