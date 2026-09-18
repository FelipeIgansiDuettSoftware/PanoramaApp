package com.panoramaapp.panorama.processing

enum class StitchingMode {
    PANORAMA,
    SCANS;

    fun alternate(): StitchingMode = if (this == PANORAMA) SCANS else PANORAMA
}
