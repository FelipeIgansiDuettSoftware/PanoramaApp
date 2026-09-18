package com.panoramaapp.panorama.capture

import java.io.File

data class CapturedImage(
    val sequence: Int,
    val file: File,
    val capturedAtEpochMs: Long,
    val rotationDegrees: Int,
    val width: Int,
    val height: Int,
    val sizeBytes: Long
)
