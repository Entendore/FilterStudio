package com.example.filterstudio.engine

import android.graphics.RectF
import com.example.filterstudio.model.CropRect

object MaskShaderMath {
    fun maskUv(x: Float, y: Float, rect: RectF, crop: CropRect?): Pair<Float, Float> {
        val uvx = ((x - rect.left) / rect.width().coerceAtLeast(1f)).coerceIn(0f, 1f)
        val uvy = ((y - rect.top) / rect.height().coerceAtLeast(1f)).coerceIn(0f, 1f)
        return (crop?.left ?: 0f) + uvx * (crop?.width ?: 1f) to
               (crop?.top ?: 0f) + uvy * (crop?.height ?: 1f)
    }
}
