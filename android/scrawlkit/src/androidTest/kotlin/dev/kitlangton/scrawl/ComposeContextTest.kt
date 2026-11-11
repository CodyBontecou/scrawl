package dev.kitlangton.scrawl

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

class ComposeHostActivity : ComponentActivity()

/** SwiftUI's `.scrawlScreen` / `.scrawlContext` as Compose modifiers (checklist 71–73). */
@RunWith(AndroidJUnit4::class)
class ComposeContextTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun settle(condition: () -> Boolean): Boolean {
        repeat(100) {
            var ok = false
            instrumentation.runOnMainSync { ok = condition() }
            if (ok) return true
            Thread.sleep(30)
        }
        return false
    }

    @Test
    fun modifiersFollowTheCompositionAndTheirValues() {
        val shown = mutableStateOf(true)
        val name = mutableStateOf("Inbox")
        val value = mutableStateOf<String?>("ses_1")
        val context = Scrawl.appContext
        ActivityScenario.launch(ComposeHostActivity::class.java).use { scenario ->
            Scrawl.screen("Outer")
            scenario.onActivity { activity ->
                activity.setContent {
                    if (shown.value) Box(Modifier.scrawlScreen(name.value).scrawlContext("session", value.value))
                }
            }
            assertTrue("appears", settle { context.currentScreen == "Inbox" && context.currentValues["session"] == "ses_1" })
            instrumentation.runOnMainSync { name.value = "Thread"; value.value = "ses_2" }
            assertTrue("follows changes", settle { context.currentScreen == "Thread" && context.currentValues["session"] == "ses_2" })
            instrumentation.runOnMainSync { shown.value = false }
            assertTrue("what it covered comes back", settle { context.currentScreen == "Outer" && context.currentValues["session"] == null })
            Scrawl.screen(null)
            assertEquals(null, context.currentScreen)
        }
    }
}
