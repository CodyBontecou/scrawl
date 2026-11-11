package dev.kitlangton.scrawl

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * The strip's material before Android 12, where there's no GPU blur: the app under the strip is
 * drawn into a small bitmap (1/[DOWNSCALE] size), box-blurred three times (≈ a Gaussian) on the
 * CPU, and drawn back scaled up and clipped to the liquid shape, under iOS's 0.45 tint. Refreshed at
 * most [REFRESH_MS] apart so it costs a fraction of a millisecond per frame on the main thread.
 */
internal class SoftwareBlur(private val density: Float) {
    private var small: Bitmap? = null
    private var pixels = IntArray(0)
    private var scratch = IntArray(0)
    private var region = RectF()
    private var drawnAt = 0L
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    /** Draws the blurred [app] behind [host] inside [path] (both in [host]'s px); false if it can't. */
    fun draw(canvas: Canvas, host: View, app: View, path: Path, bounds: RectF, alpha: Double): Boolean {
        if (bounds.isEmpty) return false
        val pad = BLUR_DP * density
        val want = RectF(bounds.left - pad, bounds.top - pad, bounds.right + pad, bounds.bottom + pad)
        val now = SystemClock.uptimeMillis()
        if (small == null || now - drawnAt >= REFRESH_MS || !region.contains(bounds)) {
            if (!capture(host, app, want)) return false
            drawnAt = now
        }
        val bitmap = small ?: return false
        paint.alpha = (alpha.coerceIn(0.0, 1.0) * 255).toInt()
        canvas.save()
        canvas.clipPath(path)
        canvas.drawBitmap(bitmap, null, region, paint)
        canvas.restore()
        return true
    }

    private fun capture(host: View, app: View, want: RectF): Boolean = runCatching {
        val w = max(2, ceil(want.width() / DOWNSCALE).toInt())
        val h = max(2, ceil(want.height() / DOWNSCALE).toInt())
        val bitmap = small?.takeIf { it.width == w && it.height == h }
            ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { small = it }
        bitmap.eraseColor(0xFF000000.toInt())
        val c = Canvas(bitmap)
        val appOrigin = IntArray(2).also { app.getLocationOnScreen(it) }
        val hostOrigin = IntArray(2).also { host.getLocationOnScreen(it) }
        c.scale(1f / DOWNSCALE, 1f / DOWNSCALE)
        c.translate(appOrigin[0] - hostOrigin[0] - want.left, appOrigin[1] - hostOrigin[1] - want.top)
        app.draw(c)
        if (pixels.size != w * h) { pixels = IntArray(w * h); scratch = IntArray(w * h) }
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val radius = max(1, (BLUR_DP * density / DOWNSCALE / 2).toInt())
        repeat(3) { boxBlur(pixels, scratch, w, h, radius) }
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        region = RectF(want.left, want.top, want.left + w * DOWNSCALE, want.top + h * DOWNSCALE)
        true
    }.getOrDefault(false)

    companion object {
        const val DOWNSCALE = 6f
        const val BLUR_DP = 24f
        const val REFRESH_MS = 66L

        /** One horizontal then one vertical box pass of [radius] (edges clamped), in place. */
        fun boxBlur(px: IntArray, tmp: IntArray, w: Int, h: Int, radius: Int) {
            pass(px, tmp, w, h, radius, horizontal = true)
            pass(tmp, px, w, h, radius, horizontal = false)
        }

        private fun pass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
            val lines = if (horizontal) h else w
            val len = if (horizontal) w else h
            val span = 2 * r + 1
            for (line in 0 until lines) {
                fun at(i: Int): Int {
                    val k = min(len - 1, max(0, i))
                    return if (horizontal) src[line * w + k] else src[k * w + line]
                }
                var a = 0; var rr = 0; var gg = 0; var bb = 0
                for (i in -r..r) { val p = at(i); a += p ushr 24; rr += (p shr 16) and 255; gg += (p shr 8) and 255; bb += p and 255 }
                for (i in 0 until len) {
                    val out = ((a / span) shl 24) or ((rr / span) shl 16) or ((gg / span) shl 8) or (bb / span)
                    if (horizontal) dst[line * w + i] = out else dst[i * w + line] = out
                    val add = at(i + r + 1); val sub = at(i - r)
                    a += (add ushr 24) - (sub ushr 24)
                    rr += ((add shr 16) and 255) - ((sub shr 16) and 255)
                    gg += ((add shr 8) and 255) - ((sub shr 8) and 255)
                    bb += (add and 255) - (sub and 255)
                }
            }
        }
    }
}
