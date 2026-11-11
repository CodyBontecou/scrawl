package dev.kitlangton.scrawl

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import java.util.WeakHashMap
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Pixels a window's own drawing leaves out: SurfaceViews (video players, cameras, maps, games) are
 * separate surfaces the window only punches a hole for. iOS's `drawHierarchy` snapshot includes such
 * layers, so app-frame movies composite them in: each is copied with [PixelCopy] (asynchronously;
 * the last copy is drawn meanwhile) and drawn into its hole, behind the views over it. TextureViews
 * need nothing: a hardware canvas draws their layer with the window. All calls on the main thread.
 */
internal class SurfaceContent {
    private val main = Handler(Looper.getMainLooper())
    private val found = WeakHashMap<View, Pair<Long, List<View>>>()
    private val copies = WeakHashMap<SurfaceView, Bitmap>()
    private val copying = WeakHashMap<SurfaceView, Boolean>()
    private val behind = Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OVER) }
    private val location = IntArray(2)
    private val rootLocation = IntArray(2)

    /** Draws [root]'s surfaces onto [canvas], which is already in [root]'s coordinates. [scale] is
     * movie px per view px, so copies are made at the movie's resolution. */
    fun draw(canvas: Canvas, root: View, scale: Float) {
        val views = views(root)
        if (views.isEmpty()) return
        root.getLocationOnScreen(rootLocation)
        for (view in views) {
            if (!view.isShown || view.width == 0 || view.height == 0) continue
            view.getLocationOnScreen(location)
            val rect = RectF(
                (location[0] - rootLocation[0]).toFloat(),
                (location[1] - rootLocation[1]).toFloat(),
                (location[0] - rootLocation[0] + view.width).toFloat(),
                (location[1] - rootLocation[1] + view.height).toFloat(),
            )
            val w = max(2, (view.width * scale).roundToInt())
            val h = max(2, (view.height * scale).roundToInt())
            if (view is SurfaceView) {
                copy(view, w, h)
                copies[view]?.let { canvas.drawBitmap(it, null, rect, behind) }
            }
        }
    }

    fun clear() {
        found.clear(); copies.clear(); copying.clear()
    }

    /** The SurfaceViews under [root], rescanned twice a second. */
    private fun views(root: View): List<View> {
        val now = SystemClock.uptimeMillis()
        found[root]?.let { (at, views) -> if (now - at < RESCAN_MS) return views }
        val views = mutableListOf<View>()
        fun walk(v: View) {
            if (v.visibility != View.VISIBLE) return
            if (v is SurfaceView) views += v
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        found[root] = now to views
        return views
    }

    /** Starts a copy of [view]'s surface unless one is in flight; the last copy is drawn meanwhile. */
    private fun copy(view: SurfaceView, w: Int, h: Int) {
        if (copying[view] == true || !view.holder.surface.isValid) return
        val bitmap = copies[view]?.takeIf { it.width == w && it.height == h }
            ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        copying[view] = true
        try {
            PixelCopy.request(view, bitmap, { result ->
                copying[view] = false
                if (result == PixelCopy.SUCCESS) copies[view] = bitmap
            }, main)
        } catch (e: IllegalArgumentException) {
            copying[view] = false // the surface went away between the check and the request
        }
    }


    private companion object {
        const val RESCAN_MS = 500L
    }
}
