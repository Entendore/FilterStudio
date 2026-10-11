package com.example.filterstudio.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import com.example.filterstudio.engine.FilterEngine

class HistogramView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {
    private var r = IntArray(0); private var g = IntArray(0); private var b = IntArray(0)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun setBitmap(source: Bitmap) {
        val small = Bitmap.createScaledBitmap(
            source, 128, (128f * source.height / source.width).toInt().coerceAtLeast(1), true)
        val px = small.pixels()
        r = IntArray(256); g = IntArray(256); b = IntArray(256)
        for (p in px) {
            r[(p shr 16) and 0xFF]++; g[(p shr 8) and 0xFF]++; b[p and 0xFF]++
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (r.isEmpty()) return
        val max = maxOf(r.max(), g.max(), b.max()).coerceAtLeast(1)
        drawChannel(canvas, r, Color.RED, max)
        drawChannel(canvas, g, Color.GREEN, max)
        drawChannel(canvas, b, Color.BLUE, max)
    }

    private fun drawChannel(canvas: Canvas, hist: IntArray, color: Int, max: Int) {
        val path = Path().apply {
            moveTo(0f, height.toFloat())
            for (i in 0..255)
                lineTo(i / 255f * width, height - hist[i].toFloat() / max * height * 0.95f)
            lineTo(width.toFloat(), height.toFloat()); close()
        }
        paint.color = color; paint.alpha = 90; paint.style = Paint.Style.FILL
        canvas.drawPath(path, paint)
    }
}
