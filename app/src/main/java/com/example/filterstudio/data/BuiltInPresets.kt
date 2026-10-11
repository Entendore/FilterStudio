package com.example.filterstudio.data

import com.example.filterstudio.engine.CurveEngine
import com.example.filterstudio.model.*

object BuiltInPresets {
    data class Recipe(val name: String, val category: String, val look: LookState)

    private fun look(
        filter: FilterType, strength: Float = 1f,
        b: Float = 0f, c: Float = 1f, s: Float = 1f, w: Float = 0f, v: Float = 0f,
        curves: Curves = Curves(), split: SplitTone? = null, frame: Frame? = null
    ) = LookState(filter, strength, Adjustments(b, c, s, w), curves, null, 1f,
                  split, null, 45f, 80, frame)

    val all: List<Recipe> = listOf(
        Recipe("Natural", "Essentials", look(FilterType.VIVID, 0.7f)),
        Recipe("Cream", "Essentials", look(FilterType.FADE, b = 0.04f, w = 0.15f, s = 0.9f)),
        Recipe("Golden", "Essentials", look(FilterType.SUNSET, w = 0.3f, v = 0.15f)),
        Recipe("Nordic", "Essentials", look(FilterType.COOL, c = 1.05f)),
        Recipe("Film Fade", "Essentials", look(FilterType.FADE,
            curves = Curves(rgb = CurveEngine.PRESETS.getValue("Matte")))),
        Recipe("Rosé", "Portrait", look(FilterType.ROSE, b = 0.03f, s = 1.05f)),
        Recipe("Soft Light", "Portrait", look(FilterType.SOFTEN, 0.7f, b = 0.03f)),
        Recipe("Drama", "Moody", look(FilterType.DRAMATIC, v = 0.35f)),
        Recipe("Storm", "Moody", look(FilterType.DRAMATIC, s = 0.7f, v = 0.45f, c = 1.1f)),
        Recipe("Matrix", "Moody", look(FilterType.MATRIX_GREEN, v = 0.3f)),
        Recipe("Ansel Red", "B&W", look(FilterType.ANSEL, c = 1.15f)),
        Recipe("High Key", "B&W", look(FilterType.PLATINUM, b = 0.08f)),
        Recipe("Noir", "B&W", look(FilterType.NOIR, v = 0.5f)),
        Recipe("Thermal", "Creative", look(FilterType.THERMAL, c = 1.15f)),
        Recipe("Cyber", "Creative", look(FilterType.DUOTONE_NAVY, v = 0.3f)),
        Recipe("XP Cross", "Creative", look(FilterType.CROSS_PROCESS)),
        Recipe("Blueprint", "Creative", look(FilterType.BLUEPRINT,
            frame = Frame(0.05f, 0xFFF2F6FF.toInt()))),
        Recipe("Night", "Creative", look(FilterType.NIGHT_VISION)))
}
