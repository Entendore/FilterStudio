package com.example.filterstudio.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.filterstudio.model.FilterType

class FilterAdapter(private val onSelect: (FilterType) -> Unit) :
    RecyclerView.Adapter<FilterAdapter.VH>() {

    private var items: List<FilterType> = FilterType.values().toList()
    private var thumbs: Map<FilterType, Bitmap> = emptyMap()
    var selected: FilterType = FilterType.ORIGINAL
        set(v) { field = v; notifyDataSetChanged() }

    fun setCategory(c: com.example.filterstudio.model.Category) {
        items = FilterType.values().filter { it.category == c }
        notifyDataSetChanged()
    }
    fun submitThumbs(t: Map<FilterType, Bitmap>) { thumbs = t; notifyDataSetChanged() }

    class VH(val root: LinearLayout) : RecyclerView.ViewHolder(root)

    override fun onCreateViewHolder(p: ViewGroup, vt: Int): VH {
        val col = LinearLayout(p.context)
        col.orientation = LinearLayout.VERTICAL
        col.gravity = Gravity.CENTER
        val iv = ImageView(p.context)
        iv.layoutParams = LinearLayout.LayoutParams((56 * p.context.resources.displayMetrics.density).toInt(),
            (56 * p.context.resources.displayMetrics.density).toInt())
        iv.scaleType = ImageView.ScaleType.CENTER_CROP
        val tv = TextView(p.context)
        tv.textSize = 10f; tv.setPadding(0, 2, 0, 0)
        col.addView(iv); col.addView(tv)
        col.setPadding(6, 4, 6, 4)
        return VH(col)
    }

    override fun onBindViewHolder(h: VH, pos: Int) {
        val f = items[pos]
        val iv = h.root.getChildAt(0) as ImageView
        val tv = h.root.getChildAt(1) as TextView
        iv.setImageBitmap(thumbs[f])
        tv.text = f.label
        tv.setTextColor(if (f == selected) Color.WHITE else Color.GRAY)
        iv.foreground = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE; cornerRadius = 12f
            setStroke(if (f == selected) 4 else 0, Color.WHITE) }
        h.root.setOnClickListener { onSelect(f) }
    }

    override fun getItemCount() = items.size
}

class CameraFilterAdapter(
    private val filters: List<FilterType>,
    private val onSelect: (FilterType) -> Unit
) : RecyclerView.Adapter<FilterAdapter.VH>() {

    private var thumbs: Map<FilterType, Bitmap> = emptyMap()
    var selected: FilterType = FilterType.ORIGINAL
        set(v) { field = v; notifyDataSetChanged() }
    fun submit(t: Map<FilterType, Bitmap>) { thumbs = t; notifyDataSetChanged() }

    override fun onCreateViewHolder(p: ViewGroup, vt: Int) =
        FilterAdapter.VH(LinearLayout(p.context).also { col ->
            col.orientation = LinearLayout.VERTICAL; col.gravity = Gravity.CENTER
            val iv = ImageView(p.context)
            iv.layoutParams = LinearLayout.LayoutParams(
                (56 * p.context.resources.displayMetrics.density).toInt(),
                (56 * p.context.resources.displayMetrics.density).toInt())
            iv.scaleType = ImageView.ScaleType.CENTER_CROP
            val tv = TextView(p.context); tv.textSize = 10f
            col.addView(iv); col.addView(tv); col.setPadding(6, 4, 6, 4)
        })

    override fun onBindViewHolder(h: FilterAdapter.VH, pos: Int) {
        val f = filters[pos]
        (h.root.getChildAt(0) as ImageView).setImageBitmap(thumbs[f])
        (h.root.getChildAt(1) as TextView).text = f.label
        h.root.setOnClickListener { onSelect(f) }
    }

    override fun getItemCount() = filters.size
}
