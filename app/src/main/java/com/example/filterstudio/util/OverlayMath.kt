package com.example.filterstudio.util

import kotlin.math.*

object OverlayGestureMath {
    const val SNAP_DEGREES = 4f
    const val MIN_SIZE = 0.03f
    const val MAX_SIZE = 1.2f

    fun snapAngle(deg: Float): Pair<Float, Boolean> {
        val nearest = (deg / 90f).roundToInt() * 90f
        return if (abs(deg - nearest) <= SNAP_DEGREES && nearest != deg) nearest to true else deg to false
    }

    fun hitTest(px: Float, py: Float, cx: Float, cy: Float, rotationDeg: Float,
                l: Float, t: Float, r: Float, b: Float, slop: Float): Boolean {
        val rad = Math.toRadians(-rotationDeg.toDouble())
        val dx = px - cx; val dy = py - cy
        val lx = dx * cos(rad) - dy * sin(rad)
        val ly = dx * sin(rad) + dy * cos(rad)
        return lx in (l - slop)..(r + slop) && ly in (t - slop)..(b + slop)
    }
}

class GestureAccumulator {
    var scale = 1f; private set
    var rotationDeg = 0f; private set
    fun reset() { scale = 1f; rotationDeg = 0f }

    fun step(prevSpan: Float, curSpan: Float, prevAngleRad: Float, curAngleRad: Float) {
        if (prevSpan > 1e-3f && curSpan > 1e-3f) scale *= curSpan / prevSpan
        var d = Math.toDegrees((curAngleRad - prevAngleRad).toDouble()).toFloat()
        if (d > 180f) d -= 360f; if (d < -180f) d += 360f
        rotationDeg += d
    }

    data class Applied(val centerX: Float, val centerY: Float,
                       val rotationDeg: Float, val size: Float, val snapped: Boolean)

    fun apply(startCenterX: Float, startCenterY: Float,
              startCentroidX: Float, startCentroidY: Float,
              curCentroidX: Float, curCentroidY: Float,
              startRotationDeg: Float, startSize: Float,
              minSize: Float = OverlayGestureMath.MIN_SIZE,
              maxSize: Float = OverlayGestureMath.MAX_SIZE): Applied {
        val rad = Math.toRadians(rotationDeg.toDouble())
        val c = cos(rad).toFloat(); val s = sin(rad).toFloat()
        val ox = startCenterX - startCentroidX; val oy = startCenterY - startCentroidY
        val (rot, snapped) = OverlayGestureMath.snapAngle(startRotationDeg + rotationDeg)
        return Applied(curCentroidX + (ox * c - oy * s) * scale,
            curCentroidY + (ox * s + oy * c) * scale, rot,
            (startSize * scale).coerceIn(minSize, maxSize), snapped)
    }
}

object OverlayHandleMath {
    enum class Kind { DELETE, ROTATE }
    data class Stepped(val rotationDeg: Float, val size: Float, val snapped: Boolean)

    fun positions(l: Float, t: Float, r: Float, b: Float) = listOf(
        Kind.DELETE to (l to t), Kind.ROTATE to (r to b))

    fun toView(lx: Float, ly: Float, cx: Float, cy: Float, rotDeg: Float): Pair<Float, Float> {
        val rad = Math.toRadians(rotDeg.toDouble())
        val c = cos(rad).toFloat(); val s = sin(rad).toFloat()
        return (cx + lx * c - ly * s) to (cy + lx * s + ly * c)
    }

    fun step(startAngleRad: Float, startDist: Float, curAngleRad: Float, curDist: Float,
             startRotationDeg: Float, startSize: Float, minSize: Float, maxSize: Float): Stepped {
        var d = Math.toDegrees((curAngleRad - startAngleRad).toDouble()).toFloat()
        if (d > 180f) d -= 360f; if (d < -180f) d += 360f
        val (rot, snapped) = OverlayGestureMath.snapAngle(startRotationDeg + d)
        val scale = if (startDist > 1e-3f) curDist / startDist else 1f
        return Stepped(rot, (startSize * scale).coerceIn(minSize, maxSize), snapped)
    }
}

object OverlayOrdering {
    fun move(overlays: List<com.example.filterstudio.model.Overlay>,
             id: Long, toFront: Boolean): List<com.example.filterstudio.model.Overlay> {
        val target = overlays.firstOrNull { it.id == id } ?: return overlays
        return if (toFront) overlays.filterNot { it.id == id } + target
        else listOf(target) + overlays.filterNot { it.id == id }
    }
}
