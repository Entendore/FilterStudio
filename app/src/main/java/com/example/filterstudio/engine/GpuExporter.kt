package com.example.filterstudio.engine

import android.graphics.Bitmap
import android.graphics.HardwareRenderer
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.HardwareBuffer
import android.os.Build
import androidx.annotation.RequiresApi

@RequiresApi(Build.VERSION_CODES.Q)
object GpuExporter {
    fun export(source: Bitmap, effect: RenderEffect): Bitmap? {
        if (maxOf(source.width, source.height) > 8192) return null
        val buffer = HardwareBuffer.create(source.width, source.height,
            HardwareBuffer.RGBA_8888,
            HardwareBuffer.USAGE_GPU_COLOR_OUTPUT or HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE)
        return try {
            val renderer = HardwareRenderer().apply { setSurface(android.view.Surface(buffer)) }
            val node = RenderNode("export").apply { setPosition(0, 0, source.width, source.height) }
            node.beginRecording(source.width, source.height).let { c ->
                c.drawBitmap(source, 0f, 0f, null); node.endRecording() }
            node.setRenderEffect(effect)
            renderer.setContentRoot(node)
            renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()
            Bitmap.wrapHardwareBuffer(buffer, null)?.copy(Bitmap.Config.ARGB_8888, false)
        } catch (t: Throwable) { null } finally { buffer.close() }
    }
}
