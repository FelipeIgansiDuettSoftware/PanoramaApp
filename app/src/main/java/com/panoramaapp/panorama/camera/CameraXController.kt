package com.panoramaapp.panorama.camera

import android.content.Context
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class CameraXController(context: Context) : CameraController {
    private val appContext = context.applicationContext
    private val frameExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var boundPreview: PreviewView? = null
    private var targetRotation: Int = Surface.ROTATION_0
    private val frameCapturing = AtomicBoolean(false)

    override fun bindPreview(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        onReady: () -> Unit,
        onError: (Throwable) -> Unit
    ) {
        if (boundPreview === previewView && imageAnalysis != null) {
            onReady()
            return
        }
        val providerFuture = ProcessCameraProvider.getInstance(appContext)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                targetRotation = previewView.display?.rotation ?: Surface.ROTATION_0
                val preview = Preview.Builder().build()
                val analysis = ImageAnalysis.Builder()
                    .setTargetRotation(targetRotation)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                preview.surfaceProvider = previewView.surfaceProvider
                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )
                cameraProvider = provider
                imageAnalysis = analysis
                boundPreview = previewView
                onReady()
            } catch (error: Throwable) {
                onError(error)
            }
        }, ContextCompat.getMainExecutor(appContext))
    }

    override fun startFrameCapture(
        outputDirectory: File,
        firstSequence: Int,
        frameRateFps: Int,
        onFrameSaved: (CapturedFrame) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        val analysis = imageAnalysis
        if (analysis == null) {
            onError(IllegalStateException("Camera analysis is not ready"))
            return
        }
        if (!frameCapturing.compareAndSet(false, true)) return
        analysis.setAnalyzer(
            frameExecutor,
            PngFrameAnalyzer(
                outputDirectory = outputDirectory,
                firstSequence = firstSequence,
                frameRateFps = frameRateFps,
                onFrameSaved = onFrameSaved,
                onError = onError
            )
        )
    }

    override fun stopFrameCapture(onStopped: () -> Unit) {
        if (!frameCapturing.compareAndSet(true, false)) {
            onStopped()
            return
        }
        imageAnalysis?.clearAnalyzer()
        frameExecutor.execute {
            ContextCompat.getMainExecutor(appContext).execute(onStopped)
        }
    }

    override fun shutdown() {
        frameCapturing.set(false)
        imageAnalysis?.clearAnalyzer()
        cameraProvider?.unbindAll()
        imageAnalysis = null
        boundPreview = null
        frameExecutor.shutdown()
    }
}
