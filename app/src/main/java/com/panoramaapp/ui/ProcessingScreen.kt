package com.panoramaapp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import com.panoramaapp.R
import com.panoramaapp.panorama.PanoramaUiState

@Composable
fun ProcessingScreen(state: PanoramaUiState.Processing) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator()
        Text(
            text = stringResource(R.string.processing_title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(top = 20.dp)
        )
        state.progress?.let { Text(stringResource(R.string.processing_progress, it)) }
    }
}

@Preview(showBackground = true, name = "Processing")
@Composable
private fun ProcessingScreenPreview() {
    ProcessingScreen(
        PanoramaUiState.Processing(progress = 68)
    )
}
