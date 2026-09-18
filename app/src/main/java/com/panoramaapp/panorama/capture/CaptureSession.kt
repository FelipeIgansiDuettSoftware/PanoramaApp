package com.panoramaapp.panorama.capture

import java.io.File

data class CaptureSession(
    val id: String,
    val directory: File,
    val images: List<CapturedImage> = emptyList(),
    val orientation: CaptureOrientation? = null,
    val startedAtEpochMs: Long = System.currentTimeMillis()
)
