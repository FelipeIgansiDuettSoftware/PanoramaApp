package com.panoramaapp.ui

import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.annotation.DrawableRes
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.LifecycleOwner
import androidx.exifinterface.media.ExifInterface
import com.panoramaapp.R
import com.panoramaapp.panorama.camera.AlignmentGuideState
import com.panoramaapp.panorama.camera.CameraController
import com.panoramaapp.panorama.camera.CameraState
import com.panoramaapp.panorama.camera.CapturedPhoto
import com.panoramaapp.panorama.capture.CaptureOrientation
import com.panoramaapp.panorama.capture.CapturedImage

@Composable
fun CaptureScreen(
    images: List<CapturedImage>,
    cameraState: CameraState,
    hasCameraPermission: Boolean,
    lifecycleOwner: LifecycleOwner,
    cameraController: CameraController,
    orientation: CaptureOrientation,
    isCapturing: Boolean,
    alignment: AlignmentGuideState,
    onCapturePhoto: () -> Unit,
    onRemoveLast: () -> Unit,
    onProcess: () -> Unit,
    onCameraReady: () -> Unit,
    onCameraError: (Throwable) -> Unit,
    onRequestPermission: () -> Unit
) {
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val cameraReady = cameraState == CameraState.Ready
    val canCapture = images.isEmpty() || alignment.captureAllowed
    val cameraContent: @Composable (Modifier) -> Unit = { modifier ->
        CameraViewport(
            modifier = modifier,
            cameraState = cameraState,
            hasCameraPermission = hasCameraPermission,
            lifecycleOwner = lifecycleOwner,
            cameraController = cameraController,
            onCameraReady = onCameraReady,
            onCameraError = onCameraError,
            onRequestPermission = onRequestPermission
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (isLandscape) {
            LandscapeCaptureLayout(
                images = images,
                isCapturing = isCapturing,
                alignment = alignment,
                cameraReady = cameraReady,
                canCapture = canCapture,
                cameraContent = cameraContent,
                onCapturePhoto = onCapturePhoto,
                onRemoveLast = onRemoveLast,
                onProcess = onProcess
            )
        } else {
            PortraitCaptureLayout(
                images = images,
                isCapturing = isCapturing,
                alignment = alignment,
                cameraReady = cameraReady,
                canCapture = canCapture,
                cameraContent = cameraContent,
                onCapturePhoto = onCapturePhoto,
                onRemoveLast = onRemoveLast,
                onProcess = onProcess
            )
        }
    }
}

@Composable
private fun PortraitCaptureLayout(
    images: List<CapturedImage>,
    isCapturing: Boolean,
    alignment: AlignmentGuideState,
    cameraReady: Boolean,
    canCapture: Boolean,
    cameraContent: @Composable (Modifier) -> Unit,
    onCapturePhoto: () -> Unit,
    onRemoveLast: () -> Unit,
    onProcess: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        Box(Modifier) {
            cameraContent(
                Modifier.fillMaxSize()
            )
            AlignmentGuideOverlay(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 156.dp),
                state = alignment
            )
            PortraitCaptureControls(
                modifier = Modifier.align(Alignment.BottomCenter),
                images = images,
                isCapturing = isCapturing,
                cameraReady = cameraReady,
                canCapture = canCapture,
                onCapturePhoto = onCapturePhoto,
                onRemoveLast = onRemoveLast,
                onProcess = onProcess
            )
        }
    }
}

@Composable
private fun LandscapeCaptureLayout(
    images: List<CapturedImage>,
    isCapturing: Boolean,
    alignment: AlignmentGuideState,
    cameraReady: Boolean,
    canCapture: Boolean,
    cameraContent: @Composable (Modifier) -> Unit,
    onCapturePhoto: () -> Unit,
    onRemoveLast: () -> Unit,
    onProcess: () -> Unit
) {
    Row(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
        ) {
            cameraContent(
                Modifier.fillMaxSize()
            )
            AlignmentGuideOverlay(
                modifier = Modifier.align(Alignment.BottomCenter),
                state = alignment
            )
            LandscapeSideRail(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .wrapContentSize()
                    .background(Color.Black.copy(alpha = 0.5f)),
                images = images,
                isCapturing = isCapturing,
                cameraReady = cameraReady,
                canCapture = canCapture,
                onCapturePhoto = onCapturePhoto,
                onRemoveLast = onRemoveLast,
                onProcess = onProcess
            )
        }
    }
}

@Composable
private fun CameraViewport(
    modifier: Modifier,
    cameraState: CameraState,
    hasCameraPermission: Boolean,
    lifecycleOwner: LifecycleOwner,
    cameraController: CameraController,
    onCameraReady: () -> Unit,
    onCameraError: (Throwable) -> Unit,
    onRequestPermission: () -> Unit
) {
    Box(
        modifier = modifier.background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        if ((cameraState == CameraState.Starting && hasCameraPermission) || cameraState == CameraState.Ready) {
            CameraPreview(
                lifecycleOwner = lifecycleOwner,
                cameraController = cameraController,
                onReady = onCameraReady,
                onError = onCameraError
            )
        } else {
            CameraStateMessage(
                state = if (!hasCameraPermission) CameraState.PermissionRequired else cameraState,
                onRequestPermission = onRequestPermission
            )
        }
    }
}

@Composable
private fun AlignmentGuideOverlay(
    modifier: Modifier,
    state: AlignmentGuideState
) {
    if (!state.active) return
    val message = when {
        state.guidance == com.panoramaapp.panorama.camera.AlignmentGuidance.READY -> stringResource(R.string.capture_ready_hint)
        state.guidance == com.panoramaapp.panorama.camera.AlignmentGuidance.CAPTURE_READY -> stringResource(R.string.alignment_aligned)
        state.guidance == com.panoramaapp.panorama.camera.AlignmentGuidance.OUT_OF_AXIS -> stringResource(R.string.alignment_keep_axis)
        state.guidance == com.panoramaapp.panorama.camera.AlignmentGuidance.KEEP_DIRECTION -> stringResource(R.string.alignment_keep_direction)
        state.guidance == com.panoramaapp.panorama.camera.AlignmentGuidance.INSUFFICIENT_OVERLAP -> stringResource(R.string.alignment_insufficient_overlap)
        state.guidance == com.panoramaapp.panorama.camera.AlignmentGuidance.MOVE_POSITIVE -> when (state.axis) {
            com.panoramaapp.panorama.camera.AlignmentAxis.HORIZONTAL -> stringResource(R.string.alignment_move_right)
            com.panoramaapp.panorama.camera.AlignmentAxis.VERTICAL -> stringResource(R.string.alignment_move_down)
        }
        state.guidance == com.panoramaapp.panorama.camera.AlignmentGuidance.MOVE_NEGATIVE -> when (state.axis) {
            com.panoramaapp.panorama.camera.AlignmentAxis.HORIZONTAL -> stringResource(R.string.alignment_move_left)
            com.panoramaapp.panorama.camera.AlignmentAxis.VERTICAL -> stringResource(R.string.alignment_move_up)
        }
        !state.hasMatch -> stringResource(R.string.alignment_find_overlap)
        else -> stringResource(R.string.alignment_adjust_height)
    }
    val guideColor = when {
        state.guidance == com.panoramaapp.panorama.camera.AlignmentGuidance.READY -> Color.White.copy(alpha = 0.72f)
        state.captureAllowed -> Color(0xFF78E08F)
        !state.hasMatch -> Color.White.copy(alpha = 0.72f)
        else -> Color(0xFFFFC857)
    }
    var showMessage by remember { androidx.compose.runtime.mutableStateOf(true) }
    LaunchedEffect(state.guidance) {
        showMessage = true
        if (state.guidance != com.panoramaapp.panorama.camera.AlignmentGuidance.READY &&
            state.guidance != com.panoramaapp.panorama.camera.AlignmentGuidance.CAPTURE_READY
        ) {
            kotlinx.coroutines.delay(1_800)
            showMessage = false
        }
    }
    Column(
        modifier = modifier
            .width(240.dp)
            .background(Color.Black.copy(alpha = 0.68f), RoundedCornerShape(18.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        if (showMessage) {
            Text(
                text = message,
                color = guideColor,
                style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.Center
            )
        }
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
        ) {
            val leftX = 26.dp.toPx()
            val rightX = size.width - 26.dp.toPx()
            val horizontal = state.axis == com.panoramaapp.panorama.camera.AlignmentAxis.HORIZONTAL
            val referenceX = if (horizontal) size.width * state.referenceX.coerceIn(0.0, 1.0) else leftX
            val currentX = if (horizontal) size.width * state.currentX.coerceIn(0.0, 1.0) else rightX
            val referenceY = if (horizontal) size.height / 2f else size.height * state.referenceY.coerceIn(0.0, 1.0)
            val currentY = if (horizontal) size.height / 2f else size.height * state.currentY.coerceIn(0.0, 1.0)
            drawCircle(
                color = guideColor,
                radius = 12.dp.toPx(),
                center = Offset(referenceX.toFloat(), referenceY.toFloat()),
                style = Stroke(width = 2.dp.toPx())
            )
            if (state.guidance != com.panoramaapp.panorama.camera.AlignmentGuidance.READY) {
                drawLine(
                    color = guideColor.copy(alpha = 0.75f),
                    start = Offset(referenceX.toFloat(), referenceY.toFloat()),
                    end = Offset(currentX.toFloat(), currentY.toFloat()),
                    strokeWidth = 2.dp.toPx()
                )
                drawCircle(
                    color = guideColor,
                    radius = 12.dp.toPx(),
                    center = Offset(currentX.toFloat(), currentY.toFloat()),
                    style = Stroke(width = 2.dp.toPx())
                )
            }
        }
    }
}

@Composable
private fun CounterOverlay(modifier: Modifier, count: Int) {
    Box(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.58f), CircleShape)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(
            text = stringResource(R.string.capture_counter, count),
            color = Color.White,
            style = MaterialTheme.typography.labelLarge
        )
    }
}

@Composable
private fun PortraitCaptureControls(
    modifier: Modifier,
    images: List<CapturedImage>,
    isCapturing: Boolean,
    cameraReady: Boolean,
    canCapture: Boolean,
    onCapturePhoto: () -> Unit,
    onRemoveLast: () -> Unit,
    onProcess: () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.5f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (images.isNotEmpty()) {
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(78.dp),
                contentPadding = PaddingValues(horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(images, key = { it.sequence }) { image -> Thumbnail(image) }
            }
        }
        CapturePrimaryActions(
            images = images,
            isCapturing = isCapturing,
            cameraReady = cameraReady,
            canCapture = canCapture,
            onCapturePhoto = onCapturePhoto,
            onRemoveLast = onRemoveLast,
            enabled = images.size >= 2 && !isCapturing,
            onProcess = onProcess
        )
    }
}

@Composable
private fun LandscapeSideRail(
    modifier: Modifier,
    images: List<CapturedImage>,
    isCapturing: Boolean,
    cameraReady: Boolean,
    canCapture: Boolean,
    onCapturePhoto: () -> Unit,
    onRemoveLast: () -> Unit,
    onProcess: () -> Unit
) {
    Row(
        modifier = modifier.padding(
            horizontal = 8.dp,
            vertical = 10.dp
        ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Column{
            CounterOverlay(
                modifier = Modifier.align(Alignment.CenterHorizontally),
                count = images.size
            )
            if (images.isNotEmpty()) {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f),
                    contentPadding = PaddingValues(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    items(images, key = { it.sequence }) { image -> Thumbnail(image) }
                }
            } else {
                Spacer(modifier = Modifier.weight(1f))
            }
        }



        Column {
            RailActionButton(
                enabled = images.isNotEmpty() && !isCapturing,
                onClick = onRemoveLast,
                icon = R.drawable.ic_btn_remove_trash
            )
            RailActionButton(
                enabled = cameraReady && !isCapturing && canCapture,
                onClick = onCapturePhoto,
                icon = R.drawable.ic_btn_camera
            )
            RailActionButton(
                enabled = images.size >= 2 && !isCapturing,
                onClick = onProcess,
                icon = R.drawable.ic_btn_panorama
            )
        }

    }

}

@Composable
private fun CapturePrimaryActions(
    images: List<CapturedImage>,
    isCapturing: Boolean,
    cameraReady: Boolean,
    canCapture: Boolean,
    onCapturePhoto: () -> Unit,
    onRemoveLast: () -> Unit,
    enabled: Boolean,
    onProcess: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedButton(
            onClick = onRemoveLast,
            enabled = images.isNotEmpty() && !isCapturing,
            modifier = Modifier.size(50.dp),
            contentPadding = PaddingValues(0.dp),
            border = BorderStroke(1.dp, Color.Gray)
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_btn_remove_trash),
                contentDescription = null,
                modifier = Modifier.size(30.dp)
            )
        }

        Spacer(Modifier.width(8.dp))
        OutlinedButton(
            onClick = onProcess,
            enabled = enabled,
            modifier = Modifier.size(50.dp),
            contentPadding = PaddingValues(0.dp),
            border = BorderStroke(1.dp, Color.Gray)
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_btn_panorama),
                contentDescription = null,
                modifier = Modifier.size(30.dp),
                tint = Color.Gray
            )
        }
        Spacer(Modifier.width(8.dp))

        OutlinedButton(
            onClick = onCapturePhoto,
            enabled = cameraReady && !isCapturing && canCapture,
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier.size(50.dp)
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_btn_camera),
                contentDescription = null,
                modifier = Modifier.size(30.dp),
                tint = Color.White
            )
        }
    }
}

@Composable
private fun RailActionButton(
    @DrawableRes
    icon: Int,
    enabled: Boolean,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(50.dp),
        contentPadding = PaddingValues(0.dp),
        border = BorderStroke(1.dp, Color.Gray)
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            modifier = Modifier.size(30.dp)
        )
    }
}

@Composable
private fun CameraPreview(
    lifecycleOwner: LifecycleOwner,
    cameraController: CameraController,
    onReady: () -> Unit,
    onError: (Throwable) -> Unit
) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
        },
        update = { previewView ->
            cameraController.bindPreview(lifecycleOwner, previewView, onReady, onError)
        }
    )
}

@Composable
private fun CameraStateMessage(state: CameraState, onRequestPermission: () -> Unit) {
    Card(modifier = Modifier.padding(24.dp)) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            when (state) {
                CameraState.Starting -> {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.camera_starting))
                }

                CameraState.PermissionRequired -> {
                    Text(stringResource(R.string.camera_permission_required))
                    Button(onClick = onRequestPermission) {
                        Text(stringResource(R.string.camera_grant_permission))
                    }
                }

                is CameraState.Unavailable -> {
                    Text(stringResource(R.string.camera_unavailable, state.message))
                }

                is CameraState.Error -> {
                    Text(stringResource(R.string.camera_error, state.message))
                }

                CameraState.Ready -> Unit
            }
        }
    }
}

@Composable
private fun Thumbnail(image: CapturedImage) {
    val bitmap by produceState<android.graphics.Bitmap?>(initialValue = null, image.file) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            BitmapFactory.Options()
                .let { bounds ->
                    bounds.inJustDecodeBounds = true
                    BitmapFactory.decodeFile(image.file.absolutePath, bounds)
                    val sampleSize = thumbnailSampleSize(bounds.outWidth, bounds.outHeight)
                    BitmapFactory.Options()
                        .apply {
                            inSampleSize = sampleSize
                            inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
                        }
                        .let { options ->
                            val decoded = BitmapFactory.decodeFile(
                                image.file.absolutePath,
                                options
                            )
                            if (decoded == null) {
                                null
                            } else {
                                val exifOrientation = ExifInterface(image.file.absolutePath).getAttributeInt(
                                    ExifInterface.TAG_ORIENTATION,
                                    ExifInterface.ORIENTATION_NORMAL
                                )
                                val rotation = when (exifOrientation) {
                                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                                    else -> 0f
                                }
                                if (rotation == 0f) decoded else {
                                    val rotated = android.graphics.Bitmap.createBitmap(
                                        decoded,
                                        0,
                                        0,
                                        decoded.width,
                                        decoded.height,
                                        Matrix().apply { postRotate(rotation) },
                                        true
                                    )
                                    if (rotated !== decoded) decoded.recycle()
                                    rotated
                                }
                            }
                        }
                }
        }
    }
    Box(
        modifier = Modifier
            .size(width = 82.dp, height = 72.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.BottomEnd
    ) {
        bitmap?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = stringResource(
                    R.string.capture_thumbnail_description,
                    image.sequence
                ),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        Text(
            text = image.sequence.toString(),
            color = Color.White,
            modifier = Modifier
                .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(topStart = 8.dp))
                .padding(horizontal = 7.dp, vertical = 3.dp)
        )
    }
}

private fun thumbnailSampleSize(width: Int, height: Int): Int {
    var sampleSize = 1
    while (maxOf(width / sampleSize, height / sampleSize) > 256) sampleSize *= 2
    return sampleSize
}

@Preview(showBackground = true, name = "Capture - permission")
@Composable
private fun CaptureScreenPermissionPreview() {
    CaptureScreen(
        images = listOf(previewCapturedImage(1), previewCapturedImage(2)),
        cameraState = CameraState.PermissionRequired,
        hasCameraPermission = false,
        lifecycleOwner = LocalLifecycleOwner.current,
        cameraController = PreviewCameraController,
        orientation = CaptureOrientation.PORTRAIT,
        isCapturing = false,
        alignment = AlignmentGuideState(),
        onCapturePhoto = {},
        onRemoveLast = {},
        onProcess = {},
        onCameraReady = {},
        onCameraError = {},
        onRequestPermission = {}
    )
}

@Preview(showBackground = true, name = "Capture - ready")
@Composable
private fun CaptureScreenReadyPreview() {
    CaptureScreen(
        images = emptyList(),
        cameraState = CameraState.Ready,
        hasCameraPermission = true,
        lifecycleOwner = LocalLifecycleOwner.current,
        cameraController = PreviewCameraController,
        orientation = CaptureOrientation.PORTRAIT,
        isCapturing = false,
        alignment = AlignmentGuideState(),
        onCapturePhoto = {},
        onRemoveLast = {},
        onProcess = {},
        onCameraReady = {},
        onCameraError = {},
        onRequestPermission = {}
    )
}

@Preview(widthDp = 800, heightDp = 400, showBackground = true, name = "Capture - landscape")
@Composable
private fun CaptureScreenLandscapePreview() {
    CaptureScreen(
        images = listOf(
            previewCapturedImage(1),
            previewCapturedImage(2),
            previewCapturedImage(3),
            previewCapturedImage(4)
        ),
        cameraState = CameraState.Ready,
        hasCameraPermission = true,
        lifecycleOwner = LocalLifecycleOwner.current,
        cameraController = PreviewCameraController,
        orientation = CaptureOrientation.LANDSCAPE,
        isCapturing = true,
        alignment = AlignmentGuideState(
            active = true,
            hasMatch = true,
            referenceY = 0.5,
            currentY = 0.53
        ),
        onCapturePhoto = {},
        onRemoveLast = {},
        onProcess = {},
        onCameraReady = {},
        onCameraError = {},
        onRequestPermission = {}
    )
}

@Preview(showBackground = true, name = "Thumbnail")
@Composable
private fun ThumbnailPreview() {
    Thumbnail(previewCapturedImage(3))
}

@Preview(showBackground = true, name = "Camera permission message")
@Composable
private fun CameraStateMessagePreview() {
    CameraStateMessage(CameraState.PermissionRequired) {}
}

private object PreviewCameraController: CameraController {

    override fun bindPreview(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        onReady: () -> Unit,
        onError: (Throwable) -> Unit
    ) = Unit

    override fun capturePhoto(
        outputDirectory: java.io.File,
        sequence: Int,
        onPhotoSaved: (CapturedPhoto) -> Unit,
        onError: (Throwable) -> Unit
    ) = Unit

    override fun setAlignmentReference(
        referenceFile: java.io.File,
        orientation: CaptureOrientation,
        expectedAxis: com.panoramaapp.panorama.camera.AlignmentAxis?,
        expectedDirection: com.panoramaapp.panorama.camera.AlignmentDirection?,
        onUpdate: (AlignmentGuideState) -> Unit,
        onError: (Throwable) -> Unit
    ) = Unit

    override fun clearAlignmentReference() = Unit

    override fun shutdown() = Unit
}
