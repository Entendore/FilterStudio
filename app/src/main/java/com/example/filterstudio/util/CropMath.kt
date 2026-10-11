package com.example.filterstudio.util

import com.example.filterstudio.model.CropRect
import kotlin.math.abs
import kotlin.math.hypot

object CropMath {
    enum class Handle { TL, TR, BL, BR, T, B, L, R, INSIDE, NONE }

    fun hitTest(r: CropRect, x: Float, y: Float, cornerR: Float, edgeR: Float): Handle {
        val corners = arrayOf(
            Handle.TL to (r.left to r.top), Handle.TR to (r.right to r.top),
            Handle.BL to (r.left to r.bottom), Handle.BR to (r.right to r.bottom))
        for ((h, p) in corners)
            if (hypot(x - p.first, y - p.second) <= cornerR) return h
        val inX = x in r.left..r.right; val inY = y in r.top..r.bottom
        if (inX && abs(y - r.top) <= edgeR) return Handle.T
        if (inX && abs(y - r.bottom) <= edgeR) return Handle.B
        if (inY && abs(x - r.left) <= edgeR) return Handle.L
        if (inY && abs(x - r.right) <= edgeR) return Handle.R
        return if (inX && inY) Handle.INSIDE else Handle.NONE
    }

    fun move(r: CropRect, dx: Float, dy: Float): CropRect = CropRect(
        (r.left + dx).coerceIn(0f, 1f - r.width),
        (r.top + dy).coerceIn(0f, 1f - r.height), r.width, r.height)

    fun resize(start: CropRect, handle: Handle, x: Float, y: Float,
               aspect: Float?, min: Float): CropRect {
        if (handle == Handle.INSIDE || handle == Handle.NONE) return start
        val movesL = handle == Handle.TL || handle == Handle.BL || handle == Handle.L
        val movesR = handle == Handle.TR || handle == Handle.BR || handle == Handle.R
        val movesT = handle == Handle.TL || handle == Handle.TR || handle == Handle.T
        val movesB = handle == Handle.BL || handle == Handle.BR || handle == Handle.B
        val isCorner = (movesL || movesR) && (movesT || movesB)
        var l = start.left; var t = start.top; var r = start.right; var b = start.bottom
        if (movesL) l = x.coerceIn(0f, r - min)
        if (movesR) r = x.coerceIn(l + min, 1f)
        if (movesT) t = y.coerceIn(0f, b - min)
        if (movesB) b = y.coerceIn(t + min, 1f)
        if (aspect == null) return CropRect(l, t, r - l, b - t)
        if (isCorner) {
            val ax = if (movesL) r else l; val ay = if (movesT) b else t
            var w = (if (movesL) ax - l else r - ax).coerceAtLeast(min)
            var hh = w / aspect
            val maxH = if (movesT) ay else 1f - ay
            if (hh > maxH) { hh = maxH; w = hh * aspect }
            val maxW = if (movesL) ax else 1f - ax
            if (w > maxW) w = maxW
            l = if (movesL) ax - w else ax; r = if (movesL) ax else ax + w
            t = if (movesT) ay - hh else ay; b = if (movesT) ay else ay + hh
        } else {
            val cx = (l + r) / 2f; val cy = (t + b) / 2f
            if (movesL || movesR) {
                var w = r - l; var hh = w / aspect
                if (hh > 1f) { hh = 1f; w = hh * aspect }
                t = (cy - hh / 2f).coerceIn(0f, 1f - hh); b = t + hh
            } else {
                var hh = b - t; var w = hh * aspect
                if (w > 1f) { w = 1f; hh = w / aspect }
                l = (cx - w / 2f).coerceIn(0f, 1f - w); r = l + w
            }
        }
        return CropRect(l, t, r - l, b - t)
    }

    fun centeredFull(imageAspect: Float, aspect: Float?): CropRect {
        aspect ?: return CropRect(0f, 0f, 1f, 1f)
        return if (imageAspect > aspect) {
            val w = aspect / imageAspect; CropRect((1f - w) / 2f, 0f, w, 1f)
        } else {
            val h = imageAspect / aspect; CropRect(0f, (1f - h) / 2f, 1f, h)
        }
    }
}
