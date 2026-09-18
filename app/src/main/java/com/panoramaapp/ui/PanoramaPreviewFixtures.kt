package com.panoramaapp.ui

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.panoramaapp.panorama.capture.CapturedImage
import java.io.File

internal fun previewCapturedImage(sequence: Int) = CapturedImage(
    sequence = sequence,
    file = File("/preview/image-${sequence.toString().padStart(3, '0')}.jpg"),
    capturedAtEpochMs = 1_758_000_000_000L + sequence,
    rotationDegrees = 0,
    width = 1920,
    height = 1080,
    sizeBytes = 256_000L + sequence
)

@Composable
internal fun rememberPreviewPanoramaBitmap(): Bitmap = remember {
    Bitmap.createBitmap(1400, 280, Bitmap.Config.ARGB_8888).also { bitmap ->
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                val red = 32 + (x * 70 / bitmap.width)
                val green = 86 + (y * 90 / bitmap.height)
                val blue = 125 + ((bitmap.width - x) * 80 / bitmap.width)
                bitmap.setPixel(x, y, Color.rgb(red, green, blue))
            }
        }
    }
}
