package com.panoramaapp.panorama

import com.panoramaapp.panorama.capture.CapturedImage
import com.panoramaapp.panorama.capture.CaptureOrientation
import com.panoramaapp.panorama.camera.AlignmentGuideState
import com.panoramaapp.panorama.processing.StitchingResult

sealed interface PanoramaUiState {
    data object CameraStarting : PanoramaUiState
    data class Capturing(
        val images: List<CapturedImage>,
        val isCapturing: Boolean = false,
        val alignment: AlignmentGuideState = AlignmentGuideState(),
        val orientation: CaptureOrientation = CaptureOrientation.PORTRAIT
    ) : PanoramaUiState
    data class Processing(val progress: Int?) : PanoramaUiState
    data class Success(val result: StitchingResult) : PanoramaUiState
    data class Error(val message: String) : PanoramaUiState
}
