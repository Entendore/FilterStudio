package com.example.filterstudio

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Size
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.filterstudio.camera.SceneChangeDetector
import com.example.filterstudio.databinding.ActivityCameraBinding
import com.example.filterstudio.engine.*
import com.example.filterstudio.model.EditState
import com.example.filterstudio.model.PixelPassKind
import com.example.filterstudio.ui.CameraFilterAdapter
import com.example.filterstudio.util.ImageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

class CameraActivity : AppCompatActivity() {

    private lateinit var b: ActivityCameraBinding
    private var state = EditState()
    private var curveBmp: Bitmap? = null; private var lutBmp: Bitmap? = null
    private var lutSize = 33
    private lateinit var imageCapture: ImageCapture
    private lateinit var adapter: CameraFilterAdapter
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var latestHist: IntArray? = null
    private var thumbJob: Job? = null

    companion object {
        val CAMERA_FILTERS = listOf(
            FilterType.ORIGINAL, FilterType.VIVID, FilterType.FADE, FilterType.GRAYSCALE,
            FilterType.NOIR, FilterType.SEPIA, FilterType.SILVER, FilterType.SUNSET,
            FilterType.COOL, FilterType.POLAROID, FilterType.DRAMATIC, FilterType.THERMAL,
            FilterType.DUOTONE_NAVY, FilterType.MATRIX_GREEN, FilterType.CROSS_PROCESS,
            FilterType.NIGHT_VISION)
    }

    private val permission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()) { if (it) startCamera() else finish() }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        b = ActivityCameraBinding.inflate(layoutInflater)
        setContentView(b.root)
        state = intent?.getStringExtra("state")
            ?.let { runCatching { editStateFromJson(it) }.getOrNull() } ?: EditState()
        // REQUIRED: TextureView path — SurfaceView bypasses setRenderEffect
        b.previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        rebuildShaderInputs()
        setupRow(); setupSlider()
        b.btnFlip.setOnClickListener {
            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK)
                CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
            startCamera() }
        b.btnShutter.setOnClickListener { shoot() }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) startCamera()
        else permission.launch(Manifest.permission.CAMERA)
    }

    private fun rebuildShaderInputs() = lifecycleScope.launch(Dispatchers.Default) {
        curveBmp = FilterEngine.effectiveLuts(state)?.let { CurveEngine.curveBitmap(it) }
    }

    private fun setupRow() {
        adapter = CameraFilterAdapter(CAMERA_FILTERS) { f ->
            state = state.copy(filter = f, filterStrength = if (f == FilterType.ORIGINAL) 1f
                else state.filterStrength.coerceAtLeast(0.15f))
            b.strengthSlider.value = state.filterStrength
            applyLive(); adapter.selected = f }
        b.filterRow.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        b.filterRow.adapter = adapter
    }

    private fun setupSlider() {
        b.strengthSlider.addOnChangeListener { _, v, fromUser ->
            if (!fromUser) return@addOnChangeListener
            state = state.copy(filterStrength = v); applyLive() }
    }

    private fun startCamera() {
        ProcessCameraProvider.getInstance(this).addListener({
            val provider = ProcessCameraProvider.getInstance(this).get()
            val preview = Preview.Builder().build()
                .also { it.setSurfaceProvider(b.previewView.surfaceProvider) }
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build()
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(androidx.camera.core.resolutionselector.ResolutionSelector.Builder()
                    .setResolutionStrategy(androidx.camera.core.resolutionselector.ResolutionStrategy(
                        Size(640, 480),
                        androidx.camera.core.resolutionselector.ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER))
                    .build())
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(ContextCompat.getMainExecutor(this)) { proxy ->
                val bmp = proxy.toBitmap()
                val rot = proxy.imageInfo.rotationDegrees
                proxy.close()
                val fixed = if (rot != 0) Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height,
                    Matrix().apply { postRotate(rot.toFloat()) }, true) else bmp
                onAnalysisFrame(fixed)
            }
            provider.unbindAll()
            provider.bindToLifecycle(this,
                CameraSelector.Builder().requireLensFacing(lensFacing).build(),
                preview, imageCapture, analysis)
            b.previewView.post { applyLive() }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun onAnalysisFrame(frame: Bitmap) = lifecycleScope.launch(Dispatchers.Default) {
        val hist = SceneChangeDetector.histogram(frame)
        if (latestHist != null && !SceneChangeDetector.changed(hist, latestHist!!)) return@launch
        latestHist = hist
        thumbJob?.cancel()
        thumbJob = launch {
            val s = 96f / maxOf(frame.width, frame.height)
            val small = Bitmap.createScaledBitmap(frame,
                (frame.width * s).toInt(), (frame.height * s).toInt(), true)
            val thumbs = CAMERA_FILTERS.associateWith { f ->
                FilterEngine.render(small, EditState(filter = f)) }
            withContext(Dispatchers.Main) { adapter.submit(thumbs) }
        }
    }

    private fun applyLive() {
        if (!RenderRouting.canGpuLive(state, Build.VERSION.SDK_INT))
            return RenderPipeline.clearLive(b.previewView)
        RenderPipeline.applyLive(b.previewView, state, 0f,
            android.graphics.RectF(0f, 0f, b.previewView.width.toFloat(),
                b.previewView.height.toFloat()),
            curveBmp, lutBmp, lutSize)
    }

    private fun shoot() {
        val file = File(cacheDir, "shared/capture_${System.currentTimeMillis()}.jpg")
            .apply { parentFile?.mkdirs() }
        imageCapture.takePicture(
            ImageCapture.OutputFileOptions.Builder(file).build(),
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(r: ImageCapture.OutputFileResults) {
                    setResult(RESULT_OK, Intent().apply {
                        data = FileProvider.getUriForFile(this@CameraActivity,
                            "$packageName.fileprovider", file)
                        putExtra("state", state.toJson())
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }); finish() }
                override fun onError(e: ImageCaptureException) = finish() })
    }
}
