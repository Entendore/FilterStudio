package com.example.filterstudio.engine

import android.graphics.Bitmap
import android.graphics.Color
import java.io.File
import java.io.Reader
import kotlin.math.min
import kotlin.math.roundToInt

class Lut3D(val size: Int, val data: FloatArray) {
    init { require(data.size == size * size * size * 3) }
    fun sample(rF: Float, gF: Float, bF: Float): FloatArray {
        val n = size - 1
        val r = rF.coerceIn(0f,1f) * n; val g = gF.coerceIn(0f,1f) * n; val b = bF.coerceIn(0f,1f) * n
        val ri = min(n.toInt(), r.toInt()); val gi = min(n.toInt(), g.toInt()); val bi = min(n.toInt(), b.toInt())
        val rd = r - ri; val gd = g - gi; val bd = b - bi
        fun px(rI: Int, gI: Int, bI: Int, c: Int) = data[(rI + gI*size + bI*size*size) * 3 + c]
        fun mix(a: Float, b: Float, t: Float) = a + (b - a) * t
        fun tri(c: Int): Float {
            val x00 = mix(px(ri,gi,bi,c), px(ri+1,gi,bi,c), rd)
            val x10 = mix(px(ri,gi+1,bi,c), px(ri+1,gi+1,bi,c), rd)
            val x01 = mix(px(ri,gi,bi+1,c), px(ri+1,gi,bi+1,c), rd)
            val x11 = mix(px(ri,gi+1,bi+1,c), px(ri+1,gi+1,bi+1,c), rd)
            return mix(mix(x00,x10,gd), mix(x01,x11,gd), bd)
        }
        return floatArrayOf(tri(0), tri(1), tri(2))
    }

    /** Packed 2D texture: x = r + g·N, y = b. */
    fun toPackedBitmap(): Bitmap {
        val bmp = Bitmap.createBitmap(size * size, size, Bitmap.Config.ARGB_8888)
        val px = IntArray(size * size * size)
        for (b in 0 until size) for (g in 0 until size) for (r in 0 until size) {
            val i = (r + g*size + b*size*size) * 3
            px[b*size*size + g*size + r] = Color.argb(255,
                (data[i]*255f).roundToInt(), (data[i+1]*255f).roundToInt(), (data[i+2]*255f).roundToInt())
        }
        bmp.setPixels(px, 0, size*size, 0, 0, size*size, size)
        return bmp
    }
}

object CubeParser {
    fun parse(reader: Reader): Lut3D {
        var size = -1
        val values = ArrayList<Float>(32768)
        reader.forEachLine { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEachLine
            val p = line.split(Regex("\\s+"))
            when {
                p[0].equals("LUT_3D_SIZE", true) -> size = p[1].toInt()
                p[0].startsWith("LUT_") || p[0].startsWith("DOMAIN") || p[0].equals("TITLE", true) -> Unit
                else -> if (size > 0) p.take(3).forEach { values.add(it.toFloat()) }
            }
        }
        require(size in 2..128)
        require(values.size == size * size * size * 3)
        return Lut3D(size, values.toFloatArray())
    }
    fun parse(file: File): Lut3D = file.bufferedReader().use { parse(it) }
}

object CubeExporter {
    fun export(state: com.example.filterstudio.model.EditState, lut: Lut3D?, size: Int = 33): String {
        val mat = FilterEngine.buildMatrix(state).array
        val curves = FilterEngine.effectiveLuts(state)
        val sb = StringBuilder(size * size * size * 24)
        sb.appendLine("TITLE FilterStudio Export"); sb.appendLine("LUT_3D_SIZE $size")
        sb.appendLine("DOMAIN_MIN 0.0 0.0 0.0"); sb.appendLine("DOMAIN_MAX 1.0 1.0 1.0")
        val inv = 1f / (size - 1)
        for (b in 0 until size) for (g in 0 until size) for (r in 0 until size) {
            val rf = r * inv; val gf = g * inv; val bf = b * inv
            fun ch(i: Int) = (mat[i]*rf + mat[i+1]*gf + mat[i+2]*bf + mat[i+4]/255f).coerceIn(0f, 1f)
            var R = ch(0); var G = ch(5); var B = ch(10)
            curves?.let { c ->
                R = c[(R*255f).roundToInt()] / 255f
                G = c[256 + (G*255f).roundToInt()] / 255f
                B = c[512 + (B*255f).roundToInt()] / 255f }
            lut?.sample(R, G, B)?.let { s -> R = s[0]; G = s[1]; B = s[2] }
            sb.append("%.6f %.6f %.6f\n".format(R, G, B))
        }
        return sb.toString()
    }
}

object BuiltinLuts {
    const val SIZE = 33
    private fun tealOrange(r: Float, g: Float, b: Float): FloatArray {
        val l = 0.299f*r + 0.587f*g + 0.114f*b
        val sw = (1f - l) * (1f - l); val hw = l * l
        return floatArrayOf((r + 0.14f*hw - 0.06f*sw).coerceIn(0f,1f),
            (g + 0.02f*hw + 0.03f*sw).coerceIn(0f,1f),
            (b + 0.10f*sw - 0.12f*hw).coerceIn(0f,1f))
    }
    private fun faded(r: Float, g: Float, b: Float): FloatArray {
        val l = 0.299f*r + 0.587f*g + 0.114f*b
        fun d(c: Float) = (c * 0.75f + l * 0.25f) * 0.94f + 0.055f
        return floatArrayOf((d(r)+0.012f).coerceIn(0f,1f), (d(g)+0.004f).coerceIn(0f,1f),
            (d(b)-0.008f).coerceIn(0f,1f))
    }
    private fun smooth(x: Float) = x * x * (3f - 2f * x)
    private fun blockbuster(r: Float, g: Float, b: Float): FloatArray {
        val t = tealOrange(r, g, b)
        return FloatArray(3) { i -> (t[i] + (smooth(t[i]) - t[i]) * 0.35f).coerceIn(0f, 1f) }
    }
    private fun noirBlue(r: Float, g: Float, b: Float): FloatArray {
        val l = 0.299f*r + 0.587f*g + 0.114f*b
        fun m(c: Float) = l + (c - l) * 0.15f
        return floatArrayOf((m(r)*0.96f).coerceIn(0f,1f), m(g).coerceIn(0f,1f),
            (m(b)*1.08f + 0.02f).coerceIn(0f,1f))
    }
    val RECIPES: Map<String, (Float, Float, Float) -> FloatArray> = linkedMapOf(
        "Teal & Orange" to ::tealOrange, "Faded Film" to ::faded,
        "Blockbuster" to ::blockbuster, "Noir Blue" to ::noirBlue)
    fun build(name: String): Lut3D {
        val f = RECIPES.getValue(name)
        val n = SIZE; val n2 = n * n
        val data = FloatArray(n * n2 * 3)
        for (b in 0 until n) for (g in 0 until n) for (r in 0 until n) {
            val out = f(r / (n - 1f), g / (n - 1f), b / (n - 1f))
            val i = (r + g*n + b*n2) * 3
            data[i] = out[0]; data[i+1] = out[1]; data[i+2] = out[2]
        }
        return Lut3D(n, data)
    }
}
