package com.example.filterstudio

import android.graphics.Color
import com.example.filterstudio.engine.*
import com.example.filterstudio.model.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt

class FilterMatrixTest {
    private fun apply(m: FloatArray, r: Float, g: Float, b: Float): FloatArray =
        FloatArray(3) { row -> m[row*5]*r + m[row*5+1]*g + m[row*5+2]*b + m[row*5+4] }

    @Test fun `multiply equals sequential application`() {
        val first = FilterEngine.brightness(0.1f)
        val second = FilterEngine.saturation(0f)
        val combined = FilterEngine.multiply(second, first)
        val p = floatArrayOf(0.8f, 0.4f, 0.2f)
        val seq = apply(first, p[0], p[1], p[2]).let { apply(second, it[0], it[1], it[2]) }
        val one = apply(combined, p[0], p[1], p[2])
        assertTrue((0..2).all { abs(seq[it] - one[it]) < 1e-3f })
    }

    @Test fun `duotone black maps to shadow color exactly`() {
        val m = FilterEngine.duotone(0xFF0A1A2F.toInt(), 0xFFCFEFFA.toInt())
        assertEquals(Color.red(0xFF0A1A2F), apply(m, 0f, 0f, 0f)[0].roundToInt())
    }

    @Test fun `split tone amount zero is identity`() {
        val m = FilterEngine.splitTone(0xFF102040.toInt(), 0xFFF0E0D0.toInt(), 0f)
        assertEquals(1f, apply(m, 1f, 0.5f, 0.25f)[0], 1e-5f)
    }

    @Test fun `strength mix interpolates`() {
        val t0 = FilterEngine.mixedFilterMatrix(FilterType.SEPIA, 0f)
        val t1 = FilterEngine.mixedFilterMatrix(FilterType.SEPIA, 1f)
        assertArrayEquals(FilterEngine.identity(), t0, 1e-5f)
        assertArrayEquals(FilterEngine.filterMatrixInternalForTest(FilterType.SEPIA), t1, 1e-5f)
    }
}

class ConvolverTest {
    private fun px(v: Int) = (255 shl 24) or (v shl 16) or (v shl 8) or v
    private fun red(c: Int) = (c shr 16) and 0xFF
    private fun const(w: Int, h: Int, v: Int) = IntArray(w * h) { px(v) }

    @Test fun `normalized kernels preserve constant images`() {
        val img = const(8, 8, 100)
        assertEquals(img.toList(), Convolver.convolve(img, 8, 8, Kernels.SHARPEN_HARD).toList())
    }

    @Test fun `blend zero is identity`() {
        val img = const(6, 6, 200)
        assertEquals(img.toList(),
            Convolver.convolve(img, 6, 6, Kernels.EMBOSS_NW, 0f).toList())
    }

    @Test fun `sobel fires on step edge only`() {
        val w = 8
        val src = IntArray(8 * w) { i -> px(if (i / w < 4) 20 else 40) }
        val out = Convolver.sobel(src, w, 8)
        assertTrue(red(out[4 * w + 4]) < 200)
        assertTrue(red(out[6 * w + 4]) > 250)
    }

    @Test fun `motion blur constant unchanged`() {
        val src = const(8, 8, 100)
        assertEquals(src.toList(),
            Convolver.motionBlur(src, 8, 8, 37f, 5f, 5).toList())
    }

    @Test fun `ink is strictly binary`() {
        val w = 8
        val src = IntArray(8 * w) { i -> px(if (i % w < 4) 10 else 220) }
        val out = Convolver.inkOutline(src, w, 8, 0.45f, false)
        assertTrue(out.all { red(it) == 0 || red(it) == 255 })
    }

    @Test fun `kuwahara uniform unchanged`() {
        val img = const(10, 10, 128)
        assertEquals(img.toList(), Convolver.kuwahara(img, 10, 10, 3).toList())
    }
}

class CurveLutTests {
    @Test fun `lut monotonic and hits endpoints`() {
        val lut = CurveEngine.buildLut(CurveEngine.PRESETS.getValue("Contrast S"))
        assertEquals(0f, lut[0], 1e-4f); assertEquals(1f, lut[255], 1e-4f)
        assertTrue((1..255).all { lut[it] >= lut[it - 1] - 1e-4f })
    }

    @Test fun `compose equals sequential`() {
        val f = CurveEngine.finalLuts(Curves(rgb = listOf(CurvePoint(0f, 0.07f), CurvePoint(1f, 1f))))!!
        val g = CurveEngine.finalLuts(Curves(rgb = listOf(
            CurvePoint(0f, 0f), CurvePoint(0.5f, 0.6f), CurvePoint(1f, 1f))))!!
        val c = CurveEngine.compose(f, g)
        assertTrue((0..255).all { v -> c[v] == g[f[v]] })
    }

    @Test fun `hs luts generate`() {
        assertNotNull(ToneCurveGen.shadowsLut(0.5f))
        assertNull(ToneCurveGen.shadowsLut(0f))
        assertNotNull(ToneCurveGen.highlightsLut(0.5f))
    }
}

class CubeTests {
    @Test fun `parser and exporter round trip`() {
        val cubeText = CubeExporter.export(EditState(), null, 2)
        val lines = cubeText.lines().drop(4).filter { it.isNotBlank() }
        assertEquals("0.000000 0.000000 0.000000", lines[0])
        assertEquals("1.000000 0.000000 0.000000", lines[1])
        val lut = CubeParser.parse(cubeText.reader())
        assertArrayEquals(floatArrayOf(0.5f, 0.5f, 0.5f), lut.sample(0.5f, 0.5f, 0.5f), 1e-3f)
    }
}

class GeometryCropTests {
    @Test fun `square 45 degrees needs sqrt2 zoom`() =
        assertEquals(1f / kotlin.math.sqrt(2f), FilterEngine.coverScale(100f, 100f, 45f), 1e-4f)

    @Test fun `crop math resize aspect`() {
        val r = CropMath.resize(CropRect(0.2f, 0.2f, 0.6f, 0.6f),
            CropMath.Handle.BR, 1.5f, 0.9f, 1f, 0.08f)
        assertEquals(r.width, r.height, 1e-4f)
        assertTrue(r.right <= 1f + 1e-4f)
    }

    @Test fun `centered full 16_9 on square`() {
        val r = CropMath.centeredFull(1f, 16f / 9f)
        assertEquals(9f / 16f, r.height, 1e-4f)
    }
}

class OverlayMathTests {
    @Test fun `rotation unwraps across seam`() {
        val a = GestureAccumulator()
        a.step(100f, 100f, Math.toRadians(170.0).toFloat(), Math.toRadians(-170.0).toFloat())
        assertEquals(20f, a.rotationDeg, 1e-4f)
    }

    @Test fun `pure scale keeps centered overlay centered`() {
        val a = GestureAccumulator(); a.step(100f, 200f, 0f, 0f)
        val r = a.apply(50f, 50f, 50f, 50f, 50f, 50f, 0f, 0.2f)
        assertEquals(0.4f, r.size, 1e-4f); assertEquals(50f, r.centerX, 1e-4f)
    }

    @Test fun `snap near cardinals`() {
        assertEquals(90f to true, OverlayGestureMath.snapAngle(88.7f))
        assertEquals(40f to false, OverlayGestureMath.snapAngle(40f))
    }

    @Test fun `ordering preserves relative order`() {
        val a = Overlay.Emoji(1, "😀"); val b2 = Overlay.Emoji(2, "🔥"); val c = Overlay.Emoji(3, "⭐")
        assertEquals(listOf(a, c, b2), OverlayOrdering.move(listOf(a, b2, c), 2, true))
    }

    @Test fun `handle step snaps and scales`() {
        val s = OverlayHandleMath.step(0f, 50f, 0f, 100f, 88f, 0.2f, 0.03f, 1.2f)
        assertEquals(90f, s.rotationDeg, 1e-4f); assertEquals(0.4f, s.size, 1e-4f)
    }
}

class AutoEnhanceTests {
    @Test fun `maps endpoints`() {
        val a = AutoEnhance.suggest(16, 240, 0.25f)
        val m = FilterEngine.chain(FilterEngine.brightness(a.brightness), FilterEngine.contrast(a.contrast))
        fun at(v: Int) = m[0] * (v / 255f) + m[4] / 255f
        assertEquals(0f, at(16), 0.01f); assertEquals(1f, at(240), 0.01f)
    }
}

class HistoryTests {
    @Test fun `undo redo eviction`() {
        val h = EditorHistory<Int>(limit = 3)
        (1..5).forEach { h.push(it) }
        assertEquals(4, h.undo(5))
        assertNull(h.undo(3))
        h.push(9); assertNull(h.redo(0))
    }
}

class PresetLookTests {
    @Test fun `withLook preserves geometry and overlays`() {
        val edited = EditState(filter = FilterType.THERMAL,
            crop = CropRect(0.1f, 0.1f, 0.8f, 0.8f), rotationDegrees = 90,
            overlays = listOf(Overlay.Emoji(1, "🔥")))
        val merged = edited.withLook(BuiltInPresets.all[0].look)
        assertEquals(CropRect(0.1f, 0.1f, 0.8f, 0.8f), merged.crop)
        assertEquals(90, merged.rotationDegrees)
        assertEquals(1, merged.overlays.size)
    }

    @Test fun `builtin presets round trip json`() {
        BuiltInPresets.all.forEach { r ->
            val rt = editStateFromJson(r.look.asPresetState().toJson())
            assertEquals(r.look.filter, rt.filter)
        }
    }
}

class VideoEffectsTests {
    @Test fun `plain grayscale ships one effect`() {
        val fx = VideoEffects.forState(EditState(filter = FilterType.GRAYSCALE), false, 33)
        assertEquals(1, fx.size)
    }
    @Test fun `multi ships pixel pass`() {
        val fx = VideoEffects.forState(EditState(filter = FilterType.GLOW), false, 33)
        assertTrue(fx[0] is PixelPassGlEffect)
    }
    @Test fun `grain appended when set`() {
        val st = EditState(adjustments = Adjustments(grain = 0.5f))
        val fx = VideoEffects.forState(st, false, 33)
        assertTrue(fx.any { it is GrainGlEffect })
    }
}

class BackgroundRemoverTests {
    private fun px(rgb: Int) = (255 shl 24) or (rgb shl 16) or (rgb shl 8) or rgb
    private fun alpha(c: Int) = (c ushr 24) and 0xFF

    @Test fun `removes border background keeps subject`() {
        val w = 8
        val src = IntArray(w * 8) { i ->
            val x = i % w; val y = i / w
            px(if (x in 2..5 && y in 2..5) 0 else 255) }
        val out = BackgroundRemover.remove(src, w, 8, 0.1f)
        assertEquals(0, alpha(out[0])); assertEquals(255, alpha(out[3 * w + 3]))
    }
}

class SceneDetectorTests {
    @Test fun `identical frames zero distance`() {
        val h = IntArray(32); h[16] = 1024
        assertEquals(0f, SceneChangeDetector.distance(h, h), 1e-5f)
    }
}
