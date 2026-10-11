package com.example.filterstudio.util

object BackgroundRemover {
    const val DEFAULT_TOLERANCE = 0.12f

    fun remove(px: IntArray, w: Int, h: Int, tolerance: Float = DEFAULT_TOLERANCE): IntArray {
        fun ch(p: Int, s: Int) = (p shr s) and 0xFF
        var sr = 0L; var sg = 0L; var sb = 0L; var n = 0
        fun acc(p: Int) { sr += ch(p, 16); sg += ch(p, 8); sb += ch(p, 0); n++ }
        for (x in 0 until w) { acc(px[x]); acc(px[(h - 1) * w + x]) }
        for (y in 0 until h) { acc(px[y * w]); acc(px[y * w + w - 1]) }
        val br = sr / n; val bg = sg / n; val bb = sb / n
        val t2 = (tolerance * 441.67f).let { it * it }
        fun close(p: Int): Boolean {
            val dr = ch(p, 16) - br; val dg = ch(p, 8) - bg; val db = ch(p, 0) - bb
            return dr * dr + dg * dg + db * db <= t2
        }
        val out = px.copyOf(); val seen = BooleanArray(w * h)
        val stack = IntArray(w * h); var top = 0
        fun push(i: Int) { if (!seen[i] && close(px[i])) { seen[i] = true; stack[top++] = i } }
        for (x in 0 until w) { push(x); push((h - 1) * w + x) }
        for (y in 0 until h) { push(y * w); push(y * w + w - 1) }
        while (top > 0) {
            val i = stack[--top]
            out[i] = out[i] and 0x00FFFFFF
            val x = i % w; val y = i / w
            if (x > 0) push(i - 1); if (x < w - 1) push(i + 1)
            if (y > 0) push(i - w); if (y < h - 1) push(i + w)
        }
        return out
    }
}
