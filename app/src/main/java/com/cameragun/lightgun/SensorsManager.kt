package com.cameragun.lightgun

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.atan2
import kotlin.math.sqrt

class SensorsManager(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val rotationVector = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        ?: sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    @Volatile var pitch: Short = 0
        private set
    @Volatile var roll: Short = 0
        private set
    @Volatile var yaw: Short = 0
        private set

    @Volatile var gyroDeltaX: Float = 0f
        private set
    @Volatile var gyroDeltaY: Float = 0f
        private set

    private val gravity = FloatArray(3)
    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)
    private var lastGyroTimestamp: Long = 0

    fun start() {
        rotationVector?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST)
        }
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        gyroscope?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_GAME_ROTATION_VECTOR, Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                SensorManager.getOrientation(rotationMatrix, orientationAngles)

                val radToDeg = 180.0 / Math.PI
                val calcYaw = (orientationAngles[0] * radToDeg).toFloat()
                val calcPitch = (orientationAngles[1] * radToDeg).toFloat()
                val calcRoll = (orientationAngles[2] * radToDeg).toFloat()

                yaw = (calcYaw * 100).toInt().coerceIn(-32768, 32767).toShort()
                pitch = (calcPitch * 100).toInt().coerceIn(-32768, 32767).toShort()
                roll = (calcRoll * 100).toInt().coerceIn(-32768, 32767).toShort()
            }
            Sensor.TYPE_GYROSCOPE -> {
                if (lastGyroTimestamp != 0L) {
                    val dt = (event.timestamp - lastGyroTimestamp) * 1.0e-9f
                    // event.values[0] = x (pitch rate), event.values[1] = y (roll rate), event.values[2] = z (yaw rate)
                    gyroDeltaX = event.values[2] * dt
                    gyroDeltaY = event.values[0] * dt
                }
                lastGyroTimestamp = event.timestamp
            }
            Sensor.TYPE_ACCELEROMETER -> {
                if (rotationVector == null) {
                    val alpha = 0.8f
                    gravity[0] = alpha * gravity[0] + (1 - alpha) * event.values[0]
                    gravity[1] = alpha * gravity[1] + (1 - alpha) * event.values[1]
                    gravity[2] = alpha * gravity[2] + (1 - alpha) * event.values[2]

                    val ax = gravity[0]
                    val ay = gravity[1]
                    val az = gravity[2]

                    val calcPitch = (atan2(ay.toDouble(), sqrt((ax * ax + az * az).toDouble())) * (180.0 / Math.PI)).toFloat()
                    val calcRoll = (atan2(-ax.toDouble(), az.toDouble()) * (180.0 / Math.PI)).toFloat()

                    pitch = (calcPitch * 100).toInt().coerceIn(-32768, 32767).toShort()
                    roll = (calcRoll * 100).toInt().coerceIn(-32768, 32767).toShort()
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
