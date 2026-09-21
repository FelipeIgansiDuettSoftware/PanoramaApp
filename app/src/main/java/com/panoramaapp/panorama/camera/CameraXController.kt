package com.panoramaapp.panorama.camera

import android.content.Context
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.LifecycleOwner
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraXController(context: Context) : CameraController {
    private val appContext = context.applicationContext
    private val captureExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var alignmentAnalyzer: AlignmentGuideAnalyzer? = null
    private var boundPreview: PreviewView? = null
    private var targetRotation: Int = Surface.ROTATION_0
    private val photoCapturing = AtomicBoolean(false)

    override fun bindPreview(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        onReady: () -> Unit,
        onError: (Throwable) -> Unit
    ) {
        if (boundPreview === previewView && imageCapture != null) {
            targetRotation = previewView.display?.rotation ?: targetRotation
            imageCapture?.setTargetRotation(targetRotation)
            imageAnalysis?.setTargetRotation(targetRotation)
            onReady()
            return
        }
        val providerFuture = ProcessCameraProvider.getInstance(appContext)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                targetRotation = previewView.display?.rotation ?: Surface.ROTATION_0
                val preview = Preview.Builder().build()
                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .setJpegQuality(100)
                    .setTargetRotation(targetRotation)
                    .build()
                val analysis = ImageAnalysis.Builder()
                    .setTargetResolution(android.util.Size(320, 240))
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
                    capture,
                    analysis
                )
                cameraProvider = provider
                imageCapture = capture
                imageAnalysis = analysis
                boundPreview = previewView
                onReady()
            } catch (error: Throwable) {
                onError(error)
            }
        }, ContextCompat.getMainExecutor(appContext))
    }

    override fun capturePhoto(
        outputDirectory: File,
        sequence: Int,
        onPhotoSaved: (CapturedPhoto) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        val capture = imageCapture
        if (capture == null) {
            onError(IllegalStateException("Camera capture is not ready"))
            return
        }
        if (!photoCapturing.compareAndSet(false, true)) {
            return
        }
        if (!outputDirectory.mkdirs() && !outputDirectory.isDirectory) {
            photoCapturing.set(false)
            onError(IllegalStateException("Unable to create photo directory"))
            return
        }
        val file = File(outputDirectory, "photo-${sequence.toString().padStart(4, '0')}.jpg")
        val options = ImageCapture.OutputFileOptions.Builder(file).build()
        capture.takePicture(
            options,
            captureExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    photoCapturing.set(false)
                    val dimensions = android.graphics.BitmapFactory.Options().apply {
                        inJustDecodeBounds = true
                    }
                    android.graphics.BitmapFactory.decodeFile(file.absolutePath, dimensions)
                    if (dimensions.outWidth <= 0 || dimensions.outHeight <= 0) {
                        file.delete()
                        photoCapturing.set(false)
                        ContextCompat.getMainExecutor(appContext).execute {
                            onError(IllegalStateException("Captured photo has invalid dimensions"))
                        }
                        return
                    }
                    val exifOrientation = ExifInterface(file.absolutePath).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL
                    )
                    val swapsDimensions = exifOrientation == ExifInterface.ORIENTATION_ROTATE_90 ||
                        exifOrientation == ExifInterface.ORIENTATION_ROTATE_270
                    val orientedWidth = if (swapsDimensions) dimensions.outHeight else dimensions.outWidth
                    val orientedHeight = if (swapsDimensions) dimensions.outWidth else dimensions.outHeight
                    ContextCompat.getMainExecutor(appContext).execute {
                        onPhotoSaved(
                            CapturedPhoto(
                                sequence = sequence,
                                file = file,
                                rotationDegrees = 0,
                                width = orientedWidth,
                                height = orientedHeight,
                                capturedAtNanos = System.nanoTime()
                            )
                        )
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    photoCapturing.set(false)
                    file.delete()
                    ContextCompat.getMainExecutor(appContext).execute {
                        onError(exception)
                    }
                }
            }
        )
    }

    override fun setAlignmentReference(
        referenceFile: File,
        onUpdate: (AlignmentGuideState) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        val analysis = imageAnalysis
        if (analysis == null) {
            onError(IllegalStateException("Camera analysis is not ready"))
            return
        }
        if (!org.opencv.android.OpenCVLoader.initLocal()) {
            onError(IllegalStateException("OpenCV could not be initialized for alignment"))
            return
        }
        clearAlignmentReference()
        runCatching {
            AlignmentGuideAnalyzer(referenceFile, onUpdate).also { analyzer ->
                alignmentAnalyzer = analyzer
                analysis.setAnalyzer(analysisExecutor, analyzer)
            }
        }.onFailure(onError)
    }

    override fun clearAlignmentReference() {
        imageAnalysis?.clearAnalyzer()
        alignmentAnalyzer?.close()
        alignmentAnalyzer = null
    }

    override fun shutdown() {
        photoCapturing.set(false)
        clearAlignmentReference()
        cameraProvider?.unbindAll()
        imageCapture = null
        imageAnalysis = null
        boundPreview = null
        captureExecutor.shutdown()
        analysisExecutor.shutdown()
    }
}
