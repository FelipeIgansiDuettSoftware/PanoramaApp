package com.panoramaapp.ui

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.panoramaapp.R
import com.panoramaapp.panorama.capture.CaptureOrientation
import com.panoramaapp.panorama.processing.StitchingResult
import com.panoramaapp.ui.theme.PanoramaAppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun ResultScreen(
    result: StitchingResult,
    onBack: () -> Unit,
    onNewSession: () -> Unit,
    previewBitmap: Bitmap? = null,
) {
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val loadedBitmap by produceState<Bitmap?>(
        initialValue = null,
        key1 = result.outputPath,
        key2 = previewBitmap,
    ) {
        value = previewBitmap ?: withContext(Dispatchers.IO) {
            decodeResultBitmap(result.outputPath)
        }
    }

    if (isLandscape) {
        LandscapeResultScreen(
            result = result,
            bitmap = loadedBitmap,
            onBackToSession = onBack,
            onNewSession = onNewSession,
        )
    } else {
        PortraitResultScreen(
            result = result,
            bitmap = loadedBitmap,
            onBackToSession = onBack,
            onNewSession = onNewSession,
        )
    }
}

@Composable
private fun PortraitResultScreen(
    result: StitchingResult,
    bitmap: Bitmap?,
    onBackToSession: () -> Unit,
    onNewSession: () -> Unit,
) {
    val imageScrollState = rememberScrollState()

    Column(
        modifier = Modifier.fillMaxSize(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp, horizontal = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ResultBackButton(onClick = onBackToSession)
            ResultHeader(result = result, modifier = Modifier.weight(1f))
            ResultNewSessionButton(onClick = onNewSession)
        }

        ResultImageSurface(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            if (bitmap == null) {
                CircularProgressIndicator()
            } else {
                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    // A lateral panorama uses the available portrait height as its scale
                    // reference and overflows horizontally when its full width does not fit.
                    val imageHeight = maxHeight
                    val imageWidth = imageHeight * bitmap.width.toFloat() / bitmap.height.toFloat()

                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .horizontalScroll(imageScrollState),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = stringResource(R.string.result_description),
                            modifier = Modifier
                                .width(imageWidth)
                                .height(imageHeight),
                            contentScale = ContentScale.Fit,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LandscapeResultScreen(
    result: StitchingResult,
    bitmap: Bitmap?,
    onBackToSession: () -> Unit,
    onNewSession: () -> Unit,
) {
    val imageScrollState = rememberScrollState()

    Row(
        modifier = Modifier
            .fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp, horizontal = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                ResultBackButton(onClick = onBackToSession)
                ResultHeader(result = result)
                ResultNewSessionButton(onClick = onNewSession)
            }

            ResultImageSurface(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                if (bitmap == null) {
                    CircularProgressIndicator()
                } else {
                    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                        val imageHeight = maxHeight
                        val imageWidth = imageHeight * bitmap.width.toFloat() / bitmap.height.toFloat()

                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .horizontalScroll(imageScrollState),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = stringResource(R.string.result_description),
                                modifier = Modifier
                                    .width(imageWidth)
                                    .height(imageHeight),
                                contentScale = ContentScale.Fit,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultHeader(result: StitchingResult, modifier: Modifier = Modifier) {
    Text(
        text = stringResource(
            R.string.result_summary,
            result.imageCount,
            result.width,
            result.height,
            result.durationMs,
        ),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        overflow = TextOverflow.Ellipsis,
        maxLines = 2,
        modifier = modifier
    )
}

@Composable
private fun ResultImageSurface(
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
private fun ResultBackButton(onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.wrapContentWidth(),
    ) {
        Icon(
            Icons.Default.ArrowBack, contentDescription = null,
            tint = Color.White
        )
    }
}

@Composable
private fun ResultNewSessionButton(onClick: () -> Unit) {
    FloatingActionButton(
        onClick = onClick,
        containerColor = Color(red = 30, green = 145, blue = 246, alpha = 255),
        modifier = Modifier.size(40.dp),
        shape = RoundedCornerShape(8.dp)
    ) {
        Icon(Icons.Default.Add, contentDescription = null)
    }
}

private fun decodeResultBitmap(path: String): Bitmap? {
    val bounds = BitmapFactory.Options()
        .apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val maxDimension = maxOf(bounds.outWidth, bounds.outHeight)
    var sampleSize = 1
    while (maxDimension / sampleSize > 4096) {
        sampleSize *= 2
    }

    return BitmapFactory.decodeFile(
        path,
        BitmapFactory.Options()
            .apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.RGB_565
            },
    )
}

@Preview(showBackground = true, widthDp = 400, heightDp = 800, backgroundColor = 0xFF000000)
@Composable
private fun PortraitResultScreenPreview() {
    PanoramaAppTheme {
        ResultScreen(
            result = previewStitchingResult(CaptureOrientation.PORTRAIT),
            onBack = {},
            onNewSession = {},
            previewBitmap = rememberPreviewPanoramaBitmap(),
        )
    }
}

@Preview(showBackground = true, widthDp = 800, heightDp = 400, backgroundColor = 0xFF000000)
@Composable
private fun LandscapeResultScreenPreview() {
    PanoramaAppTheme {
        ResultScreen(
            result = previewStitchingResult(CaptureOrientation.LANDSCAPE),
            onBack = {},
            onNewSession = {},
            previewBitmap = rememberPreviewPanoramaBitmap(),
        )
    }
}

private fun previewStitchingResult(orientation: CaptureOrientation) = StitchingResult(
    outputPath = "",
    width = 3840,
    height = 1080,
    imageCount = 12,
    durationMs = 3584,
    orientation = orientation,
)
