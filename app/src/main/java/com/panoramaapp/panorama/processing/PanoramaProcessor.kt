package com.panoramaapp.panorama.processing

import com.panoramaapp.panorama.capture.CapturedImage
import com.panoramaapp.panorama.capture.CaptureOrientation

interface PanoramaProcessor {
    suspend fun stitch(
        images: List<CapturedImage>,
        mode: StitchingMode,
        orientation: CaptureOrientation
    ): StitchingResult
}
