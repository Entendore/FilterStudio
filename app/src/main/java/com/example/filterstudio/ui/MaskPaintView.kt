package com.example.filterstudio.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.min

class MaskPaintView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {
    enum class Mode { ADD, ERASE }
    var imageRect = RectF()
    var mask: Bitmap? = null
    var mode = Mode.ADD
    var brushFraction = 0.08f
    var onStrokeCommitted: ((Bitmap) -> Unit)? = null

    private val stampPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shaderPaint = Paint()
    private val tintPaint = Paint().apply { color = 0x80FF4081.toInt() }
    private var lastX = 0f; private var lastY = 0f

    override fun onDraw(c: Canvas) {
        val m = mask ?: return
        shaderPaint.shader = BitmapShader(m, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        c.drawRect(imageRect, shaderPaint)
        tintPaint.blendMode = BlendMode.MULTIPLY
        c.drawRect(imageRect, tintPaint)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val m = mask ?: return false
        val c = Canvas(m)
        fun stamp(vx: Float, vy: Float) {
            val nx = ((vx - imageRect.left) / imageRect.width()).coerceIn(0f, 1f)
            val ny = ((vy - imageRect.top) / imageRect.height()).coerceIn(0f, 1f)
            val x = nx * m.width; val y = ny * m.height
            val r = brushFraction * min(m.width, m.height)
            stampPaint.shader = RadialGradient(x, y, r,
                intArrayOf(Color.WHITE, Color.WHITE, Color.TRANSPARENT),
                floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
            stampPaint.blendMode =
                if (mode == Mode.ADD) BlendMode.SRC_OVER else BlendMode.DST_OUT
            c.drawCircle(x, y, r, stampPaint)
            invalidate()
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                stamp(e.x, e.y); lastX = e.x; lastY = e.y }
            MotionEvent.ACTION_MOVE -> {
                val steps = (hypot(e.x - lastX, e.y - lastY) /
                    (brushFraction * imageRect.width() / 3f)).toInt().coerceAtLeast(1)
                for (i in 1..steps) stamp(lastX + (e.x - lastX) * i / steps,
                    lastY + (e.y - lastY) * i / steps)
                lastX = e.x; lastY = e.y }
            MotionEvent.ACTION_UP -> onStrokeCommitted?.invoke(
                m.copy(m.config, false))
        }
        return true
    }
}
