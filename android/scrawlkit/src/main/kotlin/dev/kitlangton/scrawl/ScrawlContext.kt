package dev.kitlangton.scrawl

import android.view.View
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToLong

/**
 * The screen and context values the host app has declared, and their changes during the current
 * recording.
 *
 * Each screen or key is a stack of setters (a view's token, or the static API's): the most recently
 * appeared one still present wins.
 */
class ScrawlAppContext internal constructor(
    /** Seconds since the recording's start, or null while not recording. */
    private val elapsedSeconds: () -> Double?,
) {
    private class Entry(val token: UUID, var value: String)

    private val screens = mutableListOf<Entry>()
    private val values = mutableMapOf<String, MutableList<Entry>>()
    private var recording = false
    private var startScreen: String? = null
    private var startValues: Map<String, String> = emptyMap()
    private val screenLog = mutableListOf<JSONObject>()
    private val contextLog = mutableListOf<JSONObject>()

    val currentScreen: String? get() = screens.lastOrNull()?.value
    val currentValues: Map<String, String> get() = values.mapValues { it.value.last().value }

    fun setScreen(name: String?, token: UUID, promote: Boolean) {
        val before = currentScreen
        set(screens, cleaned(name), token, promote)
        val after = currentScreen
        if (after == before) return
        val t = elapsed() ?: return
        screenLog += JSONObject().put("t", t).put("name", after ?: JSONObject.NULL)
    }

    fun setContext(rawKey: String, value: String?, token: UUID, promote: Boolean) {
        val key = rawKey.trim()
        if (key.isEmpty()) return
        val before = values[key]?.lastOrNull()?.value
        val stack = values[key] ?: mutableListOf()
        set(stack, cleaned(value), token, promote)
        if (stack.isEmpty()) values.remove(key) else values[key] = stack
        val after = stack.lastOrNull()?.value
        if (after == before) return
        val t = elapsed() ?: return
        contextLog += JSONObject().put("t", t).put("key", key).put("value", after ?: JSONObject.NULL)
    }

    /** The recording has started: changes from now on are timed from it. */
    fun begin() {
        recording = true
        startScreen = currentScreen
        startValues = currentValues
        screenLog.clear()
        contextLog.clear()
    }

    /** Ends the recording and returns its timeline keys (`context`, `contextChanges`, `screens`). */
    fun end(): Map<String, Any> {
        val screensArray = JSONArray()
        startScreen?.let { screensArray.put(JSONObject().put("t", 0.0).put("name", it)) }
        screenLog.forEach { screensArray.put(it) }
        val info = mapOf(
            "context" to JSONObject(startValues as Map<*, *>),
            "contextChanges" to JSONArray(contextLog),
            "screens" to screensArray,
        )
        recording = false
        startScreen = null
        startValues = emptyMap()
        screenLog.clear()
        contextLog.clear()
        return info
    }

    private fun elapsed(): Double? {
        if (!recording) return null
        val seconds = elapsedSeconds() ?: return null
        return (max(seconds, 0.0) * 1000).roundToLong() / 1000.0
    }

    private fun set(stack: MutableList<Entry>, value: String?, token: UUID, promote: Boolean) {
        val index = stack.indexOfFirst { it.token == token }
        if (value == null) {
            if (index >= 0) stack.removeAt(index)
            return
        }
        if (index >= 0 && !promote) {
            stack[index].value = value
        } else {
            if (index >= 0) stack.removeAt(index)
            stack += Entry(token, value)
        }
    }

    private fun cleaned(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }

    companion object {
        val manualToken: UUID = UUID.randomUUID()
    }
}

/**
 * Names the screen a [View] shows while it's attached (the `.scrawlScreen` modifier): set
 * [name] as it changes; when the view detaches, the screen it covered comes back.
 *
 *     ScrawlScreen.attach(view, "Settings › Experimental")
 *
 * In Compose, call it from a `DisposableEffect` with `LocalView.current`, or use [ScrawlScreen.token]
 * and [Scrawl.setScreen] directly.
 */
class ScrawlScreen private constructor(private var name: String) : View.OnAttachStateChangeListener {
    private val token = UUID.randomUUID()

    override fun onViewAttachedToWindow(v: View) = Scrawl.appContext.setScreen(name, token, promote = true)
    override fun onViewDetachedFromWindow(v: View) = Scrawl.appContext.setScreen(null, token, promote = false)

    fun update(name: String) {
        this.name = name
        Scrawl.appContext.setScreen(name, token, promote = false)
    }

    companion object {
        fun attach(view: View, name: String): ScrawlScreen = ScrawlScreen(name).also {
            view.addOnAttachStateChangeListener(it)
            if (view.isAttachedToWindow) it.onViewAttachedToWindow(view)
        }
    }
}

/**
 * Sets an app-defined value while a [View] is attached (the `.scrawlContext` modifier), following
 * [value] as it changes. `null` leaves the key to enclosing setters; when the view detaches, the
 * value it covered comes back.
 */
class ScrawlContextValue private constructor(private var key: String, private var value: String?) : View.OnAttachStateChangeListener {
    private val token = UUID.randomUUID()

    override fun onViewAttachedToWindow(v: View) = Scrawl.appContext.setContext(key, value, token, promote = true)
    override fun onViewDetachedFromWindow(v: View) = Scrawl.appContext.setContext(key, null, token, promote = false)

    fun update(key: String, value: String?) {
        if (key != this.key) {
            Scrawl.appContext.setContext(this.key, null, token, promote = false)
            this.key = key
            this.value = value
            Scrawl.appContext.setContext(key, value, token, promote = true)
        } else {
            this.value = value
            Scrawl.appContext.setContext(key, value, token, promote = false)
        }
    }

    companion object {
        fun attach(view: View, key: String, value: String?): ScrawlContextValue = ScrawlContextValue(key, value).also {
            view.addOnAttachStateChangeListener(it)
            if (view.isAttachedToWindow) it.onViewAttachedToWindow(view)
        }
    }
}
