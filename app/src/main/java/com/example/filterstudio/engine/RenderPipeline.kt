package com.example.filterstudio.engine

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Shader
import android.graphics.RectF
import android.os.Build
import android.view.View
import androidx.annotation.RequiresApi
import com.example.filterstudio.model.*
import com.example.filterstudio.model.FilterType

object RenderPipeline {
    private val tiny = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    private val shaderCache = HashMap<String, android.graphics.RuntimeShader>()
    private fun fmt(f: Float) = "%.6f".format(f)

    fun ColorMatrix.toShaderUniforms(): Pair<FloatArray, FloatArray> {
        val m = array; val mat = FloatArray(16)
        for (j in 0..3) for (i in 0..3) mat[j*4+i] = m[i*5+j]
        return mat to FloatArray(4) { i -> m[i*5+4] }
    }

    // ================= mega shader (matrix + curves + LUT + grain) =================
    const val EDITOR_SKSL = """
uniform shader input;
uniform shader curveTex;
uniform shader lutTex;
uniform float hasCurves; uniform float hasLut;
uniform float lutSize; uniform float lutAmount;
uniform float4x4 colorMat; uniform float4 colorOffset;
uniform float vigStrength;
uniform float grainAmt; uniform float grainSeed;
uniform float2 imgOrigin; uniform float2 imgScale;

half3 fetchLut(float r, float g, float b) {
    float n = lutSize;
    return half3(lutTex.eval(float2((r + g * n + 0.5) / (n * n), (b + 0.5) / n)).rgb);
}
half3 trilerp(half3 c) {
    float n = lutSize - 1.0;
    float r = clamp(c.r, 0.0, 1.0) * n; float g = clamp(c.g, 0.0, 1.0) * n;
    float b = clamp(c.b, 0.0, 1.0) * n;
    float ri = min(floor(r), n - 1.0); float gi = min(floor(g), n - 1.0); float bi = min(floor(b), n - 1.0);
    float rd = r - ri; float gd = g - gi; float bd = b - bi;
    half3 a = mix(mix(fetchLut(ri, gi, bi), fetchLut(ri + 1.0, gi, bi), half(rd)),
                  mix(fetchLut(ri, gi + 1.0, bi), fetchLut(ri + 1.0, gi + 1.0, bi), half(rd)), half(gd));
    half3 b2 = mix(mix(fetchLut(ri, gi, bi + 1.0), fetchLut(ri + 1.0, gi, bi + 1.0), half(rd)),
                   mix(fetchLut(ri, gi + 1.0, bi + 1.0), fetchLut(ri + 1.0, gi + 1.0, bi + 1.0), half(rd)), half(gd));
    return mix(a, b2, half(bd));
}
float ghash(float2 p, float s) {
    return fract(sin(dot(p, float2(127.1, 311.7)) + s * 91.7) * 43758.5453);
}
half4 main(float2 xy) {
    half4 src = input.eval(xy);
    float4 m = colorMat * float4(src.rgb, 1.0) + colorOffset;
    half3 col = clamp(m.rgb, 0.0, 1.0);
    if (hasCurves > 0.5) {
        col = half3(
            curveTex.eval(float2((col.r * 255.0 + 0.5) / 256.0, 0.16667)).r,
            curveTex.eval(float2((col.g * 255.0 + 0.5) / 256.0, 0.5)).g,
            curveTex.eval(float2((col.b * 255.0 + 0.5) / 256.0, 0.83333)).b);
    }
    if (hasLut > 0.5) col = mix(col, trilerp(col), half(lutAmount));
    if (vigStrength > 0.0) {
        float d = length((xy - imgOrigin) / imgScale * 2.0 - 1.0) / 1.4142;
        col *= half3(half(1.0 - vigStrength * smoothstep(0.55, 1.0, d)));
    }
    if (grainAmt > 0.0) {
        float n = ghash(xy, grainSeed) * 2.0 - 1.0;
        col = clamp(col + half3(half(n * grainAmt * 0.283)), 0.0, 1.0);
    }
    return half4(col, src.a);
}
"""

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun applyLiveAgsl(view: View, state: EditState, blurPx: Float,
                      curveBmp: Bitmap?, lutBmp: Bitmap?, lutSize: Int, rect: RectF) {
        val s = RuntimeShader(EDITOR_SKSL)
        val clamp = Shader.TileMode.CLAMP
        fun sh(b: Bitmap?) = BitmapShader(b ?: tiny, clamp, clamp)
        s.setInputShader("curveTex", sh(curveBmp)); s.setInputShader("lutTex", sh(lutBmp))
        s.setFloatUniform("hasCurves", if (curveBmp != null) 1f else 0f)
        s.setFloatUniform("hasLut", if (lutBmp != null) 1f else 0f)
        s.setFloatUniform("lutSize", lutSize.toFloat()); s.setFloatUniform("lutAmount", state.lutAmount)
        val (mat, off) = FilterEngine.buildMatrix(state).toShaderUniforms()
        s.setFloatUniform("colorMat", mat); s.setFloatUniform("colorOffset", off)
        s.setFloatUniform("vigStrength", state.adjustments.vignette)
        s.setFloatUniform("grainAmt", state.adjustments.grain)
        s.setFloatUniform("grainSeed", 0f)
        s.setFloatUniform("imgOrigin", floatArrayOf(rect.left, rect.top))
        s.setFloatUniform("imgScale",
            floatArrayOf(rect.width().coerceAtLeast(1f), rect.height().coerceAtLeast(1f)))
        var effect = android.graphics.RenderEffect.createRuntimeShaderEffect(s, "input")
        if (blurPx > 0f) effect = android.graphics.RenderEffect.createBlurEffect(
            blurPx, blurPx, effect, clamp)
        view.setRenderEffect(effect)
    }

    // ================= legacy (API 31–32) =================
    fun applyLiveLegacy(view: View, state: EditState, blurPx: Float) {
        var effect = android.graphics.RenderEffect.createColorFilterEffect(
            ColorMatrixColorFilter(FilterEngine.buildMatrix(state)))
        if (blurPx > 0f) effect = android.graphics.RenderEffect.createBlurEffect(
            blurPx, blurPx, effect, Shader.TileMode.CLAMP)
        view.setRenderEffect(effect)
    }

    // ================= generated conv / motion / ink / engrave shaders =================
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun convShader(k: Kernel, blend: Float): RuntimeShader {
        val key = "conv|${k.signature()}|$blend"
        return shaderCache.getOrPut(key) {
            val r = k.radius; val n2 = k.size * k.size
            val src = buildString {
                appendLine("uniform shader input; uniform float2 imgSize;")
                append("const float k[$n2] = float[$n2](")
                append(k.data.joinToString(",") { fmt(it) }); appendLine(");")
                appendLine("uniform float blend; uniform float offset;")
                appendLine("half4 main(float2 xy) {")
                appendLine("  half3 acc = half3(0.0); int i = 0;")
                appendLine("  for (int dy = -$r; dy <= $r; dy++) {")
                appendLine("    for (int dx = -$r; dx <= $r; dx++) {")
                appendLine("      acc += half3(k[i++]) * input.eval(xy + float2(dx, dy)).rgb;")
                appendLine("    } }")
                appendLine("  half3 c = clamp(acc / ${fmt(k.scale)} + half3(offset), 0.0, 1.0);")
                appendLine("  half4 o = input.eval(xy);")
                appendLine("  return half4(mix(o.rgb, c, half(blend)), o.a);")
                appendLine("}")
            }
            RuntimeShader(src).apply {
                setFloatUniform("blend", blend); setFloatUniform("offset", k.offset / 255f)
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun motionShader(blend: Float): RuntimeShader =
        shaderCache.getOrPut("motion|$blend") {
            RuntimeShader("""
                uniform shader input; uniform float2 stepVec; uniform float blend; uniform float2 imgSize;
                const int TAPS = 24;
                half4 main(float2 xy) {
                    half3 acc = half3(0.0);
                    float2 p = xy - stepVec * (TAPS / 2.0 - 0.5);
                    for (int i = 0; i < TAPS; i++) { acc += input.eval(p).rgb; p += stepVec; }
                    half4 o = input.eval(xy);
                    return half4(mix(o.rgb, acc / half(24.0), half(blend)), o.a);
                }""").apply { setFloatUniform("blend", blend) }
        }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun inkShader(threshold: Float, invert: Boolean, blend: Float): RuntimeShader =
        shaderCache.getOrPut("ink|$threshold|$invert|$blend") {
            RuntimeShader("""
                uniform shader input; uniform float blend; uniform float2 imgSize;
                const float kx[9] = float[9](-1.0,0.0,1.0,-2.0,0.0,2.0,-1.0,0.0,1.0);
                const float ky[9] = float[9](-1.0,-2.0,-1.0,0.0,0.0,0.0,1.0,2.0,1.0);
                half4 main(float2 xy) {
                    float gx = 0.0; float gy = 0.0; int i = 0;
                    for (int dy = -1; dy <= 1; dy++)
                        for (int dx = -1; dx <= 1; dx++) {
                            half3 c = input.eval(xy + float2(dx, dy)).rgb;
                            float l = dot(c, float3(0.299, 0.587, 0.114));
                            gx += kx[i] * l; gy += ky[i] * l; i++;
                        }
                    float edge = step(${fmt(threshold)}, length(float2(gx, gy)) * ${fmt(EdgePresets.SOBEL_GAIN)});
                    float v = ${if (invert) "edge" else "1.0 - edge"};
                    return half4(mix(input.eval(xy).rgb, half3(half(v)), half(blend)), input.eval(xy).a);
                }""").apply { setFloatUniform("blend", blend) }
        }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun engraveShader(periodPx: Float, blend: Float): RuntimeShader =
        shaderCache.getOrPut("engrave|$periodPx|$blend") {
            RuntimeShader("""
                uniform shader input; uniform float blend; uniform float periodPx; uniform float2 imgSize;
                half4 main(float2 xy) {
                    half3 src = input.eval(xy);
                    float l = dot(src.rgb, float3(0.299, 0.587, 0.114));
                    float shade = 0.6 + 0.4 * sin(6.28318 * xy.y / periodPx);
                    return half4(mix(src.rgb, half3(half(l * shade)), half(blend)), src.a);
                }""").apply {
                setFloatUniform("blend", blend); setFloatUniform("periodPx", periodPx) }
        }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun pixelPassShader(f: FilterType, state: EditState, imgW: Int, imgH: Int): RuntimeShader? {
        val d = PixelPassSources.AGSL
        val src = when (f) {
            FilterType.PAINTERLY -> PixelPassSources.kuwahara(d, FilterEngine.kuwaharaRadius(imgW, imgH))
            FilterType.GLOW -> PixelPassSources.glow(d)
            FilterType.DREAMY -> PixelPassSources.orton(d)
            FilterType.COMIC -> PixelPassSources.comic(d)
            FilterType.MANGA -> PixelPassSources.manga(d)
            FilterType.WATERCOLOR -> PixelPassSources.watercolor(d)
            FilterType.HALFTONE -> PixelPassSources.halftone(d)
            FilterType.POSTERIZE -> PixelPassSources.posterize(d)
            FilterType.PIXELATE -> PixelPassSources.pixelate(d, 64)
            else -> return null
        }
        return RuntimeShader(src).apply {
            setFloatUniform("imgSize", floatArrayOf(imgW.toFloat(), imgH.toFloat()))
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun applyConvLive(view: View, state: EditState, blurPx: Float, imageDiagPx: Float,
                      imgW: Int, imgH: Int) {
        val shader = when (state.filter) {
            FilterType.MOTION_BLUR -> motionShader(state.filterStrength).apply {
                val extentPx = state.motionLength / 1000.0 * imageDiagPx
                val rad = Math.toRadians(state.motionAngle.toDouble())
                setFloatUniform("stepVec", floatArrayOf(
                    (kotlin.math.cos(rad) * extentPx / 24.0).toFloat(),
                    (kotlin.math.sin(rad) * extentPx / 24.0).toFloat())) }
            FilterType.INK -> inkShader(EdgePresets.INK_THRESHOLD, false, state.filterStrength)
            FilterType.BLUEPRINT -> inkShader(EdgePresets.BLUEPRINT_THRESHOLD, true, state.filterStrength)
            FilterType.ENGRAVE -> engraveShader(
                EdgePresets.ENGRAVE_PERIOD * minOf(imgW, imgH), state.filterStrength)
            else -> Kernels.forFilter(state.filter, state.customKernel)?.let {
                convShader(it, state.filterStrength) }
        } ?: pixelPassShader(state.filter, state, imgW, imgH) ?: return clearLive(view)

        var effect = android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "input")
        effect = android.graphics.RenderEffect.createColorFilterEffect(
            ColorMatrixColorFilter(FilterEngine.buildMatrix(state)), effect)
        if (blurPx > 0f) effect = android.graphics.RenderEffect.createBlurEffect(
            blurPx, blurPx, effect, Shader.TileMode.CLAMP)
        view.setRenderEffect(effect)
    }

    fun clearLive(view: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) view.setRenderEffect(null)
    }

    /** Top-level dispatcher. */
    fun applyLive(view: View, state: EditState, blurPx: Float, rect: RectF,
                  curveBmp: Bitmap?, lutBmp: Bitmap?, lutSize: Int,
                  imgW: Int = rect.width().toInt(), imgH: Int = rect.height().toInt()) {
        val sdk = Build.VERSION.SDK_INT
        when {
            sdk >= 33 && state.filter.pass != PixelPassKind.NONE ->
                applyConvLive(view, state, blurPx,
                    kotlin.math.hypot(imgW.toFloat(), imgH.toFloat()), imgW, imgH)
            sdk >= 33 -> applyLiveAgsl(view, state, blurPx, curveBmp, lutBmp, lutSize, rect)
            sdk >= 31 -> applyLiveLegacy(view, state, blurPx)
            else -> clearLive(view)
        }
    }
}
