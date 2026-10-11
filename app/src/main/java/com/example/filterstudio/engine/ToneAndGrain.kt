package com.example.filterstudio.engine

import com.example.filterstudio.model.CurvePoint
import com.example.filterstudio.model.FilterType
import kotlin.math.roundToInt

object ToneCurveGen {
    private fun pts(vararg p: Pair<Float, Float>) = p.map { CurvePoint(it.first, it.second) }
    private fun expand(lut: FloatArray) = IntArray(768) { i -> (lut[i % 256] * 255f).roundToInt() }

    fun shadowsLut(a: Float): IntArray? = when {
        a <= 0f -> null
        else -> expand(CurveEngine.buildLut(pts(
            0f to 0.20f * a, 0.25f to 0.25f + 0.09f * a, 0.55f to 0.55f + 0.02f * a, 1f to 1f)))
    }
    fun highlightsLut(a: Float): IntArray? = when {
        a == 0f -> null
        else -> expand(CurveEngine.buildLut(pts(
            0f to 0f, 0.45f to 0.45f - 0.02f * a, 0.75f to 0.75f - 0.09f * a, 1f to 1f - 0.20f * a)))
    }
}

object FilterCurves {
    private fun pts(vararg p: Pair<Float, Float>) = p.map { CurvePoint(it.first, it.second) }
    private val THERMAL = com.example.filterstudio.model.Curves(
        red = pts(0f to 0f, 0.25f to 0.27f, 0.45f to 0.78f, 0.65f to 1f, 1f to 1f),
        green = pts(0f to 0f, 0.45f to 0.12f, 0.65f to 0.55f, 0.85f to 0.90f, 1f to 1f),
        blue = pts(0f to 0.08f, 0.25f to 0.35f, 0.45f to 0.12f, 0.65f to 0f, 0.85f to 0.31f, 1f to 1f))
    private val cache = HashMap<FilterType, IntArray?>()
    fun lut(type: FilterType): IntArray? = cache.getOrPut(type) {
        when (type) {
            FilterType.THERMAL -> CurveEngine.finalLuts(THERMAL)
            else -> null
        }
    }
}

object Grain {
    fun hash(x: Int, y: Int, seed: Int): Float {
        var h = x * 374761393 + y * 668265263 + seed * 1274126177
        h = (h xor (h ushr 13)) * 1274126177
        return ((h xor (h ushr 16)) and 0xFFFF) / 32767.5f - 1f
    }
    fun apply(src: IntArray, w: Int, h: Int, amount: Float, seed: Int = 0): IntArray {
        if (amount <= 0f) return src
        for (i in src.indices) {
            val d = (hash(i % w, i / w, seed) * amount * 72f).roundToInt()
            val p = src[i]
            fun ch(s: Int) = (((p shr s) and 0xFF) + d).coerceIn(0, 255)
            src[i] = (255 shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }
        return src
    }
}
