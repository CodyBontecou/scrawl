package dev.kitlangton.scrawl

import androidx.compose.ui.Modifier

/** Does nothing in release builds. */
fun Modifier.scrawlScreen(@Suppress("UNUSED_PARAMETER") name: String): Modifier = this

/** Does nothing in release builds. */
fun Modifier.scrawlContext(@Suppress("UNUSED_PARAMETER") key: String, @Suppress("UNUSED_PARAMETER") value: String?): Modifier = this
