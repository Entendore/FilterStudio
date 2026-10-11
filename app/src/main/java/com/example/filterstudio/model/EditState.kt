package com.example.filterstudio.model

import android.graphics.Color
import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import kotlin.math.abs
import kotlin.math.roundToInt

enum class Category(val label: String) {
    LOOKS("Looks"), TONES("Tones"), FX("Effects"), ARTISTIC("Artistic")
}
enum class PixelPassKind { NONE, CONV, MULTI }

enum class AdjustmentType(val def: Float, val from: Float, val to: Float) {
    BRIGHTNESS(0f, -0.5f, 0.5f), CONTRAST(1f, 0.5f, 1.5f), SATURATION(1f, 0f, 2f),
    WARMTH(0f, -1f, 1f), TEMPERATURE(0f, -1f, 1f), TINT(0f, -1f, 1f),
    HIGHLIGHTS(0f, -1f, 1f), SHADOWS(0f, 0f, 1f),
    VIGNETTE(0f, 0f, 1f), BLUR(0f, 0f, 1f), GRAIN(0f, 0f, 1f),
    STRAIGHTEN(0f, -45f, 45f),
    MOTION_ANGLE(45f, 0f, 359f), MOTION_LENGTH(80f, 10f, 250f)
}

@Parcelize
enum class FilterType(
    val label: String, val category: Category, val pass: PixelPassKind = PixelPassKind.NONE
) : Parcelable {
    // LOOKS
    ORIGINAL("Original", Category.LOOKS), VIVID("Vivid", Category.LOOKS),
    FADE("Fade", Category.LOOKS), DRAMATIC("Drama", Category.LOOKS),
    NOIR("Noir", Category.LOOKS), GRAYSCALE("Mono", Category.LOOKS),
    SEPIA("Sepia", Category.LOOKS), ANSEL("Ansel", Category.LOOKS),
    SILVER("Silver", Category.LOOKS), PLATINUM("Platinum", Category.LOOKS),
    SELENIUM("Selenium", Category.LOOKS), SLATE("Slate", Category.LOOKS),
    POLAROID("Polaroid", Category.LOOKS), CHROME("Chrome", Category.LOOKS),
    WATERCOLOR("Water", Category.LOOKS, PixelPassKind.MULTI),
    // TONES
    COOL("Cool", Category.TONES), SUNSET("Sunset", Category.TONES),
    AQUA("Aqua", Category.TONES), ROSE("Rose", Category.TONES),
    MARS("Mars", Category.TONES), VINTAGE("Vintage", Category.TONES),
    CROSS_PROCESS("XPro", Category.TONES), BLEACH_BYPASS("Bleach", Category.TONES),
    CYANOTYPE("Cyan", Category.TONES), XRAY("X-Ray", Category.TONES),
    TECHNICOLOR("Techni", Category.TONES), INFRARED("Infra", Category.TONES),
    // FX
    LOMO("Lomo", Category.FX), NEON("Neon", Category.FX),
    SHARPEN("Sharpen", Category.FX, PixelPassKind.CONV),
    SOFTEN("Soft", Category.FX, PixelPassKind.CONV),
    MOTION_BLUR("Motion", Category.FX, PixelPassKind.CONV),
    GLOW("Glow", Category.FX, PixelPassKind.MULTI),
    CRISP("Crisp", Category.FX, PixelPassKind.CONV),
    DREAMY("Dreamy", Category.FX, PixelPassKind.MULTI),
    HALFTONE("Halftone", Category.FX, PixelPassKind.MULTI),
    THERMAL("Thermal", Category.FX), NIGHT_VISION("Night", Category.FX),
    MATRIX_GREEN("Matrix", Category.FX), INK("Ink", Category.FX, PixelPassKind.CONV),
    PIXELATE("Pixel", Category.FX, PixelPassKind.MULTI),
    TILT_SHIFT("Tilt", Category.FX, PixelPassKind.CONV),
    CUSTOM("Custom", Category.FX, PixelPassKind.CONV),
    // ARTISTIC
    DUOTONE_NAVY("Navy", Category.ARTISTIC), DUOTONE_PEACH("Peach", Category.ARTISTIC),
    DUOTONE_ACID("Acid", Category.ARTISTIC), EMBOSS("Emboss", Category.ARTISTIC, PixelPassKind.CONV),
    RELIEF("Relief", Category.ARTISTIC, PixelPassKind.CONV), EDGE("Edges", Category.ARTISTIC, PixelPassKind.CONV),
    FINE_EDGE("Fine Edge", Category.ARTISTIC, PixelPassKind.CONV), OUTLINE("Outline", Category.ARTISTIC, PixelPassKind.CONV),
    SKETCH("Sketch", Category.ARTISTIC, PixelPassKind.CONV), PAINTERLY("Painterly", Category.ARTISTIC, PixelPassKind.MULTI),
    POSTERIZE("Poster", Category.ARTISTIC, PixelPassKind.MULTI), SWIRL("Swirl", Category.ARTISTIC, PixelPassKind.CONV),
    FISHEYE("Fisheye", Category.ARTISTIC, PixelPassKind.CONV), COMIC("Comic", Category.ARTISTIC, PixelPassKind.MULTI),
    BLUEPRINT("Print", Category.ARTISTIC, PixelPassKind.CONV), ENGRAVE("Engrave", Category.ARTISTIC, PixelPassKind.CONV),
    INVERT("Invert", Category.ARTISTIC);
    val needsPixelPass: Boolean get() = pass != PixelPassKind.NONE
}

@Parcelize
data class Adjustments(
    val brightness: Float = 0f, val contrast: Float = 1f, val saturation: Float = 1f,
    val warmth: Float = 0f, val temperature: Float = 0f, val tint: Float = 0f,
    val highlights: Float = 0f, val shadows: Float = 0f,
    val vignette: Float = 0f, val blur: Float = 0f, val grain: Float = 0f
) : Parcelable

fun Adjustments.with(type: AdjustmentType, value: Float) = when (type) {
    AdjustmentType.BRIGHTNESS -> copy(brightness = value)
    AdjustmentType.CONTRAST -> copy(contrast = value)
    AdjustmentType.SATURATION -> copy(saturation = value)
    AdjustmentType.WARMTH -> copy(warmth = value)
    AdjustmentType.TEMPERATURE -> copy(temperature = value)
    AdjustmentType.TINT -> copy(tint = value)
    AdjustmentType.HIGHLIGHTS -> copy(highlights = value)
    AdjustmentType.SHADOWS -> copy(shadows = value)
    AdjustmentType.VIGNETTE -> copy(vignette = value)
    AdjustmentType.BLUR -> copy(blur = value)
    AdjustmentType.GRAIN -> copy(grain = value)
    else -> this
}

@Parcelize data class CurvePoint(val x: Float, val y: Float) : Parcelable

@Parcelize
data class Curves(
    val rgb: List<CurvePoint> = NEUTRAL, val red: List<CurvePoint> = NEUTRAL,
    val green: List<CurvePoint> = NEUTRAL, val blue: List<CurvePoint> = NEUTRAL
) : Parcelable {
    val isNeutral get() = rgb == NEUTRAL && red == NEUTRAL && green == NEUTRAL && blue == NEUTRAL
    fun with(pts: List<CurvePoint>) = when (activeChannel) {
        CurveChannel.RGB -> copy(rgb = pts); CurveChannel.R -> copy(red = pts)
        CurveChannel.G -> copy(green = pts); CurveChannel.B -> copy(blue = pts)
    }
    companion object { val NEUTRAL = listOf(CurvePoint(0f, 0f), CurvePoint(1f, 1f)) }
}
enum class CurveChannel(val label: String) { RGB("RGB"), R("R"), G("G"), B("B") }
var activeChannel = CurveChannel.RGB   // module-level selection used by editor view

@Parcelize data class SplitTone(val shadow: Int, val highlight: Int, val amount: Float = 0.6f) : Parcelable

@Parcelize
data class CropRect(val left: Float, val top: Float, val width: Float, val height: Float) : Parcelable {
    val right get() = left + width; val bottom get() = top + height
    val isFull get() = left <= 0.001f && top <= 0.001f && right >= 0.999f && bottom >= 0.999f
}

@Parcelize data class Frame(val widthFraction: Float = 0.04f, val colorArgb: Int = Color.WHITE) : Parcelable

@Parcelize
data class KernelSpec(val values: List<Float>, val scale: Float = 1f, val offset: Float = 0f) : Parcelable {
    fun normalized(): KernelSpec {
        val s = values.sum()
        return if (abs(s) > 1e-3f) copy(scale = s) else this
    }
}

@Parcelize data class MaskRef(val id: Long) : Parcelable

@Parcelize
sealed class Overlay : Parcelable {
    abstract val id: Long; abstract val relX: Float; abstract val relY: Float
    abstract val rotation: Float; abstract val sizeFraction: Float

    @Parcelize data class Text(val id: Long, val text: String, val colorArgb: Int = Color.WHITE,
        val isBold: Boolean = true, override val relX: Float = 0.5f, override val relY: Float = 0.5f,
        override val rotation: Float = 0f, override val sizeFraction: Float = 0.06f) : Overlay()

    @Parcelize data class Emoji(val id: Long, val emoji: String,
        override val relX: Float = 0.5f, override val relY: Float = 0.5f,
        override val rotation: Float = 0f, override val sizeFraction: Float = 0.18f) : Overlay()

    @Parcelize data class Image(val id: Long, val uri: String, val removedBg: Boolean = false,
        val flip: Boolean = false, override val relX: Float = 0.5f, override val relY: Float = 0.5f,
        override val rotation: Float = 0f, override val sizeFraction: Float = 0.25f) : Overlay()
}

val Overlay.defaultSizeFraction: Float get() = when (this) {
    is Overlay.Text -> 0.06f; is Overlay.Emoji -> 0.18f; is Overlay.Image -> 0.25f
}

fun Overlay.withTransform(relX: Float, relY: Float, rotationDeg: Float, sizeFraction: Float) = when (this) {
    is Overlay.Text -> copy(relX = relX, relY = relY, rotation = rotationDeg, sizeFraction = sizeFraction)
    is Overlay.Emoji -> copy(relX = relX, relY = relY, rotation = rotationDeg, sizeFraction = sizeFraction)
    is Overlay.Image -> copy(relX = relX, relY = relY, rotation = rotationDeg, sizeFraction = sizeFraction)
}

object OverlayLayout {
    const val EMOJI_FACTOR = 1.6f
    fun textSize(s: Float, minDim: Float) = s * minDim
    fun emojiGlyph(s: Float, minDim: Float) = s * minDim * EMOJI_FACTOR
    fun stickerWidth(s: Float, minDim: Float) = s * minDim
}

@Parcelize
data class EditState(
    val filter: FilterType = FilterType.ORIGINAL,
    val filterStrength: Float = 1f,
    val adjustments: Adjustments = Adjustments(),
    val curves: Curves = Curves(),
    val lutName: String? = null,
    val lutAmount: Float = 1f,
    val splitTone: SplitTone? = null,
    val customKernel: KernelSpec? = null,
    val motionAngle: Float = 45f,
    val motionLength: Int = 80,
    val tiltFocus: Float = 0.5f,
    val tiltBand: Float = 0.15f,
    val rotationDegrees: Int = 0,
    val isFlipped: Boolean = false,
    val straightenDegrees: Float = 0f,
    val crop: CropRect? = null,
    val mask: MaskRef? = null,
    val overlays: List<Overlay> = emptyList(),
    val frame: Frame? = null
) : Parcelable

/** A preset is a LOOK, not a document: geometry/crop/overlays/mask excluded. */
data class LookState(
    val filter: FilterType = FilterType.ORIGINAL, val filterStrength: Float = 1f,
    val adjustments: Adjustments = Adjustments(), val curves: Curves = Curves(),
    val lutName: String? = null, val lutAmount: Float = 1f, val splitTone: SplitTone? = null,
    val customKernel: KernelSpec? = null, val motionAngle: Float = 45f, val motionLength: Int = 80,
    val frame: Frame? = null
)

val EditState.look: LookState get() = LookState(
    filter, filterStrength, adjustments, curves, lutName, lutAmount,
    splitTone, customKernel, motionAngle, motionLength, frame)

fun EditState.withLook(l: LookState) = copy(
    filter = l.filter, filterStrength = l.filterStrength, adjustments = l.adjustments,
    curves = l.curves, lutName = l.lutName, lutAmount = l.lutAmount, splitTone = l.splitTone,
    customKernel = l.customKernel, motionAngle = l.motionAngle, motionLength = l.motionLength,
    frame = l.frame)

fun LookState.asPresetState(): EditState = EditState().withLook(this)
