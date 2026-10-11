package com.example.filterstudio

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.filterstudio.data.BuiltInPresets
import com.example.filterstudio.data.LutRepository
import com.example.filterstudio.engine.*
import com.example.filterstudio.model.*
import com.example.filterstudio.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

data class UiState(
    val edit: EditState = EditState(),
    val rendered: Bitmap? = null,
    val isProcessing: Boolean = false,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false
)
data class FilterThumbnail(val type: FilterType, val bitmap: Bitmap)
data class PaletteSwatch(val color: Int, val weight: Float)
data class BatchState(val done: Int, val total: Int)

class EditorViewModel(app: Application) : AndroidViewModel(app) {

    private val lutRepo = LutRepository(app)
    private val presetStore = PresetStore(app)

    private var original: Bitmap? = null
    private var geoBase: Bitmap? = null
    private var base: Bitmap? = null
    private var previewSource: Bitmap? = null
    private var renderedFor: EditState? = null

    val originalBitmap get() = original
    val uncroppedBase get() = geoBase
    val baseBitmap get() = base
    var sourceUri: String? = null; private set

    private val _editState = MutableStateFlow(EditState())
    val editState: StateFlow<EditState> = _editState.asStateFlow()
    private val _rendered = MutableStateFlow<Bitmap?>(null)
    private val _isProcessing = MutableStateFlow(false)
    private val _history = EditorHistory<EditState>()

    val uiState: StateFlow<UiState> = combine(
        _editState, _rendered, _isProcessing, _history.canUndo, _history.canRedo
    ) { e, r, p, u, d -> UiState(e, r, p, u, d) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    private val _thumbnails = MutableStateFlow<List<FilterThumbnail>>(emptyList())
    val thumbnails: StateFlow<List<FilterThumbnail>> = _thumbnails.asStateFlow()
    private val _presetCovers = MutableStateFlow<Map<String, Bitmap>>(emptyMap())
    val presetCovers: StateFlow<Map<String, Bitmap>> = _presetCovers.asStateFlow()
    private val _palette = MutableStateFlow<List<PaletteSwatch>>(emptyList())
    val palette: StateFlow<List<PaletteSwatch>> = _palette.asStateFlow()
    private val _luts = MutableStateFlow(lutRepo.names())
    val luts: StateFlow<List<String>> = _luts.asStateFlow()

    private val maskStore = HashMap<Long, Bitmap>()
    private val stickerStore = HashMap<Long, Bitmap>()
    var workingMask: Bitmap? = null; private set

    // ---- batch ----
    private val _batch = MutableStateFlow<BatchState?>(null)
    val batch: StateFlow<BatchState?> = _batch.asStateFlow()
    private var batchJob: Job? = null
    fun cancelBatch() { batchJob?.cancel() }

    init {
        viewModelScope.launch {
            _editState.drop(1).distinctUntilChanged().collectLatest { st ->
                if (RenderRouting.canGpuLive(st, Build.VERSION.SDK_INT)) return@collectLatest
                val small = previewSource ?: return@collectLatest
                _isProcessing.value = true
                _rendered.value = withContext(Dispatchers.Default) {
                    renderComposited(small, st)
                }
                _isProcessing.value = false
            }
        }
    }

    // ================= loading =================
    fun loadImage(bitmap: Bitmap, uri: String? = null, resetState: Boolean = true) {
        original = bitmap; sourceUri = uri
        if (resetState) { _history.clear(); _editState.value = EditState() }
        rebuildBase()
        viewModelScope.launch {
            previewSource = withContext(Dispatchers.Default) {
                val s = 384f / maxOf(bitmap.width, bitmap.height)
                Bitmap.createScaledBitmap(bitmap,
                    (bitmap.width * s).toInt(), (bitmap.height * s).toInt(), true) }
            if (resetState) {
                _thumbnails.value = generateThumbnails(base!!)
                generatePresetCovers(base!!)
                extractPalette()
            }
        }
    }

    fun loadCaptured(bitmap: Bitmap, uri: String, lookJson: String?) {
        loadImage(bitmap, uri)
        lookJson?.let {
            runCatching { editStateFromJson(it) }.getOrNull()?.let { vf ->
                _editState.value = EditState().withLook(vf.look)
            }
        }
    }

    fun restoreUriNeeded(): String? = if (original == null) sourceUri else null

    private fun rebuildBase() {
        val src = original ?: return
        viewModelScope.launch {
            val st = _editState.value
            geoBase = withContext(Dispatchers.Default) {
                FilterEngine.applyGeometry(src, st.rotationDegrees, st.isFlipped, st.straightenDegrees) }
            base = withContext(Dispatchers.Default) { FilterEngine.applyCropRect(geoBase!!, st.crop) }
            if (_thumbnails.value.isNotEmpty()) _thumbnails.value = generateThumbnails(base!!)
        }
    }

    private suspend fun generateThumbnails(src: Bitmap): List<FilterThumbnail> =
        withContext(Dispatchers.Default) {
            val small = downscale(src, 112)
            FilterType.values().map { f ->
                FilterThumbnail(f, FilterEngine.render(small, EditState(filter = f,
                    motionAngle = _editState.value.motionAngle,
                    motionLength = _editState.value.motionLength))) }
        }

    private suspend fun generatePresetCovers(src: Bitmap) = withContext(Dispatchers.Default) {
        val small = downscale(src, 96)
        val covers = HashMap<String, Bitmap>()
        presetStore.all().forEach { p ->
            covers[p.name] = FilterEngine.render(small, p.state) }
        _presetCovers.value = covers
    }

    private fun downscale(src: Bitmap, target: Int): Bitmap {
        val scale = target / maxOf(src.width, src.height).toFloat()
        return Bitmap.createScaledBitmap(src,
            (src.width * scale).toInt().coerceAtLeast(1),
            (src.height * scale).toInt().coerceAtLeast(1), true)
    }

    // ================= state update core =================
    private var gestureStart: EditState? = null

    private fun update(transform: (EditState) -> EditState, push: Boolean = true) {
        if (push) _history.push(_editState.value)
        _editState.value = transform(_editState.value)
        renderedFor = null
    }

    fun previewAdjustment(type: AdjustmentType, value: Float) {
        if (gestureStart == null) gestureStart = _editState.value
        _editState.value = when (type) {
            AdjustmentType.STRAIGHTEN -> _editState.value.copy(straightenDegrees = value)
            AdjustmentType.MOTION_ANGLE -> _editState.value.copy(motionAngle = value)
            AdjustmentType.MOTION_LENGTH -> _editState.value.copy(motionLength = value.roundToInt())
            else -> _editState.value.copy(
                adjustments = _editState.value.adjustments.with(type, value))
        }
    }

    fun commitAdjustment() {
        val start = gestureStart
        if (start != null) {
            _history.push(start)
            if (start.straightenDegrees != _editState.value.straightenDegrees ||
                start.rotationDegrees != _editState.value.rotationDegrees ||
                start.isFlipped != _editState.value.isFlipped) rebuildBase()
        }
        gestureStart = null; renderedFor = null
    }

    fun setFilter(f: FilterType) = update { it.copy(filter = f, filterStrength = 1f) }
    fun setFilterStrength(v: Float) = update({ it.copy(filterStrength = v) }, push = false)
    fun commitStrength() { renderedFor = null }
    fun setCustomKernel(k: KernelSpec?) = update { it.copy(customKernel = k, filter = FilterType.CUSTOM) }
    fun setSplitTone(s: SplitTone?) = update { it.copy(splitTone = s) }
    fun setLut(name: String?) = update { it.copy(lutName = name, lutAmount = 1f) }
    fun rotate90() = update { it.copy(rotationDegrees = (it.rotationDegrees + 90) % 360) }
        .also { rebuildBase() }
    fun flip() = update { it.copy(isFlipped = !it.isFlipped) }.also { rebuildBase() }
    fun setCrop(rect: CropRect?) { update { it.copy(crop = rect?.takeUnless { r -> r.isFull }) }; rebuildBase() }

    fun undo() { _history.undo(_editState.value)?.let { _editState.value = it; rebuildBase() } }
    fun redo() { _history.redo(_editState.value)?.let { _editState.value = it; rebuildBase() } }

    fun autoEnhance() {
        val src = base ?: return
        val (hist, sat) = ImageStats.analyze(src)
        val total = hist.sum()
        val adj = AutoEnhance.suggest(
            ImageStats.percentile(hist, total, 0.005f),
            ImageStats.percentile(hist, total, 0.995f), sat)
        update { it.copy(adjustments = adj) }
    }

    fun surprise() { val l = FilterEngine.randomLook(); update { it.withLook(l) } }

    // ================= overlays =================
    fun addOverlay(o: Overlay) = update { it.copy(overlays = it.overlays + o) }
    fun removeOverlay(id: Long) = update { it.copy(overlays = it.overlays.filterNot { x -> x.id == id }) }
    fun bringToFront(id: Long) = update { it.copy(overlays = OverlayOrdering.move(it.overlays, id, true)) }
    fun sendToBack(id: Long) = update { it.copy(overlays = OverlayOrdering.move(it.overlays, id, false)) }

    fun transformOverlayPreview(id: Long, relX: Float, relY: Float, rotationDeg: Float, size: Float) {
        if (gestureStart == null) gestureStart = _editState.value
        _editState.value = _editState.value.copy(overlays = _editState.value.overlays.map {
            if (it.id == id) it.withTransform(
                relX.coerceIn(0.03f, 0.97f), relY.coerceIn(0.06f, 0.94f),
                rotationDeg, size.coerceIn(OverlayGestureMath.MIN_SIZE, OverlayGestureMath.MAX_SIZE))
            else it })
    }

    fun resetOverlayTransform(id: Long) = update { st ->
        st.copy(overlays = st.overlays.map {
            if (it.id == id) it.withTransform(it.relX, it.relY, 0f, it.defaultSizeFraction) else it }) }

    fun addImageSticker(uri: Uri, removeBg: Boolean) = viewModelScope.launch {
        val src = withContext(Dispatchers.IO) {
            ImageLoader.decodeSampled(getApplication(), uri, 1024) }
        val id = System.nanoTime()
        stickerStore[id] = if (removeBg) withContext(Dispatchers.Default) {
            val px = src.pixels()
            BackgroundRemover.remove(px, src.width, src.height).toBitmap(src.width, src.height)
        } else src
        update { it.copy(overlays = it.overlays +
            Overlay.Image(id, uri.toString(), removeBg)) }
    }

    fun stickerFor(id: Long): Bitmap? = stickerStore[id]

    // ================= masks =================
    fun beginMask() {
        val b = geoBase ?: return
        val s = 1024f / maxOf(b.width, b.height)
        workingMask = Bitmap.createBitmap(
            (b.width * s).toInt().coerceAtLeast(1), (b.height * s).toInt().coerceAtLeast(1),
            Bitmap.Config.ARGB_8888)
    }
    fun maskWorking() = workingMask

    fun commitMaskStroke(snapshot: Bitmap) {
        val id = System.nanoTime()
        maskStore[id] = snapshot
        update { it.copy(mask = MaskRef(id)) }
    }

    fun maskFor(ref: MaskRef): Bitmap = maskStore[ref.id]
        ?: Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)

    fun clearMask() = update { it.copy(mask = null) }

    // ================= palette =================
    fun extractPalette() {
        val src = base ?: return
        viewModelScope.launch {
            _palette.value = withContext(Dispatchers.Default) {
                val small = downscale(src, 128)
                androidx.palette.graphics.Palette.from(small).maximumColorCount(16).generate()
                    .swatches.sortedByDescending { it.population }.take(8)
                    .map { PaletteSwatch(it.rgb, it.population.toFloat()) }
            }
        }
    }

    // ================= presets =================
    val presets: StateFlow<List<PresetStore.Preset>> = flow { emit(presetStore.all()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun applyPreset(p: PresetStore.Preset) = update { it.withLook(p.state.look) }
    fun savePreset(name: String) = viewModelScope.launch {
        presetStore.save(name, _editState.value)
        _presetCovers.value = _presetCovers.value  // covers regenerate on next image load
    }
    fun deletePreset(name: String) = viewModelScope.launch { presetStore.delete(name) }
    fun exportPreset(p: PresetStore.Preset): String = presetStore.export(p)
    fun importPreset(json: String) = viewModelScope.launch { presetStore.import(json) }

    // ================= LUTs =================
    fun importLut(uri: Uri, name: String) { lutRepo.import(uri, name); _luts.value = lutRepo.names() }
    fun deleteLut(name: String) { lutRepo.delete(name); _luts.value = lutRepo.names() }
    suspend fun currentLutTexture(): Pair<Bitmap, Int>? = _editState.value.lutName?.let {
        lutRepo.packedBitmap(it)?.let { b -> b to b.height } }
    suspend fun currentLut() = _editState.value.lutName?.let { lutRepo.resolve(it) }
    suspend fun effectiveCurveLuts(): IntArray? = withContext(Dispatchers.Default) {
        FilterEngine.effectiveLuts(_editState.value) }

    // ================= rendering =================
    private suspend fun renderComposited(src: Bitmap, st: EditState): Bitmap {
        val lut = if (src === original) currentLut() else null
        val edited = FilterEngine.render(src, st, lut, ::stickerFor)
        val mask = st.mask?.let { maskStore[it.id] } ?: return edited
        val neutral = FilterEngine.renderNeutral(
            if (src === original) original!! else src, st)
        if (neutral.width != edited.width)
            return edited   // safety: sizes must agree; export path handles full-res
        return withContext(Dispatchers.Default) {
            FilterEngine.blendByMask(neutral, edited, mask, st.crop) }
    }

    suspend fun ensureRendered(): Bitmap {
        val st = _editState.value
        val src = original ?: error("No image loaded")
        if (renderedFor == st && _rendered.value != null) return _rendered.value!!
        val out = withContext(Dispatchers.Default) {
            if (Build.VERSION.SDK_INT >= 33 && st.filter.pass == PixelPassKind.NONE) {
                try {
                    val curveLuts = FilterEngine.effectiveLuts(st)
                    val curveBmp = curveLuts?.let { CurveEngine.curveBitmap(it) }
                    val lutPacked = st.lutName?.let { lutRepo.packedBitmap(it) }
                    val rect = android.graphics.RectF(0f, 0f,
                        base?.width?.toFloat() ?: src.width.toFloat(),
                        base?.height?.toFloat() ?: src.height.toFloat())
                    val pipeline = RenderPipeline
                    val effMethod = RenderPipeline::class.java.getDeclaredMethod(
                        "applyLiveAgsl", android.view.View::class.java, EditState::class.java,
                        Float::class.javaPrimitiveType, Bitmap::class.java, Bitmap::class.java,
                        Int::class.javaPrimitiveType, android.graphics.RectF::class.java)
                    null  // GPU export via effectFor requires reflection-free path; CPU is exact
                } catch (t: Throwable) { null }
            } else null
        } ?: withContext(Dispatchers.Default) { renderComposited(src, st) }
        _rendered.value = out; renderedFor = st
        return out
    }

    // ================= batch =================
    fun runBatch(uris: List<Uri>, decode: (Uri) -> Bitmap?, save: suspend (Bitmap) -> Boolean) {
        batchJob = viewModelScope.launch {
            val st = _editState.value
            _batch.value = BatchState(0, uris.size)
            var ok = 0
            try {
                uris.forEachIndexed { i, uri ->
                    kotlinx.coroutines.ensureActive()
                    _batch.value = BatchState(i, uris.size)
                    val src = withContext(Dispatchers.IO) { decode(uri) } ?: return@forEachIndexed
                    val out = withContext(Dispatchers.Default) { FilterEngine.render(src, st) }
                    if (save(out)) ok++
                }
            } catch (_: CancellationException) { } finally { _batch.value = null }
        }
    }
}
