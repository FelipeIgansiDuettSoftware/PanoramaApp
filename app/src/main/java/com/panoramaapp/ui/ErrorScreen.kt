package com.panoramaapp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import com.panoramaapp.R

@Composable
fun ErrorScreen(message: String, onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(stringResource(R.string.error_title), style = MaterialTheme.typography.headlineSmall)
        Text(message, modifier = Modifier.padding(vertical = 16.dp))
        Button(onClick = onBack) { Text(stringResource(R.string.error_back_to_capture)) }
    }
}

@Preview(showBackground = true, showSystemUi = true, name = "Error")
@Composable
private fun ErrorScreenPreview() {
    ErrorScreen(
        message = "Not enough overlapping features (2)",
        onBack = {}
    )
}
