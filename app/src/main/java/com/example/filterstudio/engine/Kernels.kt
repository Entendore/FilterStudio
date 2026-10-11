package com.example.filterstudio.engine

class Kernel(
    val size: Int, val data: FloatArray, val scale: Float = 1f, val offset: Float = 0f
) {
    val radius = size / 2
    init { require(size % 2 == 1 && data.size == size * size) }
    fun signature(): String = data.joinToString(",") { "%.4f".format(it) } + "|$scale|$offset"
}

object EdgePresets {
    const val INK_THRESHOLD = 0.45f
    const val BLUEPRINT_THRESHOLD = 0.42f
    const val ENGRAVE_PERIOD = 0.02f
    const val SOBEL_GAIN = 0.35f
}

object Kernels {
    val GAUSS1_3 = floatArrayOf(1f, 2f, 1f)
    val GAUSS1_5 = floatArrayOf(1f, 4f, 6f, 4f, 1f)
    val GAUSS_2D_3 = Kernel(3, floatArrayOf(1f,2f,1f, 2f,4f,2f, 1f,2f,1f), scale = 16f)
    val SHARPEN_HARD = Kernel(3, floatArrayOf(-1f,-1f,-1f, -1f,9f,-1f, -1f,-1f,-1f))
    val EMBOSS_NW = Kernel(3, floatArrayOf(-2f,-1f,0f, -1f,1f,1f, 0f,1f,2f), offset = 127f)
    val RELIEF_SE = Kernel(3, floatArrayOf(2f,1f,0f, 1f,-1f,-1f, 0f,-1f,-2f), offset = 127f)
    val LAPLACE = Kernel(3, floatArrayOf(0f,-1f,0f, -1f,4f,-1f, 0f,-1f,0f))
    val LOG_5 = Kernel(5, floatArrayOf(
        0f,0f,-1f,0f,0f,  0f,-1f,-2f,-1f,0f,  -1f,-2f,16f,-2f,-1f,
        0f,-1f,-2f,-1f,0f,  0f,0f,-1f,0f,0f))
    val SOBEL_X = Kernel(3, floatArrayOf(-1f,0f,1f, -2f,0f,2f, -1f,0f,1f))
    val SOBEL_Y = Kernel(3, floatArrayOf(-1f,-2f,-1f, 0f,0f,0f, 1f,2f,1f))

    /** One mapping shared by CPU, AGSL and GLSL dispatchers. */
    fun forFilter(f: com.example.filterstudio.model.FilterType,
                  custom: com.example.filterstudio.model.KernelSpec?): Kernel? =
        when (f) {
            com.example.filterstudio.model.FilterType.SOFTEN -> GAUSS_2D_3
            com.example.filterstudio.model.FilterType.CRISP -> SHARPEN_HARD
            com.example.filterstudio.model.FilterType.EMBOSS -> EMBOSS_NW
            com.example.filterstudio.model.FilterType.RELIEF -> RELIEF_SE
            com.example.filterstudio.model.FilterType.EDGE,
            com.example.filterstudio.model.FilterType.SKETCH -> LAPLACE
            com.example.filterstudio.model.FilterType.FINE_EDGE -> LOG_5
            com.example.filterstudio.model.FilterType.CUSTOM -> custom?.let {
                Kernel(3, it.values.toFloatArray(), it.scale, it.offset) }
            else -> null
        }
}
