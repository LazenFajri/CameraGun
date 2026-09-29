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

    @Volatile var pitch: Short = 0
        private set
    @Volatile var roll: Short = 0
        private set

    private val gravity = FloatArray(3)
    private val linearAccel = FloatArray(3)

    fun start() {
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        gyroscope?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            val alpha = 0.8f
            gravity[0] = alpha * gravity[0] + (1 - alpha) * event.values[0]
            gravity[1] = alpha * gravity[1] + (1 - alpha) * event.values[1]
            gravity[2] = alpha * gravity[2] + (1 - alpha) * event.values[2]

            linearAccel[0] = event.values[0] - gravity[0]
            linearAccel[1] = event.values[1] - gravity[1]
            linearAccel[2] = event.values[2] - gravity[2]

            // Hitung Pitch & Roll dalam derajat (-180 hingga +180)
            val ax = gravity[0]
            val ay = gravity[1]
            val az = gravity[2]

            val calcPitch = (atan2(ay.toDouble(), sqrt((ax * ax + az * az).toDouble())) * (180.0 / Math.PI)).toFloat()
            val calcRoll = (atan2(-ax.toDouble(), az.toDouble()) * (180.0 / Math.PI)).toFloat()

            // Skalakan ke int16
            pitch = (calcPitch * 100).toInt().coerceIn(-32768, 32767).toShort()
            roll = (calcRoll * 100).toInt().coerceIn(-32768, 32767).toShort()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
