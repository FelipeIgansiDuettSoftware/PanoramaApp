package com.panoramaapp.panorama

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.panoramaapp.R
import com.panoramaapp.panorama.camera.CameraController
import com.panoramaapp.panorama.camera.CameraState
import com.panoramaapp.panorama.camera.CapturedPhoto
import com.panoramaapp.panorama.camera.AlignmentGuideState
import com.panoramaapp.panorama.camera.AlignmentDirection
import com.panoramaapp.panorama.camera.AlignmentGuidance
import com.panoramaapp.panorama.capture.CaptureSession
import com.panoramaapp.panorama.capture.CaptureOrientation
import com.panoramaapp.panorama.capture.CaptureSessionStore
import com.panoramaapp.panorama.diagnostics.PanoramaMetrics
import com.panoramaapp.panorama.processing.PanoramaProcessor
import com.panoramaapp.panorama.processing.StitchingResult
import com.panoramaapp.panorama.processing.opencv.OpenCvPanoramaProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PanoramaViewModel(application: Application) : AndroidViewModel(application) {
    private companion object { const val TAG = "PanoramaViewModel" }

    private enum class CapturePhase {
        WAITING_FOR_FIRST_PHOTO,
        WAITING_FOR_MOVEMENT,
        CAPTURING_PHOTO,
        REGISTERING_PHOTO,
        PROCESSING,
        FINISHED,
        ERROR
    }

    private val sessionStore = CaptureSessionStore(application)
    private val appContext = application
    private val processor: PanoramaProcessor = OpenCvPanoramaProcessor(application)
    private val sessionLock = Any()
    private var session: CaptureSession = sessionStore.createSession()
    @Volatile
    private var capturing = false
    private var alignmentGuide = AlignmentGuideState()
    private var motionDirection: AlignmentDirection? = null
    private var capturePhase = CapturePhase.WAITING_FOR_FIRST_PHOTO

    private val _uiState = MutableStateFlow<PanoramaUiState>(PanoramaUiState.CameraStarting)
    val uiState: StateFlow<PanoramaUiState> = _uiState

    private val _cameraState = MutableStateFlow<CameraState>(CameraState.Starting)
    val cameraState: StateFlow<CameraState> = _cameraState

    fun onCameraReady() {
        _cameraState.value = CameraState.Ready
        showCaptureState()
    }

    fun onCameraPermissionRequired() {
        _cameraState.value = CameraState.PermissionRequired
        showCaptureState()
    }

    fun onCameraPermissionGranted() {
        _cameraState.value = CameraState.Starting
        showCaptureState()
    }

    fun onCameraError(error: Throwable) {
        _cameraState.value = CameraState.Error(error.message ?: "Camera unavailable")
        showCaptureState()
    }

    fun onCameraUnavailable(message: String) {
        _cameraState.value = CameraState.Unavailable(message)
        showCaptureState()
    }

    fun capturePhoto(cameraController: CameraController, automatic: Boolean = false) {
        if (capturing || _cameraState.value != CameraState.Ready) return
        val currentImageCount = synchronized(sessionLock) { session.images.size }
        if (automatic && capturePhase != CapturePhase.WAITING_FOR_MOVEMENT) return
        if (!automatic && currentImageCount == 0 && capturePhase != CapturePhase.WAITING_FOR_FIRST_PHOTO) return
        if (!automatic && currentImageCount > 0 &&
            (capturePhase != CapturePhase.WAITING_FOR_MOVEMENT || !alignmentGuide.captureAllowed)
        ) return
        capturing = true
        capturePhase = CapturePhase.CAPTURING_PHOTO
        val orientation = currentCaptureOrientation()
        val currentSession = synchronized(sessionLock) {
            session = sessionStore.setOrientation(session, orientation)
            session
        }
        cameraController.capturePhoto(
            outputDirectory = java.io.File(currentSession.directory, "input"),
            sequence = currentSession.images.size + 1,
            onPhotoSaved = { photo -> registerPhoto(photo, cameraController) },
            onError = { error ->
                viewModelScope.launch(Dispatchers.Main) {
                    failCapture(error)
                }
            }
        )
        publishCaptureState()
    }

    fun removeLast(cameraController: CameraController) {
        if (capturing) return
        val result = runCatching {
            synchronized(sessionLock) { session = sessionStore.removeLast(session) }
        }
        result.onFailure {
            capturePhase = CapturePhase.ERROR
            _uiState.value = PanoramaUiState.Error(it.message ?: "Unable to remove frame")
        }.onSuccess {
            val remaining = synchronized(sessionLock) { session.images.lastOrNull() }
            if (remaining == null) {
                cameraController.clearAlignmentReference()
                motionDirection = null
                alignmentGuide = AlignmentGuideState()
                capturePhase = CapturePhase.WAITING_FOR_FIRST_PHOTO
                publishCaptureState()
            } else {
                capturePhase = CapturePhase.WAITING_FOR_MOVEMENT
                cameraController.setAlignmentReference(
                    referenceFile = remaining.file,
                    orientation = currentCaptureOrientation(),
                    expectedDirection = motionDirection,
                    onUpdate = { update ->
                        viewModelScope.launch(Dispatchers.Main) {
                            if (motionDirection == null && update.direction != null) motionDirection = update.direction
                            alignmentGuide = update
                            if (_uiState.value is PanoramaUiState.Capturing) publishCaptureState()
                            if (_uiState.value is PanoramaUiState.Capturing && update.captureAllowed && !capturing) {
                                capturePhoto(cameraController, automatic = true)
                            }
                        }
                    },
                    onError = { error -> Log.w(TAG, "Alignment guide unavailable", error) }
                )
                publishCaptureState()
            }
        }
    }

    fun process() {
        if (capturing) return
        val processingSession = synchronized(sessionLock) { session }
        val images = processingSession.images
        if (images.size < 2) {
            _uiState.value = PanoramaUiState.Error(appContext.getString(R.string.error_minimum_images))
            return
        }
        _uiState.value = PanoramaUiState.Processing(progress = null)
        capturePhase = CapturePhase.PROCESSING
        viewModelScope.launch {
            runCatching {
                processor.stitch(
                    images = images,
                    orientation = processingSession.orientation ?: currentCaptureOrientation()
                )
            }
                .onSuccess { result ->
                    capturePhase = CapturePhase.FINISHED
                    writeMetrics(result)
                    _uiState.value = PanoramaUiState.Success(result)
                }
                .onFailure { error ->
                    capturePhase = CapturePhase.ERROR
                    Log.e(TAG, "Panorama processing failed: session=${session.id}", error)
                    _uiState.value = PanoramaUiState.Error(
                        error.message ?: "Panorama processing failed"
                    )
                }
        }
    }

    fun returnToCapture() {
        capturing = false
        capturePhase = if (synchronized(sessionLock) { session.images.isEmpty() }) {
            CapturePhase.WAITING_FOR_FIRST_PHOTO
        } else {
            CapturePhase.WAITING_FOR_MOVEMENT
        }
        publishCaptureState()
    }

    fun startNewSession(cameraController: CameraController) {
        cameraController.clearAlignmentReference()
        alignmentGuide = AlignmentGuideState()
        motionDirection = null
        capturePhase = CapturePhase.WAITING_FOR_FIRST_PHOTO
        val replacement = synchronized(sessionLock) {
            sessionStore.deleteSession(session)
            sessionStore.createSession()
        }
        synchronized(sessionLock) { session = replacement }
        _uiState.value = PanoramaUiState.Capturing(
            images = emptyList(),
            isCapturing = false,
            alignment = alignmentGuide,
            orientation = currentCaptureOrientation()
        )
    }

    private fun registerPhoto(frame: CapturedPhoto, cameraController: CameraController) {
        capturing = false
        capturePhase = CapturePhase.REGISTERING_PHOTO
        runCatching {
            synchronized(sessionLock) {
                session = sessionStore.registerImage(
                    session = session,
                    file = frame.file,
                    sequence = frame.sequence,
                    rotationDegrees = frame.rotationDegrees,
                    width = frame.width,
                    height = frame.height,
                    capturedAtNanos = frame.capturedAtNanos
                )
            }
        }.onFailure {
            frame.file.delete()
            Log.e(TAG, "Photo registration failed: ${frame.file.name}", it)
            _uiState.value = PanoramaUiState.Error(
                it.message ?: appContext.getString(R.string.error_save_image)
            )
        }.onSuccess {
            alignmentGuide = AlignmentGuideState(active = true, guidance = AlignmentGuidance.FIND_OVERLAP)
            capturePhase = CapturePhase.WAITING_FOR_MOVEMENT
            publishCaptureState()
            cameraController.setAlignmentReference(
                referenceFile = frame.file,
                orientation = currentCaptureOrientation(),
                expectedDirection = motionDirection,
                onUpdate = { update ->
                    viewModelScope.launch(Dispatchers.Main) {
                        if (motionDirection == null && update.direction != null) {
                            motionDirection = update.direction
                        }
                        alignmentGuide = update
                        if (_uiState.value is PanoramaUiState.Capturing) publishCaptureState()
                        if (_uiState.value is PanoramaUiState.Capturing && update.captureAllowed && !capturing) {
                            capturePhoto(cameraController, automatic = true)
                        }
                    }
                },
                onError = { error ->
                    Log.w(TAG, "Alignment guide unavailable", error)
                }
            )
        }
    }

    private fun failCapture(error: Throwable) {
        capturing = false
        capturePhase = CapturePhase.ERROR
        Log.e(TAG, "Photo capture failed", error)
        _uiState.value = PanoramaUiState.Error(
            error.message ?: appContext.getString(R.string.error_save_image)
        )
    }

    private suspend fun writeMetrics(result: StitchingResult) = withContext(Dispatchers.IO) {
        val currentSession = synchronized(sessionLock) { session }
        PanoramaMetrics(
            sessionId = currentSession.id,
            imageCount = result.imageCount,
            orientation = result.orientation.name,
            processingDurationMs = result.durationMs,
            resultWidth = result.width,
            resultHeight = result.height,
            resultPath = result.outputPath
        ).writeTo(currentSession)
    }

    private fun publishCaptureState() {
        val currentSession = synchronized(sessionLock) { session }
        _uiState.value = PanoramaUiState.Capturing(
            images = currentSession.images,
            isCapturing = capturing,
            alignment = alignmentGuide,
            orientation = currentSession.orientation ?: currentCaptureOrientation()
        )
    }

    private fun showCaptureState() {
        if (_uiState.value is PanoramaUiState.CameraStarting) publishCaptureState()
    }

    private fun currentCaptureOrientation(): CaptureOrientation {
        return CaptureOrientation.fromConfiguration(appContext.resources.configuration)
    }
}
