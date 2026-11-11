package dev.kitlangton.scrawl

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * A transparent activity that asks for one thing the app can't without an Activity — the microphone
 * permission, or MediaProjection consent — and hands the answer back.
 */
class ScrawlRequestActivity : Activity() {
    private var id = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        id = intent.getIntExtra(EXTRA_ID, -1)
        if (savedInstanceState != null) return
        when (intent.getStringExtra(EXTRA_KIND)) {
            KIND_MICROPHONE -> requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST)
            KIND_PROJECTION -> {
                val manager = getSystemService(MediaProjectionManager::class.java)
                // The whole screen, like ReplayKit (Android 14+ otherwise asks which app to share).
                val intent = if (Build.VERSION.SDK_INT >= 34) {
                    manager.createScreenCaptureIntent(android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay())
                } else {
                    manager.createScreenCaptureIntent()
                }
                @Suppress("DEPRECATION")
                startActivityForResult(intent, REQUEST)
            }
            else -> finishWith(null)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        finishWith(Answer(grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED, 0, null))
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        finishWith(Answer(resultCode == RESULT_OK && data != null, resultCode, data))
    }

    private fun finishWith(answer: Answer?) {
        pending.remove(id)?.invoke(answer ?: Answer(false, 0, null))
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) pending.remove(id)?.invoke(Answer(false, 0, null))
    }

    data class Answer(val granted: Boolean, val resultCode: Int, val data: Intent?)

    companion object {
        private const val EXTRA_ID = "dev.kitlangton.scrawl.request.id"
        private const val EXTRA_KIND = "dev.kitlangton.scrawl.request.kind"
        private const val KIND_MICROPHONE = "microphone"
        private const val KIND_PROJECTION = "projection"
        private const val REQUEST = 7301
        private val ids = AtomicInteger(1)
        private val pending = mutableMapOf<Int, (Answer) -> Unit>()

        private fun launch(context: Context, kind: String, completion: (Answer) -> Unit) {
            val id = ids.getAndIncrement()
            pending[id] = { answer -> Handler(Looper.getMainLooper()).post { completion(answer) } }
            val intent = Intent(context, ScrawlRequestActivity::class.java)
                .putExtra(EXTRA_ID, id)
                .putExtra(EXTRA_KIND, kind)
            if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }

        fun requestMicrophone(context: Context, completion: (Boolean) -> Unit) =
            launch(context, KIND_MICROPHONE) { completion(it.granted) }

        fun requestProjection(context: Context, completion: (Answer) -> Unit) =
            launch(context, KIND_PROJECTION, completion)
    }
}

/** The foreground service Android requires while MediaProjection captures the screen. */
class ScrawlProjectionService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Scrawl recording", NotificationManager.IMPORTANCE_LOW))
        }
        val notification = (if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this))
            .setContentTitle("Scrawl is recording")
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(ID, notification)
        }
        started?.invoke()
        started = null
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL = "dev.kitlangton.scrawl.recording"
        private const val ID = 7302
        private var started: (() -> Unit)? = null

        fun start(context: Context, onStarted: () -> Unit) {
            started = onStarted
            val intent = Intent(context, ScrawlProjectionService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ScrawlProjectionService::class.java))
        }
    }
}

/**
 * MediaProjection's screen recording, the ReplayKit counterpart: the exact pixels on screen,
 * overlay and keyboard included, behind Android's consent dialog (asked every recording).
 */
internal class ProjectionRecorder(
    private val context: Context,
    private val host: CaptureHost,
    private val launcher: () -> Context,
    private val videoBitRate: Int,
    private val temporaryDirectory: File,
) : ScrawlRecorder {
    override var startupTrace: StartupTrace? = null
    override var onInterruption: (() -> Unit)? = null
    override val method = ScrawlCaptureMethod.MediaProjection
    override val isAvailable: Boolean get() = Build.VERSION.SDK_INT >= 26
    override var recordsAudio = false
        private set

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var recorder: MediaRecorder? = null
    private var completion: ((Result<RecordingStart>) -> Unit)? = null
    private var cancelled = false
    private var recording = false
    private val temporaryMovie = File(temporaryDirectory, "scrawl-${java.util.UUID.randomUUID()}.mp4")
    private val main = Handler(Looper.getMainLooper())

    override fun start(microphone: Boolean, completion: (Result<RecordingStart>) -> Unit) {
        this.completion = completion
        startupTrace?.mark("permissionBegin")
        val afterMicrophone: (Boolean) -> Unit = { audio ->
            ScrawlRequestActivity.requestProjection(launcher()) { answer ->
                startupTrace?.mark("permissionEnd")
                if (cancelled) return@requestProjection
                val data = answer.data
                if (!answer.granted || data == null) {
                    fail(RecorderError("Screen capture wasn't allowed"))
                    return@requestProjection
                }
                ScrawlProjectionService.start(context) { main.post { begin(answer.resultCode, data, audio) } }
            }
        }
        when {
            !microphone -> afterMicrophone(false)
            host.hasMicrophonePermission() -> afterMicrophone(true)
            else -> host.requestMicrophonePermission { afterMicrophone(it) }
        }
    }

    private fun begin(resultCode: Int, data: Intent, audio: Boolean) {
        if (cancelled) {
            ScrawlProjectionService.stop(context); return
        }
        try {
            val manager = context.getSystemService(MediaProjectionManager::class.java)
            val projection = manager.getMediaProjection(resultCode, data) ?: throw RecorderError("No projection")
            this.projection = projection
            projection.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    main.post { if (recording) onInterruption?.invoke() }
                }
            }, main)
            val metrics = context.resources.displayMetrics
            val (w, h) = AppFrameRecorder.canvasSize(
                metrics.widthPixels / metrics.density.toDouble(),
                metrics.heightPixels / metrics.density.toDouble(),
                pixelsPerPoint = metrics.density.toDouble(),
                maxLongEdge = 1920.0,
            )
            temporaryDirectory.mkdirs()
            @Suppress("DEPRECATION")
            val recorder = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()
            if (audio) recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setOutputFile(temporaryMovie.path)
            recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            recorder.setVideoSize(w, h)
            recorder.setVideoFrameRate(30)
            recorder.setVideoEncodingBitRate(videoBitRate)
            if (audio) {
                recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                recorder.setAudioSamplingRate(MovieWriter.SAMPLE_RATE)
                recorder.setAudioChannels(1)
                recorder.setAudioEncodingBitRate(MovieWriter.AUDIO_BIT_RATE)
            }
            recorder.prepare()
            this.recorder = recorder
            display = projection.createVirtualDisplay(
                "Scrawl", w, h, metrics.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, recorder.surface, null, null,
            )
            recorder.start()
            recordsAudio = audio
            recording = true
            val completion = completion
            this.completion = null
            completion?.invoke(Result.success(RecordingStart.now()))
        } catch (e: Exception) {
            fail(e)
        }
    }

    private fun fail(error: Throwable) {
        val completion = completion
        this.completion = null
        tearDown()
        temporaryMovie.delete()
        completion?.invoke(Result.failure(error))
    }

    override fun stop(movie: File, completion: (Throwable?) -> Unit) {
        val recorder = recorder
        if (!recording || recorder == null) {
            completion(RecorderError.notRecording); return
        }
        recording = false
        val error = runCatching { recorder.stop() }.exceptionOrNull()
        tearDown()
        var failure = error
        if (failure == null) {
            movie.delete()
            movie.parentFile?.mkdirs()
            if (!temporaryMovie.renameTo(movie)) {
                failure = runCatching { temporaryMovie.copyTo(movie, true); temporaryMovie.delete() }.exceptionOrNull()
            }
        }
        if (failure != null) temporaryMovie.delete()
        completion(failure)
    }

    override fun cancel() {
        cancelled = true
        completion = null
        recording = false
        runCatching { recorder?.stop() }
        tearDown()
        temporaryMovie.delete()
    }

    /**
     * Like ReplayKit's in-app recording, which goes on but captures nothing while the app is in the
     * background: the projection (which would see other apps) is detached from the encoder until
     * the app comes back. While the consent dialog is up (starting) the app is only covered.
     */
    override fun appDidEnterBackground() {
        if (recording) runCatching { display?.surface = null }
    }

    override fun appWillEnterForeground() {
        if (recording) runCatching { display?.surface = recorder?.surface }
    }

    private fun tearDown() {
        runCatching { recorder?.release() }
        recorder = null
        runCatching { display?.release() }
        display = null
        runCatching { projection?.stop() }
        projection = null
        ScrawlProjectionService.stop(context)
    }
}
