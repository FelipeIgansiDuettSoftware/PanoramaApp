package com.panoramaapp.panorama.camera

import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import java.io.File

data class CapturedPhoto(
    val sequence: Int,
    val file: File,
    val rotationDegrees: Int,
    val width: Int,
    val height: Int,
    val capturedAtNanos: Long = 0L
)

interface CameraController {
    fun bindPreview(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        onReady: () -> Unit,
        onError: (Throwable) -> Unit
    )

    fun capturePhoto(
        outputDirectory: File,
        sequence: Int,
        onPhotoSaved: (CapturedPhoto) -> Unit,
        onError: (Throwable) -> Unit
    )

    fun setAlignmentReference(
        referenceFile: File,
        orientation: com.panoramaapp.panorama.capture.CaptureOrientation,
        expectedAxis: AlignmentAxis?,
        expectedDirection: AlignmentDirection?,
        onUpdate: (AlignmentGuideState) -> Unit,
        onError: (Throwable) -> Unit
    )

    fun clearAlignmentReference()

    fun shutdown()
}
