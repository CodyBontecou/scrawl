package dev.kitlangton.scrawl

import android.graphics.Color
import android.graphics.SurfaceTexture
import android.media.MediaMetadataRetriever
import android.os.SystemClock
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
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
 * Video players, cameras and maps draw into SurfaceViews and TextureViews, which a window's own
 * drawing leaves out. iOS's `drawHierarchy` snapshot includes such layers, so app-frame movies must
 * too: this records a red SurfaceView with a green badge over it and a blue TextureView.
 */
@RunWith(AndroidJUnit4::class)
class SurfaceCaptureTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun surfaceAndTextureViewsAppearInTheMovie() {
        EncoderProbe.assume()
        val scenario = ActivityScenario.launch(TestHostActivity::class.java)
        val ready = CountDownLatch(2)
        lateinit var decor: View
        scenario.onActivity { activity ->
            decor = activity.window.decorView.apply { setBackgroundColor(Color.WHITE) }
            val column = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            val top = FrameLayout(activity)
            val surface = SurfaceView(activity)
            surface.holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    holder.lockCanvas()?.let { it.drawColor(Color.RED); holder.unlockCanvasAndPost(it) }
                    ready.countDown()
                }
                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                    holder.lockCanvas()?.let { it.drawColor(Color.RED); holder.unlockCanvasAndPost(it) }
                }
                override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
            })
            top.addView(surface, FrameLayout.LayoutParams(-1, -1))
            val badge = View(activity).apply { setBackgroundColor(Color.GREEN) }
            top.addView(badge, FrameLayout.LayoutParams(200, 200, Gravity.CENTER))
            column.addView(top, LinearLayout.LayoutParams(-1, 0, 1f))
            column.addView(View(activity), LinearLayout.LayoutParams(-1, 0, 0.2f))
            val texture = TextureView(activity)
            texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                    texture.lockCanvas()?.let { it.drawColor(Color.BLUE); texture.unlockCanvasAndPost(it) }
                    ready.countDown()
                }
                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) = Unit
                override fun onSurfaceTextureDestroyed(st: SurfaceTexture) = true
                override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
            }
            column.addView(texture, LinearLayout.LayoutParams(-1, 0, 1f))
            activity.setContentView(column)
        }
        assertTrue("surfaces ready", ready.await(5, TimeUnit.SECONDS))
        SystemClock.sleep(300)
        val host = object : CaptureHost {
            override val lastTouchMoveNs: Long? = null
            override val isInteractingForCapture = false
            override fun isOverlay(root: View) = false
            override val keyboard = KeyboardTracker(ScrawlAppContext { null }) { false }
            override fun focusedRoot(): View = decor
            override fun hasMicrophonePermission() = false
            override fun requestMicrophonePermission(completion: (Boolean) -> Unit) = completion(false)
        }
        val app = instrumentation.targetContext
        val recorder = AppFrameRecorder(host, AppFrameRecorder.VIDEO_BIT_RATE, File(app.cacheDir, "ScrawlTempSurface"))
        val started = CountDownLatch(1)
        instrumentation.runOnMainSync { recorder.start(false) { started.countDown() } }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        SystemClock.sleep(1_200)
        val movie = File(app.cacheDir, "surfaces.mp4")
        val stopped = CountDownLatch(1)
        var error: Throwable? = null
        instrumentation.runOnMainSync { recorder.stop(movie) { error = it; stopped.countDown() } }
        assertTrue(stopped.await(20, TimeUnit.SECONDS))
        assertNull(error)

        val retriever = MediaMetadataRetriever().apply { setDataSource(movie.path) }
        val frame = retriever.getFrameAtTime(900_000, MediaMetadataRetriever.OPTION_CLOSEST)!!
        retriever.release()
        val w = frame.width
        val h = frame.height
        fun near(c: Int, r: Int, g: Int, b: Int, tolerance: Int = 40) =
            abs(Color.red(c) - r) <= tolerance && abs(Color.green(c) - g) <= tolerance && abs(Color.blue(c) - b) <= tolerance
        fun hex(c: Int) = Integer.toHexString(c)
        // Column weights 1 : 0.2 : 1 under the status bar; sample well inside each band.
        val surfacePixel = frame.getPixel(w / 6, (h * 0.25).toInt())
        val badgePixel = frame.getPixel(w / 2, (h * 0.05 + h * 0.95 * 1 / 4.4).toInt())
        val texturePixel = frame.getPixel(w / 6, (h * 0.8).toInt())
        assertTrue("SurfaceView content, was ${hex(surfacePixel)}", near(surfacePixel, 255, 0, 0))
        assertTrue("views over the SurfaceView stay on top, was ${hex(badgePixel)}", near(badgePixel, 0, 255, 0))
        assertTrue("TextureView content, was ${hex(texturePixel)}", near(texturePixel, 0, 0, 255))
        scenario.close()
    }
}
