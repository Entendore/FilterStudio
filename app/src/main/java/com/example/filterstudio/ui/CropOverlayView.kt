package com.example.filterstudio.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.example.filterstudio.model.CropRect
import com.example.filterstudio.util.CropMath
import kotlin.math.min

class CropOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {
    private val dp get() = resources.displayMetrics.density
    var imageRect = RectF()
    var aspectRatio: Float? = null
    var rect: CropRect = CropRect(0f, 0f, 1f, 1f); private set
    var onRectChanged: ((CropRect) -> Unit)? = null

    private val scrim = Paint().apply { color = 0x99000000.toInt() }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f; color = Color.WHITE }
    private val grid = Paint().apply { strokeWidth = 1f; color = 0x66FFFFFF }
    private val corner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 5f; color = Color.WHITE; strokeCap = Paint.Cap.ROUND }
    private val path = Path()

    private var handle = CropMath.Handle.NONE
    private lateinit var dragStart: CropRect
    private var startNX = 0f; private var startNY = 0f
    private val cornerR = 24 * dp; private val edgeR = 18 * dp; private val arm = 20 * dp
    private val minNorm = 0.08f

    fun resetRect(r: CropRect) { rect = r; invalidate() }
    private fun toView(r: CropRect) = RectF(
        imageRect.left + r.left * imageRect.width(), imageRect.top + r.top * imageRect.height(),
        imageRect.left + r.right * imageRect.width(), imageRect.top + r.bottom * imageRect.height())
    private fun nx(v: Float) = ((v - imageRect.left) / imageRect.width()).coerceIn(0f, 1f)
    private fun ny(v: Float) = ((v - imageRect.top) / imageRect.height()).coerceIn(0f, 1f)

    override fun onDraw(c: Canvas) {
        if (imageRect.isEmpty) return
        val r = toView(rect)
        path.reset()
        path.addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
        path.addRect(r, Path.Direction.CW)
        c.drawPath(path, scrim.apply { fillType = Path.FillType.EVEN_ODD })
        c.drawRect(r, border)
        repeat(2) { i ->
            c.drawLine(r.left + r.width() / 3 * (i + 1), r.top,
                r.left + r.width() / 3 * (i + 1), r.bottom, grid)
            c.drawLine(r.left, r.top + r.height() / 3 * (i + 1),
                r.right, r.top + r.height() / 3 * (i + 1), grid)
        }
        fun bracket(x: Float, y: Float, sx: Float, sy: Float) {
            c.drawLine(x, y, x + sx * arm, y, corner); c.drawLine(x, y, x, y + sy * arm, corner) }
        bracket(r.left, r.top, 1f, 1f); bracket(r.right, r.top, -1f, 1f)
        bracket(r.left, r.bottom, 1f, -1f); bracket(r.right, r.bottom, -1f, -1f)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (imageRect.isEmpty) return false
        val x = nx(e.x); val y = ny(e.y)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                handle = CropMath.hitTest(rect, x, y,
                    cornerR / imageRect.width().coerceAtLeast(1f),
                    edgeR / imageRect.width().coerceAtLeast(1f))
                if (handle == CropMath.Handle.NONE) return false
                dragStart = rect; startNX = x; startNY = y
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                rect = if (handle == CropMath.Handle.INSIDE)
                    CropMath.move(dragStart, x - startNX, y - startNY)
                else CropMath.resize(dragStart, handle, x, y, aspectRatio, minNorm)
                invalidate()
            }
            MotionEvent.ACTION_UP -> { onRectChanged?.invoke(rect); handle = CropMath.Handle.NONE }
        }
        return true
    }
}
