package dev.kitlangton.scrawl

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import android.os.HandlerThread
import android.view.Surface
import java.io.File
import kotlin.math.max
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * The encoders and muxer behind an app-frame recording. Frames are drawn on the main thread into
 * the video encoder's input [surface]; encoding, audio, and muxing run on background threads.
 *
 * Video and audio are both stamped on `CLOCK_MONOTONIC` (`System.nanoTime`), and the movie starts
 * at [startNs]: the first video frame is pinned to t=0 and audio from before it is dropped, so movie
 * time 0 is the timeline's t=0.
 */
internal class MovieWriter(
    private val file: File,
    val width: Int,
    val height: Int,
    private val videoBitRate: Int,
    private val startNs: Long,
) {
    private val surface: Surface
    private val video: MediaCodec
    private val videoThread = HandlerThread("dev.kitlangton.scrawl.video").apply { start() }
    private val muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private val lock = Object()
    private var videoTrack = -1
    private var audioTrack = -1
    private var muxerStarted = false

    /** Null until the microphone has either started (true) or been given up on (false). */
    private var expectsAudio: Boolean? = null
    private val pending = mutableListOf<Triple<Boolean, ByteArray, MediaCodec.BufferInfo>>()
    private var videoDone = false
    private var audioDone = true
    private var finishCallback: ((Throwable?) -> Unit)? = null
    private var failure: Throwable? = null
    private val framesPosted = AtomicInteger(0)
    private val framesEncoded = AtomicInteger(0)
    private var firstVideo = true
    val videoSampleCount = AtomicInteger(0)
    val audioSampleCount = AtomicInteger(0)
    private var microphone: Microphone? = null

    /** The encoder settings asked for (after any profile fallback). */
    val videoFormat: MediaFormat

    init {
        // (MediaFormat.removeKey is API 29: build the format with or without the profile instead.)
        fun format(high: Boolean) = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, videoBitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, FRAMES_PER_SECOND)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            if (high) {
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
                setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel41)
            }
            setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
            setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
            // Before API 29 there's no key for it; encoders there default to no B-frames.
            if (Build.VERSION.SDK_INT >= 29) setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
        }
        val choice = EncoderChoice.pick(EncoderChoice.available())
        video = choice?.let { runCatching { MediaCodec.createByCodecName(it.name) }.getOrNull() }
            ?: MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        // High where the encoder has it (phones' hardware encoders); software encoders, like an
        // emulator's, may accept the key and quietly write Baseline, so don't ask them.
        val high = runCatching {
            video.codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).profileLevels
                .any { it.profile == MediaCodecInfo.CodecProfileLevel.AVCProfileHigh }
        }.getOrDefault(false)
        var format = format(high)
        try {
            video.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (e: Exception) {
            // Some encoders reject High profile or an explicit level: fall back to their defaults.
            video.reset()
            format = format(false)
            video.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
        videoFormat = format
        video.setCallback(
            object : MediaCodec.Callback() {
                override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit
                override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) =
                    drainVideo(codec, index, info)

                override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) = fail(e)
                override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) = addTrack(video = true, format)
            },
            Handler(videoThread.looper),
        )
        surface = video.createInputSurface()
        video.start()
    }

    /** Whether a frame can be drawn now without waiting on the encoder. */
    val isReadyForVideo: Boolean get() = framesPosted.get() - framesEncoded.get() < MAX_FRAMES_IN_FLIGHT && !videoDone

    /** A hardware canvas on the encoder's input surface for the next frame. */
    fun lockCanvas(): android.graphics.Canvas = surface.lockHardwareCanvas()

    /** Hands the frame drawn on [canvas] (from [lockCanvas]) to the encoder, captured at [captureNs]. */
    fun post(canvas: android.graphics.Canvas, captureNs: Long) {
        surface.unlockCanvasAndPost(canvas)
        captureTimes.add(captureNs)
        framesPosted.incrementAndGet()
    }

    /** When each posted frame was captured, in order. Frames are stamped with these, as iOS stamps
     * appended pixel buffers, not with when the hardware canvas finished queueing them (which can
     * trail by a GPU frame or more). */
    private val captureTimes = java.util.concurrent.ConcurrentLinkedQueue<Long>()
    private var lastPtsUs = -1L

    /** Starts the microphone into this movie; [completion] says whether it's running. */
    fun startAudio(trace: StartupTrace? = null, completion: (Boolean) -> Unit) {
        val mic = Microphone(this, trace)
        microphone = mic
        mic.start { running ->
            synchronized(lock) {
                expectsAudio = running
                audioDone = !running
                if (!running) microphone = null
                maybeStartMuxer()
            }
            completion(running)
        }
    }

    fun noAudio() {
        synchronized(lock) {
            expectsAudio = false
            maybeStartMuxer()
        }
    }

    /** Ends both streams (the caller has just drawn the last frame) and finishes the file. */
    fun finish(completion: (Throwable?) -> Unit) {
        synchronized(lock) {
            finishCallback = completion
            if (expectsAudio == null) expectsAudio = false
        }
        microphone?.stop()
        // The last frame was drawn through a hardware canvas, which reaches the encoder
        // asynchronously: end the stream once it's been encoded (or after a short wait for encoders
        // that hold a frame back), or it's dropped and the movie ends before the stop instant.
        val handler = Handler(videoThread.looper)
        val deadline = SystemClock.uptimeMillis() + LAST_FRAME_WAIT_MS
        fun endWhenDrained() {
            if (framesEncoded.get() < framesPosted.get() && SystemClock.uptimeMillis() < deadline) {
                handler.postDelayed(::endWhenDrained, 5)
                return
            }
            runCatching { video.signalEndOfInputStream() }.onFailure { fail(it) }
        }
        handler.post(::endWhenDrained)
    }

    fun cancel() {
        microphone?.stop()
        microphone = null
        runCatching { video.stop() }
        runCatching { video.release() }
        runCatching { surface.release() }
        runCatching { muxer.release() }
        videoThread.quitSafely()
        file.delete()
    }

    // Muxing

    private fun addTrack(video: Boolean, format: MediaFormat) {
        synchronized(lock) {
            if (muxerStarted) return
            if (video) videoTrack = muxer.addTrack(format) else audioTrack = muxer.addTrack(format)
            maybeStartMuxer()
        }
    }

    /** Under [lock]. */
    private fun maybeStartMuxer() {
        if (muxerStarted || videoTrack < 0) return
        val audio = expectsAudio ?: return
        if (audio && audioTrack < 0) return
        muxer.start()
        muxerStarted = true
        for ((isVideo, bytes, info) in pending) write(isVideo, ByteBuffer.wrap(bytes), info)
        pending.clear()
        checkDone()
    }

    /** Under [lock]. */
    private fun write(isVideo: Boolean, buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (!muxerStarted) {
            val bytes = ByteArray(info.size)
            buffer.position(info.offset)
            buffer.get(bytes)
            val copy = MediaCodec.BufferInfo().apply { set(0, info.size, info.presentationTimeUs, info.flags) }
            pending += Triple(isVideo, bytes, copy)
            return
        }
        muxer.writeSampleData(if (isVideo) videoTrack else audioTrack, buffer, info)
        if (isVideo) videoSampleCount.incrementAndGet() else audioSampleCount.incrementAndGet()
    }

    private fun drainVideo(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
        try {
            val buffer = codec.getOutputBuffer(index)
            val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
            if (buffer != null && info.size > 0 && !config) {
                framesEncoded.incrementAndGet()
                val captureNs = captureTimes.poll() ?: info.presentationTimeUs * 1000
                val ptsUs = if (firstVideo) 0L else max(lastPtsUs + 1, (captureNs - startNs) / 1000)
                lastPtsUs = ptsUs
                firstVideo = false
                val stamped = MediaCodec.BufferInfo().apply { set(info.offset, info.size, ptsUs, info.flags) }
                synchronized(lock) { write(true, buffer, stamped) }
            }
            codec.releaseOutputBuffer(index, false)
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                synchronized(lock) {
                    videoDone = true
                    checkDone()
                }
            }
        } catch (e: Exception) {
            fail(e)
        }
    }

    /** From [Microphone]'s thread: an encoded AAC sample, stamped in monotonic nanoseconds. */
    fun writeAudio(buffer: ByteBuffer, info: MediaCodec.BufferInfo, monoNs: Long) {
        if (monoNs < startNs) return
        val stamped = MediaCodec.BufferInfo().apply { set(info.offset, info.size, (monoNs - startNs) / 1000, info.flags) }
        synchronized(lock) { write(false, buffer, stamped) }
    }

    fun addAudioTrack(format: MediaFormat) = addTrack(video = false, format)

    fun audioEnded() {
        synchronized(lock) {
            audioDone = true
            checkDone()
        }
    }

    /** Under [lock]. */
    private fun checkDone() {
        val done = finishCallback ?: return
        if (!videoDone || !audioDone || !muxerStarted) {
            if (videoDone && audioDone && !muxerStarted) {
                // Never got a format: nothing was encoded.
                failure = failure ?: RecorderError.cannotWrite("no frames were recorded")
            } else {
                return
            }
        }
        finishCallback = null
        var error = failure
        if (error == null && videoSampleCount.get() == 0) error = RecorderError.cannotWrite("no frames were recorded")
        runCatching { if (muxerStarted) muxer.stop() }.onFailure { if (error == null) error = it }
        runCatching { muxer.release() }
        runCatching { video.stop() }
        runCatching { video.release() }
        runCatching { surface.release() }
        videoThread.quitSafely()
        done(error)
    }

    private fun fail(e: Throwable) {
        synchronized(lock) {
            failure = failure ?: e
            videoDone = true
            audioDone = true
            if (finishCallback != null) checkDone()
        }
    }

    companion object {
        const val FRAMES_PER_SECOND = 30
        const val AUDIO_BIT_RATE = 96_000
        const val LAST_FRAME_WAIT_MS = 300L
        const val SAMPLE_RATE = 48_000
        private const val MAX_FRAMES_IN_FLIGHT = 3
    }
}

/**
 * The microphone through `AudioRecord` (48 kHz mono PCM, resampled by the system on any route) into
 * an AAC encoder. Buffers are stamped from `AudioRecord.getTimestamp` on the monotonic clock and run
 * on contiguously, resyncing only after a real gap; a buffer more than 50 ms early is dropped. No
 * audio focus is requested, so it mixes with whatever else is playing.
 */
internal class Microphone(private val writer: MovieWriter, private val trace: StartupTrace? = null) {
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null
    private val nextNs = AtomicLong(Long.MIN_VALUE)

    @SuppressLint("MissingPermission")
    fun start(completion: (Boolean) -> Unit) {
        thread = Thread({ run(completion) }, "dev.kitlangton.scrawl.microphone").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    fun stop() {
        running.set(false)
    }

    @SuppressLint("MissingPermission")
    private fun run(completion: (Boolean) -> Unit) {
        val rate = MovieWriter.SAMPLE_RATE
        val minBuffer = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        fun open(): AudioRecord? = runCatching {
            AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer * 4, 8192))
        }.getOrNull()?.takeIf { it.state == AudioRecord.STATE_INITIALIZED }
        // The stages iOS marks around its audio session and engine.
        trace?.mark("audioSessionBegin")
        val first = open()
        if (first != null) trace?.mark("audioSessionEnd")
        if (first == null) {
            completion(false)
            return
        }
        var record: AudioRecord = first
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, MovieWriter.AUDIO_BIT_RATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        trace?.mark("engineBegin")
        val encoder = runCatching {
            MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }
        }.getOrNull()
        if (encoder == null) {
            record.release()
            completion(false)
            return
        }
        try {
            record.startRecording()
        } catch (e: Exception) {
            encoder.release(); record.release()
            completion(false)
            return
        }
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            encoder.release(); record.release()
            completion(false)
            return
        }
        trace?.mark("engineEnd")
        trace?.mark("microphoneReady")
        running.set(true)
        completion(true)
        val pcm = ByteArray(2048 * 2)
        val info = MediaCodec.BufferInfo()
        val stamp = AudioTimestamp()
        var framesRead = 0L
        var restarts = 0
        val sampleNs = 1_000_000_000.0 / rate
        var eosSent = false
        try {
            while (true) {
                val stopping = !running.get()
                if (!eosSent) {
                    val index = encoder.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val input = encoder.getInputBuffer(index)!!
                        if (stopping) {
                            encoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            eosSent = true
                        } else {
                            var n = record.read(pcm, 0, minOf(pcm.size, input.remaining()))
                            if (failReadsForTesting > 0 && n > 0) {
                                failReadsForTesting -= 1
                                n = AudioRecord.ERROR_DEAD_OBJECT
                            }
                            if (n < 0 && restarts < MAX_RESTARTS) {
                                // The input went away (a route change, another app took the mic):
                                // start a new recorder and resync after the gap, as iOS restarts
                                // its engine.
                                restarts += 1
                                restartsForTesting += 1
                                runCatching { record.stop() }
                                record.release()
                                Thread.sleep(200)
                                record = open() ?: break
                                runCatching { record.startRecording() }
                                framesRead = 0
                                nextNs.set(Long.MIN_VALUE)
                                n = 0
                            }
                            if (n > 0) {
                                // Where this buffer's first frame was captured, on the monotonic clock.
                                val measuredNs = if (record.getTimestamp(stamp, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS) {
                                    stamp.nanoTime + ((framesRead - stamp.framePosition) * sampleNs).toLong()
                                } else {
                                    System.nanoTime() - ((n / 2) * sampleNs).toLong()
                                }
                                val expected = nextNs.get()
                                // Contiguous unless there was a real gap; drop buffers that are early.
                                val ptsNs = stamp(expected, measuredNs)
                                framesRead += n / 2
                                nextNs.set(ptsNs + ((n / 2) * sampleNs).toLong())
                                input.clear()
                                input.put(pcm, 0, n)
                                encoder.queueInputBuffer(index, 0, n, ptsNs / 1000, 0)
                            } else {
                                encoder.queueInputBuffer(index, 0, 0, 0, 0)
                            }
                        }
                    }
                }
                var out = encoder.dequeueOutputBuffer(info, 0)
                while (out >= 0) {
                    val buffer = encoder.getOutputBuffer(out)
                    if (buffer != null && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        writer.writeAudio(buffer, info, info.presentationTimeUs * 1000)
                    }
                    encoder.releaseOutputBuffer(out, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    out = encoder.dequeueOutputBuffer(info, 0)
                }
                if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    writer.addAudioTrack(encoder.outputFormat)
                }
            }
        } catch (e: Exception) {
            // The microphone went away mid-recording: the movie keeps what it has.
        } finally {
            runCatching { record.stop() }
            record.release()
            runCatching { encoder.stop() }
            encoder.release()
            writer.audioEnded()
        }
    }

    companion object {
        private const val TOLERANCE_NS = 50_000_000L
        private const val MAX_RESTARTS = 5

        /** Tests make the next reads fail as if the input went away. */
        @Volatile internal var failReadsForTesting = 0
        @Volatile internal var restartsForTesting = 0

        /**
         * A buffer's presentation time: contiguous with the last one ([expected]) unless the
         * measured time is a real gap (late by more than the tolerance) or far early, which resyncs.
         */
        fun stamp(expected: Long, measured: Long): Long = when {
            expected == Long.MIN_VALUE -> measured
            measured - expected > TOLERANCE_NS -> measured
            expected - measured > TOLERANCE_NS * 4 -> measured
            else -> expected
        }
    }
}

/** Which H.264 encoder records: like AVAssetWriter on iOS, hardware High profile when there is one. */
internal object EncoderChoice {
    data class Encoder(val name: String, val hardware: Boolean, val high: Boolean)

    /**
     * Hardware with High, then any with High; otherwise null keeps the platform's default encoder.
     * Before Android 10, Codec2 (`c2.*`) encoders were experimental (an emulator image can force
     * them on, and Android 9's software one aborts on surface input), so an OMX encoder is chosen.
     */
    fun pick(encoders: List<Encoder>, sdk: Int = Build.VERSION.SDK_INT): Encoder? {
        if (sdk < 29) {
            val omx = encoders.filter { it.name.startsWith("OMX.") }
            return omx.firstOrNull { it.hardware && it.high } ?: omx.firstOrNull { it.high }
                ?: omx.firstOrNull { it.hardware } ?: omx.firstOrNull()
        }
        return encoders.firstOrNull { it.hardware && it.high } ?: encoders.firstOrNull { it.high }
    }

    fun available(): List<Encoder> = runCatching {
        android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { it.isEncoder && it.supportedTypes.any { t -> t.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true) } }
            .map { info ->
                val high = runCatching {
                    info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).profileLevels
                        .any { it.profile == MediaCodecInfo.CodecProfileLevel.AVCProfileHigh }
                }.getOrDefault(false)
                val hardware = if (Build.VERSION.SDK_INT >= 29) info.isHardwareAccelerated && !info.isSoftwareOnly
                else !info.name.startsWith("OMX.google.") && !info.name.startsWith("c2.android.")
                Encoder(info.name, hardware, high)
            }
    }.getOrDefault(emptyList())
}
