package com.example.filterstudio.util

import android.graphics.Bitmap
import android.os.Build

data class ExportOptions(val format: Format = Format.JPEG, val quality: Int = 92) {
    enum class Format(val label: String, val mime: String) {
        JPEG("JPEG", "image/jpeg"), PNG("PNG", "image/png"), WEBP("WebP", "image/webp")
    }
    val compress: Bitmap.CompressFormat
        get() = when (format) {
            Format.JPEG -> Bitmap.CompressFormat.JPEG
            Format.PNG -> Bitmap.CompressFormat.PNG
            Format.WEBP -> if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY
                           else @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
        }
    val effectiveQuality get() = if (format == Format.PNG) 100 else quality
}
