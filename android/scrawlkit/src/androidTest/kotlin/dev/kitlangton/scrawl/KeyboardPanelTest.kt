package dev.kitlangton.scrawl

import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.os.SystemClock
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * The soft keyboard is another process's window, so app-frame recordings draw a labelled panel
 * where it is. This records a real movie with a keyboard frame and reads the pixels back.
 */
@RunWith(AndroidJUnit4::class)
class KeyboardPanelTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun theKeyboardIsDrawnAsALabelledPanelInTheMovie() {
        EncoderProbe.assume()
        val scenario = ActivityScenario.launch(TestHostActivity::class.java)
        lateinit var decor: View
        scenario.onActivity { decor = it.window.decorView.apply { setBackgroundColor(Color.WHITE) } }
        val context = ScrawlAppContext { null }
        val keyboard = KeyboardTracker(context) { false }
        val imePx = 800
        val host = object : CaptureHost {
            override val lastTouchMoveNs: Long? = null
            override val isInteractingForCapture = false
            override fun isOverlay(root: View) = false
            override val keyboard = keyboard

            // Stands in for a real IME: the tracker keeps the frame given below.
            override fun refreshKeyboard() = Unit
            override fun focusedRoot(): View = decor
            override fun hasMicrophonePermission() = false
            override fun requestMicrophonePermission(completion: (Boolean) -> Unit) = completion(false)
        }
        val app = instrumentation.targetContext
        val recorder = AppFrameRecorder(host, AppFrameRecorder.VIDEO_BIT_RATE, File(app.cacheDir, "ScrawlTempTest"))
        val started = CountDownLatch(1)
        instrumentation.runOnMainSync {
            keyboard.setTracking(true)
            val loc = IntArray(2).also { decor.getLocationOnScreen(it) }
            // A keyboard that has finished sliding up (start far in the past).
            keyboard.update(imePx, loc[0].toFloat(), (loc[0] + decor.width).toFloat(), (loc[1] + decor.height).toFloat(), 0)
            recorder.start(false) { started.countDown() }
        }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        SystemClock.sleep(1_200)
        val movie = File(app.cacheDir, "keyboard-panel.mp4")
        val stopped = CountDownLatch(1)
        var error: Throwable? = null
        instrumentation.runOnMainSync { recorder.stop(movie) { error = it; stopped.countDown() } }
        assertTrue(stopped.await(20, TimeUnit.SECONDS))
        assertNull(error)

        val retriever = MediaMetadataRetriever().apply { setDataSource(movie.path) }
        val frame = retriever.getFrameAtTime(600_000, MediaMetadataRetriever.OPTION_CLOSEST)!!
        retriever.release()
        val w = frame.width
        val h = frame.height
        val panelTop = h - (imePx.toFloat() / decor.height * h).toInt()
        fun near(c: Int, r: Int, g: Int, b: Int, tolerance: Int = 24) =
            abs(Color.red(c) - r) <= tolerance && abs(Color.green(c) - g) <= tolerance && abs(Color.blue(c) - b) <= tolerance

        // Above the keyboard: the app (white). Inside it, away from the label: the panel's grey.
        val above = frame.getPixel(w / 2, panelTop - 40)
        assertTrue("app above, was ${Integer.toHexString(above)} (frame ${w}x$h, top ${Integer.toHexString(frame.getPixel(w / 2, h / 4))})", near(above, 255, 255, 255))
        val panel = frame.getPixel(w / 10, (panelTop + h) / 2)
        assertTrue("panel background, was ${Integer.toHexString(panel)}", near(panel, 209, 213, 219))
        // The "Keyboard" label and glyph: noticeably darker pixels along the panel's centre line.
        val y = (panelTop + h) / 2
        val dark = (w / 4 until 3 * w / 4).count { x -> Color.red(frame.getPixel(x, y)) < 170 }
        assertTrue("label pixels on the centre line: $dark", dark > 6)
        scenario.close()
    }
}
