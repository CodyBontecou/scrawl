package dev.kitlangton.scrawl

import android.annotation.SuppressLint
import android.app.Activity
import android.view.View
import android.view.Window
import android.view.WindowManager
import java.lang.ref.WeakReference

/**
 * The app's windows: every root view the process has added (activities, dialogs, popups, toasts),
 * back to front, through `WindowManagerGlobal` — the same hook Espresso's root matcher uses. There
 * is no public API for a process's own windows; when reflection is unavailable, the known
 * activities' decor views stand in.
 */
@SuppressLint("PrivateApi", "DiscouragedPrivateApi")
internal object Windows {
    data class Root(val view: View, val params: WindowManager.LayoutParams?)

    private val global: Any? by lazy {
        runCatching {
            Class.forName("android.view.WindowManagerGlobal").getMethod("getInstance").invoke(null)
        }.getOrNull()
    }
    private val viewsField by lazy {
        runCatching { global!!.javaClass.getDeclaredField("mViews").apply { isAccessible = true } }.getOrNull()
    }
    private val paramsField by lazy {
        runCatching { global!!.javaClass.getDeclaredField("mParams").apply { isAccessible = true } }.getOrNull()
    }
    private val lockField by lazy {
        runCatching { global!!.javaClass.getDeclaredField("mLock").apply { isAccessible = true } }.getOrNull()
    }

    /** Activities seen by the lifecycle callbacks: the fallback when reflection fails. */
    private val activities = mutableListOf<WeakReference<Activity>>()

    fun track(activity: Activity) {
        activities.removeAll { it.get() == null || it.get() === activity }
        activities += WeakReference(activity)
    }

    fun untrack(activity: Activity) {
        activities.removeAll { it.get() == null || it.get() === activity }
    }

    /** Every root view, in the order they were added (WindowManager's z-order within a layer). */
    fun roots(): List<Root> {
        val g = global
        val views = viewsField
        val params = paramsField
        if (g != null && views != null && params != null) {
            val result = runCatching {
                val lock = lockField?.get(g) ?: g
                synchronized(lock) {
                    @Suppress("UNCHECKED_CAST")
                    val v = (views.get(g) as List<View>).toList()

                    @Suppress("UNCHECKED_CAST")
                    val p = (params.get(g) as List<WindowManager.LayoutParams>).toList()
                    v.indices.map { Root(v[it], p.getOrNull(it)) }
                }
            }.getOrNull()
            if (result != null) return result
        }
        return activities.mapNotNull { it.get() }.filter { !it.isFinishing }.map {
            Root(it.window.decorView, it.window.attributes)
        }
    }

    private val windowField by lazy {
        runCatching {
            Class.forName("com.android.internal.policy.DecorView").getDeclaredField("mWindow").apply { isAccessible = true }
        }.getOrNull()
    }

    /** The `Window` behind a root view, when it's a `DecorView` (activities and dialogs). */
    fun windowOf(root: View): Window? = runCatching { windowField?.get(root) as? Window }.getOrNull()
}
