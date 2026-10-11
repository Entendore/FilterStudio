package com.example.filterstudio.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.example.filterstudio.engine.BuiltinLuts
import com.example.filterstudio.engine.CubeParser
import com.example.filterstudio.engine.Lut3D
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class LutRepository(private val context: Context) {
    private val dir = File(context.applicationContext.filesDir, "luts").apply { mkdirs() }
    private val packedCache = mutableMapOf<String, Bitmap>()

    fun names(): List<String> =
        BuiltinLuts.RECIPES.keys.toList() +
        (dir.listFiles { f -> f.extension == "cube" }?.map { it.nameWithoutExtension } ?: emptyList())

    fun import(uri: Uri, name: String) {
        context.contentResolver.openInputStream(uri)?.use { inp ->
            File(dir, "$name.cube").outputStream().use { inp.copyTo(it) }
        }
    }
    fun delete(name: String) { File(dir, "$name.cube").delete(); packedCache.remove(name) }

    suspend fun packedBitmap(name: String): Bitmap = packedCache.getOrPut(name) {
        withContext(Dispatchers.Default) {
            val lut = if (BuiltinLuts.RECIPES.containsKey(name)) BuiltinLuts.build(name)
                      else CubeParser.parse(File(dir, "$name.cube"))
            lut.toPackedBitmap()
        }
    }
    suspend fun resolve(name: String): Lut3D? = withContext(Dispatchers.Default) {
        if (BuiltinLuts.RECIPES.containsKey(name)) BuiltinLuts.build(name)
        else File(dir, "$name.cube").takeIf { it.exists() }?.let { CubeParser.parse(it) }
    }
}
