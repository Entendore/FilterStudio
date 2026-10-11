package com.example.filterstudio.engine

import com.example.filterstudio.model.EditState
import com.example.filterstudio.model.PixelPassKind

object RenderRouting {
    fun canGpuLive(s: EditState, sdk: Int): Boolean = when {
        sdk >= 33 -> when (s.filter.pass) {
            PixelPassKind.NONE -> true                       // mega or masked shader
            PixelPassKind.CONV -> s.mask == null &&
                (s.curves.isNeutral && s.lutName == null && FilterCurves.lut(s.filter) == null &&
                 s.adjustments.highlights == 0f && s.adjustments.shadows == 0f)
            PixelPassKind.MULTI -> s.mask == null &&
                (s.curves.isNeutral && s.lutName == null && FilterCurves.lut(s.filter) == null &&
                 s.adjustments.highlights == 0f && s.adjustments.shadows == 0f)
        }
        sdk >= 31 -> s.filter.pass == PixelPassKind.NONE && s.mask == null &&
            s.curves.isNeutral && s.lutName == null && FilterCurves.lut(s.filter) == null
        else -> false
    }
}
