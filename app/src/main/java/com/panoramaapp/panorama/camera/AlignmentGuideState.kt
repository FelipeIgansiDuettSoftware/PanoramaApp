package com.panoramaapp.panorama.camera

enum class AlignmentAxis { HORIZONTAL, VERTICAL }

enum class AlignmentDirection { POSITIVE, NEGATIVE }

enum class AlignmentGuidance {
    READY,
    FIND_OVERLAP,
    MOVE_POSITIVE,
    MOVE_NEGATIVE,
    KEEP_DIRECTION,
    OUT_OF_AXIS,
    INSUFFICIENT_OVERLAP,
    CAPTURE_READY
}

data class AlignmentGuideState(
    val active: Boolean = true,
    val hasMatch: Boolean = false,
    val referenceY: Double = 0.5,
    val currentY: Double = 0.5,
    val referenceX: Double = 0.5,
    val currentX: Double = 0.5,
    val matchCount: Int = 0,
    val hysteresisAligned: Boolean? = null,
    val axis: AlignmentAxis = AlignmentAxis.VERTICAL,
    val axisLocked: Boolean = false,
    val direction: AlignmentDirection? = null,
    val progress: Double = 0.0,
    val overlapRatio: Double = 0.0,
    val inlierRatio: Double = 0.0,
    val reprojectionError: Double = 0.0,
    val captureAllowed: Boolean = false,
    val guidance: AlignmentGuidance = AlignmentGuidance.READY
) {
    val verticalDelta: Double
        get() = currentY - referenceY

    val horizontalDelta: Double
        get() = currentX - referenceX

    val aligned: Boolean
        get() = captureAllowed || (hysteresisAligned ?: (hasMatch && kotlin.math.abs(verticalDelta) <= 0.045))
}
