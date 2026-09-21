package com.panoramaapp.panorama.processing.opencv

import android.content.Context
import com.panoramaapp.panorama.capture.CapturedImage
import com.panoramaapp.panorama.capture.CaptureOrientation
import com.panoramaapp.panorama.processing.ImageNormalizer
import com.panoramaapp.panorama.processing.PanoramaProcessor
import com.panoramaapp.panorama.processing.StitchingResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.opencv.android.OpenCVLoader
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.DMatch
import org.opencv.core.Mat
import org.opencv.core.MatOfDMatch
import org.opencv.core.MatOfKeyPoint
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.features.DescriptorMatcher
import org.opencv.features.ORB
import org.opencv.features.SIFT
import org.opencv.geometry.Geometry
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Feature-based panorama processor.
 *
 * Registration is done at a reduced resolution and composition is rendered separately.
 * Each warped frame receives a distance-to-edge weight, producing a gradual fade at
 * overlaps instead of copying or averaging complete rectangular frames.
 */
class OpenCvPanoramaProcessor(context: Context) : PanoramaProcessor {
    private companion object {
        const val REGISTRATION_MAX_DIMENSION = 960
        const val COMPOSITION_MAX_DIMENSION = 1_920
        const val MAX_CANVAS_DIMENSION = 12_000
        const val MAX_CANVAS_PIXELS = 16_000_000L
        const val MAX_LOOKAHEAD = 6
        const val MAX_FEATURES = 2_500
        const val MIN_GOOD_MATCHES = 6
        const val MIN_INLIER_RATIO = 0.30
        const val MAX_REPROJECTION_ERROR = 8.0
        const val MIN_PROGRESS_PIXELS = 4.0
        const val RANSAC_REPROJECTION_THRESHOLD = 3.0
        const val MAX_AUTOMATIC_TILT_CORRECTION = 2.0

        // OpenCV DistanceTypes.DIST_L2 is not exposed by the current Java AAR.
        const val DISTANCE_L2 = 2
    }

    private val cacheDirectory = File(context.cacheDir, "panorama-processing")

    override suspend fun stitch(
        images: List<CapturedImage>,
        orientation: CaptureOrientation
    ): StitchingResult = withContext(Dispatchers.Default) {
        require(images.size >= 2) { "At least two images are required" }
        check(OpenCVLoader.initLocal()) { "OpenCV could not be initialized" }
        check(cacheDirectory.mkdirs() || cacheDirectory.isDirectory) {
            "Unable to create processing directory"
        }

        val orderedImages = images.sortedWith(
            compareBy<CapturedImage> { it.sequence }.thenBy { it.capturedAtNanos }
        )
        val sessionDirectory = orderedImages.first().file.parentFile?.parentFile ?: cacheDirectory
        val output = File(sessionDirectory, "result-panorama.jpg")
        val workDirectory = File(cacheDirectory, "work-${System.nanoTime()}")
            .also { check(it.mkdirs()) { "Unable to create work directory" } }
        val startedAt = System.currentTimeMillis()

        try {
            val selection = selectSequentialFrames(orderedImages, workDirectory)
            check(selection.images.size >= 2) {
                "Movement produced fewer than two coherent frames; keep moving slowly with visible overlap"
            }

            val globalTransforms = composeGlobalTransforms(selection.relativeTransforms)
            try {
                correctGlobalTilt(globalTransforms, selection.images.first())

                val composition = composeWithFeatheredBlend(
                    images = selection.images,
                    registrationTransforms = globalTransforms,
                    workDirectory = workDirectory,
                    output = output
                )
                val averageInlierRatio = selection.registrations.map { it.inlierRatio }.average()
                val averageError = selection.registrations.map { it.reprojectionError }.average()

                StitchingResult(
                    outputPath = output.absolutePath,
                    imageCount = orderedImages.size,
                    width = composition.width,
                    height = composition.height,
                    durationMs = System.currentTimeMillis() - startedAt,
                    orientation = orientation,
                    acceptedImageCount = selection.images.size,
                    discardedImageCount = orderedImages.size - selection.images.size,
                    averageInlierRatio = averageInlierRatio,
                    averageReprojectionError = averageError,
                    coverageRatio = composition.coverageRatio,
                    diagnostics = listOf(
                        "PANORAMA: sequential ${selection.images.size}/${orderedImages.size} frames",
                        "Registration detectors: ${selection.registrations.groupingBy { it.detector }.eachCount()}",
                        "Average inliers: ${(averageInlierRatio * 100.0).roundToInt()}%",
                        "Average reprojection error: ${"%.2f".format(averageError)} px",
                        "Orientation: ${orientation.name.lowercase()}; crop coverage: ${(composition.coverageRatio * 100.0).roundToInt()}%",
                        "Distance-weighted fade applied at every overlap"
                    )
                )
            } finally {
                globalTransforms.forEach(Mat::release)
            }
        } finally {
            workDirectory.deleteRecursively()
        }
    }

    /** Selects frames by sequence and only accepts coherent visual movement. */
    private fun selectSequentialFrames(
        images: List<CapturedImage>,
        workDirectory: File
    ): FrameSelection {
        val normalizer = ImageNormalizer(workDirectory, REGISTRATION_MAX_DIMENSION)
        val acceptedImages = mutableListOf(images.first())
        val registrations = mutableListOf<Registration>()
        val relativeTransforms = mutableListOf<Mat>()
        var anchor = loadImage(images.first(), normalizer)
        var anchorIndex = 0

        try {
            while (anchorIndex < images.lastIndex) {
                var chosen: Candidate? = null
                val endIndex = min(images.lastIndex, anchorIndex + MAX_LOOKAHEAD)

                for (candidateIndex in (anchorIndex + 1)..endIndex) {
                    val candidateImage = loadImage(images[candidateIndex], normalizer)
                    val registration = try {
                        estimateRegistration(anchor, candidateImage)
                    } catch (_: RuntimeException) {
                        null
                    }

                    if (registration == null || registration.progressPixels < MIN_PROGRESS_PIXELS) {
                        registration?.transform?.release()
                        candidateImage.release()
                        continue
                    }

                    val candidate = Candidate(candidateIndex, candidateImage, registration)
                    if (chosen == null || candidate.registration.score > chosen.registration.score) {
                        chosen?.registration?.transform?.release()
                        chosen?.image?.release()
                        chosen = candidate
                    } else {
                        registration.transform.release()
                        candidateImage.release()
                    }

                    if (registration.progressPixels >= MIN_PROGRESS_PIXELS * 1.5) break
                }

                val selected = chosen ?: error(
                    "No coherent sequential overlap after frame ${images[anchorIndex].sequence}; " +
                        "the movement may be too small, too fast, or contain excessive parallax"
                )
                acceptedImages += images[selected.index]
                registrations += selected.registration
                relativeTransforms += selected.registration.transform
                anchor.release()
                anchor = selected.image
                anchorIndex = selected.index
            }
        } catch (error: Throwable) {
            anchor.release()
            relativeTransforms.forEach(Mat::release)
            throw error
        }

        anchor.release()
        return FrameSelection(acceptedImages, relativeTransforms, registrations)
    }

    private fun loadImage(image: CapturedImage, normalizer: ImageNormalizer): Mat {
        val normalized = normalizer.normalize(image)
        return try {
            Imgcodecs.imread(normalized.absolutePath, Imgcodecs.IMREAD_COLOR).also {
                check(!it.empty()) { "Unable to read ${image.file.name}" }
            }
        } finally {
            normalized.delete()
        }
    }

    private fun estimateRegistration(anchor: Mat, current: Mat): Registration? {
        for (detector in Detector.values()) {
            val anchorFeatures = extractFeatures(anchor, detector) ?: continue
            val currentFeatures = extractFeatures(current, detector)
            if (currentFeatures == null) {
                anchorFeatures.release()
                continue
            }

            try {
                val matches = mutualRatioMatches(
                    currentFeatures.descriptors,
                    anchorFeatures.descriptors,
                    detector
                )
                if (matches.size < MIN_GOOD_MATCHES) continue

                val anchorKeypoints = anchorFeatures.keypoints.toArray()
                val currentKeypoints = currentFeatures.keypoints.toArray()
                val source = MatOfPoint2f(*matches.map { currentKeypoints[it.queryIdx].pt }.toTypedArray())
                val destination = MatOfPoint2f(*matches.map { anchorKeypoints[it.trainIdx].pt }.toTypedArray())
                val inlierMask = Mat()
                try {
                    val transform = Geometry.findHomography(
                        source,
                        destination,
                        Geometry.RANSAC,
                        RANSAC_REPROJECTION_THRESHOLD,
                        inlierMask
                    )
                    if (transform.empty()) {
                        transform.release()
                        continue
                    }

                    val inlierCount = countInliers(inlierMask, matches.size)
                    val inlierRatio = inlierCount.toDouble() / matches.size.toDouble()
                    val reprojectionError = reprojectionError(source, destination, transform, inlierMask)
                    val progressPixels = cornerProgress(current, transform)
                    val rotationDegrees = rotationDegrees(current, transform)
                    if (
                        inlierCount < 5 ||
                        inlierRatio < MIN_INLIER_RATIO ||
                        reprojectionError > MAX_REPROJECTION_ERROR ||
                        !isTransformStable(transform, current)
                    ) {
                        transform.release()
                        continue
                    }

                    return Registration(
                        transform = transform,
                        detector = detector.name,
                        inlierRatio = inlierRatio,
                        reprojectionError = reprojectionError,
                        progressPixels = progressPixels,
                        rotationDegrees = rotationDegrees,
                        score = inlierRatio * 100.0 + inlierCount - reprojectionError
                    )
                } finally {
                    source.release()
                    destination.release()
                    inlierMask.release()
                }
            } finally {
                anchorFeatures.release()
                currentFeatures.release()
            }
        }
        return null
    }

    private fun extractFeatures(image: Mat, detector: Detector): FeatureSet? {
        val gray = Mat()
        val keypoints = MatOfKeyPoint()
        val descriptors = Mat()
        return try {
            Imgproc.cvtColor(image, gray, Imgproc.COLOR_BGR2GRAY)
            when (detector) {
                Detector.SIFT -> SIFT.create(MAX_FEATURES).detectAndCompute(gray, Mat(), keypoints, descriptors)
                Detector.ORB -> ORB.create(MAX_FEATURES).detectAndCompute(gray, Mat(), keypoints, descriptors)
            }
            if (keypoints.empty() || descriptors.empty()) {
                keypoints.release()
                descriptors.release()
                null
            } else {
                FeatureSet(keypoints, descriptors)
            }
        } catch (_: RuntimeException) {
            keypoints.release()
            descriptors.release()
            null
        } finally {
            gray.release()
        }
    }

    private fun mutualRatioMatches(
        current: Mat,
        anchor: Mat,
        detector: Detector
    ): List<DMatch> {
        val matcher = DescriptorMatcher.create(
            if (detector == Detector.ORB) DescriptorMatcher.BRUTEFORCE_HAMMING else DescriptorMatcher.BRUTEFORCE
        )
        val forward = mutableListOf<MatOfDMatch>()
        val reverse = mutableListOf<MatOfDMatch>()
        try {
            matcher.knnMatch(current, anchor, forward, 2)
            matcher.knnMatch(anchor, current, reverse, 2)
            val reverseBest = reverse.mapIndexedNotNull { queryIndex, pair ->
                pair.toArray().firstOrNull()?.let { queryIndex to it.trainIdx }
            }.toMap()
            val ratioMatches = forward.mapNotNull { pair ->
                val candidates = pair.toArray()
                val first = candidates.getOrNull(0)
                val second = candidates.getOrNull(1)
                if (
                    first != null && second != null &&
                    first.distance < 0.80f * second.distance
                ) first else null
            }
            val mutualMatches = forward.mapIndexedNotNull { queryIndex, pair ->
                val candidates = pair.toArray()
                val first = candidates.getOrNull(0)
                val second = candidates.getOrNull(1)
                if (
                    first != null && second != null &&
                    first.distance < 0.80f * second.distance &&
                    reverseBest[first.trainIdx] == queryIndex
                ) first else null
            }
            return if (mutualMatches.size >= MIN_GOOD_MATCHES) mutualMatches else ratioMatches
        } finally {
            forward.forEach(MatOfDMatch::release)
            reverse.forEach(MatOfDMatch::release)
        }
    }

    private fun countInliers(mask: Mat, size: Int): Int {
        if (mask.empty()) return 0
        return (0 until size).count { index ->
            val value = if (mask.rows() == 1) mask.get(0, index) else mask.get(index, 0)
            value?.firstOrNull()?.let { it > 0.0 } == true
        }
    }

    private fun reprojectionError(
        source: MatOfPoint2f,
        destination: MatOfPoint2f,
        transform: Mat,
        mask: Mat
    ): Double {
        val projected = MatOfPoint2f()
        return try {
            Core.perspectiveTransform(source, projected, transform)
            val expected = destination.toArray()
            projected.toArray().mapIndexedNotNull { index, point ->
                if (isInlier(mask, index)) {
                    val dx = point.x - expected[index].x
                    val dy = point.y - expected[index].y
                    sqrt(dx * dx + dy * dy)
                } else null
            }.average()
        } finally {
            projected.release()
        }
    }

    private fun isInlier(mask: Mat, index: Int): Boolean {
        if (mask.empty()) return false
        val value = if (mask.rows() == 1) mask.get(0, index) else mask.get(index, 0)
        return value?.firstOrNull()?.let { it > 0.0 } == true
    }

    private fun cornerProgress(image: Mat, transform: Mat): Double {
        val corners = cornersFor(image)
        val original = corners.toArray()
        val transformed = MatOfPoint2f()
        return try {
            Core.perspectiveTransform(corners, transformed, transform)
            transformed.toArray().mapIndexed { index, point -> distance(original[index], point) }.average()
        } finally {
            corners.release()
            transformed.release()
        }
    }

    private fun rotationDegrees(image: Mat, transform: Mat): Double {
        val corners = MatOfPoint2f(Point(0.0, 0.0), Point(image.cols().toDouble(), 0.0))
        val transformed = MatOfPoint2f()
        return try {
            Core.perspectiveTransform(corners, transformed, transform)
            val points = transformed.toArray()
            atan2(points[1].y - points[0].y, points[1].x - points[0].x) * 180.0 / PI
        } finally {
            corners.release()
            transformed.release()
        }
    }

    private fun isTransformStable(transform: Mat, image: Mat): Boolean {
        if (transform.rows() != 3 || transform.cols() != 3) return false
        val corners = cornersFor(image)
        val transformed = MatOfPoint2f()
        return try {
            Core.perspectiveTransform(corners, transformed, transform)
            val points = transformed.toArray()
            if (points.size != 4 || points.any { !it.x.isFinite() || !it.y.isFinite() }) return false
            val edges = listOf(
                distance(points[0], points[1]),
                distance(points[1], points[2]),
                distance(points[2], points[3]),
                distance(points[3], points[0])
            )
            val shortest = edges.minOrNull() ?: return false
            val longest = edges.maxOrNull() ?: return false
            val maxCoordinate = maxOf(image.cols(), image.rows()) * 5.0
            shortest > 20.0 && longest / shortest < 2.5 &&
                points.all { abs(it.x) < maxCoordinate && abs(it.y) < maxCoordinate }
        } catch (_: RuntimeException) {
            false
        } finally {
            corners.release()
            transformed.release()
        }
    }

    private fun composeGlobalTransforms(relativeTransforms: List<Mat>): MutableList<Mat> {
        val result = mutableListOf(Mat.eye(3, 3, CvType.CV_64F))
        return try {
            relativeTransforms.forEach { relative ->
                val composed = Mat()
                multiply(result.last(), relative, composed)
                result += composed
            }
            result
        } catch (error: Throwable) {
            result.forEach(Mat::release)
            throw error
        } finally {
            relativeTransforms.forEach(Mat::release)
        }
    }

    private fun correctGlobalTilt(transforms: List<Mat>, firstImage: CapturedImage) {
        val size = normalizedSize(firstImage, REGISTRATION_MAX_DIMENSION)
        val angles = transforms.drop(1).mapNotNull { transform ->
            val corners = MatOfPoint2f(Point(0.0, 0.0), Point(size.first.toDouble(), 0.0))
            val projected = MatOfPoint2f()
            try {
                Core.perspectiveTransform(corners, projected, transform)
                val points = projected.toArray()
                atan2(points[1].y - points[0].y, points[1].x - points[0].x) * 180.0 / PI
            } catch (_: RuntimeException) {
                null
            } finally {
                corners.release()
                projected.release()
            }
        }
        if (angles.isEmpty()) return
        val median = angles.sorted()[angles.size / 2]
        if (abs(median) < 0.2 || abs(median) > MAX_AUTOMATIC_TILT_CORRECTION) return

        val center = Point(size.first / 2.0, size.second / 2.0)
        val correction2d = Geometry.getRotationMatrix2D(center, -median, 1.0)
        val correction = Mat.eye(3, 3, CvType.CV_64F)
        correction2d.copyTo(correction.submat(0, 2, 0, 3))
        correction2d.release()
        try {
            transforms.forEach { transform ->
                val corrected = Mat()
                multiply(correction, transform, corrected)
                corrected.copyTo(transform)
                corrected.release()
            }
        } finally {
            correction.release()
        }
    }

    private fun composeWithFeatheredBlend(
        images: List<CapturedImage>,
        registrationTransforms: List<Mat>,
        workDirectory: File,
        output: File
    ): CompositionResult {
        val normalizer = ImageNormalizer(workDirectory, COMPOSITION_MAX_DIMENSION)
        val sizes = images.map { image ->
            val normalized = normalizer.normalize(image)
            val probe = try {
                Imgcodecs.imread(normalized.absolutePath, Imgcodecs.IMREAD_COLOR).also {
                    check(!it.empty()) { "Unable to read ${image.file.name}" }
                }
            } finally {
                normalized.delete()
            }
            try {
                probe.width() to probe.height()
            } finally {
                probe.release()
            }
        }

        val transforms = registrationTransforms.mapIndexed { index, transform ->
            scaleTransform(
                transform,
                normalizedSize(images.first(), REGISTRATION_MAX_DIMENSION),
                normalizedSize(images[index], REGISTRATION_MAX_DIMENSION),
                sizes.first(),
                sizes[index]
            )
        }
        val bounds = transformedBounds(sizes, transforms)
        val canvasWidth = ceil(bounds.maxX - bounds.minX).toInt()
        val canvasHeight = ceil(bounds.maxY - bounds.minY).toInt()
        check(canvasWidth in 1..MAX_CANVAS_DIMENSION && canvasHeight in 1..MAX_CANVAS_DIMENSION) {
            "Estimated panorama canvas is too large (${canvasWidth}x${canvasHeight})"
        }
        check(canvasWidth.toLong() * canvasHeight <= MAX_CANVAS_PIXELS) {
            "Estimated panorama requires too much memory (${canvasWidth}x${canvasHeight})"
        }

        val translation = Mat.eye(3, 3, CvType.CV_64F).also {
            it.put(0, 2, -bounds.minX)
            it.put(1, 2, -bounds.minY)
        }
        val accumulator = Mat.zeros(canvasHeight, canvasWidth, CvType.CV_32FC3)
        val weightAccumulator = Mat.zeros(canvasHeight, canvasWidth, CvType.CV_32FC1)
        val reference = loadImage(images.first(), normalizer)
        val referenceMean = try {
            Core.mean(reference)
        } finally {
            reference.release()
        }

        try {
            images.forEachIndexed { index, image ->
                val source = loadImage(image, normalizer)
                try {
                    blendFrame(
                        source = source,
                        transform = transforms[index],
                        translation = translation,
                        canvasWidth = canvasWidth,
                        canvasHeight = canvasHeight,
                        referenceMean = referenceMean,
                        accumulator = accumulator,
                        weightAccumulator = weightAccumulator
                    )
                } finally {
                    source.release()
                }
            }

            val validMask = Mat()
            val validThreshold = Mat()
            val safeWeight = Mat()
            val safeWeight3 = Mat()
            val normalized = Mat()
            val points = MatOfPoint()
            try {
                Imgproc.threshold(weightAccumulator, validThreshold, 0.01, 255.0, Imgproc.THRESH_BINARY)
                validThreshold.convertTo(validMask, CvType.CV_8UC1)
                check(Core.countNonZero(validMask) > 0) { "No valid panorama coverage was produced" }
                Core.findNonZero(validMask, points)
                val boundsRect = boundingRect(points, canvasWidth, canvasHeight)
                val rect = largestCoveredRect(validMask, canvasWidth, canvasHeight)
                val coverage = Core.countNonZero(validMask).toDouble() /
                    (boundsRect.width * boundsRect.height).toDouble()
                check(coverage >= 0.55) {
                    "Panorama contains an internal coverage gap (${(coverage * 100.0).roundToInt()}%)"
                }

                Core.add(weightAccumulator, Scalar(1.0e-6), safeWeight)
                Core.merge(listOf(safeWeight, safeWeight, safeWeight), safeWeight3)
                Core.divide(accumulator, safeWeight3, normalized)
                val cropped = normalized.submat(rect)
                val output8 = Mat()
                try {
                    cropped.convertTo(output8, CvType.CV_8UC3)
                    check(Imgcodecs.imwrite(output.absolutePath, output8)) {
                        "Unable to save panorama result"
                    }
                } finally {
                    output8.release()
                    cropped.release()
                }
                return CompositionResult(rect.width, rect.height, coverage)
            } finally {
                validMask.release()
                validThreshold.release()
                safeWeight.release()
                safeWeight3.release()
                normalized.release()
                points.release()
            }
        } finally {
            translation.release()
            accumulator.release()
            weightAccumulator.release()
            transforms.forEach(Mat::release)
        }
    }

    private fun blendFrame(
        source: Mat,
        transform: Mat,
        translation: Mat,
        canvasWidth: Int,
        canvasHeight: Int,
        referenceMean: Scalar,
        accumulator: Mat,
        weightAccumulator: Mat
    ) {
        val adjusted = Mat()
        val warped = Mat.zeros(canvasHeight, canvasWidth, CvType.CV_8UC3)
        val sourceMask = Mat.ones(source.rows(), source.cols(), CvType.CV_8UC1)
        val mask = Mat.zeros(canvasHeight, canvasWidth, CvType.CV_8UC1)
        val binaryMask = Mat.zeros(canvasHeight, canvasWidth, CvType.CV_8UC1)
        val distance = Mat()
        val weight = Mat()
        val warpedFloat = Mat()
        val corrected = Mat()
        val weight3 = Mat()
        val weightedPixels = Mat()
        val inverseMask = Mat()
        try {
            multiply(translation, transform, adjusted)
            val canvasSize = Size(canvasWidth.toDouble(), canvasHeight.toDouble())
            Imgproc.warpPerspective(source, warped, adjusted, canvasSize)
            Imgproc.warpPerspective(
                sourceMask,
                mask,
                adjusted,
                canvasSize,
                Imgproc.INTER_NEAREST,
                Core.BORDER_CONSTANT,
                Scalar(0.0)
            )
            Imgproc.threshold(mask, binaryMask, 0.0, 255.0, Imgproc.THRESH_BINARY)
            Imgproc.distanceTransform(binaryMask, distance, DISTANCE_L2, Imgproc.DIST_MASK_3)
            Core.add(distance, Scalar(1.0), weight)
            Core.bitwise_not(binaryMask, inverseMask)
            weight.setTo(Scalar(0.0), inverseMask)

            warped.convertTo(warpedFloat, CvType.CV_32FC3)
            val currentMean = Core.mean(source)
            val gain = Scalar(
                channelGain(referenceMean.`val`[0], currentMean.`val`[0]),
                channelGain(referenceMean.`val`[1], currentMean.`val`[1]),
                channelGain(referenceMean.`val`[2], currentMean.`val`[2])
            )
            Core.multiply(warpedFloat, gain, corrected)
            Core.merge(listOf(weight, weight, weight), weight3)
            Core.multiply(corrected, weight3, weightedPixels)
            Core.add(accumulator, weightedPixels, accumulator)
            Core.add(weightAccumulator, weight, weightAccumulator)
        } finally {
            adjusted.release()
            warped.release()
            sourceMask.release()
            mask.release()
            binaryMask.release()
            distance.release()
            weight.release()
            warpedFloat.release()
            corrected.release()
            weight3.release()
            weightedPixels.release()
            inverseMask.release()
        }
    }

    private fun channelGain(reference: Double, current: Double): Double {
        if (current < 1.0) return 1.0
        return (reference / current).coerceIn(0.80, 1.25)
    }

    private fun transformedBounds(sizes: List<Pair<Int, Int>>, transforms: List<Mat>): Bounds {
        var minX = 0.0
        var minY = 0.0
        var maxX = sizes.first().first.toDouble()
        var maxY = sizes.first().second.toDouble()
        sizes.forEachIndexed { index, size ->
            val corners = MatOfPoint2f(
                Point(0.0, 0.0),
                Point(size.first.toDouble(), 0.0),
                Point(size.first.toDouble(), size.second.toDouble()),
                Point(0.0, size.second.toDouble())
            )
            val projected = MatOfPoint2f()
            try {
                Core.perspectiveTransform(corners, projected, transforms[index])
                projected.toArray().forEach { point ->
                    check(point.x.isFinite() && point.y.isFinite()) { "Invalid image transform" }
                    check(abs(point.x) <= MAX_CANVAS_DIMENSION && abs(point.y) <= MAX_CANVAS_DIMENSION) {
                        "Image transform is unstable"
                    }
                    minX = min(minX, point.x)
                    minY = min(minY, point.y)
                    maxX = max(maxX, point.x)
                    maxY = max(maxY, point.y)
                }
            } finally {
                corners.release()
                projected.release()
            }
        }
        return Bounds(minX, minY, maxX, maxY)
    }

    private fun scaleTransform(
        transform: Mat,
        firstRegistration: Pair<Int, Int>,
        sourceRegistration: Pair<Int, Int>,
        firstComposition: Pair<Int, Int>,
        sourceComposition: Pair<Int, Int>
    ): Mat {
        val destinationScale = scaleMatrix(
            firstComposition.first.toDouble() / firstRegistration.first,
            firstComposition.second.toDouble() / firstRegistration.second
        )
        val sourceInverseScale = scaleMatrix(
            sourceRegistration.first.toDouble() / sourceComposition.first,
            sourceRegistration.second.toDouble() / sourceComposition.second
        )
        val intermediate = Mat()
        val scaled = Mat()
        multiply(transform, sourceInverseScale, intermediate)
        multiply(destinationScale, intermediate, scaled)
        destinationScale.release()
        sourceInverseScale.release()
        intermediate.release()
        return scaled
    }

    private fun scaleMatrix(x: Double, y: Double): Mat {
        return Mat.eye(3, 3, CvType.CV_64F).also {
            it.put(0, 0, x)
            it.put(1, 1, y)
        }
    }

    private fun normalizedSize(image: CapturedImage, maxDimension: Int): Pair<Int, Int> {
        var sample = 1
        while (maxOf(image.width / sample, image.height / sample) > maxDimension) sample *= 2
        return max(1, image.width / sample) to max(1, image.height / sample)
    }

    private fun boundingRect(points: MatOfPoint, width: Int, height: Int): Rect {
        val all = points.toArray()
        val minX = all.minOf { it.x }.roundToInt().coerceIn(0, width - 1)
        val minY = all.minOf { it.y }.roundToInt().coerceIn(0, height - 1)
        val maxX = all.maxOf { it.x }.roundToInt().coerceIn(minX + 1, width)
        val maxY = all.maxOf { it.y }.roundToInt().coerceIn(minY + 1, height)
        return Rect(minX, minY, maxX - minX, maxY - minY)
    }

    /**
     * Returns the largest axis-aligned rectangle whose pixels are all covered by
     * at least one warped frame. The outer bounding box may contain triangular
     * uncovered corners when a homography tilts a frame; using it directly would
     * write those corners as black pixels into the JPEG.
     */
    private fun largestCoveredRect(mask: Mat, width: Int, height: Int): Rect {
        val heights = IntArray(width)
        val stack = IntArray(width + 1)
        val row = ByteArray(width)
        var bestArea = 0L
        var bestLeft = 0
        var bestTop = 0
        var bestWidth = 0
        var bestHeight = 0

        for (y in 0 until height) {
            mask.get(y, 0, row)
            for (x in 0 until width) {
                heights[x] = if ((row[x].toInt() and 0xFF) > 0) heights[x] + 1 else 0
            }

            var stackSize = 0
            for (x in 0..width) {
                val currentHeight = if (x == width) 0 else heights[x]
                while (stackSize > 0 && heights[stack[stackSize - 1]] > currentHeight) {
                    val barIndex = stack[--stackSize]
                    val left = if (stackSize == 0) 0 else stack[stackSize - 1] + 1
                    val rectangleWidth = x - left
                    val rectangleHeight = heights[barIndex]
                    val area = rectangleWidth.toLong() * rectangleHeight.toLong()
                    if (area > bestArea) {
                        bestArea = area
                        bestLeft = left
                        bestTop = y - rectangleHeight + 1
                        bestWidth = rectangleWidth
                        bestHeight = rectangleHeight
                    }
                }
                if (x < width) {
                    stack[stackSize++] = x
                }
            }
        }

        check(bestWidth > 0 && bestHeight > 0) {
            "No fully covered panorama rectangle was produced"
        }
        return Rect(bestLeft, bestTop, bestWidth, bestHeight)
    }

    private fun cornersFor(image: Mat): MatOfPoint2f {
        return MatOfPoint2f(
            Point(0.0, 0.0),
            Point(image.cols().toDouble(), 0.0),
            Point(image.cols().toDouble(), image.rows().toDouble()),
            Point(0.0, image.rows().toDouble())
        )
    }

    private fun distance(first: Point, second: Point): Double {
        return sqrt((first.x - second.x) * (first.x - second.x) + (first.y - second.y) * (first.y - second.y))
    }

    private fun multiply(left: Mat, right: Mat, destination: Mat) {
        val empty = Mat()
        try {
            Core.gemm(left, right, 1.0, empty, 0.0, destination)
        } finally {
            empty.release()
        }
    }

    private enum class Detector {
        SIFT,
        ORB
    }

    private data class FeatureSet(
        val keypoints: MatOfKeyPoint,
        val descriptors: Mat
    ) {
        fun release() {
            keypoints.release()
            descriptors.release()
        }
    }

    private data class Registration(
        val transform: Mat,
        val detector: String,
        val inlierRatio: Double,
        val reprojectionError: Double,
        val progressPixels: Double,
        val rotationDegrees: Double,
        val score: Double
    )

    private data class Candidate(
        val index: Int,
        val image: Mat,
        val registration: Registration
    )

    private data class FrameSelection(
        val images: List<CapturedImage>,
        val relativeTransforms: List<Mat>,
        val registrations: List<Registration>
    )

    private data class Bounds(
        val minX: Double,
        val minY: Double,
        val maxX: Double,
        val maxY: Double
    )

    private data class CompositionResult(
        val width: Int,
        val height: Int,
        val coverageRatio: Double
    )
}
