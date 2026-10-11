package com.example.filterstudio

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.*
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.filterstudio.databinding.ActivityMainBinding
import com.example.filterstudio.engine.*
import com.example.filterstudio.model.*
import com.example.filterstudio.ui.*
import com.example.filterstudio.util.*
import com.github.chrisbanes.photoview.PhotoView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.slider.Slider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private val viewModel: EditorViewModel by lazy { EditorViewModel(application) }

    private lateinit var filterAdapter: FilterAdapter
    private var activeAdjustment = AdjustmentType.BRIGHTNESS
    private var inCropMode = false; private var inMaskMode = false
    private var compareOn = false

    // ---- pickers ----
    private val pickImage = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let { loadUri(it) } }
    private val pickBatch = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(10)) { uris ->
        if (uris.isNotEmpty()) runBatch(uris) }
    private val pickSticker = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let {
        AlertDialog.Builder(this).setTitle("Remove background?")
            .setPositiveButton("Remove") { _, _ -> viewModel.addImageSticker(it, true) }
            .setNegativeButton("Keep") { _, _ -> viewModel.addImageSticker(it, false) }.show() } }
    private val pickLut = registerForActivityResult(
        ActivityResultContracts.OpenDocument()) { uri -> uri?.let {
        viewModel.importLut(it, "imported_${System.currentTimeMillis() % 100000}") } }
    private val pickPresetFile = registerForActivityResult(
        ActivityResultContracts.OpenDocument()) { uri -> uri?.let {
        contentResolver.openInputStream(it)?.bufferedReader()?.use { r ->
            viewModel.importPreset(r.readText()) } } }
    private val shoot = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()) { r ->
        val uri = r.data?.data ?: return@registerForActivityResult
        val look = r.data?.getStringExtra("state")
        lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) { ImageLoader.decodeSampled(this@MainActivity, uri) }
            viewModel.loadCaptured(bmp, uri.toString(), look)
        } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        setupFilterRow(); setupChips(); setupSlider(); setupToolbar(); setupPickers()
        observeState(); observePresets(); observeBatch()

        viewModel.restoreUriNeeded()?.let { loadUri(Uri.parse(it)) }
        ?: lifecycleScope.launch {
            // load a demo drawable so the app opens usable
            val bmp = withContext(Dispatchers.IO) {
                val d = ContextCompat.getDrawable(this@MainActivity,
                    androidx.core.R.drawable.notification_bg_low) ?: return@withContext null
                val bmp = Bitmap.createBitmap(512, 384, Bitmap.Config.ARGB_8888)
                d.setBounds(0, 0, 512, 384); d.draw(android.graphics.Canvas(bmp)); bmp
            }
            bmp?.let { viewModel.loadImage(it) }
        }
    }

    private fun loadUri(uri: Uri) = lifecycleScope.launch {
        val bmp = withContext(Dispatchers.IO) { ImageLoader.decodeSampled(this@MainActivity, uri) }
        viewModel.loadImage(bmp, uri.toString())
    }

    // ---------- filter row ----------
    private fun setupFilterRow() {
        filterAdapter = FilterAdapter { f ->
            viewModel.setFilter(f)
            photoView().announceForAccessibility(getString(R.string.filter_applied, f.label))
        }
        b.filterRecycler.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        b.filterRecycler.adapter = filterAdapter
        b.categoryChips.setOnCheckedStateChangeListener { _, ids ->
            if (ids.isEmpty()) return@setOnCheckedStateChangeListener
            filterAdapter.setCategory(when (ids.first()) {
                R.id.cat_tones -> Category.TONES; R.id.cat_fx -> Category.FX
                R.id.cat_art -> Category.ARTISTIC; else -> Category.LOOKS })
        }
    }

    private fun photoView(): PhotoView = b.imagePreview

    // ---------- adjustment chips ----------
    private val ADJ_CHIPS = listOf(
        AdjustmentType.BRIGHTNESS to "Bright", AdjustmentType.CONTRAST to "Contrast",
        AdjustmentType.SATURATION to "Saturate", AdjustmentType.WARMTH to "Warm",
        AdjustmentType.TEMPERATURE to "Temp", AdjustmentType.TINT to "Tint",
        AdjustmentType.HIGHLIGHTS to "Highs", AdjustmentType.SHADOWS to "Shadows",
        AdjustmentType.VIGNETTE to "Vignette", AdjustmentType.BLUR to "Blur",
        AdjustmentType.GRAIN to "Grain", AdjustmentType.STRAIGHTEN to "Straighten")

    private fun setupChips() {
        ADJ_CHIPS.forEach { (type, label) ->
            val chip = Chip(this).apply {
                text = label; style = R.style.Widget_Material3_Chip_Filter
                isCheckable = true
                tag = type
            }
            chip.setOnCheckedChangeListener { _, checked ->
                if (checked) { activeAdjustment = type; syncSlider() } }
            b.chipsRow.addView(chip)
        }
        (b.chipsRow.getChildAt(0) as Chip).isChecked = true
    }

    private fun setupSlider() {
        b.adjustSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) b.adjustSlider.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            viewModel.previewAdjustment(activeAdjustment, value) }
        b.adjustSlider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {}
            override fun onStopTrackingTouch(slider: Slider) = viewModel.commitAdjustment() })
        b.strengthSlider.addOnChangeListener { _, v, fromUser ->
            if (fromUser) { viewModel.setFilterStrength(v); viewModel.commitStrength() } }
    }

    private fun syncSlider() {
        val t = activeAdjustment
        val st = viewModel.editState.value
        val value = when (t) {
            AdjustmentType.STRAIGHTEN -> st.straightenDegrees
            AdjustmentType.MOTION_ANGLE -> st.motionAngle
            AdjustmentType.MOTION_LENGTH -> st.motionLength.toFloat()
            else -> when (t) {
                AdjustmentType.BRIGHTNESS -> st.adjustments.brightness
                AdjustmentType.CONTRAST -> st.adjustments.contrast
                AdjustmentType.SATURATION -> st.adjustments.saturation
                AdjustmentType.WARMTH -> st.adjustments.warmth
                AdjustmentType.TEMPERATURE -> st.adjustments.temperature
                AdjustmentType.TINT -> st.adjustments.tint
                AdjustmentType.HIGHLIGHTS -> st.adjustments.highlights
                AdjustmentType.SHADOWS -> st.adjustments.shadows
                AdjustmentType.VIGNETTE -> st.adjustments.vignette
                AdjustmentType.BLUR -> st.adjustments.blur
                else -> st.adjustments.grain }
        }
        b.adjustSlider.valueFrom = t.from; b.adjustSlider.valueTo = t.to
        b.adjustSlider.value = value.coerceIn(t.from, t.to)
    }

    // ---------- toolbar ----------
    private fun setupToolbar() {
        b.btnUndo.setOnClickListener { viewModel.undo() }
        b.btnRedo.setOnClickListener { viewModel.redo() }
        b.btnRotate.setOnClickListener { viewModel.rotate90() }
        b.btnFlip.setOnClickListener { viewModel.flip() }
        b.btnAuto.setOnClickListener { viewModel.autoEnhance() }
        b.btnSurprise.setOnClickListener { viewModel.surprise() }
        b.btnGallery.setOnClickListener {
            pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
        b.btnCamera.setOnClickListener {
            shoot.launch(Intent(this, CameraActivity::class.java)
                .putExtra("state", viewModel.editState.value.toJson())) }
        b.btnBatch.setOnClickListener {
            pickBatch.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
        b.btnLut.setOnClickListener { showLutMenu() }
        b.btnCompare.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> toggleCompare(true)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> toggleCompare(false) }
            true }
        b.btnCrop.setOnClickListener { enterCropMode(true) }
        b.btnCropApply.setOnClickListener { viewModel.setCrop(b.cropOverlay.rect); enterCropMode(false) }
        b.btnCropCancel.setOnClickListener { enterCropMode(false) }
        b.aspectChips.setOnCheckedStateChangeListener { _, ids ->
            if (!inCropMode || ids.isEmpty()) return@setOnCheckedStateChangeListener
            val ratio = when (ids.first()) {
                R.id.aspect_1_1 -> 1f; R.id.aspect_4_3 -> 4f / 3f
                R.id.aspect_16_9 -> 16f / 9f; else -> null }
            b.cropOverlay.aspectRatio = ratio
            val base = viewModel.uncroppedBase ?: return@setOnCheckedStateChangeListener
            b.cropOverlay.resetRect(CropMath.centeredFull(
                base.width.toFloat() / base.height, ratio)) }
        b.btnMask.setOnClickListener { enterMaskMode(true) }
        b.btnMaskDone.setOnClickListener { enterMaskMode(false) }
        b.btnMaskClear.setOnClickListener { viewModel.clearMask() }
        b.chipBrushAdd.setOnCheckedChangeListener { _, c ->
            if (c) b.maskPaint.mode = MaskPaintView.Mode.ADD }
        b.chipBrushErase.setOnCheckedChangeListener { _, c ->
            if (c) b.maskPaint.mode = MaskPaintView.Mode.ERASE }
        b.btnSave.setOnClickListener { showExportDialog() }
        b.btnVideo.setOnClickListener { toast("Pick a video via your gallery app, then Share → FilterStudio") }
        b.btnAddText.setOnClickListener { addTextDialog() }
        b.btnAddSticker.setOnClickListener { showStickerPicker() }
        b.btnDeleteOverlay.setOnClickListener {
            b.overlayView.selectedId?.let { viewModel.removeOverlay(it); b.overlayView.selectedId = null } }
        b.btnFront.setOnClickListener { b.overlayView.selectedId?.let { viewModel.bringToFront(it) } }
        b.btnBack.setOnClickListener { b.overlayView.selectedId?.let { viewModel.sendToBack(it) } }
    }

    private fun setupPickers() {
        b.overlayView.apply {
            stickers = viewModel::stickerFor
            aspectOf = { id -> viewModel.stickerFor(id)?.let { it.width.toFloat() / it.height } }
            onTransformed = { id, x, y, rot, size ->
                viewModel.transformOverlayPreview(id, x, y, rot, size) }
            onGestureEnded = { viewModel.commitAdjustment() }
            onDoubleTapped = { viewModel.resetOverlayTransform(it) }
            onDeleted = { viewModel.removeOverlay(it) }
            onSelected = { id ->
                val sel = id != null
                b.btnDeleteOverlay.isVisible = sel
                b.btnFront.isVisible = sel; b.btnBack.isVisible = sel }
        }
        b.maskPaint.onStrokeCommitted = { snap ->
            viewModel.commitMaskStroke(snap)
            b.maskPaint.mask = viewModel.maskWorking() }
        photoView().setOnMatrixChangeListener {
            val rect = android.graphics.RectF(photoView().displayRect)
            b.overlayView.imageRect = rect
            b.cropOverlay.imageRect = rect
            b.maskPaint.imageRect = rect }
    }

    private fun addTextDialog() {
        val input = EditText(this); input.hint = "Your text"
        AlertDialog.Builder(this).setTitle(R.string.add_text).setView(input)
            .setPositiveButton(R.string.add) { _, _ ->
                val t = input.text.toString()
                if (t.isNotBlank()) viewModel.addOverlay(Overlay.Text(System.nanoTime(), t)) }
            .setNegativeButton(R.string.cancel, null).show()
    }

    private fun showStickerPicker() {
        val emojis = "😀😂😍🤩😎🥳👻💀🤖🎃🐶🐱🦄🍕🍔🌮☕🌈⭐🔥💥🌵🌊".toCharArray().map { it.toString() }
        val grid = GridLayout(this).apply { columnCount = 6 }
        emojis.forEach { e ->
            grid.addView(Button(this).apply {
                text = e; background = null; textSize = 24f
                setOnClickListener { viewModel.addOverlay(Overlay.Emoji(System.nanoTime(), e)); dlg.dismiss() } }) }
        val dlg = AlertDialog.Builder(this).setTitle(R.string.add_sticker).setView(grid).show()
        AlertDialog.Builder(this) // gallery sticker entry
        val gallery = Button(this).apply { text = "From gallery…" }
        gallery.setOnClickListener { dlg.dismiss(); pickSticker.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
        (grid.parent as? android.view.ViewGroup)?.addView(gallery)
    }

    private fun showLutMenu() {
        val popup = PopupMenu(this, b.btnLut)
        popup.menu.add("＋ " + getString(R.string.import_lut))
        viewModel.luts.value.forEach { popup.menu.add(it) }
        popup.setOnMenuItemClickListener { m ->
            when {
                m.title == "＋ " + getString(R.string.import_lut) -> pickLut.launch(arrayOf("*/*"))
                else -> viewModel.setLut(m.title.toString())
            }; true }
        popup.show()
    }

    // ---------- modes ----------
    private fun editorPanels() = listOf(b.categoryChips, b.filterRecycler, b.chipsRow,
        b.adjustSlider, b.strengthSlider)

    private fun enterCropMode(on: Boolean) {
        inCropMode = on
        b.cropOverlay.isVisible = on; b.cropToolbar.isVisible = on
        editorPanels().forEach { it.isVisible = !on }
        photoView().setZoomable(!on)
        if (on) {
            photoView().setImageBitmap(viewModel.uncroppedBase)
            photoView().post {
                b.cropOverlay.imageRect = android.graphics.RectF(photoView().displayRect)
                b.cropOverlay.resetRect(viewModel.editState.value.crop ?: CropRect(0f, 0f, 1f, 1f)) }
        } else {
            photoView().setImageBitmap(viewModel.baseBitmap)
            applyLive(viewModel.editState.value)
        }
    }

    private fun enterMaskMode(on: Boolean) {
        inMaskMode = on
        b.maskPaint.isVisible = on; b.maskToolbar.isVisible = on
        editorPanels().forEach { it.isVisible = !on }
        photoView().setZoomable(!on)
        if (on) {
            viewModel.beginMask()
            b.maskPaint.mask = viewModel.maskWorking()
            photoView().post {
                b.maskPaint.imageRect = android.graphics.RectF(photoView().displayRect) }
        }
    }

    private fun toggleCompare(on: Boolean) = lifecycleScope.launch {
        compareOn = on
        b.compareView.isVisible = on
        photoView().isVisible = !on
        if (on) {
            val rendered = viewModel.ensureRendered()
            b.compareView.setBitmaps(viewModel.uncroppedBase, rendered)
            b.compareView.post { b.compareView.setSplit(0.5f) }
        }
    }

    private fun applyLive(st: EditState) {
        val rect = android.graphics.RectF(photoView().displayRect)
        lifecycleScope.launch {
            val curves = viewModel.effectiveCurveLuts()?.let { CurveEngine.curveBitmap(it) }
            val lut = viewModel.currentLutTexture()
            RenderPipeline.applyLive(photoView(), st,
                st.adjustments.blur * 24f * resources.displayMetrics.density,
                rect, curves, lut?.first, lut?.second ?: 33,
                viewModel.baseBitmap?.width ?: rect.width().toInt(),
                viewModel.baseBitmap?.height ?: rect.height().toInt())
        }
        b.vignetteOverlay.alpha = st.adjustments.vignette
        b.strengthSlider.isVisible = st.filter != FilterType.ORIGINAL
    }

    // ---------- observe ----------
    private fun observeState() = lifecycleScope.launch {
        viewModel.uiState.collect { st ->
            b.btnUndo.isEnabled = st.canUndo; b.btnRedo.isEnabled = st.canRedo
            b.overlayView.overlays = st.edit.overlays
            b.batchBar.isVisible = st.isProcessing
            if (!inCropMode) {
                if (st.rendered != null && !RenderRouting.canGpuLive(st.edit, Build.VERSION.SDK_INT))
                    photoView().setImageBitmap(st.rendered)
                applyLive(st.edit)
            }
            syncSlider()
        }
        // note: collect never returns; kept simple for readability
    }

    private fun observePresets() = lifecycleScope.launch {
        combine(viewModel.presets, viewModel.presetCovers) { p, c -> p to c }.collect { (presets, covers) ->
            b.presetRow.removeAllViews()
            b.presetRow.addView(chip("＋ Save") { askPresetName { n -> viewModel.savePreset(n) } })
            b.presetRow.addView(chip("⬇ Import") {
                pickPresetFile.launch(arrayOf("application/json", "text/*")) })
            presets.forEach { p ->
                b.presetRow.addView(chip(p.name, { viewModel.applyPreset(p) }, {
                    PopupMenu(this@MainActivity, b.presetRow).apply {
                        menu.add("Export").setOnMenuItemClickListener {
                            shareText("${p.name}.preset.json", viewModel.exportPreset(p)); true }
                        if (!p.builtin) menu.add("Delete").setOnMenuItemClickListener {
                            viewModel.deletePreset(p.name); true }
                    }.show(); true }))
            }
        }
    }

    private fun chip(text: String, onClick: () -> Unit,
                     longClick: (() -> Boolean)? = null): Chip =
        Chip(this).apply {
            this.text = text; isCheckable = false
            setOnClickListener { onClick() }
            longClick?.let { setOnLongClickListener { it() } }
        }

    private fun askPresetName(cb: (String) -> Unit) {
        val input = EditText(this); input.hint = "Preset name"
        AlertDialog.Builder(this).setTitle("Save preset").setView(input)
            .setPositiveButton("Save") { _, _ -> cb(input.text.toString()) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun observeBatch() = lifecycleScope.launch {
        viewModel.batch.collect { bs ->
            b.batchBar.isVisible = bs != null
            bs?.let { b.batchBar.progress = it.done * 100 / it.total.coerceAtLeast(1) }
        }
    }

    private fun runBatch(uris: List<Uri>) {
        val opts = ExportOptions()
        viewModel.runBatch(uris,
            decode = { uri -> runCatching { ImageLoader.decodeSampled(this, uri) }.getOrNull() },
            save = { bmp -> saveToGallery(bmp, opts) })
        toast("Batch: ${uris.size} images")
    }

    private fun showExportDialog() = lifecycleScope.launch {
        val bmp = viewModel.ensureRendered()
        val formats = ExportOptions.Format.values()
        var chosen = ExportOptions.Format.JPEG
        var quality = 92
        val layout = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL;
            setPadding(48, 24, 48, 24) }
        val group = ChipGroup(this@MainActivity).apply { singleSelection = true; selectionRequired = true }
        formats.forEach { f ->
            group.addView(Chip(this@MainActivity).apply {
                text = f.label; isCheckable = true; isChecked = f == chosen; tag = f
                setOnCheckedChangeListener { _, c -> if (c) chosen = f } }) }
        layout.addView(group)
        toast("Saved ${if (saveToGallery(bmp, ExportOptions(chosen, quality))) "✓" else "✗"}")
    }

    private fun saveToGallery(bitmap: Bitmap, opts: ExportOptions): Boolean = runCatching {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "EDIT_${System.currentTimeMillis()}.${opts.format.label.lowercase()}")
            put(MediaStore.Images.Media.MIME_TYPE, opts.format.mime)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/FilterStudio")
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        contentResolver.openOutputStream(uri)?.use {
            bitmap.compress(opts.compress, opts.effectiveQuality, it)
        }
        true
    }.getOrDefault(false)

    private fun shareText(name: String, text: String) {
        val file = File(cacheDir, "shared/$name").apply { parentFile?.mkdirs(); writeText(text) }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "application/json"; putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }, "Share preset"))
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
