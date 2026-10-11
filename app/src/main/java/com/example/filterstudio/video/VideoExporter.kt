package com.example.filterstudio.video

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.Effects
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.example.filterstudio.data.LutRepository
import com.example.filterstudio.engine.FilterEngine
import com.example.filterstudio.model.EditState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object OverlayCompositor {
    data class VideoSize(val w: Int, val h: Int)
    fun probe(context: Context, input: Uri): VideoSize {
        val mmr = MediaMetadataRetriever()
        mmr.setDataSource(context, input)
        val w = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 1280
        val h = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 720
        val rot = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        mmr.release()
        return if (rot == 90 || rot == 270) VideoSize(h, w) else VideoSize(w, h)
    }
}

object VideoExporter {
    interface Handle { fun cancel() }

    fun durationMs(context: Context, input: Uri): Long {
        val mmr = MediaMetadataRetriever()
        mmr.setDataSource(context, input)
        val d = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        mmr.release(); return d
    }

    suspend fun export(
        context: Context, input: Uri, state: EditState, lutRepo: LutRepository?,
        trimStartMs: Long, trimEndMs: Long, output: File,
        onProgress: (Float) -> Unit
    ): Boolean = withContext(Dispatchers.Main) {
        suspendCoroutineVideo { cont ->
            val hasLut = state.lutName != null
            val lutSize = 33
            val effects = VideoEffects.forState(state, hasLut, lutSize)
            val clipped = MediaItem.Builder().setUri(input)
                .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(trimStartMs).setEndPositionMs(trimEndMs).build())
                .build()
            val holder = ProgressHolder()
            val transformer = Transformer.Builder(context)
                .setVideoMimeType(MimeTypes.VIDEO_MP4)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Any, result: ExportResult) { cont.resume(true) }
                    override fun onError(composition: Any, result: ExportResult,
                                         exception: ExportException) { cont.resume(false) }
                }).build()
            val handler = Handler(Looper.getMainLooper())
            val poll = object : Runnable {
                override fun run() {
                    if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE)
                        onProgress(holder.progress / 100f)
                    handler.postDelayed(this, 250)
                }
            }
            transformer.start(EditedMediaItem.Builder(clipped)
                .setEffects(Effects(listOf(), effects)).build(), output.absolutePath)
            handler.post(poll)
        }
    }

    private suspend inline fun <T> suspendCoroutineVideo(crossinline block: (SimpleCont<T>) -> Unit): T =
        kotlinx.coroutines.suspendCoroutine { c ->
            block(object : SimpleCont<T> {
                override fun resume(v: T) = c.resume(v)
            })
        }

    interface SimpleCont<T> { fun resume(v: T) }
}
