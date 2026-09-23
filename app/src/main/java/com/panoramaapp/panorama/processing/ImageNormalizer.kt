package com.panoramaapp.panorama.processing

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.content.res.Resources
import androidx.exifinterface.media.ExifInterface
import com.panoramaapp.R
import com.panoramaapp.panorama.capture.CapturedImage
import java.io.File
import kotlin.math.roundToInt

internal class ImageNormalizer(
    private val temporaryDirectory: File,
    private val maxDimension: Int = 1600,
    private val targetHeight: Int? = null,
    private val resources: Resources
) {
    fun normalize(image: CapturedImage): File {
        val source = image.file
        val exif = ExifInterface(source.absolutePath)
        val exifOrientation = exif.getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL
        )
        val orientation = if (exifOrientation == ExifInterface.ORIENTATION_NORMAL) {
            orientationForDegrees(image.rotationDegrees)
        } else {
            exifOrientation
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.absolutePath, bounds)
        check(bounds.outWidth > 0 && bounds.outHeight > 0) {
            resources.getString(R.string.error_read_dimensions, source.name)
        }
        val exifSwapsDimensions = orientation == ExifInterface.ORIENTATION_ROTATE_90 ||
            orientation == ExifInterface.ORIENTATION_ROTATE_270
        val orientedHeight = if (exifSwapsDimensions) bounds.outWidth else bounds.outHeight
        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, orientedHeight)
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        val bitmap = BitmapFactory.decodeFile(source.absolutePath, decodeOptions)
            ?: error(resources.getString(R.string.error_decode_image, source.name))
        val transformed = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> rotate(bitmap, 90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> rotate(bitmap, 180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> rotate(bitmap, 270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> flip(bitmap, horizontal = true)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> flip(bitmap, horizontal = false)
            else -> bitmap
        }
        val resized = targetHeight?.let { requestedHeight ->
            if (transformed.height > requestedHeight) {
                Bitmap.createScaledBitmap(
                    transformed,
                    (transformed.width * requestedHeight.toDouble() / transformed.height).roundToInt(),
                    requestedHeight,
                    true
                )
            } else {
                transformed
            }
        } ?: transformed
        val target = File.createTempFile("normalized-${image.sequence}-", ".png", temporaryDirectory)
        target.outputStream().use { output ->
            check(resized.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                resources.getString(R.string.error_normalize_image, source.name)
            }
        }
        if (resized !== transformed) resized.recycle()
        if (transformed !== bitmap) transformed.recycle()
        bitmap.recycle()
        return target
    }

    private fun rotate(bitmap: Bitmap, degrees: Float): Bitmap {
        return Bitmap.createBitmap(
            bitmap,
            0,
            0,
            bitmap.width,
            bitmap.height,
            Matrix().apply { postRotate(degrees) },
            true
        )
    }

    private fun flip(bitmap: Bitmap, horizontal: Boolean): Bitmap {
        return Bitmap.createBitmap(
            bitmap,
            0,
            0,
            bitmap.width,
            bitmap.height,
            Matrix().apply { postScale(if (horizontal) -1f else 1f, if (horizontal) 1f else -1f) },
            true
        )
    }

    private fun sampleSizeFor(width: Int, height: Int, orientedHeight: Int): Int {
        targetHeight?.let { requestedHeight ->
            var sampleSize = 1
            val decodeHeadroom = requestedHeight * 1.25
            while (orientedHeight / sampleSize > decodeHeadroom) {
                sampleSize *= 2
            }
            return sampleSize
        }
        var sampleSize = 1
        while (maxOf(width / sampleSize, height / sampleSize) > maxDimension) {
            sampleSize *= 2
        }
        return sampleSize
    }

    private fun orientationForDegrees(degrees: Int): Int {
        return when ((degrees % 360 + 360) % 360) {
            90 -> ExifInterface.ORIENTATION_ROTATE_90
            180 -> ExifInterface.ORIENTATION_ROTATE_180
            270 -> ExifInterface.ORIENTATION_ROTATE_270
            else -> ExifInterface.ORIENTATION_NORMAL
        }
    }
}
