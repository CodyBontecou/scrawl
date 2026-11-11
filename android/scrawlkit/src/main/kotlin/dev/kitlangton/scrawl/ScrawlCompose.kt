package dev.kitlangton.scrawl

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import java.util.UUID

/*
 * Compose counterparts of SwiftUI's `.scrawlScreen(_:)` and `.scrawlContext(_:_:)`. Compose is a
 * compileOnly dependency: these are only loaded by apps that use Compose themselves.
 */

/**
 * Names the screen while this element is in the composition (`Scrawl.screen`), following [name] as
 * it changes; when it leaves, the screen it covered comes back.
 */
fun Modifier.scrawlScreen(name: String): Modifier = composed {
    val token = remember { UUID.randomUUID() }
    val context = Scrawl.appContext
    val appeared = remember { booleanArrayOf(false) }
    DisposableEffect(token) {
        context.setScreen(name, token, promote = true)
        appeared[0] = true
        onDispose { context.setScreen(null, token, promote = false) }
    }
    LaunchedEffect(name) {
        if (appeared[0]) context.setScreen(name, token, promote = false)
    }
    this
}

/**
 * Sets an app-defined value (`Scrawl.context`) while this element is in the composition, following
 * [value] as it changes. `null` leaves the key to enclosing elements; when it leaves, the value it
 * covered comes back.
 */
fun Modifier.scrawlContext(key: String, value: String?): Modifier = composed {
    val token = remember { UUID.randomUUID() }
    val context = Scrawl.appContext
    // Re-keyed when the key changes: the old key is released and the new one promoted (iOS onChange(of: key)).
    DisposableEffect(token, key) {
        context.setContext(key, value, token, promote = true)
        onDispose { context.setContext(key, null, token, promote = false) }
    }
    val lastValue = remember(key) { arrayOf(value) }
    LaunchedEffect(key, value) {
        if (lastValue[0] != value) {
            lastValue[0] = value
            context.setContext(key, value, token, promote = false)
        }
    }
    this
}
