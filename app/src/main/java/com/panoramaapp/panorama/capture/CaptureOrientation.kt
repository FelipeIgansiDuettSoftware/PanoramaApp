package com.panoramaapp.panorama.capture

import android.content.res.Configuration

enum class CaptureOrientation {
    PORTRAIT,
    LANDSCAPE;

    val expectedAxis: Axis
        get() = if (this == LANDSCAPE) Axis.X else Axis.Y

    enum class Axis { X, Y }

    companion object {
        fun fromConfiguration(configuration: Configuration): CaptureOrientation {
            return if (configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                LANDSCAPE
            } else {
                PORTRAIT
            }
        }
    }
}
