# Panorama Lab

Panorama Lab is an experimental Android 14 demo for measuring horizontal, lateral panorama capture in indoor spaces such as warehouses.

The MVP uses Kotlin, Jetpack Compose, CameraX and OpenCV. Camera preview/capture, session storage, image normalization, stitching and result inspection are separated so later `ImageAnalysis`, sensor assistance and automatic capture can be added without coupling the UI to image processing.

Each session is stored temporarily under the app cache directory with ordered input images, `session.json`, generated results and per-mode metrics. The app intentionally has no distribution or legacy-device compatibility commitment.

## Current flow

1. Grant camera permission.
2. Capture two or more overlapping images while moving sideways.
3. Review the ordered thumbnails and remove the last image if necessary.
4. Process the session as `PANORAMA` or `SCANS`.
5. Inspect the wide result and reprocess the same session using the alternate mode.

Build and device validation should be performed on an Android 14 reference device. The project was not built as part of this change.
