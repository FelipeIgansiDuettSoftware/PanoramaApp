package com.panoramaapp.panorama.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.panoramaapp.panorama.capture.CaptureOrientation

data class MotionSample(
    val timestampNanos: Long,
    val acceleration: FloatArray,
    val gyroscope: FloatArray
)

/** Tracks directional movement on the X/Y axes while a frame session is active. */
class DeviceMotionRepository(context: Context) : SensorEventListener {
    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val linearAcceleration = sensorManager?.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private var lastAcceleration = FloatArray(3)
    private var lastGyroscope = FloatArray(3)
    private val gravity = FloatArray(3)
    private var gravityInitialized = false
    private var activeMotionSamples = 0
    private var expectedAxis = CaptureOrientation.Axis.X
    @Volatile
    var movementDetected: Boolean = false
        private set
    var onSample: ((MotionSample) -> Unit)? = null

    fun start(orientation: CaptureOrientation) {
        expectedAxis = orientation.expectedAxis
        movementDetected = false
        activeMotionSamples = 0
        lastAcceleration = FloatArray(3)
        lastGyroscope = FloatArray(3)
        gravity.fill(0f)
        gravityInitialized = false
        sensorManager?.let { manager ->
            (linearAcceleration ?: accelerometer)?.let {
                manager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            }
            gyroscope?.let { manager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        }
    }

    fun stop() {
        sensorManager?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_LINEAR_ACCELERATION -> lastAcceleration = event.values.copyOf()
            Sensor.TYPE_ACCELEROMETER -> {
                if (!gravityInitialized) {
                    event.values.copyInto(gravity)
                    lastAcceleration = FloatArray(3)
                    gravityInitialized = true
                } else {
                    val alpha = 0.8f
                    for (index in 0..2) {
                        gravity[index] = alpha * gravity[index] + (1f - alpha) * event.values[index]
                        lastAcceleration[index] = event.values[index] - gravity[index]
                    }
                }
            }
            Sensor.TYPE_GYROSCOPE -> lastGyroscope = event.values.copyOf()
        }
        val axisIndex = if (expectedAxis == CaptureOrientation.Axis.X) 0 else 1
        val movedOnLinearAxis =
            kotlin.math.abs(lastAcceleration[axisIndex]) >= LINEAR_ACCELERATION_THRESHOLD
        val rotatedOnAxis =
            kotlin.math.abs(lastGyroscope[axisIndex]) >= ANGULAR_VELOCITY_THRESHOLD
        if (movedOnLinearAxis || rotatedOnAxis) {
            activeMotionSamples++
            if (activeMotionSamples >= REQUIRED_ACTIVE_SAMPLES) movementDetected = true
        } else {
            activeMotionSamples = 0
        }
        onSample?.invoke(
            MotionSample(event.timestamp, lastAcceleration, lastGyroscope)
        )
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private companion object {
        const val LINEAR_ACCELERATION_THRESHOLD = 0.65f
        const val ANGULAR_VELOCITY_THRESHOLD = 0.20f
        const val REQUIRED_ACTIVE_SAMPLES = 2
    }
}
