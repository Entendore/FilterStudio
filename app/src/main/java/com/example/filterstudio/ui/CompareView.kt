package com.example.filterstudio.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView

class CompareView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    val editedView = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
    private val original = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
    private val handle = View(context).apply { setBackgroundColor(Color.WHITE) }
    private var split = 0.5f

    init {
        addView(editedView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(original, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(handle, LayoutParams((3 * resources.displayMetrics.density).toInt(),
            LayoutParams.MATCH_PARENT))
        original.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) =
                o.setRect(0, 0, (width * split).toInt(), height)
        }
        original.clipToOutline = true
        setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN ||
                e.actionMasked == MotionEvent.ACTION_MOVE) setSplit(e.x / width)
            true
        }
    }

    fun setSplit(s: Float) {
        split = s.coerceIn(0.02f, 0.98f)
        handle.translationX = width * split - handle.measuredWidth / 2f
        original.invalidateOutline()
    }

    fun setBitmaps(before: android.graphics.Bitmap?, after: android.graphics.Bitmap?) {
        original.setImageBitmap(before); editedView.setImageBitmap(after)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { setSplit(split) }
}
