package com.example.filterstudio.engine

import android.graphics.Bitmap
import android.graphics.Color
import com.example.filterstudio.model.CurvePoint
import com.example.filterstudio.model.Curves
import kotlin.math.roundToInt

object CurveEngine {

    fun buildLut(pointsIn: List<CurvePoint>): FloatArray {
        val pts = pointsIn.sortedBy { it.x }.toMutableList()
        if (pts.first().x > 0f) pts.add(0, CurvePoint(0f, pts.first().y))
        if (pts.last().x < 1f) pts.add(CurvePoint(1f, pts.last().y))
        val p = mutableListOf(pts.first())
        for (q in pts.drop(1)) if (q.x - p.last().x > 1e-4f) p.add(q)
        if (p.size < 2) return FloatArray(256) { it / 255f }

        val n = p.size
        val dx = FloatArray(n - 1); val d = FloatArray(n - 1)
        for (i in 0 until n - 1) { dx[i] = p[i+1].x - p[i].x; d[i] = (p[i+1].y - p[i].y) / dx[i] }
        val m = FloatArray(n)
        m[0] = d[0]; m[n-1] = d[n-2]
        for (i in 1 until n - 1)
            m[i] = if (d[i-1] * d[i] <= 0f) 0f else 2f / (1f / d[i-1] + 1f / d[i])
        val out = FloatArray(256); var seg = 0
        for (i in 0..255) {
            val x = i / 255f
            while (seg < n - 2 && x > p[seg + 1].x) seg++
            val t = ((x - p[seg].x) / dx[seg]).coerceIn(0f, 1f)
            val t2 = t * t; val t3 = t2 * t
            out[i] = (p[seg].y * (2*t3 - 3*t2 + 1) + dx[seg] * m[seg] * (t3 - 2*t2 + t) +
                      p[seg+1].y * (-2*t3 + 3*t2) + dx[seg] * m[seg+1] * (t3 - t2)).coerceIn(0f, 1f)
        }
        return out
    }

    private fun grayToPlane(lut: FloatArray): IntArray =
        IntArray(256) { i -> (lut[i] * 255f).roundToInt().coerceIn(0, 255) }

    fun finalLuts(curves: Curves): IntArray? {
        if (curves.isNeutral) return null
        val rgb = grayToPlane(buildLut(curves.rgb))
        fun composed(channel: List<CurvePoint>): IntArray {
            val ch = grayToPlane(buildLut(channel))
            return IntArray(256) { i -> rgb[ch[i]] }
        }
        return composed(curves.red) + composed(curves.green) + composed(curves.blue)
    }

    /** g after f (point-op composition); either may be null. */
    fun compose(f: IntArray?, g: IntArray?): IntArray? = when {
        f == null -> g
        g == null -> f
        else -> IntArray(768) { i -> g[i / 256 * 256 + f[i]] }
    }

    fun curveBitmap(luts: IntArray): Bitmap {
        val bmp = Bitmap.createBitmap(256, 3, Bitmap.Config.ARGB_8888)
        for (i in 0..255) {
            bmp.setPixel(i, 0, Color.rgb(luts[i], luts[i], luts[i]))
            bmp.setPixel(i, 1, Color.rgb(luts[256+i], luts[256+i], luts[256+i]))
            bmp.setPixel(i, 2, Color.rgb(luts[512+i], luts[512+i], luts[512+i]))
        }
        return bmp
    }

    val PRESETS = mapOf(
        "Contrast S" to listOf(CurvePoint(0f,0f), CurvePoint(0.28f,0.22f), CurvePoint(0.72f,0.78f), CurvePoint(1f,1f)),
        "Matte" to listOf(CurvePoint(0f,0.07f), CurvePoint(0.45f,0.47f), CurvePoint(1f,0.96f)),
        "Crush" to listOf(CurvePoint(0f,0f), CurvePoint(0.5f,0.4f), CurvePoint(1f,0.9f)),
        "Lift" to listOf(CurvePoint(0f,0.12f), CurvePoint(0.5f,0.55f), CurvePoint(1f,1f)),
        "Punch" to listOf(CurvePoint(0f,0f), CurvePoint(0.35f,0.28f), CurvePoint(0.65f,0.74f), CurvePoint(1f,1f)))
}
