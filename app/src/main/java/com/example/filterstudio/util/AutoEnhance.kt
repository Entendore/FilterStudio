package com.example.filterstudio.util

import android.graphics.Bitmap
import com.example.filterstudio.engine.FilterEngine
import com.example.filterstudio.model.Adjustments
import kotlin.math.roundToInt

object ImageStats {
    fun analyze(src: Bitmap): Pair<IntArray, Float> {
        val small = Bitmap.createScaledBitmap(
            src, 96, (96f * src.height / src.width).toInt().coerceAtLeast(1), true)
        val px = small.pixels()
        val hist = IntArray(256); var satSum = 0f
        for (p in px) {
            val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
            val mx = maxOf(r, g, b); val mn = minOf(r, g, b)
            hist[(0.299f*r + 0.587f*g + 0.114f*b).roundToInt().coerceIn(0, 255)]++
            if (mx > 0) satSum += (mx - mn).toFloat() / mx
        }
        return hist to satSum / px.size
    }
    fun percentile(hist: IntArray, total: Int, frac: Float): Int {
        var acc = 0L; val target = total * frac
        for (v in 0..255) { acc += hist[v]; if (acc >= target) return v }
        return 255
    }
}

object AutoEnhance {
    fun suggest(low: Int, high: Int, satMean: Float): Adjustments {
        val span = (high - low).coerceAtLeast(1)
        val c = (255f / span).coerceIn(0.9f, 1.5f)
        val b = (-(c * low + (1f - c) * 128f) / 255f).coerceIn(-0.35f, 0.35f)
        val s = 1f + (0.30f - satMean).coerceIn(0f, 0.30f)
        return Adjustments(brightness = b, contrast = c, saturation = s)
    }
}
