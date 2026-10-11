package com.example.filterstudio.ui

import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import com.example.filterstudio.model.*
import com.example.filterstudio.util.GestureAccumulator
import com.example.filterstudio.util.OverlayGestureMath
import com.example.filterstudio.util.OverlayHandleMath
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

class OverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private enum class Mode { IDLE, DRAG, TRANSFORM, ROTATE_HANDLE }

    var imageRect = RectF()
    var overlays: List<Overlay> = emptyList()
        set(value) { field = value; invalidate() }
    var selectedId: Long? = null
        set(value) { field = value; onSelected?.invoke(value); invalidate() }
    var onSelected: ((Long?) -> Unit)? = null
    var onTransformed: ((Long, Float, Float, Float, Float) -> Unit)? = null
    var onGestureEnded: (() -> Unit)? = null
    var onDoubleTapped: ((Long) -> Unit)? = null
    var onDeleted: ((Long) -> Unit)? = null
    var stickers: (Long) -> Bitmap? = { null }
    var aspectOf: (Long) -> Float? = { null }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f; color = Color.WHITE
        pathEffect = DashPathEffect(floatArrayOf(8f, 6f), 0f) }
    private val bounds = Rect()
    private val pad get() = 12f * resources.displayMetrics.density
    private val handleR get() = 11f * resources.displayMetrics.density
    private val handleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val handleStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f; color = 0xFF333333.toInt() }
    private val handleGlyph = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f; color = 0xFF333333.toInt()
        strokeCap = Paint.Cap.ROUND }

    private var mode = Mode.IDLE
    private var activeId: Long? = null
    private var pidA = -1; private var pidB = -1
    private var lx0 = 0f; private var ly0 = 0f; private var lx1 = 0f; private var ly1 = 0f
    private var startCx = 0f; private var startCy = 0f
    private var startCentroidX = 0f; private var startCentroidY = 0f
    private var startRot = 0f; private var startSize = 1f
    private var lastX = 0f; private var lastY = 0f
    private val acc = GestureAccumulator()
    private var lastTapId: Long? = null; private var lastTapAt = 0L
    private var rotStartAngle = 0f; private var rotStartDist = 0f
    private var rotStartRot = 0f; private var rotStartSize = 1f
    private var rotCx = 0f; private var rotCy = 0f

    private fun viewX(rel: Float) = imageRect.left + rel * imageRect.width()
    private fun viewY(rel: Float) = imageRect.top + rel * imageRect.height()
    private fun nx(v: Float) = (v - imageRect.left) / imageRect.width()
    private fun ny(v: Float) = (v - imageRect.top) / imageRect.height()

    private fun localBox(o: Overlay, minDim: Float): RectF = when (o) {
        is Overlay.Text -> {
            paint.textSize = OverlayLayout.textSize(o.sizeFraction, minDim)
            paint.isFakeBoldText = o.isBold
            paint.getTextBounds(o.text, 0, o.text.length, bounds)
            RectF(-bounds.width()/2f - pad, bounds.top - pad, bounds.width()/2f + pad, bounds.bottom + pad) }
        is Overlay.Emoji -> {
            paint.textSize = OverlayLayout.emojiGlyph(o.sizeFraction, minDim)
            paint.getTextBounds(o.emoji, 0, o.emoji.length, bounds)
            RectF(-bounds.width()/2f - pad, bounds.top - pad, bounds.width()/2f + pad, bounds.bottom + pad) }
        is Overlay.Image -> {
            val w = OverlayLayout.stickerWidth(o.sizeFraction, minDim)
            val h = w / (aspectOf(o.id) ?: 1f).coerceAtLeast(0.05f)
            RectF(-w/2f - pad, -h/2f - pad, w/2f + pad, h/2f + pad) }
    }

    private fun hit(x: Float, y: Float): Long? {
        if (imageRect.isEmpty) return null
        val minDim = min(imageRect.width(), imageRect.height())
        val slop = 10f * resources.displayMetrics.density
        return overlays.lastOrNull { o ->
            val box = localBox(o, minDim)
            OverlayGestureMath.hitTest(x, y, viewX(o.relX), viewY(o.relY), o.rotation,
                box.left, box.top, box.right, box.bottom, slop)
        }?.id
    }

    private fun hitHandle(x: Float, y: Float): OverlayHandleMath.Kind? {
        val o = overlays.firstOrNull { it.id == selectedId } ?: return null
        val box = localBox(o, min(imageRect.width(), imageRect.height()))
        val cx = viewX(o.relX); val cy = viewY(o.relY)
        for ((kind, p) in OverlayHandleMath.positions(box.left, box.top, box.right, box.bottom)) {
            val (hx, hy) = OverlayHandleMath.toView(p.first, p.second, cx, cy, o.rotation)
            if (hypot(x - hx, y - hy) <= handleR * 1.6f) return kind
        }
        return null
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (imageRect.isEmpty) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                when (hitHandle(e.x, e.y)) {
                    OverlayHandleMath.Kind.DELETE -> {
                        selectedId?.let { onDeleted?.invoke(it) }
                        announceForAccessibility(context.getString(
                            com.example.filterstudio.R.string.overlay_deleted))
                        selectedId = null; return true }
                    OverlayHandleMath.Kind.ROTATE -> {
                        selectedId?.let { beginRotateHandle(it, e) }; return true }
                    null -> Unit
                }
                val id = hit(e.x, e.y)
                if (id == null) {
                    if (selectedId != null) { selectedId = null; return true }
                    return false
                }
                selectedId = id; beginDrag(id, e, e.actionIndex)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (mode == Mode.IDLE || pidB != -1 || e.pointerCount > 2) return true
                pidB = e.getPointerId(e.actionIndex)
                beginTransform(e)
            }
            MotionEvent.ACTION_MOVE -> when (mode) {
                Mode.DRAG -> moveDrag(e)
                Mode.TRANSFORM -> moveTransform(e)
                Mode.ROTATE_HANDLE -> moveRotateHandle(e)
                Mode.IDLE -> Unit
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val up = e.getPointerId(e.actionIndex)
                if (mode == Mode.TRANSFORM && (up == pidA || up == pidB)) {
                    val remaining = if (up == pidA) pidB else pidA
                    val idx = e.findPointerIndex(remaining)
                    if (idx >= 0 && activeId != null) beginDrag(activeId!!, e, idx) else endGesture()
                }
            }
            MotionEvent.ACTION_UP -> { checkDoubleTap(e); endGesture() }
            MotionEvent.ACTION_CANCEL -> endGesture()
        }
        return mode != Mode.IDLE
    }

    private fun beginDrag(id: Long, e: MotionEvent, idx: Int) {
        mode = Mode.DRAG; activeId = id; pidA = e.getPointerId(idx); pidB = -1
        lastX = e.getX(idx); lastY = e.getY(idx)
        overlays.first { it.id == id }.let { startRot = it.rotation; startSize = it.sizeFraction }
        parent?.requestDisallowInterceptTouchEvent(true)
    }

    private fun moveDrag(e: MotionEvent) {
        val idx = e.findPointerIndex(pidA); if (idx < 0) return
        val o = overlays.first { it.id == activeId }
        onTransformed?.invoke(activeId!!,
            nx(viewX(o.relX) + e.getX(idx) - lastX).coerceIn(0.03f, 0.97f),
            ny(viewY(o.relY) + e.getY(idx) - lastY).coerceIn(0.06f, 0.94f), startRot, startSize)
        lastX = e.getX(idx); lastY = e.getY(idx)
    }

    private fun beginTransform(e: MotionEvent) {
        mode = Mode.TRANSFORM
        val i0 = e.findPointerIndex(pidA); val i1 = e.findPointerIndex(pidB)
        lx0 = e.getX(i0); ly0 = e.getY(i0); lx1 = e.getX(i1); ly1 = e.getY(i1)
        startCentroidX = (lx0 + lx1) / 2f; startCentroidY = (ly0 + ly1) / 2f
        overlays.first { it.id == activeId }.let {
            startCx = viewX(it.relX); startCy = viewY(it.relY)
            startRot = it.rotation; startSize = it.sizeFraction }
        acc.reset()
    }

    private fun moveTransform(e: MotionEvent) {
        val id = activeId ?: return
        val i0 = e.findPointerIndex(pidA); val i1 = e.findPointerIndex(pidB)
        if (i0 < 0 || i1 < 0) return
        val x0 = e.getX(i0); val y0 = e.getY(i0); val x1 = e.getX(i1); val y1 = e.getY(i1)
        acc.step(hypot(lx1 - lx0, ly1 - ly0), hypot(x1 - x0, y1 - y0),
                 atan2(ly1 - ly0, lx1 - lx0), atan2(y1 - y0, x1 - x0))
        lx0 = x0; ly0 = y0; lx1 = x1; ly1 = y1
        val a = acc.apply(startCx, startCy, startCentroidX, startCentroidY,
                          (x0 + x1) / 2f, (y0 + y1) / 2f, startRot, startSize)
        if (a.snapped) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        onTransformed?.invoke(id, nx(a.centerX).coerceIn(0.03f, 0.97f),
            ny(a.centerY).coerceIn(0.06f, 0.94f), a.rotationDeg, a.size)
    }

    private fun beginRotateHandle(id: Long, e: MotionEvent) {
        mode = Mode.ROTATE_HANDLE; activeId = id
        val o = overlays.first { it.id == id }
        rotCx = viewX(o.relX); rotCy = viewY(o.relY)
        rotStartAngle = atan2(e.y - rotCy, e.x - rotCx)
        rotStartDist = hypot(e.x - rotCx, e.y - rotCy).coerceAtLeast(1f)
        rotStartRot = o.rotation; rotStartSize = o.sizeFraction
        parent?.requestDisallowInterceptTouchEvent(true)
    }

    private fun moveRotateHandle(e: MotionEvent) {
        val id = activeId ?: return
        val s = OverlayHandleMath.step(rotStartAngle, rotStartDist,
            atan2(e.y - rotCy, e.x - rotCx), hypot(e.x - rotCx, e.y - rotCy),
            rotStartRot, rotStartSize, OverlayGestureMath.MIN_SIZE, OverlayGestureMath.MAX_SIZE)
        if (s.snapped) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        val o = overlays.first { it.id == id }
        onTransformed?.invoke(id, o.relX, o.relY, s.rotationDeg, s.size)
    }

    private fun checkDoubleTap(e: MotionEvent) {
        val now = SystemClock.uptimeMillis()
        val id = hit(e.x, e.y)
        if (id != null && id == lastTapId && now - lastTapAt < 300) onDoubleTapped?.invoke(id)
        lastTapId = id; lastTapAt = now
    }

    private fun endGesture() {
        if (mode != Mode.IDLE) onGestureEnded?.invoke()
        mode = Mode.IDLE; activeId = null; pidA = -1; pidB = -1
    }

    override fun onDraw(c: Canvas) {
        if (imageRect.isEmpty) return
        val minDim = min(imageRect.width(), imageRect.height())
        overlays.forEach { o ->
            val cx = viewX(o.relX); val cy = viewY(o.relY)
            val box = localBox(o, minDim)
            c.save(); c.rotate(o.rotation, cx, cy); c.translate(cx, cy)
            when (o) {
                is Overlay.Text -> {
                    paint.color = o.colorArgb; paint.isFakeBoldText = o.isBold
                    c.drawText(o.text, 0f, 0f, paint) }
                is Overlay.Emoji -> c.drawText(o.emoji, 0f, 0f, paint)
                is Overlay.Image -> stickers(o.id)?.let { bmp ->
                    val w = box.width() - 2 * pad; val h = box.height() - 2 * pad
                    if (o.flip) c.scale(-1f, 1f)
                    c.drawBitmap(bmp, null, RectF(-w/2f, -h/2f, w/2f, h/2f), paint)
                    if (o.flip) c.scale(-1f, 1f) }
            }
            if (o.id == selectedId) {
                c.drawRect(box, ring)
                drawHandle(c, box.left, box.top) { hx, hy ->
                    c.drawLine(hx - handleR/2, hy - handleR/2, hx + handleR/2, hy + handleR/2, handleGlyph)
                    c.drawLine(hx - handleR/2, hy + handleR/2, hx + handleR/2, hy - handleR/2, handleGlyph) }
                drawHandle(c, box.right, box.bottom) { hx, hy ->
                    val rr = RectF(hx - handleR/2, hy - handleR/2, hx + handleR/2, hy + handleR/2)
                    c.drawArc(rr, -60f, 300f, false, handleGlyph)
                    val ax = hx + handleR/2 * kotlin.math.cos(Math.toRadians(-60.0)).toFloat()
                    val ay = hy + handleR/2 * kotlin.math.sin(Math.toRadians(-60.0)).toFloat()
                    c.drawCircle(ax, ay, 3f, handleFill) }
            }
            c.restore()
        }
    }

    private inline fun drawHandle(c: Canvas, x: Float, y: Float, glyph: (Float, Float) -> Unit) {
        c.drawCircle(x, y, handleR, handleFill); c.drawCircle(x, y, handleR, handleStroke)
        glyph(x, y)
    }
}
