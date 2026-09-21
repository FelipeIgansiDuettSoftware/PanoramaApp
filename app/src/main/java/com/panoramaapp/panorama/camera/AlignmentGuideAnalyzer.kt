package com.panoramaapp.panorama.camera

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDMatch
import org.opencv.core.MatOfKeyPoint
import org.opencv.features.DescriptorMatcher
import org.opencv.features.ORB
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File

/**
 * Uses the latest camera preview only to estimate vertical alignment. These frames
 * are never written to the capture session and are not used as panorama inputs.
 */
class AlignmentGuideAnalyzer(
    referenceFile: File,
    private val onUpdate: (AlignmentGuideState) -> Unit
) : ImageAnalysis.Analyzer {
    private val reference = Imgcodecs.imread(referenceFile.absolutePath, Imgcodecs.IMREAD_COLOR)
    private val referenceGray = Mat()
    private val referenceKeypoints = MatOfKeyPoint()
    private val referenceDescriptors = Mat()
    private val detector = ORB.create(1_200)
    private val matcher = DescriptorMatcher.create(DescriptorMatcher.BRUTEFORCE_HAMMING)
    @Volatile
    private var closed = false
    private var filteredReferenceY: Double? = null
    private var filteredCurrentY: Double? = null
    private var alignedState = false
    private var lastEmittedState: AlignmentGuideState? = null
    private var lastEmissionNanos = 0L

    init {
        if (!reference.empty()) {
            Imgproc.cvtColor(reference, referenceGray, Imgproc.COLOR_BGR2GRAY)
            detector.detectAndCompute(
                referenceGray,
                Mat(),
                referenceKeypoints,
                referenceDescriptors
            )
        }
    }

    override fun analyze(image: ImageProxy) {
        try {
            if (closed) return
            if (reference.empty() || referenceDescriptors.empty()) {
                publishNoMatch()
                return
            }

            val current = image.toUprightMat()
            val currentGray = Mat()
            val currentKeypoints = MatOfKeyPoint()
            val currentDescriptors = Mat()
            val matches = mutableListOf<MatOfDMatch>()
            try {
                Imgproc.cvtColor(current, currentGray, Imgproc.COLOR_BGR2GRAY)
                detector.detectAndCompute(
                    currentGray,
                    Mat(),
                    currentKeypoints,
                    currentDescriptors
                )
                if (currentDescriptors.empty()) {
                    publishNoMatch()
                    return
                }

                matcher.knnMatch(referenceDescriptors, currentDescriptors, matches, 2)
                val goodMatches = matches.mapNotNull { pair ->
                    val candidates = pair.toArray()
                    val first = candidates.getOrNull(0)
                    val second = candidates.getOrNull(1)
                    if (first != null && second != null && first.distance < 0.80f * second.distance) {
                        first
                    } else {
                        null
                    }
                }
                if (goodMatches.size < MIN_GUIDE_MATCHES) {
                    publishNoMatch(goodMatches.size)
                    return
                }

                val referencePoints = referenceKeypoints.toArray()
                val currentPoints = currentKeypoints.toArray()
                val referenceY = median(
                    goodMatches.map { match ->
                        referencePoints[match.queryIdx].pt.y / reference.rows().toFloat()
                    }
                )
                val currentY = median(
                    goodMatches.map { match ->
                        currentPoints[match.trainIdx].pt.y / current.rows().toFloat()
                    }
                )
                val smoothedReferenceY = smoothReference(referenceY.coerceIn(0.0, 1.0))
                val smoothedCurrentY = smoothCurrent(currentY.coerceIn(0.0, 1.0))
                val delta = smoothedCurrentY - smoothedReferenceY
                alignedState = if (alignedState) {
                    kotlin.math.abs(delta) <= ALIGNED_EXIT_THRESHOLD
                } else {
                    kotlin.math.abs(delta) <= ALIGNED_ENTER_THRESHOLD
                }
                emit(
                    AlignmentGuideState(
                        active = true,
                        hasMatch = true,
                        referenceY = smoothedReferenceY,
                        currentY = smoothedCurrentY,
                        matchCount = goodMatches.size,
                        hysteresisAligned = alignedState
                    )
                )
            } finally {
                current.release()
                currentGray.release()
                currentKeypoints.release()
                currentDescriptors.release()
                matches.forEach(MatOfDMatch::release)
            }
        } catch (_: RuntimeException) {
            if (!closed) publishNoMatch()
        } finally {
            image.close()
        }
    }

    fun close() {
        closed = true
        reference.release()
        referenceGray.release()
        referenceKeypoints.release()
        referenceDescriptors.release()
    }

    private fun publishNoMatch(matchCount: Int = 0) {
        alignedState = false
        emit(
            AlignmentGuideState(
                active = true,
                matchCount = matchCount
            )
        )
    }

    private fun smoothReference(value: Double): Double {
        val previous = filteredReferenceY
        val smoothed = previous?.let { it + SMOOTHING_ALPHA * (value - it) } ?: value
        filteredReferenceY = smoothed
        return smoothed
    }

    private fun smoothCurrent(value: Double): Double {
        val previous = filteredCurrentY
        val smoothed = previous?.let { it + SMOOTHING_ALPHA * (value - it) } ?: value
        filteredCurrentY = smoothed
        return smoothed
    }

    private fun emit(state: AlignmentGuideState) {
        val now = System.nanoTime()
        val previous = lastEmittedState
        val sameMatchStatus = previous?.hasMatch == state.hasMatch
        val updateTooSoon = now - lastEmissionNanos < MIN_UPDATE_INTERVAL_NANOS
        val deltaTooSmall = previous != null &&
            kotlin.math.abs(state.verticalDelta - previous.verticalDelta) < MIN_VISIBLE_DELTA
        if (previous != null && sameMatchStatus && updateTooSoon && state.aligned == previous.aligned && deltaTooSmall) {
            return
        }
        lastEmittedState = state
        lastEmissionNanos = now
        onUpdate(state)
    }

    private fun ImageProxy.toUprightMat(): Mat {
        val plane = planes.firstOrNull() ?: error("Alignment frame has no pixel plane")
        val width = width
        val height = height
        val pixelStride = plane.pixelStride
        val row = ByteArray(width * pixelStride)
        val packed = ByteArray(width * height * 4)
        val buffer = plane.buffer.duplicate()
        for (y in 0 until height) {
            buffer.position(y * plane.rowStride)
            buffer.get(row, 0, row.size)
            for (x in 0 until width) {
                val sourceOffset = x * pixelStride
                val targetOffset = (y * width + x) * 4
                packed[targetOffset] = row[sourceOffset]
                packed[targetOffset + 1] = row[sourceOffset + 1]
                packed[targetOffset + 2] = row[sourceOffset + 2]
                packed[targetOffset + 3] = row[sourceOffset + 3]
            }
        }

        val rgba = Mat(height, width, CvType.CV_8UC4)
        val bgr = Mat()
        val upright = Mat()
        try {
            rgba.put(0, 0, packed)
            Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
            when ((imageInfo.rotationDegrees % 360 + 360) % 360) {
                90 -> Core.rotate(bgr, upright, Core.ROTATE_90_CLOCKWISE)
                180 -> Core.rotate(bgr, upright, Core.ROTATE_180)
                270 -> Core.rotate(bgr, upright, Core.ROTATE_90_COUNTERCLOCKWISE)
                else -> bgr.copyTo(upright)
            }
            return upright.clone()
        } finally {
            rgba.release()
            bgr.release()
            upright.release()
        }
    }

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        return sorted[sorted.size / 2]
    }

    private companion object {
        const val MIN_GUIDE_MATCHES = 5
        const val SMOOTHING_ALPHA = 0.16
        const val ALIGNED_ENTER_THRESHOLD = 0.035
        const val ALIGNED_EXIT_THRESHOLD = 0.065
        const val MIN_VISIBLE_DELTA = 0.006
        const val MIN_UPDATE_INTERVAL_NANOS = 100_000_000L
    }
}
