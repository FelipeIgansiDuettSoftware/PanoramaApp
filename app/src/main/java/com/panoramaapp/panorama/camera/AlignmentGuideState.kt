package com.panoramaapp.panorama.camera

data class AlignmentGuideState(
    val active: Boolean = false,
    val hasMatch: Boolean = false,
    val referenceY: Double = 0.5,
    val currentY: Double = 0.5,
    val matchCount: Int = 0,
    val hysteresisAligned: Boolean? = null
) {
    val verticalDelta: Double
        get() = currentY - referenceY

    val aligned: Boolean
        get() = hysteresisAligned ?: (hasMatch && kotlin.math.abs(verticalDelta) <= 0.045)
}
