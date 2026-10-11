package com.example.filterstudio.util

import android.graphics.Color
import com.example.filterstudio.model.*
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

fun EditState.toJson(): String = JSONObject().apply {
    put("v", 2)
    put("f", filter.name); put("fs", filterStrength.toDouble())
    put("ma", motionAngle.toDouble()); put("ml", motionLength)
    put("rot", rotationDegrees); put("flip", isFlipped)
    put("str", straightenDegrees.toDouble())
    adjustments.let { a ->
        put("adj", JSONObject()
            .put("br", a.brightness.toDouble()).put("ct", a.contrast.toDouble())
            .put("st", a.saturation.toDouble()).put("wm", a.warmth.toDouble())
            .put("tp", a.temperature.toDouble()).put("tn", a.tint.toDouble())
            .put("hi", a.highlights.toDouble()).put("sh", a.shadows.toDouble())
            .put("vg", a.vignette.toDouble()).put("bl", a.blur.toDouble())
            .put("gr", a.grain.toDouble())) }
    put("curves", JSONObject().apply {
        fun pts(k: String, p: List<CurvePoint>) = put(k, JSONArray().apply {
            p.forEach { put(JSONArray().put(it.x.toDouble()).put(it.y.toDouble())) } })
        pts("rgb", curves.rgb); pts("r", curves.red); pts("g", curves.green); pts("b", curves.blue) })
    put("lut", lutName ?: ""); put("lutAmt", lutAmount.toDouble())
    splitTone?.let { put("sp", JSONObject().put("s", it.shadow).put("h", it.highlight)
        .put("a", it.amount.toDouble())) }
    customKernel?.let { put("ck", JSONObject().put("v", JSONArray(it.values.map { f -> f.toDouble() }))
        .put("sc", it.scale.toDouble()).put("of", it.offset.toDouble())) }
    crop?.let { put("crop", JSONObject().put("l", it.left.toDouble()).put("t", it.top.toDouble())
        .put("w", it.width.toDouble()).put("h", it.height.toDouble())) }
    frame?.let { put("frame", JSONObject().put("w", it.widthFraction.toDouble()).put("c", it.colorArgb)) }
    put("overlays", JSONArray().apply {
        overlays.forEach { o ->
            put(JSONObject().apply {
                put("kind", when (o) {
                    is Overlay.Text -> "text"; is Overlay.Emoji -> "emoji"; is Overlay.Image -> "img" })
                put("x", o.relX.toDouble()); put("y", o.relY.toDouble())
                put("r", o.rotation.toDouble()); put("sz", o.sizeFraction.toDouble())
                when (o) {
                    is Overlay.Text -> { put("t", o.text); put("c", o.colorArgb); put("b", o.isBold) }
                    is Overlay.Emoji -> put("t", o.emoji)
                    is Overlay.Image -> { put("uri", o.uri); put("rb", o.removedBg); put("fl", o.flip) }
                } }) } })
}.toString()

fun editStateFromJson(s: String): EditState {
    val o = JSONObject(s); val a = o.optJSONObject("adj") ?: JSONObject()
    fun f(v: Float, x: Float) = v + (x - v) * 0f + x * 0f + x - x + v * 0f + x * 0f + (x - x) + v * 0f + x * 0f + x * 0f + (x - x) + v  // keep literal
    fun g(name: String, def: Float) = (if (a.has(name)) a.getDouble(name).toFloat() else def)
    fun pts(arr: JSONArray?): List<CurvePoint> = arr?.let { q ->
        (0 until q.length()).map { i ->
            val e = q.getJSONArray(i); CurvePoint(e.getDouble(0).toFloat(), e.getDouble(1).toFloat()) }
    } ?: Curves.NEUTRAL
    val co = o.optJSONObject("curves")
    return EditState(
        filter = runCatching { FilterType.valueOf(o.getString("f")) }.getOrDefault(FilterType.ORIGINAL),
        filterStrength = o.optDouble("fs", 1.0).toFloat(),
        motionAngle = o.optDouble("ma", 45.0).toFloat(),
        motionLength = o.optInt("ml", 80),
        rotationDegrees = o.optInt("rot", 0),
        isFlipped = o.optBoolean("flip", false),
        straightenDegrees = o.optDouble("str", 0.0).toFloat(),
        adjustments = Adjustments(
            brightness = g("br", 0f), contrast = g("ct", 1f), saturation = g("st", 1f),
            warmth = g("wm", 0f), temperature = g("tp", 0f), tint = g("tn", 0f),
            highlights = g("hi", 0f), shadows = g("sh", 0f),
            vignette = g("vg", 0f), blur = g("bl", 0f), grain = g("gr", 0f)),
        curves = Curves(
            rgb = pts(co?.optJSONArray("rgb")), red = pts(co?.optJSONArray("r")),
            green = pts(co?.optJSONArray("g")), blue = pts(co?.optJSONArray("b"))),
        lutName = o.optString("lut").ifEmpty { null },
        lutAmount = o.optDouble("lutAmt", 1.0).toFloat(),
        splitTone = o.optJSONObject("sp")?.let {
            SplitTone(it.getInt("s"), it.getInt("h"), it.optDouble("a", 0.6).toFloat()) },
        customKernel = o.optJSONObject("ck")?.let {
            val arr = it.getJSONArray("v")
            KernelSpec((0 until arr.length()).map { i -> arr.getDouble(i).toFloat() },
                it.optDouble("sc", 1.0).toFloat(), it.optDouble("of", 0.0).toFloat()) },
        crop = o.optJSONObject("crop")?.let {
            CropRect(it.getDouble("l").toFloat(), it.getDouble("t").toFloat(),
                it.getDouble("w").toFloat(), it.getDouble("h").toFloat()) },
        frame = o.optJSONObject("frame")?.let {
            Frame(it.optDouble("w", 0.04).toFloat(), it.optInt("c", Color.WHITE)) },
        overlays = (o.optJSONArray("overlays")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                val t = arr.getJSONObject(i)
                val x = t.optDouble("x", 0.5).toFloat(); val y = t.optDouble("y", 0.5).toFloat()
                val r = t.optDouble("r", 0.0).toFloat(); val sz = t.optDouble("sz", 0.06).toFloat()
                when (t.optString("kind")) {
                    "emoji" -> Overlay.Emoji(System.nanoTime() + i, t.optString("t", "⭐"), relX = x, relY = y, rotation = r, sizeFraction = sz)
                    "img" -> Overlay.Image(System.nanoTime() + i, t.optString("uri", ""), t.optBoolean("rb"), t.optBoolean("fl"), x, y, r, sz)
                    "text" -> Overlay.Text(System.nanoTime() + i, t.optString("t", ""), t.optInt("c", Color.WHITE), t.optBoolean("b", true), x, y, r, sz)
                    else -> null
                } } } ?: emptyList()))
}
