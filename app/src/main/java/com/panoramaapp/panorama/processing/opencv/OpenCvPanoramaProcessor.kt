package com.panoramaapp.panorama.processing.opencv

import android.content.Context
import com.panoramaapp.panorama.capture.CapturedImage
import com.panoramaapp.panorama.capture.CaptureOrientation
import com.panoramaapp.panorama.processing.ImageNormalizer
import com.panoramaapp.panorama.processing.PanoramaProcessor
import com.panoramaapp.panorama.processing.StitchingMode
import com.panoramaapp.panorama.processing.StitchingResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.opencv.android.OpenCVLoader
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDMatch
import org.opencv.core.MatOfKeyPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.features.DescriptorMatcher
import org.opencv.features.ORB
import org.opencv.geometry.Geometry
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Android OpenCV's Maven AAR does not include the high-level Stitcher wrapper.
 * This adapter keeps the UI independent from OpenCV and implements the MVP
 * pipeline with features, robust matching, and a pairwise mosaic.
 */
class OpenCvPanoramaProcessor(context: Context) : PanoramaProcessor {
    private companion object {
        const val MAX_CANVAS_DIMENSION = 20_000
        const val MAX_CANVAS_PIXELS = 10_000_000L
        const val MAX_PROCESSING_DIMENSION = 1_280
        const val MAX_PROCESSING_IMAGES = 24
    }

    private val cacheDirectory = File(context.cacheDir, "panorama-processing")

    override suspend fun stitch(
        images: List<CapturedImage>,
        mode: StitchingMode,
        orientation: CaptureOrientation
    ): StitchingResult = withContext(Dispatchers.Default) {
        require(images.size >= 2) { "At least two images are required" }
        check(OpenCVLoader.initLocal()) { "OpenCV could not be initialized" }
        check(cacheDirectory.mkdirs() || cacheDirectory.isDirectory) {
            "Unable to create processing directory"
        }

        val processingImages = selectImagesForProcessing(images)
        val sessionDirectory = images.first().file.parentFile?.parentFile ?: cacheDirectory
        val output = File(sessionDirectory, "result-${mode.name.lowercase()}.jpg")
        val normalizedDirectory = File(cacheDirectory, "normalized-${System.nanoTime()}")
            .also { check(it.mkdirs()) { "Unable to create normalization directory" } }
        val normalizedFiles = mutableListOf<File>()
        val mats = mutableListOf<Mat>()
        val transforms = mutableListOf<Mat>()
        val startedAt = System.currentTimeMillis()
        var usedAffineFallback = false

        try {
            val normalizer = ImageNormalizer(normalizedDirectory, MAX_PROCESSING_DIMENSION)
            processingImages.forEach { image ->
                val normalized = normalizer.normalize(image)
                normalizedFiles += normalized
                val mat = Imgcodecs.imread(normalized.absolutePath, Imgcodecs.IMREAD_COLOR)
                if (mat.empty()) {
                    mat.release()
                    error("Unable to read ${image.file.name}")
                }
                mats += mat
            }

            var bounds: Quadruple
            var canvasDimensions: Pair<Int, Int>
            try {
                transforms += estimateTransforms(mats, mode, orientation)
                bounds = transformedBounds(mats, transforms)
                canvasDimensions = canvasDimensionsFor(bounds)
            } catch (error: IllegalStateException) {
                if (mode != StitchingMode.PANORAMA) throw error
                transforms.forEach(Mat::release)
                transforms.clear()
                transforms += estimateTransforms(mats, StitchingMode.SCANS, orientation)
                usedAffineFallback = true
                bounds = transformedBounds(mats, transforms)
                canvasDimensions = canvasDimensionsFor(bounds)
            }

            val translation = Mat.eye(3, 3, CvType.CV_64F).also {
                it.put(0, 2, -bounds.first)
                it.put(1, 2, -bounds.second)
            }
            val canvasWidth = canvasDimensions.first
            val canvasHeight = canvasDimensions.second
            val canvasSize = org.opencv.core.Size(canvasWidth.toDouble(), canvasHeight.toDouble())
            val result = Mat.zeros(canvasHeight, canvasWidth, CvType.CV_8UC3)
            val occupiedMask = Mat.zeros(canvasHeight, canvasWidth, CvType.CV_8UC1)
            try {
                mats.zip(transforms).forEach { (source, transform) ->
                    val adjusted = Mat()
                    val warped = Mat.zeros(canvasHeight, canvasWidth, CvType.CV_8UC3)
                    val sourceMask = Mat.ones(source.rows(), source.cols(), CvType.CV_8UC1)
                    val mask = Mat.zeros(canvasHeight, canvasWidth, CvType.CV_8UC1)
                    val binaryMask = Mat.zeros(canvasHeight, canvasWidth, CvType.CV_8UC1)
                    val overlapMask = Mat.zeros(canvasHeight, canvasWidth, CvType.CV_8UC1)
                    val inverseOccupiedMask = Mat.zeros(canvasHeight, canvasWidth, CvType.CV_8UC1)
                    val newPixelsMask = Mat.zeros(canvasHeight, canvasWidth, CvType.CV_8UC1)
                    val blended = Mat.zeros(canvasHeight, canvasWidth, CvType.CV_8UC3)
                    try {
                        multiply(translation, transform, adjusted)
                        Imgproc.warpPerspective(source, warped, adjusted, canvasSize)
                        Imgproc.warpPerspective(sourceMask, mask, adjusted, canvasSize)
                        Imgproc.threshold(mask, binaryMask, 0.0, 255.0, Imgproc.THRESH_BINARY)
                        Core.bitwise_and(occupiedMask, binaryMask, overlapMask)
                        Core.bitwise_not(occupiedMask, inverseOccupiedMask)
                        Core.bitwise_and(binaryMask, inverseOccupiedMask, newPixelsMask)
                        warped.copyTo(result, newPixelsMask)
                        Core.addWeighted(result, 0.5, warped, 0.5, 0.0, blended)
                        blended.copyTo(result, overlapMask)
                        Core.bitwise_or(occupiedMask, binaryMask, occupiedMask)
                    } finally {
                        adjusted.release()
                        warped.release()
                        sourceMask.release()
                        mask.release()
                        binaryMask.release()
                        overlapMask.release()
                        inverseOccupiedMask.release()
                        newPixelsMask.release()
                        blended.release()
                    }
                }

                check(Imgcodecs.imwrite(output.absolutePath, result)) {
                    "Unable to save panorama result"
                }
                val dimensions = result.width() to result.height()
                StitchingResult(
                    outputPath = output.absolutePath,
                    mode = mode,
                    imageCount = images.size,
                    width = dimensions.first,
                    height = dimensions.second,
                    durationMs = System.currentTimeMillis() - startedAt,
                    orientation = orientation,
                    diagnostics = listOf(
                        "ORB features with ${mode.name.lowercase()} lateral transforms and stability checks",
                        "Processed ${processingImages.size} representative frames from ${images.size} captured frames",
                        if (usedAffineFallback) "PANORAMA lateral transform was unstable; scan fallback used" else "",
                        "Overlapping frames are feather-blended instead of being copied over one another"
                    ).filter(String::isNotBlank)
                )
            } finally {
                translation.release()
                result.release()
                occupiedMask.release()
            }
        } finally {
            mats.forEach(Mat::release)
            transforms.forEach(Mat::release)
            normalizedFiles.forEach { it.delete() }
            normalizedDirectory.delete()
        }
    }

    private fun selectImagesForProcessing(images: List<CapturedImage>): List<CapturedImage> {
        val ordered = images.sortedBy(CapturedImage::sequence)
        if (ordered.size <= MAX_PROCESSING_IMAGES) return ordered
        val lastIndex = ordered.lastIndex
        return (0 until MAX_PROCESSING_IMAGES)
            .map { index -> ordered[(index * lastIndex.toDouble() / (MAX_PROCESSING_IMAGES - 1)).roundToInt()] }
            .distinctBy(CapturedImage::sequence)
    }

    private fun estimateTransform(
        previous: Mat,
        current: Mat,
        mode: StitchingMode,
        orientation: CaptureOrientation
    ): Mat {
        val orb = ORB.create(3000)
        val previousKeypoints = MatOfKeyPoint()
        val currentKeypoints = MatOfKeyPoint()
        val previousDescriptors = Mat()
        val currentDescriptors = Mat()
        val matches = mutableListOf<MatOfDMatch>()
        val sourcePoints = MatOfPoint2f()
        val destinationPoints = MatOfPoint2f()
        val inlierMask = Mat()

        try {
            orb.detectAndCompute(previous, Mat(), previousKeypoints, previousDescriptors)
            orb.detectAndCompute(current, Mat(), currentKeypoints, currentDescriptors)
            check(!previousDescriptors.empty() && !currentDescriptors.empty()) {
                "Images do not contain enough visual features"
            }
            DescriptorMatcher.create(DescriptorMatcher.BRUTEFORCE_HAMMING)
                .knnMatch(currentDescriptors, previousDescriptors, matches, 2)

            val goodMatches = matches.mapNotNull { candidate ->
                val pair = candidate.toArray()
                if (pair.size >= 2 && pair[0].distance < 0.75f * pair[1].distance) pair[0] else null
            }
            val previousPoints = previousKeypoints.toArray()
            val currentPoints = currentKeypoints.toArray()
            check(goodMatches.size >= if (mode == StitchingMode.PANORAMA) 4 else 3) {
                "Not enough overlapping features (${goodMatches.size})"
            }
            sourcePoints.fromArray(*goodMatches.map { currentPoints[it.queryIdx].pt }.toTypedArray())
            destinationPoints.fromArray(*goodMatches.map { previousPoints[it.trainIdx].pt }.toTypedArray())

            val transform = when (mode) {
                StitchingMode.PANORAMA -> estimateLateralTransform(
                    sourcePoints,
                    destinationPoints,
                    inlierMask,
                    orientation
                )
                StitchingMode.SCANS -> {
                    estimateAffineTransform(sourcePoints, destinationPoints, inlierMask)
                }
            }
            check(!transform.empty()) { "Could not estimate image transform" }
            check(isTransformUsable(transform, current)) {
                "Could not estimate a stable image transform"
            }
            return transform
        } finally {
            previousKeypoints.release()
            currentKeypoints.release()
            previousDescriptors.release()
            currentDescriptors.release()
            matches.forEach(MatOfDMatch::release)
            sourcePoints.release()
            destinationPoints.release()
            inlierMask.release()
        }
    }

    private fun estimateTransforms(
        images: List<Mat>,
        mode: StitchingMode,
        orientation: CaptureOrientation
    ): MutableList<Mat> {
        val transforms = mutableListOf<Mat>()
        try {
            transforms += Mat.eye(3, 3, CvType.CV_64F)
            images.zipWithNext().forEach { (previous, current) ->
                val relative = estimateTransform(previous, current, mode, orientation)
                val composed = Mat()
                multiply(transforms.last(), relative, composed)
                check(
                    if (mode == StitchingMode.PANORAMA) {
                        isMosaicTransformStable(composed, current)
                    } else {
                        isTransformUsable(composed, current)
                    }
                ) {
                    "Accumulated image transform is unstable"
                }
                transforms += composed
                relative.release()
            }
            return transforms
        } catch (error: Throwable) {
            transforms.forEach(Mat::release)
            throw error
        }
    }

    private fun canvasDimensionsFor(bounds: Quadruple): Pair<Int, Int> {
        val width = max(1, ceil(bounds.third - bounds.first).toInt())
        val height = max(1, ceil(bounds.fourth - bounds.second).toInt())
        check(width <= MAX_CANVAS_DIMENSION && height <= MAX_CANVAS_DIMENSION) {
            "Estimated panorama canvas is too large (${width}x${height})"
        }
        check(width.toLong() * height <= MAX_CANVAS_PIXELS) {
            "Estimated panorama requires too much memory (${width}x${height})"
        }
        return width to height
    }

    private fun estimateAffineTransform(
        sourcePoints: MatOfPoint2f,
        destinationPoints: MatOfPoint2f,
        inlierMask: Mat
    ): Mat {
        val affine = Geometry.estimateAffinePartial2D(
            sourcePoints,
            destinationPoints,
            inlierMask,
            Geometry.RANSAC,
            5.0
        )
        check(!affine.empty()) { "Could not estimate affine transform" }
        return Mat.eye(3, 3, CvType.CV_64F).also { homogeneous ->
            affine.copyTo(homogeneous.submat(0, 2, 0, 3))
            affine.release()
        }
    }

    private fun estimateLateralTransform(
        sourcePoints: MatOfPoint2f,
        destinationPoints: MatOfPoint2f,
        inlierMask: Mat,
        orientation: CaptureOrientation
    ): Mat {
        val affine = estimateAffineTransform(sourcePoints, destinationPoints, inlierMask)
        affine.release()
        val source = sourcePoints.toArray()
        val destination = destinationPoints.toArray()
        val deltas = (source.indices).mapNotNull { index ->
            if (isInlier(inlierMask, index)) {
                destination[index].x - source[index].x to destination[index].y - source[index].y
            } else {
                null
            }
        }
        check(deltas.size >= 2) { "Could not estimate lateral image movement" }
        val translationX = deltas.map { it.first }.sorted()[deltas.size / 2]
        val translationY = deltas.map { it.second }.sorted()[deltas.size / 2]
        return Mat.eye(3, 3, CvType.CV_64F).also {
            it.put(
                0,
                2,
                if (orientation.expectedAxis == CaptureOrientation.Axis.X) translationX else 0.0
            )
            it.put(
                1,
                2,
                if (orientation.expectedAxis == CaptureOrientation.Axis.Y) translationY else 0.0
            )
        }
    }

    private fun isInlier(mask: Mat, index: Int): Boolean {
        if (mask.empty()) return true
        val value = if (mask.rows() == 1) {
            mask.get(0, index)?.firstOrNull()
        } else {
            mask.get(index, 0)?.firstOrNull()
        }
        return value != null && value > 0.0
    }

    private fun isMosaicTransformStable(transform: Mat, image: Mat): Boolean {
        if (!isTransformUsable(transform, image)) return false
        val corners = MatOfPoint2f(
            Point(0.0, 0.0),
            Point(image.cols().toDouble(), 0.0),
            Point(image.cols().toDouble(), image.rows().toDouble()),
            Point(0.0, image.rows().toDouble())
        )
        val transformed = MatOfPoint2f()
        return try {
            Core.perspectiveTransform(corners, transformed, transform)
            val points = transformed.toArray()
            if (points.size != 4) return false
            val top = distance(points[0], points[1])
            val right = distance(points[1], points[2])
            val bottom = distance(points[2], points[3])
            val left = distance(points[3], points[0])
            val edges = listOf(top, right, bottom, left)
            val shortestEdge = edges.minOrNull() ?: return false
            val longestEdge = edges.maxOrNull() ?: return false
            shortestEdge > 1.0 && longestEdge / shortestEdge <= 1.35 &&
                maxOf(top, bottom) <= image.cols() * 1.5 &&
                maxOf(left, right) <= image.rows() * 1.5
        } catch (_: RuntimeException) {
            false
        } finally {
            corners.release()
            transformed.release()
        }
    }

    private fun distance(first: Point, second: Point): Double {
        return sqrt((first.x - second.x) * (first.x - second.x) + (first.y - second.y) * (first.y - second.y))
    }

    private fun isTransformUsable(transform: Mat, image: Mat): Boolean {
        val corners = MatOfPoint2f(
            Point(0.0, 0.0),
            Point(image.cols().toDouble(), 0.0),
            Point(image.cols().toDouble(), image.rows().toDouble()),
            Point(0.0, image.rows().toDouble())
        )
        val transformed = MatOfPoint2f()
        return try {
            Core.perspectiveTransform(corners, transformed, transform)
            val maxAllowedCoordinate = maxOf(2_000, maxOf(image.cols(), image.rows()) * 6)
            transformed.toArray().all { point ->
                point.x.isFinite() &&
                    point.y.isFinite() &&
                    abs(point.x) <= maxAllowedCoordinate &&
                    abs(point.y) <= maxAllowedCoordinate
            }
        } catch (_: RuntimeException) {
            false
        } finally {
            corners.release()
            transformed.release()
        }
    }

    private fun multiply(left: Mat, right: Mat, destination: Mat) {
        val empty = Mat()
        try {
            Core.gemm(left, right, 1.0, empty, 0.0, destination)
        } finally {
            empty.release()
        }
    }

    private fun transformedBounds(
        mats: List<Mat>,
        transforms: List<Mat>
    ): Quadruple {
        var minX = 0.0
        var minY = 0.0
        var maxX = mats.first().cols().toDouble()
        var maxY = mats.first().rows().toDouble()
        mats.drop(1).zip(transforms.drop(1)).forEach { (mat, transform) ->
            val corners = MatOfPoint2f(
                Point(0.0, 0.0),
                Point(mat.cols().toDouble(), 0.0),
                Point(mat.cols().toDouble(), mat.rows().toDouble()),
                Point(0.0, mat.rows().toDouble())
            )
            val transformed = MatOfPoint2f()
            Core.perspectiveTransform(corners, transformed, transform)
            transformed.toArray().forEach { point ->
                check(point.x.isFinite() && point.y.isFinite()) {
                    "Invalid image transform"
                }
                check(abs(point.x) <= MAX_CANVAS_DIMENSION && abs(point.y) <= MAX_CANVAS_DIMENSION) {
                    "Image transform is unstable"
                }
                minX = min(minX, point.x)
                minY = min(minY, point.y)
                maxX = max(maxX, point.x)
                maxY = max(maxY, point.y)
            }
            corners.release()
            transformed.release()
        }
        return Quadruple(minX, minY, maxX, maxY)
    }

    private data class Quadruple(
        val first: Double,
        val second: Double,
        val third: Double,
        val fourth: Double
    )
}
