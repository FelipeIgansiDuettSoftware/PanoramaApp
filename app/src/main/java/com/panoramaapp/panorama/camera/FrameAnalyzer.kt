package com.panoramaapp.panorama.camera

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.io.File
import kotlin.math.max

/**
 * Saves selected RGBA analysis frames as lossless PNG files. No encoded video is
 * produced; the frame files are the temporary capture source for processing.
 */
class PngFrameAnalyzer(
    private val outputDirectory: File,
    firstSequence: Int,
    frameRateFps: Int,
    private val onFrameSaved: (CapturedFrame) -> Unit,
    private val onError: (Throwable) -> Unit
) : ImageAnalysis.Analyzer {
    private val minimumIntervalNanos = 1_000_000_000L / max(1, frameRateFps)
    private var nextSequence = firstSequence
    private var lastSavedTimestampNanos = Long.MIN_VALUE
    private var previousSignature: IntArray? = null

    override fun analyze(image: ImageProxy) {
        try {
            val timestamp = image.imageInfo.timestamp.takeIf { it > 0L } ?: System.nanoTime()
            if (
                lastSavedTimestampNanos != Long.MIN_VALUE &&
                timestamp - lastSavedTimestampNanos < minimumIntervalNanos
            ) {
                return
            }

            val sourceBitmap = image.toBitmap()
            val bitmap = sourceBitmap.rotateToDisplayOrientation(image.imageInfo.rotationDegrees)
            try {
                val signature = signatureOf(bitmap)
                if (previousSignature?.let { meanDifference(it, signature) < SIMILARITY_THRESHOLD } == true) {
                    return
                }
                previousSignature = signature

                val file = File(
                    outputDirectory,
                    "frame-${nextSequence.toString().padStart(4, '0')}.png"
                )
                outputDirectory.mkdirs()
                check(file.outputStream().use { output ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                }) { "Unable to save frame ${file.name}" }

                lastSavedTimestampNanos = timestamp
                onFrameSaved(
                    CapturedFrame(
                        sequence = nextSequence++,
                        file = file,
                        rotationDegrees = 0,
                        width = bitmap.width,
                        height = bitmap.height
                    )
                )
            } finally {
                bitmap.recycle()
                if (sourceBitmap !== bitmap) sourceBitmap.recycle()
            }
        } catch (error: Throwable) {
            onError(error)
        } finally {
            image.close()
        }
    }

    private fun ImageProxy.toBitmap(): Bitmap {
        check(format == ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888) {
            "Expected RGBA analysis frames, got format $format"
        }
        val plane = planes.firstOrNull() ?: error("Frame has no pixel plane")
        check(plane.pixelStride >= 4) { "Unsupported RGBA pixel stride ${plane.pixelStride}" }
        val buffer = plane.buffer.duplicate()
        val pixels = IntArray(width * height)
        val row = ByteArray(width * plane.pixelStride)
        for (y in 0 until height) {
            buffer.position(y * plane.rowStride)
            buffer.get(row, 0, row.size)
            for (x in 0 until width) {
                val offset = x * plane.pixelStride
                pixels[y * width + x] = Color.argb(
                    row[offset + 3].toInt() and 0xFF,
                    row[offset].toInt() and 0xFF,
                    row[offset + 1].toInt() and 0xFF,
                    row[offset + 2].toInt() and 0xFF
                )
            }
        }
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            it.setPixels(pixels, 0, width, 0, 0, width, height)
        }
    }

    private fun Bitmap.rotateToDisplayOrientation(rotationDegrees: Int): Bitmap {
        val normalizedDegrees = (rotationDegrees % 360 + 360) % 360
        if (normalizedDegrees == 0) return this
        return Bitmap.createBitmap(
            this,
            0,
            0,
            width,
            height,
            Matrix().apply { postRotate(normalizedDegrees.toFloat()) },
            true
        )
    }

    private fun signatureOf(bitmap: Bitmap): IntArray {
        val signature = IntArray(SIGNATURE_WIDTH * SIGNATURE_HEIGHT)
        var index = 0
        for (y in 0 until SIGNATURE_HEIGHT) {
            val sourceY = y * bitmap.height / SIGNATURE_HEIGHT
            for (x in 0 until SIGNATURE_WIDTH) {
                val sourceX = x * bitmap.width / SIGNATURE_WIDTH
                val pixel = bitmap.getPixel(sourceX, sourceY)
                signature[index++] = (
                    Color.red(pixel) * 299 +
                        Color.green(pixel) * 587 +
                        Color.blue(pixel) * 114
                    ) / 1000
            }
        }
        return signature
    }

    private fun meanDifference(first: IntArray, second: IntArray): Double {
        return first.indices.sumOf { index -> kotlin.math.abs(first[index] - second[index]) } /
            first.size.toDouble()
    }

    private companion object {
        const val SIGNATURE_WIDTH = 32
        const val SIGNATURE_HEIGHT = 24
        const val SIMILARITY_THRESHOLD = 3.5
    }
}
