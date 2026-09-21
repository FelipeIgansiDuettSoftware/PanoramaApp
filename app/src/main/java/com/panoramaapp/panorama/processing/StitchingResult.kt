package com.panoramaapp.panorama.processing

import com.panoramaapp.panorama.capture.CaptureOrientation

data class StitchingResult(
    val outputPath: String,
    val imageCount: Int,
    val width: Int,
    val height: Int,
    val durationMs: Long,
    val orientation: CaptureOrientation = CaptureOrientation.PORTRAIT,
    val diagnostics: List<String> = emptyList(),
    val acceptedImageCount: Int = imageCount,
    val discardedImageCount: Int = 0,
    val averageInlierRatio: Double = 0.0,
    val averageReprojectionError: Double = 0.0,
    val coverageRatio: Double = 1.0
)
