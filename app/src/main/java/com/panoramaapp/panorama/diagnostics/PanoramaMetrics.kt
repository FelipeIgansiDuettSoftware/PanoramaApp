package com.panoramaapp.panorama.diagnostics

import com.panoramaapp.panorama.capture.CaptureSession
import java.io.File
import java.nio.charset.StandardCharsets

data class PanoramaMetrics(
    val sessionId: String,
    val imageCount: Int,
    val mode: String,
    val orientation: String,
    val processingDurationMs: Long,
    val resultWidth: Int,
    val resultHeight: Int,
    val resultPath: String
) {
    fun writeTo(session: CaptureSession) {
        val json = """
            {
              "sessionId": "${sessionId.jsonEscape()}",
              "imageCount": $imageCount,
              "mode": "${mode.jsonEscape()}",
              "orientation": "${orientation.jsonEscape()}",
              "processingDurationMs": $processingDurationMs,
              "resultWidth": $resultWidth,
              "resultHeight": $resultHeight,
              "resultPath": "${resultPath.jsonEscape()}"
            }
        """.trimIndent() + "\n"
        File(session.directory, "metrics-${mode.lowercase()}.json")
            .writeText(json, StandardCharsets.UTF_8)
    }
}

private fun String.jsonEscape(): String = replace("\\", "\\\\").replace("\"", "\\\"")
