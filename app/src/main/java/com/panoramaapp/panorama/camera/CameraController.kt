package com.panoramaapp.panorama.camera

import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import java.io.File

data class CapturedFrame(
    val sequence: Int,
    val file: File,
    val rotationDegrees: Int,
    val width: Int,
    val height: Int
)

interface CameraController {
    fun bindPreview(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        onReady: () -> Unit,
        onError: (Throwable) -> Unit
    )

    fun startFrameCapture(
        outputDirectory: File,
        firstSequence: Int,
        frameRateFps: Int,
        onFrameSaved: (CapturedFrame) -> Unit,
        onError: (Throwable) -> Unit
    )

    fun stopFrameCapture(onStopped: () -> Unit)

    fun shutdown()
}
