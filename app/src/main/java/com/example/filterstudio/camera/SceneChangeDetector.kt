package com.example.filterstudio.camera

import android.graphics.Bitmap
import kotlin.math.abs

object SceneChangeDetector {
    fun histogram(src: Bitmap): IntArray {
        val small = Bitmap.createScaledBitmap(src, 32, 32, true)
        val px = IntArray(32 * 32); small.getPixels(px, 0, 32, 0, 0, 32, 32)
        val hist = IntArray(32)
        for (p in px) {
            val l = (0.299f * ((p shr 16) and 0xFF) +
                0.587f * ((p shr 8) and 0xFF) + 0.114f * (p and 0xFF))
            hist[(l / 8f).toInt().coerceIn(0, 31)]++
        }
        return hist
    }

    fun distance(a: IntArray, b: IntArray): Float {
        val total = a.sum().coerceAtLeast(1).toFloat()
        return a.indices.sumOf { abs(a[it] - b[it]).toDouble() }.toFloat() / total
    }

    const val THRESHOLD = 0.06f
    fun changed(a: IntArray, b: IntArray) = distance(a, b) > THRESHOLD
}
