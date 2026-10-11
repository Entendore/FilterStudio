package com.example.filterstudio.video

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import androidx.media3.effect.RgbMatrix
import androidx.media3.effect.SingleFrameGlShaderProgram
import com.example.filterstudio.engine.*
import com.example.filterstudio.model.EditState
import com.example.filterstudio.model.FilterType
import com.example.filterstudio.model.PixelPassKind

// ================= LookEffect (all matrix-based looks) =================
class LookEffect(private val state: EditState) : RgbMatrix() {
    override fun getMatrix(presentationFrameUs: Long): FloatArray =
        FilterEngine.buildMatrix(state).let { VideoShaderSources.run { it.toGlMatrix() } }
}

// ================= single-frame program wrappers =================
@OptIn(UnstableApi::class)
open class SourceProgram(private val build: (Int, Int) -> String) :
    SingleFrameGlShaderProgram(false, false) {
    override fun createFragmentProgram(texWidth: Int, texHeight: Int): String = build(texWidth, texHeight)
}

// NOTE: texture-upload paths (uLutTex / uMask / uOverlay) are version-sensitive in
// media3 1.3.x — bind CLAMP_TO_EDGE + LINEAR once at first draw via GlProgram if
// your pinned version exposes the hook; fallback = effect skipped (never crash).

class ConvGlEffect(private val k: Kernel, private val blend: Float) : GlEffect {
    override fun getProgram(presentationFrameUs: Long) =
        SourceProgram { w, h -> VideoShaderSources.conv(w, h, k, blend) }
}
class MotionGlEffect(private val state: EditState) : GlEffect {
    override fun getProgram(presentationFrameUs: Long) =
        SourceProgram { w, h ->
            VideoShaderSources.motion(w, h, state.motionAngle, state.motionLength, state.filterStrength) }
}
class EdgeGlEffect(private val threshold: Float, private val invert: Boolean, private val blend: Float) : GlEffect {
    override fun getProgram(presentationFrameUs: Long) =
        SourceProgram { w, h -> VideoShaderSources.edgeBinary(w, h, threshold, invert, blend) }
}
class EngraveGlEffect(private val periodFrac: Float, private val blend: Float) : GlEffect {
    override fun getProgram(presentationFrameUs: Long) =
        SourceProgram { w, h -> VideoShaderSources.engrave(w, h, periodFrac, blend) }
}
class PixelPassGlEffect(private val state: EditState) : GlEffect {
    override fun getProgram(presentationFrameUs: Long) = SourceProgram { w, h ->
        val d = PixelPassSources.glsl(w, h, state.filterStrength)
        when (state.filter) {
            FilterType.PAINTERLY -> PixelPassSources.kuwahara(d, FilterEngine.kuwaharaRadius(w, h))
            FilterType.GLOW -> PixelPassSources.glow(d)
            FilterType.DREAMY -> PixelPassSources.orton(d)
            FilterType.COMIC -> PixelPassSources.comic(d)
            FilterType.MANGA -> PixelPassSources.manga(d)
            FilterType.WATERCOLOR -> PixelPassSources.watercolor(d)
            FilterType.HALFTONE -> PixelPassSources.halftone(d)
            FilterType.POSTERIZE -> PixelPassSources.posterize(d)
            FilterType.PIXELATE -> PixelPassSources.pixelate(d, 64)
            else -> VideoShaderSources.HEADER + "void main(){ outColor=texture(uTex,vTexSamplingCoord); }"
        } }
}
class GrainGlEffect(private val amount: Float) : GlEffect {
    private val programs = (0..3).map { seed ->
        SourceProgram { w, h -> VideoShaderSources.grain(seed, amount) } }
    override fun getProgram(us: Long): GlShaderProgram =
        programs[((us / 33_333_333L) % 4).toInt()]
}
class GradingGlEffect(private val curves: IntArray?, private val vignette: Float,
                      private val hasLut: Boolean, private val lutSize: Int, private val lutAmt: Float) : GlEffect {
    override fun getProgram(presentationFrameUs: Long) =
        SourceProgram { _, _ -> VideoShaderSources.grading(curves, vignette, hasLut, lutSize, lutAmt) }
}
class OverlayGlEffect : GlEffect {
    // Version-sensitive: needs uOverlay texture upload + OVERLAY_FRAGMENT program.
    // Kept as source-program placeholder: composites are stills-complete; for video
    // enable by hosting on BaseGlShaderProgram (see HEADER comment above).
    override fun getProgram(presentationFrameUs: Long): GlShaderProgram =
        SourceProgram { _, _ ->
            VideoShaderSources.HEADER +
                "void main(){ outColor=texture(uTex,vTexSamplingCoord); }" }
}

object VideoEffects {
    /** Canonical order: pixel pass → matrix → grading → grain → overlay. */
    fun forState(state: EditState, hasLut: Boolean, lutSize: Int): List<GlEffect> {
        val out = mutableListOf<GlEffect>()
        val blend = state.filterStrength
        when (state.filter.pass) {
            PixelPassKind.CONV -> when (state.filter) {
                FilterType.MOTION_BLUR -> out.add(MotionGlEffect(state))
                FilterType.INK -> out.add(EdgeGlEffect(EdgePresets.INK_THRESHOLD, false, blend))
                FilterType.BLUEPRINT -> out.add(EdgeGlEffect(EdgePresets.BLUEPRINT_THRESHOLD, true, blend))
                FilterType.ENGRAVE -> out.add(EngraveGlEffect(EdgePresets.ENGRAVE_PERIOD, blend))
                else -> Kernels.forFilter(state.filter, state.customKernel)?.let {
                    out.add(ConvGlEffect(it, blend)) }
            }
            PixelPassKind.MULTI -> out.add(PixelPassGlEffect(state))
            PixelPassKind.NONE -> Unit
        }
        out.add(LookEffect(state))
        val curves = FilterEngine.effectiveLuts(state)
        if (curves != null || state.adjustments.vignette > 0f || hasLut)
            out.add(GradingGlEffect(curves, state.adjustments.vignette, hasLut, lutSize, state.lutAmount))
        if (state.adjustments.grain > 0f) out.add(GrainGlEffect(state.adjustments.grain))
        if (state.overlays.isNotEmpty() || state.frame != null) out.add(OverlayGlEffect())
        return out
    }
}
