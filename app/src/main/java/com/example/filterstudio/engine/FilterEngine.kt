package com.example.filterstudio.engine

import android.graphics.*
import com.example.filterstudio.model.*
import kotlin.math.*
import kotlin.random.Random

object FilterEngine {

    // ================= matrix primitives (offsets in 0..255) =================
    internal fun identity() = floatArrayOf(
        1f,0f,0f,0f,0f, 0f,1f,0f,0f,0f, 0f,0f,1f,0f,0f, 0f,0f,0f,1f,0f)
    internal fun brightness(a: Float) = floatArrayOf(
        1f,0f,0f,0f,a*255f, 0f,1f,0f,0f,a*255f, 0f,0f,1f,0f,a*255f, 0f,0f,0f,1f,0f)
    internal fun contrast(f: Float) = floatArrayOf(
        f,0f,0f,0f,(1f-f)*128f, 0f,f,0f,0f,(1f-f)*128f,
        0f,0f,f,0f,(1f-f)*128f, 0f,0f,0f,1f,0f)
    internal fun saturation(s: Float): FloatArray =
        ColorMatrix().apply { setSaturation(s) }.array
    internal fun warmth(t: Float) = floatArrayOf(
        1f+t*0.25f,0f,0f,0f,0f, 0f,1f,0f,0f,0f, 0f,0f,1f-t*0.25f,0f,0f, 0f,0f,0f,1f,0f)
    internal fun tintAxis(temp: Float, tint: Float) = floatArrayOf(
        1f,0f,0f,0f,temp*40f+tint*20f, 0f,1f,0f,0f,-tint*40f,
        0f,0f,1f,0f,-temp*40f+tint*20f, 0f,0f,0f,1f,0f)
    internal fun duotone(shadow: Int, highlight: Int): FloatArray {
        fun row(sa: Float, sb: Float) = floatArrayOf(
            0.299f*(sb-sa), 0.587f*(sb-sa), 0.114f*(sb-sa), 0f, sa)
        return floatArrayOf(
            *row(Color.red(shadow).toFloat(), Color.red(highlight).toFloat()),
            *row(Color.green(shadow).toFloat(), Color.green(highlight).toFloat()),
            *row(Color.blue(shadow).toFloat(), Color.blue(highlight).toFloat()),
            0f,0f,0f,1f,0f)
    }
    internal fun splitTone(shadow: Int, highlight: Int, s: Float): FloatArray {
        val d = duotone(shadow, highlight); val k = s.coerceIn(0f, 1f); val id = identity()
        return FloatArray(20) { i -> (1f - k) * id[i] + k * d[i] }
    }
    internal fun tint(color: Int, t: Float): FloatArray {
        val k = 1f - t
        val r = Color.red(color)*t; val g = Color.green(color)*t; val b = Color.blue(color)*t
        return floatArrayOf(k,0f,0f,0f,r, 0f,k,0f,0f,g, 0f,0f,k,0f,b, 0f,0f,0f,1f,0f)
    }
    internal fun hueShift(deg: Float): FloatArray =
        ColorMatrix().apply { setRotate(2, deg) }.array
    internal fun grayscaleWeighted(rw: Float, gw: Float, bw: Float) = floatArrayOf(
        rw,gw,bw,0f,0f, rw,gw,bw,0f,0f, rw,gw,bw,0f,0f, 0f,0f,0f,1f,0f)
    internal fun invertM() = floatArrayOf(
        -1f,0f,0f,0f,255f, 0f,-1f,0f,0f,255f, 0f,0f,-1f,0f,255f, 0f,0f,0f,1f,0f)
    internal fun channelSwapRB() = floatArrayOf(
        0f,0f,1f,0f,0f, 0f,1f,0f,0f,0f, 1f,0f,0f,0f,0f, 0f,0f,0f,1f,0f)

    internal fun multiply(a: FloatArray, b: FloatArray): FloatArray {
        val out = FloatArray(20)
        for (row in 0..3) for (col in 0..4) {
            var sum = 0f
            for (k in 0..3) sum += a[row*5+k] * b[k*5+col]
            if (col == 4) sum += a[row*5+4]
            out[row*5+col] = sum
        }
        return out
    }
    internal fun chain(vararg steps: FloatArray): FloatArray {
        var acc = steps[0]
        for (i in 1 until steps.size) acc = multiply(steps[i], acc)
        return acc
    }

    private fun filterMatrix(type: FilterType): FloatArray = when (type) {
        FilterType.ORIGINAL -> identity()
        FilterType.GRAYSCALE -> saturation(0f)
        FilterType.SEPIA -> floatArrayOf(
            0.393f,0.769f,0.189f,0f,0f, 0.349f,0.686f,0.168f,0f,0f,
            0.272f,0.534f,0.131f,0f,0f, 0f,0f,0f,1f,0f)
        FilterType.VIVID -> chain(saturation(1.4f), contrast(1.05f))
        FilterType.FADE -> chain(contrast(0.85f), brightness(0.07f), saturation(0.8f))
        FilterType.NOIR -> chain(saturation(0f), contrast(1.35f), brightness(-0.02f))
        FilterType.DRAMATIC -> chain(contrast(1.45f), saturation(0.9f), brightness(-0.06f))
        FilterType.INVERT -> invertM()
        FilterType.COOL -> chain(warmth(-0.5f), saturation(1.05f))
        FilterType.SUNSET -> chain(warmth(0.5f), saturation(1.15f), contrast(1.08f))
        FilterType.AQUA -> chain(saturation(0.6f), tint(0xFF20B2AA.toInt(), 0.35f))
        FilterType.ROSE -> chain(saturation(0.75f), tint(0xFFFF6F91.toInt(), 0.30f), brightness(0.05f))
        FilterType.MARS -> chain(saturation(0.7f), tint(0xFFB22222.toInt(), 0.55f), contrast(1.2f))
        FilterType.VINTAGE -> chain(saturation(0.65f), contrast(0.9f), brightness(0.08f),
            tint(0xFFC8B78A.toInt(), 0.22f))
        FilterType.LOMO -> chain(contrast(1.3f), saturation(1.2f), brightness(-0.05f))
        FilterType.NEON -> chain(hueShift(30f), saturation(1.8f), contrast(1.1f))
        FilterType.DUOTONE_NAVY -> duotone(0xFF0F1B4C.toInt(), 0xFF7FD8FF.toInt())
        FilterType.DUOTONE_PEACH -> duotone(0xFF3A1C71.toInt(), 0xFFFFAF7B.toInt())
        FilterType.DUOTONE_ACID -> duotone(0xFF0B0B0B.toInt(), 0xFFB4FF39.toInt())
        FilterType.SKETCH -> chain(saturation(0f), invertM())
        FilterType.ANSEL -> chain(grayscaleWeighted(0.55f,0.35f,0.10f), contrast(1.35f), brightness(0.03f))
        FilterType.CROSS_PROCESS -> chain(splitTone(0xFF2E4A0F.toInt(), 0xFFF8FF9C.toInt(), 0.5f),
            contrast(1.18f), saturation(1.1f))
        FilterType.BLEACH_BYPASS -> chain(saturation(0.35f), contrast(1.55f), brightness(0.06f))
        FilterType.CYANOTYPE -> duotone(0xFF0A1A2F.toInt(), 0xFFCFEFFA.toInt())
        FilterType.XRAY -> chain(invertM(), tint(0xFF4655FF.toInt(), 0.35f))
        FilterType.INFRARED -> chain(channelSwapRB(), saturation(1.7f), contrast(1.1f))
        FilterType.SILVER -> chain(grayscaleWeighted(0.35f,0.45f,0.20f), contrast(1.35f), brightness(0.04f))
        FilterType.PLATINUM -> chain(grayscaleWeighted(0.30f,0.50f,0.20f), contrast(0.86f),
            brightness(0.11f), tint(0xFFF2EAE0.toInt(), 0.20f))
        FilterType.SELENIUM -> chain(grayscaleWeighted(0.30f,0.59f,0.11f),
            splitTone(0xFF2E2140.toInt(), 0xFFEDDFC5.toInt(), 0.45f), contrast(1.08f))
        FilterType.SLATE -> chain(grayscaleWeighted(0.28f,0.55f,0.17f),
            splitTone(0xFF16212E.toInt(), 0xFFD9E4F0.toInt(), 0.50f))
        FilterType.POLAROID -> chain(contrast(0.84f), saturation(0.88f), brightness(0.05f),
            splitTone(0xFF22301F.toInt(), 0xFFFFE9C0.toInt(), 0.35f))
        FilterType.CHROME -> chain(saturation(1.55f), contrast(1.22f))
        FilterType.TECHNICOLOR -> chain(saturation(1.75f), contrast(1.12f), tint(0xFFFFF2D8.toInt(), 0.08f))
        FilterType.NIGHT_VISION -> chain(grayscaleWeighted(0.30f,0.59f,0.11f), contrast(1.15f),
            brightness(0.06f), duotone(0xFF001A0D.toInt(), 0xFF52FF8A.toInt()))
        FilterType.MATRIX_GREEN -> chain(grayscaleWeighted(0.30f,0.59f,0.11f), contrast(1.25f),
            duotone(0xFF000000.toInt(), 0xFF39FF3C.toInt()))
        FilterType.THERMAL -> saturation(0f)      // palette arrives via baked curve
        FilterType.BLUEPRINT -> duotone(0xFF0D2B6B.toInt(), 0xFFEAF2FF.toInt())
        FilterType.WATERCOLOR -> chain(saturation(1.2f), brightness(0.04f))
        else -> identity()   // pixel-pass filters own their look
    }

    internal fun mixedFilterMatrix(type: FilterType, strength: Float): FloatArray {
        if (strength >= 1f) return filterMatrix(type)
        val base = identity(); val target = filterMatrix(type); val t = strength.coerceIn(0f, 1f)
        return FloatArray(20) { i -> base[i] + (target[i] - base[i]) * t }
    }

    fun buildMatrix(state: EditState): ColorMatrix {
        val a = state.adjustments
        val st = state.splitTone?.let { splitTone(it.shadow, it.highlight, it.amount) } ?: identity()
        return ColorMatrix(multiply(
            chain(brightness(a.brightness), contrast(a.contrast), saturation(a.saturation),
                  warmth(a.warmth), tintAxis(a.temperature, a.tint)),
            multiply(mixedFilterMatrix(state.filter, state.filterStrength), st)))
    }

    // ================= geometry =================
    internal fun coverScale(w: Float, h: Float, deg: Float): Float {
        val r = Math.toRadians(abs(deg).toDouble())
        val c = cos(r).toFloat(); val s = sin(r).toFloat()
        val w2 = w * c + h * s; val h2 = w * s + h * c
        return max(w / w2, h / h2)
    }

    fun applyGeometry(src: Bitmap, rotation: Int, flip: Boolean, straighten: Float = 0f): Bitmap {
        val rotate = rotation % 360
        val swapped = rotate == 90 || rotate == 270
        val outW = if (swapped) src.height else src.width
        val outH = if (swapped) src.width else src.height
        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val transform = Matrix().apply {
            if (rotate != 0) postRotate(rotate.toFloat(), src.width / 2f, src.height / 2f)
            postTranslate((outW - src.width) / 2f, (outH - src.height) / 2f)
            if (straighten != 0f) {
                val k = coverScale(outW.toFloat(), outH.toFloat(), straighten)
                postRotate(straighten, outW / 2f, outH / 2f)
                postScale(k, k, outW / 2f, outH / 2f)
            }
            if (flip) postScale(-1f, 1f, outW / 2f, outH / 2f)
        }
        Canvas(out).drawBitmap(src, transform, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        return out
    }

    fun applyCropRect(src: Bitmap, crop: CropRect?): Bitmap {
        crop ?: return src
        val l = (crop.left * src.width).roundToInt().coerceIn(0, src.width - 1)
        val t = (crop.top * src.height).roundToInt().coerceIn(0, src.height - 1)
        val cw = (crop.width * src.width).roundToInt().coerceIn(1, src.width - l)
        val ch = (crop.height * src.height).roundToInt().coerceIn(1, src.height - t)
        if (l == 0 && t == 0 && cw == src.width && ch == src.height) return src
        return Bitmap.createBitmap(src, l, t, cw, ch)
    }

    // ================= pixel pass =================
    fun kuwaharaRadius(w: Int, h: Int) =
        (sqrt(w.toFloat() * h) / 280f).roundToInt().coerceIn(2, 6)

    fun Bitmap.pixels(): IntArray = IntArray(width * height).also {
        getPixels(it, 0, width, 0, 0, width, height)
    }
    fun IntArray.toBitmap(w: Int, h: Int): Bitmap =
        Bitmap.createBitmap(this, w, h, Bitmap.Config.ARGB_8888)

    internal fun applyPixelPass(src: Bitmap, state: EditState): Bitmap {
        val t = state.filterStrength.coerceIn(0f, 1f)
        val w = src.width; val h = src.height
        if (state.filter == FilterType.PIXELATE) return pixelate(src)
        val px = src.pixels()
        val out: IntArray = when (state.filter) {
            FilterType.SOFTEN -> Convolver.convolveSeparable(px, w, h, Kernels.GAUSS1_3, Kernels.GAUSS1_3, t)
            FilterType.MOTION_BLUR -> Convolver.motionBlurFractional(px, w, h, state.motionAngle, state.motionLength, t)
            FilterType.SHARPEN -> Convolver.unsharp(px, w, h, 1.5f * t)
            FilterType.CRISP -> Convolver.convolve(px, w, h, Kernels.SHARPEN_HARD, t)
            FilterType.EMBOSS -> Convolver.convolve(px, w, h, Kernels.EMBOSS_NW, t)
            FilterType.RELIEF -> Convolver.convolve(px, w, h, Kernels.RELIEF_SE, t)
            FilterType.EDGE -> Convolver.convolve(px, w, h, Kernels.LAPLACE, t)
            FilterType.FINE_EDGE -> Convolver.convolve(px, w, h, Kernels.LOG_5, t)
            FilterType.OUTLINE -> Convolver.sobel(px, w, h, t)
            FilterType.SKETCH -> Convolver.convolve(px, w, h, Kernels.LAPLACE, t)
            FilterType.INK -> Convolver.inkOutline(px, w, h, EdgePresets.INK_THRESHOLD, false, t)
            FilterType.BLUEPRINT -> Convolver.inkOutline(px, w, h, EdgePresets.BLUEPRINT_THRESHOLD, true, t)
            FilterType.ENGRAVE -> Convolver.engrave(px, w, h, EdgePresets.ENGRAVE_PERIOD, t)
            FilterType.SWIRL -> Convolver.swirl(px, w, h, t, 1f)
            FilterType.FISHEYE -> Convolver.fisheye(px, w, h, t, 1f)
            FilterType.TILT_SHIFT -> Convolver.tiltShift(px, w, h, state.tiltFocus, state.tiltBand, t)
            FilterType.CUSTOM -> state.customKernel?.let {
                Convolver.convolve(px, w, h, Kernel(3, it.values.toFloatArray(), it.scale, it.offset), t)
            } ?: px
            FilterType.PAINTERLY -> Convolver.kuwahara(px, w, h, kuwaharaRadius(w, h), t)
            FilterType.GLOW -> Convolver.glow(px, w, h, blendT = t)
            FilterType.DREAMY -> Convolver.orton(px, w, h, t)
            FilterType.COMIC -> Convolver.comic(px, w, h, t)
            FilterType.HALFTONE -> Convolver.halftone(px, w, h, t)
            FilterType.WATERCOLOR -> Convolver.watercolor(px, w, h, t)
            FilterType.MANGA -> Convolver.manga(px, w, h, t)
            FilterType.POSTERIZE -> { Convolver.posterizeInPlace(px, 5); px }
            else -> px
        }
        return if (out === px) src else out.toBitmap(w, h)
    }

    internal fun fastBlur(src: Bitmap, amount: Float): Bitmap {
        val scale = 1f - (0.92f * amount.coerceIn(0f, 1f))
        val w = (src.width * scale).toInt().coerceAtLeast(1)
        val h = (src.height * scale).toInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(src, w, h, true)
        return Bitmap.createScaledBitmap(small, src.width, src.height, true)
    }

    internal fun pixelate(src: Bitmap, blocks: Int = 64): Bitmap {
        val small = Bitmap.createScaledBitmap(
            src, blocks, (blocks * src.height / src.width).coerceAtLeast(1), true)
        return Bitmap.createScaledBitmap(small, src.width, src.height, false)
    }

    // ================= curves + LUT + grain stages =================
    private val IDENTITY_LUT = IntArray(768) { it % 256 }

    fun effectiveLuts(state: EditState): IntArray? {
        val a = state.adjustments
        val hs = CurveEngine.compose(
            ToneCurveGen.highlightsLut(a.highlights), ToneCurveGen.shadowsLut(a.shadows))
        val flut = FilterCurves.lut(state.filter)?.takeIf { state.filterStrength > 0f }?.let { f ->
            val t = state.filterStrength.coerceIn(0f, 1f)
            if (t >= 1f) f else IntArray(768) { i ->
                (IDENTITY_LUT[i] + (f[i] - IDENTITY_LUT[i]) * t).roundToInt().coerceIn(0, 255) }
        }
        return CurveEngine.compose(CurveEngine.compose(hs, flut), CurveEngine.finalLuts(state.curves))
    }

    internal fun applyCurveLut(src: Bitmap, luts: IntArray): Bitmap {
        val w = src.width; val h = src.height
        val px = src.pixels()
        for (i in px.indices) {
            val p = px[i]
            px[i] = (p and 0xFF000000.toInt()) or
                (luts[(p shr 16) and 0xFF] shl 16) or
                (luts[256 + ((p shr 8) and 0xFF)] shl 8) or luts[512 + (p and 0xFF)]
        }
        return px.toBitmap(w, h)
    }

    fun applyLut3d(src: Bitmap, lut: Lut3D, amount: Float): Bitmap {
        if (amount <= 0f) return src
        val px = src.pixels()
        val inv = 1f / 255f
        for (k in px.indices) {
            val p = px[k]
            val s = lut.sample(((p shr 16) and 0xFF) * inv, ((p shr 8) and 0xFF) * inv, (p and 0xFF) * inv)
            px[k] = (255 shl 24) or
                ((((p shr 16) and 0xFF) + (s[0]*255f - ((p shr 16) and 0xFF)) * amount)
                    .roundToInt().coerceIn(0, 255) shl 16) or
                ((((p shr 8) and 0xFF) + (s[1]*255f - ((p shr 8) and 0xFF)) * amount)
                    .roundToInt().coerceIn(0, 255) shl 8) or
                (((p and 0xFF) + (s[2]*255f - (p and 0xFF)) * amount).roundToInt().coerceIn(0, 255))
        }
        return px.toBitmap(src.width, src.height)
    }

    // ================= draw layers =================
    fun drawVignette(canvas: Canvas, w: Int, h: Int, strength: Float) {
        val cx = w / 2f; val cy = h / 2f
        val paint = Paint().apply {
            shader = RadialGradient(cx, cy, hypot(cx, cy),
                intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT,
                    Color.argb((strength * 200).toInt(), 0, 0, 0)),
                floatArrayOf(0.55f, 0.75f, 1f), Shader.TileMode.CLAMP)
        }
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
    }

    fun drawFrame(canvas: Canvas, w: Int, h: Int, frame: Frame) {
        val inset = frame.widthFraction * min(w, h)
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply {
            style = Paint.Style.STROKE; strokeWidth = inset * 2; color = frame.colorArgb
        })
    }

    fun drawOverlays(canvas: Canvas, w: Int, h: Int, overlays: List<Overlay>,
                     stickers: (Long) -> Bitmap? = { null }) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
        paint.setShadowLayer(6f, 2f, 2f, 0x99000000)
        overlays.forEach { o ->
            val cx = w * o.relX; val cy = h * o.relY
            canvas.save(); canvas.rotate(o.rotation, cx, cy)
            when (o) {
                is Overlay.Text -> {
                    paint.color = o.colorArgb; paint.isFakeBoldText = o.isBold
                    paint.textSize = OverlayLayout.textSize(o.sizeFraction, min(w, h))
                    canvas.drawText(o.text, cx, cy, paint)
                }
                is Overlay.Emoji -> {
                    paint.textSize = OverlayLayout.emojiGlyph(o.sizeFraction, min(w, h))
                    canvas.drawText(o.emoji, cx, cy, paint)
                }
                is Overlay.Image -> stickers(o.id)?.let { bmp ->
                    val bw = OverlayLayout.stickerWidth(o.sizeFraction, min(w, h))
                    val bh = bw * bmp.height / bmp.width.coerceAtLeast(1)
                    if (o.flip) canvas.scale(-1f, 1f, cx, cy)
                    canvas.drawBitmap(bmp, null, RectF(cx - bw/2, cy - bh/2, cx + bw/2, cy + bh/2), paint)
                    if (o.flip) canvas.scale(-1f, 1f, cx, cy)
                }
            }
            canvas.restore()
        }
    }

    // ================= full render =================
    fun render(source: Bitmap, state: EditState, lut: Lut3D? = null,
               stickers: (Long) -> Bitmap? = { null }): Bitmap {
        val a = state.adjustments
        var work = if (a.blur > 0f) fastBlur(source, a.blur) else source
        work = applyGeometry(work, state.rotationDegrees, state.isFlipped, state.straightenDegrees)
        work = applyCropRect(work, state.crop)
        work = applyPixelPass(work, state)

        var out = Bitmap.createBitmap(work.width, work.height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(work, 0f, 0f,
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                colorFilter = ColorMatrixColorFilter(buildMatrix(state)) })
        if (work !== source && work !== out) work.recycle()

        effectiveLuts(state)?.let { c -> applyCurveLut(out, c).let { n -> out.recycle(); out = n } }
        lut?.let { l -> applyLut3d(out, l, state.lutAmount).let { n -> out.recycle(); out = n } }
        if (a.grain > 0f) Grain.apply(out.pixels(), out.width, out.height, a.grain)
            .toBitmap(out.width, out.height).let { n -> out.recycle(); out = n }

        val canvas = Canvas(out)
        val vig = max(a.vignette, if (state.filter == FilterType.LOMO) 0.45f else 0f)
        if (vig > 0f) drawVignette(canvas, out.width, out.height, vig)
        state.frame?.let { drawFrame(canvas, out.width, out.height, it) }
        drawOverlays(canvas, out.width, out.height, state.overlays, stickers)
        return out
    }

    /** Neutral render for mask compositing (lens properties only). */
    fun renderNeutral(source: Bitmap, state: EditState): Bitmap {
        var work = if (state.adjustments.blur > 0f) fastBlur(source, state.adjustments.blur) else source
        work = applyGeometry(work, state.rotationDegrees, state.isFlipped, state.straightenDegrees)
        work = applyCropRect(work, state.crop)
        return work
    }

    fun blendByMask(neutral: Bitmap, edited: Bitmap, mask: Bitmap, crop: CropRect?): Bitmap {
        val out = Convolver.blendByMask(neutral.pixels(), edited.pixels(),
            edited.width, edited.height, mask.pixels(), mask.width, mask.height, crop)
        return out.toBitmap(edited.width, edited.height)
    }

    fun randomLook(): EditState {
        val pool = FilterType.values().filter { it != FilterType.ORIGINAL }
        val f = pool.random()
        return EditState(filter = f, filterStrength = 0.6f + Random.nextFloat() * 0.4f,
            motionAngle = if (f == FilterType.MOTION_BLUR) Random.nextFloat() * 360f else 45f,
            motionLength = if (f == FilterType.MOTION_BLUR) (10..250).random() else 80)
    }
}
