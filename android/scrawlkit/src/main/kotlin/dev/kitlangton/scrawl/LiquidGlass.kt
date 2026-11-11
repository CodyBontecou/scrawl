package dev.kitlangton.scrawl

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import androidx.annotation.RequiresApi

/**
 * iOS 26's Liquid Glass (`.glassEffect(.regular.tint(.black.opacity(0.45)))` inside a glass
 * container) for Android 13+: one AGSL pass over the blurred app behind the strip.
 *
 * - The body and the bubble are one shape: their capsules joined by a smooth minimum, so the
 *   bubble buds out of the strip and merges back the way glass shapes in a container do.
 * - Near the edge the backdrop is bent inward along the surface normal (a lens), which is what
 *   makes the material read as glass rather than frosted plastic.
 * - A specular rim lit from the top left, a faint inner shadow on the far side, and the 0.45 black
 *   tint, all clipped to the shape with a one-pixel anti-aliased edge.
 */
@RequiresApi(33)
internal class LiquidGlass(private val density: Float) {
    private val shader = RuntimeShader(SOURCE)

    /** The effect for the backdrop node: blur, then the glass. Rects are in the node's px. */
    fun effect(body: Rect, bubble: Rect?, alpha: Float, blurPx: Float): RenderEffect {
        val d = density
        shader.setFloatUniform("body", (body.minX * d).toFloat(), (body.minY * d).toFloat(), (body.maxX * d).toFloat(), (body.maxY * d).toFloat())
        if (bubble != null && !bubble.isEmpty) {
            shader.setFloatUniform("bubble", (bubble.minX * d).toFloat(), (bubble.minY * d).toFloat(), (bubble.maxX * d).toFloat(), (bubble.maxY * d).toFloat())
            shader.setFloatUniform("hasBubble", 1f)
        } else {
            shader.setFloatUniform("bubble", 0f, 0f, 0f, 0f)
            shader.setFloatUniform("hasBubble", 0f)
        }
        shader.setFloatUniform("density", d)
        shader.setFloatUniform("tint", StripMetrics.GLASS_TINT.toFloat())
        shader.setFloatUniform("alpha", alpha.coerceIn(0f, 1f))
        val blur = RenderEffect.createBlurEffect(blurPx, blurPx, Shader.TileMode.CLAMP)
        return RenderEffect.createChainEffect(RenderEffect.createRuntimeShaderEffect(shader, "content"), blur)
    }

    companion object {
        /** Unit-testable copy of the shape the shader draws (points in px). Negative inside. */
        fun distance(px: Double, py: Double, body: android.graphics.RectF, bubble: android.graphics.RectF?, density: Float): Double {
            fun capsule(r: android.graphics.RectF): Double {
                val cx = r.centerX().toDouble(); val cy = r.centerY().toDouble()
                val hx = r.width() / 2.0; val hy = r.height() / 2.0
                val rad = minOf(hx, hy)
                val qx = kotlin.math.abs(px - cx) - hx + rad
                val qy = kotlin.math.abs(py - cy) - hy + rad
                return kotlin.math.hypot(maxOf(qx, 0.0), maxOf(qy, 0.0)) + minOf(maxOf(qx, qy), 0.0) - rad
            }
            val a = capsule(body)
            if (bubble == null) return a
            val b = capsule(bubble)
            val k = MERGE_DP * density
            val h = ((0.5 + 0.5 * (b - a) / k)).coerceIn(0.0, 1.0)
            return b + (a - b) * h - k * h * (1 - h)
        }

        const val MERGE_DP = 14.0

        private val SOURCE = """
            uniform shader content;
            uniform float4 body;
            uniform float4 bubble;
            uniform float hasBubble;
            uniform float density;
            uniform float tint;
            uniform float alpha;

            float capsule(float2 p, float4 r) {
                float2 c = (r.xy + r.zw) * 0.5;
                float2 h = (r.zw - r.xy) * 0.5;
                float rad = min(h.x, h.y);
                float2 q = abs(p - c) - h + rad;
                return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - rad;
            }

            float shape(float2 p) {
                float a = capsule(p, body);
                if (hasBubble < 0.5) return a;
                float b = capsule(p, bubble);
                float k = ${MERGE_DP} * density;
                float h = clamp(0.5 + 0.5 * (b - a) / k, 0.0, 1.0);
                return mix(b, a, h) - k * h * (1.0 - h);
            }

            half4 main(float2 p) {
                float d = shape(p);
                float coverage = clamp(0.5 - d, 0.0, 1.0);
                if (coverage <= 0.0) return half4(0.0);
                float e = 1.0;
                float2 g = float2(shape(p + float2(e, 0.0)) - shape(p - float2(e, 0.0)),
                                  shape(p + float2(0.0, e)) - shape(p - float2(0.0, e)));
                float gl = length(g);
                float2 n = gl > 0.0001 ? g / gl : float2(0.0);
                // The lens: strongest at the rim, nothing past `band` inside.
                float band = 16.0 * density;
                float t = clamp(1.0 + d / band, 0.0, 1.0);
                float bend = t * t * t;
                float2 sp = p - n * bend * band * 0.75;
                half4 c = content.eval(sp);
                // A touch of dispersion right at the edge.
                half4 r = content.eval(sp - n * bend * 1.5 * density);
                half4 b = content.eval(sp + n * bend * 1.5 * density);
                c.r = r.r; c.b = b.b;
                c.rgb = mix(c.rgb, half3(0.0), half(tint));
                // Specular rim lit from the top left, a faint shadow opposite.
                float rim = smoothstep(2.5 * density, 0.0, -d);
                float2 light = normalize(float2(-0.55, -0.85));
                float facing = dot(n, light);
                c.rgb += half3(rim * (0.10 + 0.42 * max(facing, 0.0)));
                c.rgb -= half3(rim * 0.10 * max(-facing, 0.0));
                c.rgb = clamp(c.rgb, half3(0.0), half3(1.0));
                float a = coverage * alpha;
                return half4(c.rgb * half(a), half(a));
            }
        """.trimIndent()
    }
}
