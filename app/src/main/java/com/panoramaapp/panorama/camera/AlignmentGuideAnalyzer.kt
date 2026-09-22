package com.panoramaapp.panorama.camera

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.exifinterface.media.ExifInterface
import com.google.common.math.Quantiles.median
import com.panoramaapp.panorama.capture.CaptureOrientation
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDMatch
import org.opencv.core.MatOfKeyPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.features.DescriptorMatcher
import org.opencv.features.ORB
import org.opencv.geometry.Geometry
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt

/** Uses preview frames only to decide when the next full-resolution photo is valid. */
class AlignmentGuideAnalyzer(
    private val referenceFile: File,
    private val orientation: CaptureOrientation,
    expectedDirection: AlignmentDirection?,
    private val onUpdate: (AlignmentGuideState) -> Unit
) : ImageAnalysis.Analyzer {
    private var reference = Imgcodecs.imread(referenceFile.absolutePath, Imgcodecs.IMREAD_COLOR)
    private val referenceGray = Mat()
    private val referenceKeypoints = MatOfKeyPoint()
    private val referenceDescriptors = Mat()
    private val detector = ORB.create(1_200)
    private val matcher = DescriptorMatcher.create(DescriptorMatcher.BRUTEFORCE_HAMMING)
    private val preferredAxis = if (orientation == CaptureOrientation.LANDSCAPE) AlignmentAxis.HORIZONTAL else AlignmentAxis.VERTICAL
    private var axis: AlignmentAxis? = null
    @Volatile private var closed = false
    private var direction: AlignmentDirection? = expectedDirection
    private var referencePrepared = false
    private var referenceOriented = false
    private var lastEmittedState: AlignmentGuideState? = null
    private var lastEmissionNanos = 0L

    override fun analyze(image: ImageProxy) {
        try {
            if (closed) return
            if (reference.empty()) {
                publishNoMatch()
                return
            }
            val current = image.toUprightMat()
            prepareReferenceFor(current.cols(), current.rows())
            if (referenceDescriptors.empty()) {
                publishNoMatch()
                return
            }
            val currentGray = Mat()
            val currentKeypoints = MatOfKeyPoint()
            val currentDescriptors = Mat()
            val matches = mutableListOf<MatOfDMatch>()
            try {
                Imgproc.cvtColor(current, currentGray, Imgproc.COLOR_BGR2GRAY)
                detector.detectAndCompute(currentGray, Mat(), currentKeypoints, currentDescriptors)
                if (currentDescriptors.empty()) {
                    publishNoMatch()
                    return
                }
                matcher.knnMatch(referenceDescriptors, currentDescriptors, matches, 2)
                val goodMatches = matches.mapNotNull { pair ->
                    val candidates = pair.toArray()
                    val first = candidates.getOrNull(0)
                    val second = candidates.getOrNull(1)
                    if (first != null && second != null && first.distance < 0.80f * second.distance) first else null
                }
                if (goodMatches.size < MIN_GUIDE_MATCHES) {
                    publishNoMatch(goodMatches.size)
                    return
                }

                val referencePoints = referenceKeypoints.toArray()
                val currentPoints = currentKeypoints.toArray()
                val source = MatOfPoint2f(*goodMatches.map { currentPoints[it.trainIdx].pt }.toTypedArray())
                val destination = MatOfPoint2f(*goodMatches.map { referencePoints[it.queryIdx].pt }.toTypedArray())
                val inlierMask = Mat()
                val transform = try {
                    Geometry.findHomography(source, destination, Geometry.RANSAC, RANSAC_REPROJECTION_THRESHOLD, inlierMask)
                } finally {
                    source.release()
                    destination.release()
                }
                try {
                    if (transform.empty()) {
                        publishNoMatch(goodMatches.size)
                        return
                    }
                    val inliers = countInliers(inlierMask, goodMatches.size)
                    val inlierRatio = inliers.toDouble() / goodMatches.size.toDouble()
                    val reprojectionError = reprojectionError(
                        goodMatches.map { currentPoints[it.trainIdx].pt },
                        goodMatches.map { referencePoints[it.queryIdx].pt },
                        transform,
                        inlierMask
                    )
                    if (inliers < MIN_GUIDE_INLIERS || inlierRatio < MIN_GUIDE_INLIER_RATIO || reprojectionError > MAX_GUIDE_REPROJECTION_ERROR) {
                        publishNoMatch(goodMatches.size)
                        return
                    }

                    // Use the median displacement of inlier feature matches. The homography
                    // center can remain stationary during a pan around the phone's own axis.
                    val displacements = goodMatches.mapIndexedNotNull { index, match ->
                        val maskValue = if (inlierMask.rows() == 1) inlierMask.get(0, index) else inlierMask.get(index, 0)
                        if (maskValue?.firstOrNull()?.let { it > 0.0 } == true) {
                            val from = currentPoints[match.trainIdx].pt
                            val to = referencePoints[match.queryIdx].pt
                            ((to.x - from.x) / current.cols()) to ((to.y - from.y) / current.rows())
                        } else null
                    }
                    if (displacements.size < MIN_GUIDE_INLIERS) {
                        publishNoMatch(goodMatches.size)
                        return
                    }
                    val horizontalDelta = median(displacements.map { it.first })
                    val verticalDelta = median(displacements.map { it.second })
                    val chosenAxis = axis ?: when {
                        abs(horizontalDelta) > abs(verticalDelta) * AXIS_DOMINANCE_RATIO -> AlignmentAxis.HORIZONTAL
                        abs(verticalDelta) > abs(horizontalDelta) * AXIS_DOMINANCE_RATIO -> AlignmentAxis.VERTICAL
                        else -> preferredAxis
                    }
                    if (axis == null && maxOf(abs(horizontalDelta), abs(verticalDelta)) >= MIN_PROGRESS) axis = chosenAxis
                    val mainDelta = if (chosenAxis == AlignmentAxis.HORIZONTAL) horizontalDelta else verticalDelta
                    val crossDelta = if (chosenAxis == AlignmentAxis.HORIZONTAL) verticalDelta else horizontalDelta
                    val smoothedReferenceX = 0.5
                    val smoothedCurrentX = (0.5 + horizontalDelta).coerceIn(0.0, 1.0)
                    val smoothedReferenceY = 0.5
                    val smoothedCurrentY = (0.5 + verticalDelta).coerceIn(0.0, 1.0)
                    val detectedDirection = direction ?: directionFor(mainDelta)
                    val validAxis = abs(crossDelta) <= MAX_CROSS_AXIS_DELTA
                    val validDirection = direction == null || detectedDirection == direction
                    val progress = abs(mainDelta)
                    val overlap = (1.0 - progress).coerceIn(0.0, 1.0)
                    val captureAllowed = direction != null && validAxis && validDirection &&
                        progress >= TARGET_PROGRESS_MIN && overlap >= MIN_OVERLAP_RATIO
                    val guidance = when {
                        captureAllowed -> AlignmentGuidance.CAPTURE_READY
                        !validAxis -> AlignmentGuidance.OUT_OF_AXIS
                        !validDirection -> AlignmentGuidance.KEEP_DIRECTION
                        overlap < MIN_OVERLAP_RATIO -> AlignmentGuidance.INSUFFICIENT_OVERLAP
                        direction == null && progress < MIN_PROGRESS -> AlignmentGuidance.FIND_OVERLAP
                        detectedDirection == AlignmentDirection.POSITIVE -> AlignmentGuidance.MOVE_POSITIVE
                        else -> AlignmentGuidance.MOVE_NEGATIVE
                    }
                    emit(
                        AlignmentGuideState(
                            active = true,
                            hasMatch = true,
                            referenceY = smoothedReferenceY,
                            currentY = smoothedCurrentY,
                            referenceX = smoothedReferenceX,
                            currentX = smoothedCurrentX,
                            matchCount = goodMatches.size,
                            hysteresisAligned = captureAllowed,
                        axis = chosenAxis,
                            direction = direction ?: detectedDirection,
                            progress = progress,
                            overlapRatio = overlap,
                            inlierRatio = inlierRatio,
                            reprojectionError = reprojectionError,
                            captureAllowed = captureAllowed,
                            guidance = guidance
                        )
                    )
                    if (direction == null && detectedDirection != null && progress >= MIN_PROGRESS) direction = detectedDirection
                } finally {
                    transform.release()
                    inlierMask.release()
                }
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

    /** Feature matching and RANSAC must use the same pixel scale for reference and preview. */
    private fun prepareReferenceFor(width: Int, height: Int) {
        if (referencePrepared) return
        if (reference.empty()) return
        if (!referenceOriented) {
            orientReferenceFromExif()
            referenceOriented = true
        }
        if (reference.cols() != width || reference.rows() != height) {
            val resized = Mat()
            Imgproc.resize(reference, resized, org.opencv.core.Size(width.toDouble(), height.toDouble()))
            reference.release()
            reference = resized
        }
        Imgproc.cvtColor(reference, referenceGray, Imgproc.COLOR_BGR2GRAY)
        detector.detectAndCompute(referenceGray, Mat(), referenceKeypoints, referenceDescriptors)
        referencePrepared = true
    }

    private fun orientReferenceFromExif() {
        val exifOrientation = ExifInterface(referenceFile.absolutePath).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL
        )
        when (exifOrientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> Core.rotate(reference, reference, Core.ROTATE_90_CLOCKWISE)
            ExifInterface.ORIENTATION_ROTATE_180 -> Core.rotate(reference, reference, Core.ROTATE_180)
            ExifInterface.ORIENTATION_ROTATE_270 -> Core.rotate(reference, reference, Core.ROTATE_90_COUNTERCLOCKWISE)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> Core.flip(reference, reference, 1)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> Core.flip(reference, reference, 0)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                val transposed = Mat()
                Core.transpose(reference, transposed)
                transposed.copyTo(reference)
                transposed.release()
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                val transposed = Mat()
                Core.transpose(reference, transposed)
                Core.flip(transposed, reference, -1)
                transposed.release()
            }
        }
    }

    private fun publishNoMatch(matchCount: Int = 0) {
        emit(AlignmentGuideState(active = true, hasMatch = false, axis = axis ?: preferredAxis, direction = direction, matchCount = matchCount, guidance = AlignmentGuidance.FIND_OVERLAP))
    }

    private fun directionFor(delta: Double): AlignmentDirection? = when {
        delta > MIN_PROGRESS -> AlignmentDirection.POSITIVE
        delta < -MIN_PROGRESS -> AlignmentDirection.NEGATIVE
        else -> null
    }

    private fun countInliers(mask: Mat, size: Int): Int = (0 until size).count { index ->
        val value = if (mask.rows() == 1) mask.get(0, index) else mask.get(index, 0)
        value?.firstOrNull()?.let { it > 0.0 } == true
    }

    private fun reprojectionError(
        sourcePoints: List<org.opencv.core.Point>,
        destinationPoints: List<org.opencv.core.Point>,
        transform: Mat,
        mask: Mat
    ): Double {
        val source = MatOfPoint2f(*sourcePoints.toTypedArray())
        val projected = MatOfPoint2f()
        return try {
            Core.perspectiveTransform(source, projected, transform)
            projected.toArray().mapIndexedNotNull { index, point ->
                val value = if (mask.rows() == 1) mask.get(0, index) else mask.get(index, 0)
                if (value?.firstOrNull()?.let { it > 0.0 } == true) {
                    val expected = destinationPoints[index]
                    sqrt((point.x - expected.x) * (point.x - expected.x) + (point.y - expected.y) * (point.y - expected.y))
                } else null
            }.average()
        } finally {
            source.release()
            projected.release()
        }
    }

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[middle - 1] + sorted[middle]) / 2.0
        } else {
            sorted[middle]
        }
    }

    private fun smooth(value: Double, previous: Double?, save: (Double) -> Unit): Double {
        val smoothed = previous?.let { it + SMOOTHING_ALPHA * (value - it) } ?: value
        save(smoothed)
        return smoothed
    }

    private fun emit(state: AlignmentGuideState) {
        val now = System.nanoTime()
        val previous = lastEmittedState
        val updateTooSoon = now - lastEmissionNanos < MIN_UPDATE_INTERVAL_NANOS
        val deltaTooSmall = previous != null && abs(state.progress - previous.progress) < MIN_VISIBLE_PROGRESS
        if (previous != null && updateTooSoon && state.guidance == previous.guidance && deltaTooSmall) return
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

    private companion object {
        const val MIN_GUIDE_MATCHES = 6
        const val MIN_GUIDE_INLIERS = 5
        const val MIN_GUIDE_INLIER_RATIO = 0.30
        const val MAX_GUIDE_REPROJECTION_ERROR = 8.0
        const val RANSAC_REPROJECTION_THRESHOLD = 3.0
        const val MIN_PROGRESS = 0.06
        const val AXIS_DOMINANCE_RATIO = 1.25
        const val TARGET_PROGRESS_MIN = 0.18
        const val MIN_OVERLAP_RATIO = 0.38
        const val MAX_CROSS_AXIS_DELTA = 0.16
        const val SMOOTHING_ALPHA = 0.16
        const val MIN_VISIBLE_PROGRESS = 0.006
        const val MIN_UPDATE_INTERVAL_NANOS = 100_000_000L
    }
}
