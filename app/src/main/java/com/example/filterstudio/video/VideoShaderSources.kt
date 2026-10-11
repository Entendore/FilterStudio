package com.example.filterstudio.video

import android.graphics.ColorMatrix
import com.example.filterstudio.engine.*
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

object VideoShaderSources {
    const val HEADER = """
        #version 300 es
        precision highp float;
        uniform sampler2D uTex;
        in vec2 vTexSamplingCoord;
        out vec4 outColor;
        """

    val VERTEX = """
        #version 300 es
        in vec2 aFramePosition;
        in vec2 aTexPosition;
        out vec2 vTexSamplingCoord;
        void main() {
            gl_Position = vec4(aFramePosition, 0.0, 1.0);
            vTexSamplingCoord = aTexPosition;
        }
        """

    fun ColorMatrix.toGlMatrix(): FloatArray {
        val m = array; val out = FloatArray(16)
        for (j in 0..2) for (i in 0..3) out[j*4+i] = m[i*5+j] / 255f
        for (i in 0..2) out[i*4+3] = m[i*5+4] / 255f
        out[15] = 1f
        return out
    }

    fun conv(texW: Int, texH: Int, k: Kernel, blend: Float): String = HEADER + buildString {
        append("const vec2 TEXEL = vec2(%.9f, %.9f);\n".format(1f / texW, 1f / texH))
        append("const float blend = %.4f;\n".format(blend))
        val r = k.radius; val n2 = k.size * k.size
        append("const float k[$n2] = float[$n2](")
            .append(k.data.joinToString(",") { "%.6f".format(it) }).append(");\n")
        append("void main() {\n vec3 acc = vec3(0.0); int i = 0;\n")
        append(" for (int dy = -$r; dy <= $r; dy++) {\n  for (int dx = -$r; dx <= $r; dx++) {\n")
        append("   acc += k[i++] * texture(uTex, vTexSamplingCoord + TEXEL * vec2(float(dx), float(dy))).rgb;\n")
        append("  } }\n")
        append(" vec3 c = clamp(acc / %.6f + vec3(%.6f), 0.0, 1.0);\n".format(k.scale, k.offset / 255f))
        append(" outColor = vec4(mix(texture(uTex, vTexSamplingCoord).rgb, c, blend), 1.0);\n}")
    }

    fun motion(texW: Int, texH: Int, angleDeg: Float, permille: Int, blend: Float): String {
        val diag = hypot(texW.toDouble(), texH.toDouble())
        val extent = permille / 1000.0 * diag
        val rad = Math.toRadians(angleDeg.toDouble())
        val sx = cos(rad) * extent / texW / 24.0; val sy = sin(rad) * extent / texH / 24.0
        return HEADER + """
            const vec2 STEP = vec2(%.9f, %.9f);
            const float blend = %.4f;
            void main() {
                vec3 acc = vec3(0.0);
                vec2 p = vTexSamplingCoord - STEP * 11.5;
                for (int i = 0; i < 24; i++) { acc += texture(uTex, p).rgb; p += STEP; }
                outColor = vec4(mix(texture(uTex, vTexSamplingCoord).rgb, acc / 24.0, blend), 1.0);
            }""".trimIndent().format(sx, sy, blend)
    }

    fun edgeBinary(texW: Int, texH: Int, threshold: Float, invert: Boolean, blend: Float): String =
        HEADER + """
        const vec2 TEXEL = vec2(%.9f, %.9f);
        const float THR = %.4f;
        const float INVERT = %.1f;
        const float blend = %.4f;
        void main() {
            float gx = 0.0; float gy = 0.0;
            #define L(U, V, W) dot(texture(uTex, vTexSamplingCoord + TEXEL * vec2(U, V)).rgb, vec3(0.299, 0.587, 0.114)) * W
            gx += L(-1,-1,-1.) + L(0,-1,-2.) + L(1,-1,1.) + L(-1,0,-2.) + L(1,0,2.) + L(-1,1,-1.) + L(0,1,2.) + L(1,1,1.);
            gy += L(-1,-1,-1.) + L(0,-1,-2.) + L(1,-1,1.) + L(-1,1,1.) + L(0,1,2.) + L(1,1,1.);
            #undef L
            float edge = step(THR, length(vec2(gx, gy)) * %.4f);
            float v = mix(1.0 - edge, edge, INVERT);
            outColor = vec4(mix(texture(uTex, vTexSamplingCoord).rgb, vec3(v), blend), 1.0);
        }""".trimIndent().format(1f / texW, 1f / texH, threshold,
            if (invert) 1f else 0f, blend, EdgePresets.SOBEL_GAIN)

    fun engrave(texW: Int, texH: Int, periodFrac: Float, blend: Float): String {
        val period = (periodFrac * minOf(texW, texH)).coerceAtLeast(2f)
        return HEADER + """
            const float CYCLES = %.2f;
            const float blend = %.4f;
            void main() {
                vec3 src = texture(uTex, vTexSamplingCoord).rgb;
                float l = dot(src, vec3(0.299, 0.587, 0.114));
                float shade = 0.6 + 0.4 * sin(6.28318 * vTexSamplingCoord.y * CYCLES);
                outColor = vec4(mix(src, vec3(l * shade), blend), 1.0);
            }""".trimIndent().format(texH / period, blend)
    }

    fun grain(seed: Int, amount: Float): String = HEADER + """
        const float AMT = %.4f;
        const float SEED = %d.0;
        void main() {
            float h = fract(sin(dot(vTexSamplingCoord, vec2(127.1, 311.7)) + SEED * 91.7) * 43758.5453);
            vec3 c = texture(uTex, vTexSamplingCoord).rgb + (h * 2.0 - 1.0) * AMT * 0.283;
            outColor = vec4(clamp(c, 0.0, 1.0), 1.0);
        }""".trimIndent().format(amount, seed)

    fun grading(curves: IntArray?, vignette: Float, hasLut: Boolean,
                lutSize: Int, lutAmt: Float): String = HEADER + buildString {
        append("const float VIG = %.4f;\n".format(vignette))
        append("const float HAS_LUT = %.1f;\n".format(if (hasLut) 1f else 0f))
        append("const float LUT_N = ${lutSize}.0;\n")
        append("const float LUT_AMT = %.4f;\n".format(lutAmt))
        append("uniform sampler2D uLutTex;\n")
        if (curves != null) {
            listOf("R" to 0, "G" to 256, "B" to 512).forEach { (name, off) ->
                append("const float CURVE_$name[256] = float[256](")
                    .append(IntArray(256) { curves[it + off] }
                        .joinToString(",") { "$it.0" }).append(");\n") }
        }
        append("""
        vec3 fetchLut(vec3 c) {
            float n = LUT_N;
            return texture(uLutTex, vec2((c.r + c.g * n + 0.5) / (n * n), (c.b + 0.5) / n)).rgb;
        }
        vec3 trilerp(vec3 c) {
            float n = LUT_N - 1.0;
            vec3 g = clamp(c, 0.0, 1.0) * n;
            vec3 f = floor(g); vec3 d = g - f; vec3 dn = min(f + 1.0, n);
            #define PX(RI,GI,BI) fetchLut(vec3(RI,GI,BI) / (LUT_N - 1.0))
            vec3 a  = mix(mix(PX(f.x,f.y,f.z), PX(dn.x,f.y,f.z), d.x),
                          mix(PX(f.x,dn.y,f.z), PX(dn.x,dn.y,f.z), d.x), d.y);
            vec3 b2 = mix(mix(PX(f.x,f.y,dn.z), PX(dn.x,f.y,dn.z), d.x),
                          mix(PX(f.x,dn.y,dn.z), PX(dn.x,dn.y,dn.z), d.x), d.y);
            return mix(a, b2, d.z);
        }
        void main() {
            vec3 col = texture(uTex, vTexSamplingCoord).rgb;
            ${if (curves != null) """
            col = vec3(
                CURVE_R[int(clamp(col.r * 255.0 + 0.5, 0.0, 255.0))] / 255.0,
                CURVE_G[int(clamp(col.g * 255.0 + 0.5, 0.0, 255.0))] / 255.0,
                CURVE_B[int(clamp(col.b * 255.0 + 0.5, 0.0, 255.0))] / 255.0);""" else ""}
            if (HAS_LUT > 0.5) col = mix(col, trilerp(col), LUT_AMT);
            if (VIG > 0.0) {
                vec2 p = vTexSamplingCoord * 2.0 - 1.0;
                col *= 1.0 - VIG * smoothstep(0.55, 1.0, length(p) / 1.4142);
            }
            outColor = vec4(col, 1.0);
        }""".trimIndent())
    }

    val OVERLAY_FRAGMENT = """
        uniform sampler2D uOverlay;
        void main() {
            vec4 f = texture(uTex, vTexSamplingCoord);
            vec4 o = texture(uOverlay, vTexSamplingCoord);
            float a = o.a + f.a * (1.0 - o.a);
            vec3 rgb = (o.rgb * o.a + f.rgb * f.a * (1.0 - o.a)) / max(a, 1e-5);
            outColor = vec4(rgb, a);
        }
    """
}
