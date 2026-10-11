package com.example.filterstudio.engine

/** Dialect-driven single-pass builders shared by AGSL (preview) and GLSL (video). */
object PixelPassSources {

    class Dialect(
        val header: (blend: String) -> String,
        val uv: String, val px: String, val center: String,
        val fetch: (String, String) -> String,
        val fetchUv: (String) -> String,
        val v2: String, val v3: String,
        val tail: (String) -> String
    )

    val AGSL = Dialect(
        header = { b -> "uniform shader input; uniform float2 imgSize; const float blend = $b;\nhalf4 main(float2 xy) {" },
        uv = "(xy / imgSize)", px = "xy", center = "input.eval(xy).rgb",
        fetch = { dx, dy -> "input.eval(xy + float2($dx.0, $dy.0)).rgb" },
        fetchUv = { uv -> "input.eval($uv * imgSize).rgb" },
        v2 = "float2", v3 = "half3",
        tail = { c -> "return half4(mix(input.eval(xy).rgb, half3($c), half(blend)), input.eval(xy).a); }" })

    fun glsl(texW: Int, texH: Int, blend: Float) = Dialect(
        header = { b -> VideoShaderSources.HEADER +
            "const vec2 TEXEL = vec2(%.9f, %.9f);\nconst float blend = $b;\nvoid main() {"
                .format(1f / texW, 1f / texH) },
        uv = "vTexSamplingCoord",
        px = "(vTexSamplingCoord * vec2(${texW}.0, ${texH}.0))",
        center = "texture(uTex, vTexSamplingCoord).rgb",
        fetch = { dx, dy -> "texture(uTex, vTexSamplingCoord + TEXEL * vec2($dx.0, $dy.0)).rgb" },
        fetchUv = { uv -> "texture(uTex, $uv).rgb" },
        v2 = "vec2", v3 = "vec3",
        tail = { c -> "outColor = vec4(mix(texture(uTex, vTexSamplingCoord).rgb, $c, blend), 1.0); }" })

    private fun lum(expr: String) = "dot($expr, vec3(0.299, 0.587, 0.114))"

    private fun blur9(d: Dialect, name: String) =
        "${d.v3} $name = (" + (0..8).joinToString(" + ") { i ->
            val w = listOf(1f,2f,1f,2f,4f,2f,1f,2f,1f)[i]
            val dx = i % 3 - 1; val dy = i / 3 - 1
            "%.4f".format(w / 16f) + " * " + d.fetch("$dx", "$dy") } + ");\n"

    private fun sobelMag(d: Dialect, name: String): String {
        var gx = StringBuilder(); var gy = StringBuilder()
        Kernels.SOBEL_X.data.forEachIndexed { i, w ->
            if (w != 0f) {
                val dx = i % 3 - 1; val dy = i / 3 - 1
                val f = d.fetch("$dx", "$dy")
                gx.append("${w} * ${lum(f)} + ")
                gy.append("${Kernels.SOBEL_Y.data[i]} * ${lum(f)} + ") } }
        return "float gx = $gx 0.0;\nfloat gy = $gy 0.0;\n" +
            "float $name = clamp(length(float2(gx, gy)) * ${EdgePresets.SOBEL_GAIN}, 0.0, 1.0);\n"
    }

    fun posterize(d: Dialect, levels: Int = 5) = d.header("1.0") +
        "${d.v3} q = floor(${d.center} * ${levels - 1}.0 + 0.5) / ${levels - 1}.0;\n" + d.tail("q")

    fun pixelate(d: Dialect, blocks: Int) = d.header("1.0") +
        "const float N = ${blocks}.0;\n" +
        "${d.v2} g = floor(${d.uv} * N);\n" + d.tail(d.fetchUv("(g + 0.5) / N"))

    private val BAYER = "0.0,8.0,2.0,10.0,12.0,4.0,14.0,6.0,3.0,11.0,1.0,9.0,15.0,7.0,13.0,5.0"

    fun halftone(d: Dialect) = d.header("1.0") +
        "const float BAYER[16] = float[16]($BAYER);\n" +
        "${d.v3} c = ${d.center};\nfloat l = ${lum("c")};\n" +
        "${d.v2} p = mod(${d.px}, 4.0);\n" +
        "float t = (BAYER[int(p.y) * 4 + int(p.x)] + 0.5) / 16.0;\n" +
        d.tail("vec3(float(l >= t ? 1.0 : 0.0))")

    fun orton(d: Dialect) = d.header("1.0") + blur9(d, "bl") +
        d.tail("1.0 - (1.0 - ${d.center}) * (1.0 - bl)")

    fun glow(d: Dialect) = d.header("1.0") + blur9(d, "accRaw") +
        "${d.v3} acc = ${d.v3}(0.0);\n" +
        (0..8).joinToString("\n") { i ->
            val w = listOf(1f,2f,1f,2f,4f,2f,1f,2f,1f)[i]
            val dx = i % 3 - 1; val dy = i / 3 - 1
            "{ ${d.v3} c = ${d.fetch("$dx", "$dy")}; float l = ${lum("c")};" +
            " acc += %.4f".format(w / 16f) + " * c * max((l - 0.62) / 0.38, 0.0); };" } + "\n" +
        d.tail("1.0 - (1.0 - ${d.center}) * (1.0 - acc)")

    fun comic(d: Dialect) = d.header("1.0") + sobelMag(d, "edge") +
        "${d.v3} q = floor(${d.center} * 3.0 + 0.5) / 3.0;\n" +
        d.tail("q * (1.0 - edge * 0.9)")

    fun watercolor(d: Dialect) = d.header("1.0") + blur9(d, "bl") + sobelMag(d, "edge") +
        "${d.v3} q = floor(bl * 5.0 + 0.5) / 5.0;\n" +
        d.tail("q * (1.0 - edge * 0.45)")

    fun manga(d: Dialect) = d.header("1.0") + sobelMag(d, "edge") +
        "const float BAYER[16] = float[16]($BAYER);\n" +
        "float l = ${lum(d.center)};\nfloat l3 = floor(l * 3.0 + 0.5) / 3.0;\n" +
        "${d.v2} p = mod(${d.px}, 4.0);\n" +
        "float t = (BAYER[int(p.y) * 4 + int(p.x)] + 0.5) / 16.0;\n" +
        "float v = (l3 > 0.30 && l3 < 0.75) ? ((l3 < t + 0.18) ? 0.157 : 0.922) : l3;\n" +
        "if (edge > 0.32) v = 0.0;\n" + d.tail("vec3(v)")

    fun kuwahara(d: Dialect, radius: Int): String {
        val b = StringBuilder(d.header("1.0"))
        b.append("${d.v3} best = ${d.center}; float bestVar = 1e9;\n")
        for (q in 0 until 4) {
            val sx = if (q and 1 == 0) -1 else 1; val sy = if (q and 2 == 0) -1 else 1
            b.append("${d.v3} s = ${d.v3}(0.0); float sl = 0.0; float sl2 = 0.0; float n = 0.0;\n")
            b.append("for (int dy = 0; dy <= $radius; dy++) { for (int dx = 0; dx <= $radius; dx++) {\n")
            b.append("  ${d.v3} c = ${d.fetch("(dx * $sx)", "(dy * $sy)")};\n")
            b.append("  s += c; float l = ${lum("c")}; sl += l; sl2 += l * l; n += 1.0; } }\n")
            b.append("float v = sl2 / n - (sl / n) * (sl / n);\n" +
                "if (v < bestVar) { bestVar = v; best = s / n; }\n")
        }
        return b.toString() + d.tail("best")
    }
}
