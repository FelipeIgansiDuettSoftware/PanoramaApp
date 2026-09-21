package com.panoramaapp.ui

import android.content.res.Configuration
import android.graphics.BitmapFactory
import androidx.camera.view.PreviewView
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.LifecycleOwner
import com.panoramaapp.R
import com.panoramaapp.panorama.camera.CameraController
import com.panoramaapp.panorama.camera.CameraState
import com.panoramaapp.panorama.camera.CapturedPhoto
import com.panoramaapp.panorama.camera.AlignmentGuideState
import com.panoramaapp.panorama.capture.CapturedImage
import com.panoramaapp.panorama.capture.CaptureOrientation

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
            .background(MaterialTheme.colorScheme.background)
    ) {
        if (isLandscape) {
            LandscapeCaptureLayout(
                images = images,
                orientation = orientation,
                isCapturing = isCapturing,
                alignment = alignment,
                cameraReady = cameraReady,
                cameraContent = cameraContent,
                onCapturePhoto = onCapturePhoto,
                onRemoveLast = onRemoveLast,
                onProcess = onProcess
            )
        } else {
            PortraitCaptureLayout(
                images = images,
                orientation = orientation,
                isCapturing = isCapturing,
                alignment = alignment,
                cameraReady = cameraReady,
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
    orientation: CaptureOrientation,
    isCapturing: Boolean,
    alignment: AlignmentGuideState,
    cameraReady: Boolean,
    cameraContent: @Composable (Modifier) -> Unit,
    onCapturePhoto: () -> Unit,
    onRemoveLast: () -> Unit,
    onProcess: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        cameraContent(
            Modifier
                .fillMaxSize()
                .padding(8.dp)
        )
        CaptureStatusOverlay(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(20.dp)
                .fillMaxWidth(0.72f),
            isCapturing = isCapturing,
            orientation = orientation
        )
        CounterOverlay(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(20.dp),
            count = images.size
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
            onCapturePhoto = onCapturePhoto,
            onRemoveLast = onRemoveLast,
            onProcess = onProcess
        )
    }
}

@Composable
private fun LandscapeCaptureLayout(
    images: List<CapturedImage>,
    orientation: CaptureOrientation,
    isCapturing: Boolean,
    alignment: AlignmentGuideState,
    cameraReady: Boolean,
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
                Modifier
                    .fillMaxSize()
                    .padding(8.dp)
            )
            CaptureStatusOverlay(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(20.dp)
                    .fillMaxWidth(0.68f),
                isCapturing = isCapturing,
                orientation = orientation
            )
            AlignmentGuideOverlay(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 20.dp),
                state = alignment
            )
        }
        LandscapeSideRail(
            modifier = Modifier
                .width(132.dp)
                .fillMaxHeight()
                .background(Color.Black.copy(alpha = 0.72f)),
            images = images,
            isCapturing = isCapturing,
            cameraReady = cameraReady,
            onCapturePhoto = onCapturePhoto,
            onRemoveLast = onRemoveLast,
            onProcess = onProcess
        )
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
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color.Black),
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
private fun CaptureStatusOverlay(
    modifier: Modifier,
    orientation: CaptureOrientation,
    isCapturing: Boolean
) {
    Column(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.58f), RoundedCornerShape(16.dp))
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Text(
            text = stringResource(
                if (isCapturing) R.string.capture_saving_hint
                else R.string.capture_ready_hint
            ),
            color = Color.White,
            style = MaterialTheme.typography.labelLarge
        )
        Text(
            text = stringResource(R.string.capture_overlap_hint),
            color = Color.White.copy(alpha = 0.82f),
            style = MaterialTheme.typography.labelSmall
        )
    }
}

@Composable
private fun AlignmentGuideOverlay(
    modifier: Modifier,
    state: AlignmentGuideState
) {
    if (!state.active) return
    val message = when {
        !state.hasMatch -> stringResource(R.string.alignment_find_overlap)
        state.aligned   -> stringResource(R.string.alignment_aligned)
        else            -> stringResource(R.string.alignment_adjust_height)
    }
    val guideColor = when {
        !state.hasMatch -> Color.White.copy(alpha = 0.72f)
        state.aligned   -> Color(0xFF78E08F)
        else            -> Color(0xFFFFC857)
    }
    Column(
        modifier = modifier
            .width(240.dp)
            .background(Color.Black.copy(alpha = 0.68f), RoundedCornerShape(18.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = message,
            color = guideColor,
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center
        )
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
        ) {
            val leftX = 26.dp.toPx()
            val rightX = size.width - 26.dp.toPx()
            val referenceY = size.height * state.referenceY.coerceIn(0.0, 1.0)
            val currentY = size.height * state.currentY.coerceIn(0.0, 1.0)
            drawLine(
                color = guideColor.copy(alpha = 0.75f),
                start = Offset(leftX, referenceY.toFloat()),
                end = Offset(rightX, currentY.toFloat()),
                strokeWidth = 2.dp.toPx()
            )
            drawCircle(
                color = guideColor,
                radius = 12.dp.toPx(),
                center = Offset(leftX, referenceY.toFloat()),
                style = Stroke(width = 2.dp.toPx())
            )
            drawCircle(
                color = guideColor,
                radius = 12.dp.toPx(),
                center = Offset(rightX, currentY.toFloat()),
                style = Stroke(width = 2.dp.toPx())
            )
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
    onCapturePhoto: () -> Unit,
    onRemoveLast: () -> Unit,
    onProcess: () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.72f))
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
            onCapturePhoto = onCapturePhoto,
            onRemoveLast = onRemoveLast
        )
        ProcessingActions(
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
    onCapturePhoto: () -> Unit,
    onRemoveLast: () -> Unit,
    onProcess: () -> Unit
) {
    Column(modifier = modifier.padding(horizontal = 8.dp, vertical = 10.dp)) {
        CounterOverlay(
            modifier = Modifier.align(Alignment.CenterHorizontally),
            count = images.size
        )
        if (images.isNotEmpty()) {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                items(images, key = { it.sequence }) { image -> Thumbnail(image) }
            }
        } else {
            Spacer(modifier = Modifier.weight(1f))
        }
        RailActionButton(
            text = stringResource(R.string.capture_remove_last),
            enabled = images.isNotEmpty() && !isCapturing,
            onClick = onRemoveLast
        )
        RailActionButton(
            text = stringResource(R.string.capture_button),
            enabled = cameraReady && !isCapturing,
            onClick = onCapturePhoto,
            primary = true
        )
        RailActionButton(
            text = stringResource(R.string.process_panorama),
            enabled = images.size >= 2 && !isCapturing,
            onClick = onProcess
        )
    }
}

@Composable
private fun CapturePrimaryActions(
    images: List<CapturedImage>,
    isCapturing: Boolean,
    cameraReady: Boolean,
    onCapturePhoto: () -> Unit,
    onRemoveLast: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedButton(
            onClick = onRemoveLast,
            enabled = images.isNotEmpty() && !isCapturing,
            modifier = Modifier.weight(0.9f),
            contentPadding = PaddingValues(horizontal = 8.dp)
        ) {
            Text(text = stringResource(R.string.capture_remove_last), maxLines = 1)
        }
        Button(
            onClick = onCapturePhoto,
            enabled = cameraReady && !isCapturing,
            modifier = Modifier.weight(1.1f)
        ) {
            Text(
                text = stringResource(R.string.capture_button),
                maxLines = 1
            )
        }
    }
}

@Composable
private fun ProcessingActions(
    enabled: Boolean,
    onProcess: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilledTonalButton(
            onClick = onProcess,
            enabled = enabled,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 8.dp)
        ) { Text(text = stringResource(R.string.process_panorama), maxLines = 1) }
    }
}

@Composable
private fun RailActionButton(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
    primary: Boolean = false
) {
    val buttonModifier = Modifier
        .fillMaxWidth()
        .padding(vertical = 2.dp)
    if (primary) {
        Button(
            onClick = onClick,
            enabled = enabled,
            modifier = buttonModifier,
            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)
        ) {
            Text(
                text = text,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelSmall
            )
        }
    } else {
        FilledTonalButton(
            onClick = onClick,
            enabled = enabled,
            modifier = buttonModifier,
            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)
        ) {
            Text(
                text = text,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

@Composable
private fun CameraPreview(
    lifecycleOwner: LifecycleOwner,
    cameraController: CameraController,
    onReady: () -> Unit,
    onError: (Throwable) -> Unit
) {
    androidx.compose.ui.viewinterop.AndroidView(
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
                            BitmapFactory.decodeFile(
                                image.file.absolutePath,
                                options
                            )
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

@Preview(showBackground = true, showSystemUi = true, name = "Capture - permission")
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

@Preview(showBackground = true, showSystemUi = true, name = "Capture - ready")
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
        onUpdate: (AlignmentGuideState) -> Unit,
        onError: (Throwable) -> Unit
    ) = Unit

    override fun clearAlignmentReference() = Unit

    override fun shutdown() = Unit
}
