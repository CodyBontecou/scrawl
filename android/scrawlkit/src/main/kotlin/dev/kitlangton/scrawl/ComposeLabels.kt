package dev.kitlangton.scrawl

import android.view.View
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import kotlin.math.hypot
import kotlin.math.max

/**
 * Touch labels for Compose: the merged semantics tree of an `AndroidComposeView` (through
 * `ViewRootForTest`, the hook Compose's own UI tests use), with the same rules as for Views: the
 * deepest node under the touch with a description or text, up to 7 levels up, else the nearest text
 * inside a compact (≤ 110 dp) node. Only touched when the app has Compose on its classpath.
 */
internal object ComposeLabels {
    private val available: Boolean by lazy {
        runCatching { Class.forName("androidx.compose.ui.platform.ViewRootForTest") }.isSuccess
    }

    /** [x], [y] in the window's px. */
    fun label(view: View, x: Float, y: Float): String? {
        if (!available) return null
        return runCatching { labelOrNull(view, x, y) }.getOrNull()
    }

    private fun labelOrNull(view: View, x: Float, y: Float): String? {
        val root = (view as? ViewRootForTest)?.semanticsOwner?.rootSemanticsNode ?: return null
        val chain = mutableListOf<SemanticsNode>()
        var node: SemanticsNode? = root
        while (node != null) {
            chain += node
            node = node.children.lastOrNull { child ->
                val b = child.boundsInWindow
                x >= b.left && x < b.right && y >= b.top && y < b.bottom
            }
        }
        val density = view.resources.displayMetrics.density
        for (n in chain.asReversed().take(7)) {
            own(n)?.let { return it }
            if (n.boundsInWindow.height / density <= 110) nearest(n, x, y)?.let { return it }
        }
        return null
    }

    private fun own(node: SemanticsNode): String? {
        val config = node.config
        config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ")?.let { HitLabels.cleaned(it) }?.let { return it }
        config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }?.let { HitLabels.cleaned(it) }?.let { return it }
        return null
    }

    private fun nearest(container: SemanticsNode, x: Float, y: Float): String? {
        var best: Pair<Double, String>? = null
        fun walk(node: SemanticsNode, remaining: Int) {
            if (remaining < 0) return
            own(node)?.let { text ->
                val b = node.boundsInWindow
                val dx = max(max(b.left - x, 0f), x - b.right).toDouble()
                val dy = max(max(b.top - y, 0f), y - b.bottom).toDouble()
                val d = hypot(dx, dy)
                if (best == null || d < best!!.first) best = d to text
            }
            for (child in node.children) walk(child, remaining - 1)
        }
        for (child in container.children) walk(child, 2)
        return best?.second
    }
}
