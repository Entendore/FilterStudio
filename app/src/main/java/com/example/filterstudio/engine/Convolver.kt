package com.example.filterstudio.engine

import com.example.filterstudio.model.EdgePresetsCompat
import kotlin.math.*

object Convolver {
    // ---------- pack / blend ----------
    fun pack(r: Float, g: Float, b: Float): Int = (255 shl 24) or
        (r.roundToInt().coerceIn(0, 255) shl 16) or
        (g.roundToInt().coerceIn(0, 255) shl 8) or b.roundToInt().coerceIn(0, 255)

    fun blend(orig: Int, new: Int, t: Float): Int {
        if (t >= 1f) return new
        val or = (orig shr 16) and 0xFF; val og = (orig shr 8) and 0xFF; val ob = orig and 0xFF
        val nr = (new shr 16) and 0xFF; val ng = (new shr 8) and 0xFF; val nb = new and 0xFF
        return (orig and 0xFF000000.toInt()) or
            (or + ((nr - or) * t).roundToInt() shl 16) or
            (og + ((ng - og) * t).roundToInt() shl 8) or
            (ob + ((nb - ob) * t).roundToInt())
    }

    private fun screen(x: Int, y: Int) = 255 - ((255 - x) * (255 - y)) / 255

    // ---------- generic dense convolution ----------
    fun convolve(src: IntArray, w: Int, h: Int, k: Kernel, blendT: Float = 1f): IntArray {
        val r = k.radius; val kk = k.data; val inv = 1f / k.scale; val off = k.offset
        val out = IntArray(src.size)
        for (y in 0 until h) {
            val rowBase = y * w
            for (x in 0 until w) {
                var sr = 0f; var sg = 0f; var sb = 0f; var idx = 0
                for (dy in -r..r) {
                    val yy = (y + dy).coerceIn(0, h - 1) * w
                    for (dx in -r..r) {
                        val kv = kk[idx++]; if (kv == 0f) continue
                        val c = src[yy + (x + dx).coerceIn(0, w - 1)]
                        sr += kv * ((c shr 16) and 0xFF)
                        sg += kv * ((c shr 8) and 0xFF); sb += kv * (c and 0xFF)
                    }
                }
                out[rowBase + x] = blend(src[rowBase + x],
                    pack(sr * inv + off, sg * inv + off, sb * inv + off), blendT)
            }
        }
        return out
    }

    // ---------- separable convolution ----------
    fun convolveSeparable(src: IntArray, w: Int, h: Int,
                          row: FloatArray, col: FloatArray, blendT: Float = 1f): IntArray {
        val rx = row.size / 2; val ry = col.size / 2
        val rs = row.sum(); val cs = col.sum()
        val tmp = IntArray(src.size); val out = IntArray(src.size)
        for (y in 0 until h) for (x in 0 until w) {
            var sr = 0f; var sg = 0f; var sb = 0f
            for (i in row.indices) {
                val c = src[y * w + (x + i - rx).coerceIn(0, w - 1)]; val wt = row[i] / rs
                sr += wt * ((c shr 16) and 0xFF); sg += wt * ((c shr 8) and 0xFF); sb += wt * (c and 0xFF)
            }
            tmp[y * w + x] = pack(sr, sg, sb)
        }
        for (y in 0 until h) for (x in 0 until w) {
            var sr = 0f; var sg = 0f; var sb = 0f
            for (i in col.indices) {
                val c = tmp[(y + i - ry).coerceIn(0, h - 1) * w + x]; val wt = col[i] / cs
                sr += wt * ((c shr 16) and 0xFF); sg += wt * ((c shr 8) and 0xFF); sb += wt * (c and 0xFF)
            }
            val i2 = y * w + x
            out[i2] = blend(src[i2], pack(sr, sg, sb), blendT)
        }
        return out
    }

    // ---------- unsharp ----------
    fun unsharp(src: IntArray, w: Int, h: Int, amount: Float): IntArray {
        if (amount <= 0f) return src.copyOf()
        val blurred = convolveSeparable(src, w, h, Kernels.GAUSS1_3, Kernels.GAUSS1_3, 0f)
        val out = IntArray(src.size)
        for (i in src.indices) {
            val o = src[i]; val b = blurred[i]
            out[i] = pack(
                ((o shr 16) and 0xFF) + amount * (((o shr 16) and 0xFF) - ((b shr 16) and 0xFF)),
                ((o shr 8) and 0xFF) + amount * (((o shr 8) and 0xFF) - ((b shr 8) and 0xFF)),
                (o and 0xFF) + amount * ((o and 0xFF) - (b and 0xFF)))
        }
        return out
    }

    // ---------- Sobel ----------
    fun edgeMagnitude(src: IntArray, w: Int, h: Int): FloatArray {
        val lum = FloatArray(src.size) { i ->
            val c = src[i]
            0.299f * ((c shr 16) and 0xFF) + 0.587f * ((c shr 8) and 0xFF) + 0.114f * (c and 0xFF)
        }
        val out = FloatArray(src.size)
        val kx = Kernels.SOBEL_X.data; val ky = Kernels.SOBEL_Y.data
        for (y in 0 until h) for (x in 0 until w) {
            var gx = 0f; var gy = 0f; var idx = 0
            for (dy in -1..1) {
                val yy = (y + dy).coerceIn(0, h - 1) * w
                for (dx in -1..1) {
                    val l = lum[yy + (x + dx).coerceIn(0, w - 1)]
                    gx += kx[idx] * l; gy += ky[idx] * l; idx++
                }
            }
            out[y * w + x] = (hypot(gx, gy) * EdgePresets.SOBEL_GAIN / 255f).coerceIn(0f, 1f)
        }
        return out
    }

    fun sobel(src: IntArray, w: Int, h: Int, blendT: Float = 1f): IntArray {
        val mag = edgeMagnitude(src, w, h)
        val out = IntArray(src.size)
        for (i in src.indices) {
            val v = (255f - mag[i] * 255f).roundToInt().coerceIn(0, 255)
            out[i] = blend(src[i], (255 shl 24) or (v * 0x010101), blendT)
        }
        return out
    }

    // ---------- Kuwahara ----------
    fun kuwahara(src: IntArray, w: Int, h: Int, radius: Int, blendT: Float = 1f): IntArray {
        val out = IntArray(src.size)
        for (y in 0 until h) for (x in 0 until w) {
            var bestVar = Float.MAX_VALUE; var br = 0; var bg = 0; var bb = 0
            for (q in 0 until 4) {
                val sx = if (q and 1 == 0) -1 else 1; val sy = if (q and 2 == 0) -1 else 1
                var sr = 0f; var sg = 0f; var sb = 0f; var sl = 0f; var sl2 = 0f; var n = 0
                for (dy in 0..radius) for (dx in 0..radius) {
                    val c = src[(y + dy * sy).coerceIn(0, h - 1) * w + (x + dx * sx).coerceIn(0, w - 1)]
                    val R = ((c shr 16) and 0xFF).toFloat(); val G = ((c shr 8) and 0xFF).toFloat()
                    val B = (c and 0xFF).toFloat()
                    sr += R; sg += G; sb += B
                    val l = 0.299f * R + 0.587f * G + 0.114f * B
                    sl += l; sl2 += l * l; n++
                }
                val mL = sl / n; val v = sl2 / n - mL * mL
                if (v < bestVar) {
                    bestVar = v
                    br = (sr / n).roundToInt(); bg = (sg / n).roundToInt(); bb = (sb / n).roundToInt()
                }
            }
            val i2 = y * w + x
            out[i2] = blend(src[i2], (255 shl 24) or (br shl 16) or (bg shl 8) or bb, blendT)
        }
        return out
    }

    // ---------- glow / orton ----------
    fun glow(px: IntArray, w: Int, h: Int, threshold: Float = 0.62f, blendT: Float = 1f): IntArray {
        val bright = IntArray(px.size)
        for (i in px.indices) {
            val c = px[i]
            val R = ((c shr 16) and 0xFF); val G = ((c shr 8) and 0xFF); val B = c and 0xFF
            val l = (0.299f * R + 0.587f * G + 0.114f * B) / 255f
            val f = if (l > threshold) (l - threshold) / (1f - threshold) else 0f
            bright[i] = (255 shl 24) or ((R * f).roundToInt().coerceAtMost(255) shl 16) or
                ((G * f).roundToInt().coerceAtMost(255) shl 8) or (B * f).roundToInt().coerceAtMost(255)
        }
        var blurred = bright
        repeat(2) { blurred = convolveSeparable(blurred, w, h, Kernels.GAUSS1_5, Kernels.GAUSS1_5, 0f) }
        val out = IntArray(px.size)
        for (i in px.indices) {
            val a = px[i]; val b = blurred[i]
            out[i] = blend(a, (255 shl 24) or
                (screen((a shr 16) and 0xFF, (b shr 16) and 0xFF) shl 16) or
                (screen((a shr 8) and 0xFF, (b shr 8) and 0xFF) shl 8) or
                screen(a and 0xFF, b and 0xFF), blendT)
        }
        return out
    }

    fun orton(px: IntArray, w: Int, h: Int, blendT: Float = 1f): IntArray {
        val blurred = convolve(px, w, h, Kernels.GAUSS_2D_3, 0f)
        val out = IntArray(px.size)
        for (i in px.indices) {
            val a = px[i]; val b = blurred[i]
            out[i] = blend(a, (255 shl 24) or
                (screen((a shr 16) and 0xFF, (b shr 16) and 0xFF) shl 16) or
                (screen((a shr 8) and 0xFF, (b shr 8) and 0xFF) shl 8) or
                screen(a and 0xFF, b and 0xFF), blendT)
        }
        return out
    }

    // ---------- comic / halftone ----------
    private val BAYER4 = arrayOf(intArrayOf(0,8,2,10), intArrayOf(12,4,14,6),
        intArrayOf(3,11,1,9), intArrayOf(15,7,13,5))

    fun posterizeInPlace(px: IntArray, levels: Int = 5) {
        val lut = posterizeLut(levels)
        for (i in px.indices) {
            val p = px[i]
            px[i] = (p and 0xFF000000.toInt()) or
                (lut[(p shr 16) and 0xFF] shl 16) or (lut[(p shr 8) and 0xFF] shl 8) or lut[p and 0xFF]
        }
    }

    fun posterizeLut(levels: Int): IntArray = IntArray(256) { v ->
        ((v * (levels - 1) + 127) / 255) * (255 / (levels - 1))
    }

    fun comic(px: IntArray, w: Int, h: Int, blendT: Float = 1f): IntArray {
        posterizeInPlace(px, 4)
        val mag = edgeMagnitude(px, w, h)
        val out = IntArray(px.size)
        for (i in px.indices) {
            val f = 1f - mag[i] * 0.9f; val p = px[i]
            out[i] = blend(p, (255 shl 24) or
                (((p shr 16) and 0xFF) * f).roundToInt() shl 16 or
                (((p shr 8) and 0xFF) * f).roundToInt() shl 8 or
                ((p and 0xFF) * f).roundToInt(), blendT)
        }
        return out
    }

    fun halftone(px: IntArray, w: Int, h: Int, blendT: Float = 1f): IntArray {
        val out = IntArray(px.size)
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x; val p = px[i]
            val l = (0.299f * ((p shr 16) and 0xFF) + 0.587f * ((p shr 8) and 0xFF) +
                0.114f * (p and 0xFF)) / 255f
            val t = (BAYER4[y and 3][x and 3] + 0.5f) / 16f
            val v = if (l >= t) 255 else 0
            out[i] = blend(p, (255 shl 24) or (v * 0x010101), blendT)
        }
        return out
    }

    fun watercolor(px: IntArray, w: Int, h: Int, blendT: Float = 1f): IntArray {
        val work = convolve(px, w, h, Kernels.GAUSS_2D_3, 0f)
        posterizeInPlace(work, 6)
        val mag = edgeMagnitude(work, w, h)
        val out = IntArray(px.size)
        for (i in px.indices) {
            val f = 1f - mag[i] * 0.45f; val p = work[i]
            out[i] = blend(px[i], pack(
                ((p shr 16) and 0xFF) * f, ((p shr 8) and 0xFF) * f, (p and 0xFF) * f), blendT)
        }
        return out
    }

    fun manga(px: IntArray, w: Int, h: Int, blendT: Float = 1f): IntArray {
        val mag = edgeMagnitude(px, w, h)
        val out = IntArray(px.size)
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x; val p = px[i]
            var l = (0.299f * ((p shr 16) and 0xFF) + 0.587f * ((p shr 8) and 0xFF) +
                0.114f * (p and 0xFF)) / 255f
            l = (l * 3f).roundToInt() / 3f
            var v = (l * 255f).roundToInt()
            if (l in 0.30f..0.75f) {
                val t = (BAYER4[y and 3][x and 3] + 0.5f) / 16f
                v = if (l < t + 0.18f) 40 else 235
            }
            if (mag[i] > 0.32f) v = 0
            out[i] = blend(p, (255 shl 24) or (v * 0x010101), blendT)
        }
        return out
    }

    // ---------- motion blur (bilinear line integration) ----------
    private fun bilinear3(src: IntArray, w: Int, h: Int, fx: Float, fy: Float): FloatArray {
        val x1 = floor(fx).toInt(); val y1 = floor(fy).toInt()
        val tx = fx - x1; val ty = fy - y1
        val xa = x1.coerceIn(0, w - 1); val xb = (x1 + 1).coerceIn(0, w - 1)
        val ya = y1.coerceIn(0, h - 1); val yb = (y1 + 1).coerceIn(0, h - 1)
        val ca = src[ya * w + xa]; val cb = src[ya * w + xb]
        val cc = src[yb * w + xa]; val cd = src[yb * w + xb]
        fun ch(s: Int): Float {
            val v1 = (ca shr s) and 0xFF; val v2 = (cb shr s) and 0xFF
            val v3 = (cc shr s) and 0xFF; val v4 = (cd shr s) and 0xFF
            val top = v1 + (v2 - v1) * tx; val bot = v3 + (v4 - v3) * tx
            return top + (bot - top) * ty
        }
        return floatArrayOf(ch(16), ch(8), ch(0))
    }

    fun motionBlur(src: IntArray, w: Int, h: Int, angleDeg: Float,
                   extentPx: Float, taps: Int, blendT: Float = 1f): IntArray {
        if (extentPx <= 0.5f) return src.copyOf()
        val rad = Math.toRadians(angleDeg.toDouble())
        val dx = cos(rad).toFloat(); val dy = sin(rad).toFloat()
        val step = extentPx / taps
        val out = IntArray(src.size)
        for (y in 0 until h) for (x in 0 until w) {
            var sr = 0f; var sg = 0f; var sb = 0f
            for (i in 0 until taps) {
                val t = -extentPx / 2f + (i + 0.5f) * step
                val c = bilinear3(src, w, h, x + dx * t, y + dy * t)
                sr += c[0]; sg += c[1]; sb += c[2]
            }
            val i2 = y * w + x
            out[i2] = blend(src[i2], pack(sr / taps, sg / taps, sb / taps), blendT)
        }
        return out
    }

    fun motionBlurFractional(src: IntArray, w: Int, h: Int, angleDeg: Float,
                             permille: Int, blendT: Float): IntArray {
        val extent = permille / 1000f * hypot(w.toFloat(), h.toFloat())
        val taps = (extent / 2f).roundToInt().coerceIn(5, 48)
        return motionBlur(src, w, h, angleDeg, extent, taps, blendT)
    }

    // ---------- ink / engrave / swirl / fisheye / tilt ----------
    fun inkOutline(src: IntArray, w: Int, h: Int, threshold: Float,
                   invert: Boolean, blendT: Float = 1f): IntArray {
        val mag = edgeMagnitude(src, w, h)
        val line = if (invert) 255 else 0; val bg = if (invert) 0 else 255
        val out = IntArray(src.size)
        for (i in src.indices) {
            val v = if (mag[i] > threshold.coerceIn(0f, 1f)) line else bg
            out[i] = blend(src[i], (255 shl 24) or (v * 0x010101), blendT)
        }
        return out
    }

    fun engrave(src: IntArray, w: Int, h: Int, periodFrac: Float, blendT: Float = 1f): IntArray {
        val period = (periodFrac * min(w, h)).coerceAtLeast(2f)
        val out = IntArray(src.size)
        for (y in 0 until h) {
            val shade = 0.60f + 0.40f * sin(2.0 * PI * y / period).toFloat()
            for (x in 0 until w) {
                val i = y * w + x; val p = src[i]
                val l = 0.299f * ((p shr 16) and 0xFF) + 0.587f * ((p shr 8) and 0xFF) + 0.114f * (p and 0xFF)
                out[i] = blend(p, (255 shl 24) or
                    ((l * shade).roundToInt().coerceIn(0, 255) * 0x010101), blendT)
            }
        }
        return out
    }

    fun swirl(src: IntArray, w: Int, h: Int, strength: Float, blendT: Float = 1f): IntArray {
        val cx = w / 2f; val cy = h / 2f; val rMax = min(cx, cy)
        val out = IntArray(src.size)
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x; val dx = x - cx; val dy = y - cy
            val r = hypot(dx, dy) / rMax
            if (r >= 1f) { out[i] = src[i]; continue }
            val ang = (1f - r) * (1f - r) * strength * 2f * PI.toFloat()
            val cs = cos(ang); val sn = sin(ang)
            val c = bilinear3(src, w, h, cx + dx * cs - dy * sn, cy + dx * sn + dy * cs)
            out[i] = blend(src[i], pack(c[0], c[1], c[2]), blendT)
        }
        return out
    }

    fun fisheye(src: IntArray, w: Int, h: Int, strength: Float, blendT: Float = 1f): IntArray {
        val k = 1f + strength.coerceIn(-0.9f, 2f)
        val cx = w / 2f; val cy = h / 2f; val rMax = min(cx, cy)
        val out = IntArray(src.size)
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x; val dx = x - cx; val dy = y - cy
            val r = hypot(dx, dy)
            if (r >= rMax || r < 1e-4f) { out[i] = src[i]; continue }
            val scale = r.pow(k) / r
            val c = bilinear3(src, w, h, cx + dx * scale, cy + dy * scale)
            out[i] = blend(src[i], pack(c[0], c[1], c[2]), blendT)
        }
        return out
    }

    fun tiltShift(px: IntArray, w: Int, h: Int, focusY: Float, band: Float, blendT: Float = 1f): IntArray {
        val out = px.copyOf()
        fun tap(x: Int, y: Int, c: Int) =
            (px[y.coerceIn(0, h - 1) * w + x.coerceIn(0, w - 1)] shr c) and 0xFF
        for (y in 0 until h) {
            val d = abs(y / h.toFloat() - focusY)
            val raw = ((d - band) / band.coerceAtLeast(0.01f)).coerceIn(0f, 1f)
            val t = raw * raw * (3f - 2f * raw) * blendT
            if (t <= 0f) continue
            for (x in 0 until w) {
                val i = y * w + x
                fun ch(c: Int) = (tap(x - 3, y, c) + tap(x + 3, y, c) +
                    tap(x, y - 3, c) + tap(x, y + 3, c) + tap(x - 2, y - 2, c) +
                    tap(x + 2, y - 2, c) + tap(x - 2, y + 2, c) + tap(x + 2, y + 2, c) +
                    4 * tap(x, y, c)) / 12
                out[i] = blend(px[i], (255 shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0), t)
            }
        }
        return out
    }

    // ---------- mask composite ----------
    fun blendByMask(neutral: IntArray, edited: IntArray, w: Int, h: Int,
                    maskPx: IntArray, mw: Int, mh: Int, crop: com.example.filterstudio.model.CropRect?): IntArray {
        val out = IntArray(edited.size)
        val cl = ((crop?.left ?: 0f) * mw).toInt()
        val ct = ((crop?.top ?: 0f) * mh).toInt()
        val sx = mw.toFloat() / w * (crop?.width ?: 1f)
        val sy = mh.toFloat() / h * (crop?.height ?: 1f)
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            val m = (maskPx[(ct + (y * sy).toInt()) * mw + cl + (x * sx).toInt()] shr 16) and 0xFF
            if (m == 0) { out[i] = neutral[i]; continue }
            if (m == 255) { out[i] = edited[i]; continue }
            val a = neutral[i]; val e = edited[i]
            fun mix(sa: Int, se: Int) = sa + ((se - sa) * m) / 255
            out[i] = (a and 0xFF000000.toInt()) or
                (mix((a shr 16) and 0xFF, (e shr 16) and 0xFF) shl 16) or
                (mix((a shr 8) and 0xFF, (e shr 8) and 0xFF) shl 8) or
                mix(a and 0xFF, e and 0xFF)
        }
        return out
    }
}
