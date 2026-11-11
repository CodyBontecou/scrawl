package dev.kitlangton.scrawl

import android.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Whether this device can encode H.264 from a Surface at all. Android 9's emulator image forces its
 * experimental Codec2 software encoder, which aborts on surface input; Android's own `screenrecord`
 * fails there the same way. Movie tests are skipped (not passed) on such a device.
 */
object EncoderProbe {
    val canEncodeFromSurface: Boolean by lazy {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        runCatching {
            val file = File(app.cacheDir, "probe.mp4")
            val writer = MovieWriter(file, 128, 128, 1_000_000, System.nanoTime())
            repeat(3) {
                val canvas = writer.lockCanvas()
                canvas.drawColor(Color.RED)
                writer.post(canvas, System.nanoTime())
                Thread.sleep(40)
            }
            writer.noAudio()
            val done = CountDownLatch(1)
            var error: Throwable? = null
            writer.finish { error = it; done.countDown() }
            done.await(8, TimeUnit.SECONDS) && error == null && file.length() > 0
        }.getOrDefault(false)
    }

    fun assume() = assumeTrue("this device's H.264 encoder can't take surface input (Android's screenrecord fails too)", canEncodeFromSurface)
}
