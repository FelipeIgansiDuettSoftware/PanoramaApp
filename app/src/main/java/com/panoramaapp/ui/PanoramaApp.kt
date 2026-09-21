package com.panoramaapp.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.core.content.ContextCompat
import com.panoramaapp.panorama.PanoramaUiState
import com.panoramaapp.panorama.PanoramaViewModel
import com.panoramaapp.panorama.camera.CameraController
import com.panoramaapp.panorama.capture.CaptureOrientation

@Composable
fun PanoramaApp(
    viewModel: PanoramaViewModel,
    cameraController: CameraController
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOrientation = CaptureOrientation.fromConfiguration(configuration)
    val uiState by viewModel.uiState.collectAsState()
    val cameraState by viewModel.cameraState.collectAsState()
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.onCameraPermissionGranted() else viewModel.onCameraPermissionRequired()
    }
    val hasCameraPermission = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED

    LaunchedEffect(hasCameraPermission) {
        if (hasCameraPermission) {
            viewModel.returnToCapture()
        } else {
            viewModel.onCameraPermissionRequired()
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    when (val state = uiState) {
        PanoramaUiState.CameraStarting -> CaptureScreen(
            images = emptyList(),
            cameraState = cameraState,
            hasCameraPermission = hasCameraPermission,
            lifecycleOwner = lifecycleOwner,
            cameraController = cameraController,
            orientation = currentOrientation,
            isCapturing = false,
            alignment = com.panoramaapp.panorama.camera.AlignmentGuideState(),
            onCapturePhoto = { viewModel.capturePhoto(cameraController) },
            onRemoveLast = viewModel::removeLast,
            onProcess = viewModel::process,
            onCameraReady = viewModel::onCameraReady,
            onCameraError = viewModel::onCameraError,
            onRequestPermission = {
                permissionLauncher.launch(Manifest.permission.CAMERA)
            }
        )

        is PanoramaUiState.Capturing -> CaptureScreen(
            images = state.images,
            cameraState = cameraState,
            hasCameraPermission = hasCameraPermission,
            lifecycleOwner = lifecycleOwner,
            cameraController = cameraController,
            orientation = state.orientation,
            isCapturing = state.isCapturing,
            alignment = state.alignment,
            onCapturePhoto = { viewModel.capturePhoto(cameraController) },
            onRemoveLast = viewModel::removeLast,
            onProcess = viewModel::process,
            onCameraReady = viewModel::onCameraReady,
            onCameraError = viewModel::onCameraError,
            onRequestPermission = {
                permissionLauncher.launch(Manifest.permission.CAMERA)
            }
        )

        is PanoramaUiState.Processing -> ProcessingScreen(state)
        is PanoramaUiState.Success -> ResultScreen(
            result = state.result,
            onBack = viewModel::returnToCapture,
            onNewSession = { viewModel.startNewSession(cameraController) }
        )

        is PanoramaUiState.Error -> ErrorScreen(
            message = state.message,
            onBack = viewModel::returnToCapture
        )
    }
}
