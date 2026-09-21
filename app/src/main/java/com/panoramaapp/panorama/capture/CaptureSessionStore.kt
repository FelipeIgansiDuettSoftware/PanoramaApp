package com.panoramaapp.panorama.capture

import android.content.Context
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.UUID

class CaptureSessionStore(context: Context) {
    private val rootDirectory = File(context.cacheDir, "panorama-sessions")

    fun createSession(): CaptureSession {
        val id = "session-${SESSION_ID_FORMAT.format(System.currentTimeMillis())}-${UUID.randomUUID().toString().take(8)}"
        val directory = File(rootDirectory, id)
        val inputDirectory = File(directory, "input")
        check(inputDirectory.mkdirs() || inputDirectory.isDirectory) {
            "Unable to create session directory"
        }
        return CaptureSession(id = id, directory = directory).also(::writeMetadata)
    }

    fun registerImage(
        session: CaptureSession,
        file: File,
        sequence: Int,
        rotationDegrees: Int,
        width: Int,
        height: Int,
        capturedAtNanos: Long = 0L
    ): CaptureSession {
        require(file.isFile && file.length() > 0) { "Captured image is empty" }
        val image = CapturedImage(
            sequence = sequence,
            file = file,
            capturedAtEpochMs = System.currentTimeMillis(),
            capturedAtNanos = capturedAtNanos,
            rotationDegrees = rotationDegrees,
            width = width,
            height = height,
            sizeBytes = file.length()
        )
        return session.copy(images = session.images + image).also(::writeMetadata)
    }

    fun setOrientation(
        session: CaptureSession,
        orientation: CaptureOrientation
    ): CaptureSession {
        return session.copy(orientation = orientation).also(::writeMetadata)
    }

    fun deleteSession(session: CaptureSession) {
        check(!session.directory.exists() || session.directory.deleteRecursively()) {
            "Unable to discard capture session"
        }
    }

    fun removeLast(session: CaptureSession): CaptureSession {
        val last = session.images.lastOrNull() ?: return session
        check(!last.file.exists() || last.file.delete()) { "Unable to remove captured image" }
        return session.copy(images = session.images.dropLast(1)).also(::writeMetadata)
    }

    private fun writeMetadata(session: CaptureSession) {
        val metadata = buildString {
            append("{\n")
            append("  \"sessionId\": \"${session.id}\",\n")
            append("  \"startedAtEpochMs\": ${session.startedAtEpochMs},\n")
            append("  \"orientation\": \"${session.orientation?.name ?: "UNKNOWN"}\",\n")
            append("  \"images\": [\n")
            session.images.forEachIndexed { index, image ->
                append("    {\n")
                append("      \"sequence\": ${image.sequence},\n")
                append("      \"file\": \"${image.file.name}\",\n")
                append("      \"capturedAtEpochMs\": ${image.capturedAtEpochMs},\n")
                append("      \"capturedAtNanos\": ${image.capturedAtNanos},\n")
                append("      \"rotationDegrees\": ${image.rotationDegrees},\n")
                append("      \"width\": ${image.width},\n")
                append("      \"height\": ${image.height},\n")
                append("      \"sizeBytes\": ${image.sizeBytes}\n")
                append("    }")
                if (index < session.images.lastIndex) append(",")
                append("\n")
            }
            append("  ]\n")
            append("}\n")
        }
        File(session.directory, "session.json").writeText(metadata, StandardCharsets.UTF_8)
    }

    private companion object {
        val SESSION_ID_FORMAT = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
    }
}
