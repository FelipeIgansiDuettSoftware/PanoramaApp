package com.panoramaapp.panorama

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.panoramaapp.R
import com.panoramaapp.panorama.camera.CameraController
import com.panoramaapp.panorama.camera.CameraState
import com.panoramaapp.panorama.camera.CapturedFrame
import com.panoramaapp.panorama.capture.CaptureSession
import com.panoramaapp.panorama.capture.CaptureOrientation
import com.panoramaapp.panorama.capture.CaptureSessionStore
import com.panoramaapp.panorama.diagnostics.PanoramaMetrics
import com.panoramaapp.panorama.processing.PanoramaProcessor
import com.panoramaapp.panorama.processing.StitchingMode
import com.panoramaapp.panorama.processing.StitchingResult
import com.panoramaapp.panorama.processing.opencv.OpenCvPanoramaProcessor
import com.panoramaapp.panorama.sensors.DeviceMotionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PanoramaViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        const val FRAME_RATE_FPS = 30
        private const val TAG = "PanoramaViewModel"
    }

    private val sessionStore = CaptureSessionStore(application)
    private val appContext = application
    private val processor: PanoramaProcessor = OpenCvPanoramaProcessor(application)
    private val motionRepository = DeviceMotionRepository(application)
    private val sessionLock = Any()
    private var session: CaptureSession = sessionStore.createSession()
    @Volatile
    private var recording = false

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

    fun startRecording(cameraController: CameraController) {
        if (recording || _cameraState.value != CameraState.Ready) return
        recording = true
        val orientation = currentCaptureOrientation()
        motionRepository.start(orientation)
        val currentSession = synchronized(sessionLock) {
            session = sessionStore.setOrientation(session, orientation)
            session
        }
        cameraController.startFrameCapture(
            outputDirectory = java.io.File(currentSession.directory, "input"),
            firstSequence = currentSession.images.size + 1,
            frameRateFps = FRAME_RATE_FPS,
            onFrameSaved = ::registerFrame,
            onError = { error ->
                viewModelScope.launch(Dispatchers.Main) {
                    failRecording(cameraController, error)
                }
            }
        )
        publishCaptureState()
    }

    fun stopRecording(cameraController: CameraController) {
        if (!recording) return
        recording = false
        motionRepository.stop()
        cameraController.stopFrameCapture {
            viewModelScope.launch(Dispatchers.IO) {
                val moved = motionRepository.movementDetected
                val stoppedSession = synchronized(sessionLock) { session }
                if (!moved) {
                    sessionStore.deleteSession(stoppedSession)
                    val replacement = sessionStore.createSession()
                    synchronized(sessionLock) { session = replacement }
                    withContext(Dispatchers.Main) {
                        _uiState.value = PanoramaUiState.Error(
                            appContext.getString(R.string.error_no_motion)
                        )
                    }
                } else if (stoppedSession.images.size < 2) {
                    withContext(Dispatchers.Main) {
                        _uiState.value = PanoramaUiState.Error(
                            appContext.getString(R.string.error_minimum_images)
                        )
                    }
                } else {
                    withContext(Dispatchers.Main) { publishCaptureState() }
                }
            }
        }
    }

    fun removeLast() {
        if (recording) return
        val result = runCatching {
            synchronized(sessionLock) { session = sessionStore.removeLast(session) }
        }
        result.onFailure {
            _uiState.value = PanoramaUiState.Error(it.message ?: "Unable to remove frame")
        }.onSuccess { publishCaptureState() }
    }

    fun process(mode: StitchingMode) {
        if (recording) return
        val processingSession = synchronized(sessionLock) { session }
        val images = processingSession.images
        if (images.size < 2) {
            _uiState.value = PanoramaUiState.Error(appContext.getString(R.string.error_minimum_images))
            return
        }
        _uiState.value = PanoramaUiState.Processing(progress = null, mode = mode)
        viewModelScope.launch {
            runCatching {
                processor.stitch(
                    images = images,
                    mode = mode,
                    orientation = processingSession.orientation ?: currentCaptureOrientation()
                )
            }
                .onSuccess { result ->
                    writeMetrics(result)
                    _uiState.value = PanoramaUiState.Success(result)
                }
                .onFailure { error ->
                    Log.e(TAG, "Panorama processing failed: mode=$mode session=${session.id}", error)
                    _uiState.value = PanoramaUiState.Error(
                        error.message ?: "Panorama processing failed"
                    )
                }
        }
    }

    fun returnToCapture() {
        recording = false
        publishCaptureState()
    }

    fun startNewSession() {
        val replacement = synchronized(sessionLock) {
            sessionStore.deleteSession(session)
            sessionStore.createSession()
        }
        synchronized(sessionLock) { session = replacement }
        _uiState.value = PanoramaUiState.Capturing(
            images = emptyList(),
            isRecording = false,
            motionDetected = false,
            orientation = currentCaptureOrientation()
        )
    }

    override fun onCleared() {
        motionRepository.stop()
        super.onCleared()
    }

    private fun registerFrame(frame: CapturedFrame) {
        if (!recording) {
            frame.file.delete()
            return
        }
        runCatching {
            synchronized(sessionLock) {
                session = sessionStore.registerImage(
                    session = session,
                    file = frame.file,
                    sequence = frame.sequence,
                    rotationDegrees = frame.rotationDegrees,
                    width = frame.width,
                    height = frame.height
                )
            }
        }.onFailure {
            frame.file.delete()
            Log.e(TAG, "Frame registration failed: ${frame.file.name}", it)
        }.onSuccess { publishCaptureState() }
    }

    private fun failRecording(cameraController: CameraController, error: Throwable) {
        if (!recording) return
        recording = false
        motionRepository.stop()
        cameraController.stopFrameCapture {
            Log.e(TAG, "Frame capture failed", error)
            _uiState.value = PanoramaUiState.Error(
                error.message ?: appContext.getString(R.string.error_save_frame)
            )
        }
    }

    private suspend fun writeMetrics(result: StitchingResult) = withContext(Dispatchers.IO) {
        val currentSession = synchronized(sessionLock) { session }
        PanoramaMetrics(
            sessionId = currentSession.id,
            imageCount = result.imageCount,
            mode = result.mode.name,
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
            isRecording = recording,
            motionDetected = motionRepository.movementDetected,
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
