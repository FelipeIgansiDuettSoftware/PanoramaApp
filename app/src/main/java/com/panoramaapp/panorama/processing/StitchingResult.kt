package com.panoramaapp.panorama.processing

import com.panoramaapp.panorama.capture.CaptureOrientation

data class StitchingResult(
    val outputPath: String,
    val mode: StitchingMode,
    val imageCount: Int,
    val width: Int,
    val height: Int,
    val durationMs: Long,
    val orientation: CaptureOrientation = CaptureOrientation.PORTRAIT,
    val diagnostics: List<String> = emptyList()
)
