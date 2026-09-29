package com.cameragun.lightgun

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface

class SensorsManager(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationVector = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        ?: sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    // Current Raw Angles (Degrees)
    @Volatile var rawYawDeg: Float = 0f
        private set
    @Volatile var rawPitchDeg: Float = 0f
        private set
    @Volatile var rawRollDeg: Float = 0f
        private set

    // Legacy short values for backward-compatible telemetry packets
    @Volatile var pitch: Short = 0
        private set
    @Volatile var roll: Short = 0
        private set
    @Volatile var yaw: Short = 0
        private set

    // Gyro Delta for dead-reckoning
    @Volatile var gyroDeltaX: Float = 0f
        private set
    @Volatile var gyroDeltaY: Float = 0f
        private set

    // Center Reference (Calibrated Aiming like GKHeart)
    @Volatile var hasCenter: Boolean = false
        private set
    @Volatile var centerYaw: Float = 0f
        private set
    @Volatile var centerPitch: Float = 0f
        private set

    // Aiming Sensitivity (Default: 1.0f)
    var sensitivityX: Float = 1.0f
    var sensitivityY: Float = 1.0f

    // Current Display Rotation
    @Volatile var displayRotation: Int = Surface.ROTATION_90

    private val rotationMatrix = FloatArray(9)
    private val remappedMatrix = FloatArray(9)
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

    /**
     * Kunci posisi tengah monitor (GKHeart RECENTER)
     */
    fun setCenter() {
        centerYaw = rawYawDeg
        centerPitch = rawPitchDeg
        hasCenter = true
    }

    fun resetCenter() {
        hasCenter = false
    }

    /**
     * Menghitung selisih sudut terpendek (-180° s/d +180°)
     */
    fun angleDeltaDegrees(current: Float, center: Float): Float {
        var diff = (current - center) % 360f
        if (diff > 180f) diff -= 360f
        if (diff < -180f) diff += 360f
        return diff
    }

    /**
     * Menghitung koordinat kursor monitor (0.0 s/d 1.0) berbasis Gyroscope 100Hz
     * Menggunakan Sweep Angle ergonomis monitor (horizontal ~30°, vertikal ~18°)
     */
    fun computeAimCoordinates(): Pair<Float, Float> {
        if (!hasCenter) {
            return Pair(0.5f, 0.5f)
        }

        val deltaYaw = angleDeltaDegrees(rawYawDeg, centerYaw)
        val deltaPitch = angleDeltaDegrees(rawPitchDeg, centerPitch)

        // Rentang sudut pandang monitor tipikal pada jarak duduk / berdiri (16:9):
        // Horizontal: ~30° total sweep (-15° s/d +15°)
        // Vertikal:   ~18° total sweep (-9° s/d +9°)
        val sweepH = 30.0f / sensitivityX.coerceAtLeast(0.2f)
        val sweepV = 18.0f / sensitivityY.coerceAtLeast(0.2f)

        var u = 0.5f + (deltaYaw / sweepH)
        var v = 0.5f - (deltaPitch / sweepV)

        // Micro-deadzone untuk stabilitas kursor saat tangan diam
        val du = u - 0.5f
        val dv = v - 0.5f
        if (Math.hypot(du.toDouble(), dv.toDouble()) < 0.002) {
            u = 0.5f
            v = 0.5f
        }

        // Clamp bersih 0.0 - 1.0
        val normX = u.coerceIn(0.0f, 1.0f)
        val normY = v.coerceIn(0.0f, 1.0f)

        return Pair(normX, normY)
    }

    /**
     * Deteksi Off-Screen Reload otomatis (saat moncong diarahkan ke bawah luar layar)
     */
    fun isOffscreenReload(): Boolean {
        if (!hasCenter) return false
        val deltaPitch = angleDeltaDegrees(rawPitchDeg, centerPitch)
        return deltaPitch < -20.0f
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_GAME_ROTATION_VECTOR, Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)

                // Remap koordinat sistem berdasarkan orientasi layar HP (Landscape / Portrait)
                when (displayRotation) {
                    Surface.ROTATION_90 -> {
                        SensorManager.remapCoordinateSystem(
                            rotationMatrix,
                            SensorManager.AXIS_Y,
                            SensorManager.AXIS_MINUS_X,
                            remappedMatrix
                        )
                    }
                    Surface.ROTATION_270 -> {
                        SensorManager.remapCoordinateSystem(
                            rotationMatrix,
                            SensorManager.AXIS_MINUS_Y,
                            SensorManager.AXIS_X,
                            remappedMatrix
                        )
                    }
                    Surface.ROTATION_180 -> {
                        SensorManager.remapCoordinateSystem(
                            rotationMatrix,
                            SensorManager.AXIS_MINUS_X,
                            SensorManager.AXIS_MINUS_Y,
                            remappedMatrix
                        )
                    }
                    else -> { // Surface.ROTATION_0 (Portrait)
                        System.arraycopy(rotationMatrix, 0, remappedMatrix, 0, 9)
                    }
                }

                SensorManager.getOrientation(remappedMatrix, orientationAngles)

                val radToDeg = (180.0 / Math.PI).toFloat()
                val curYaw = orientationAngles[0] * radToDeg
                val curPitch = orientationAngles[1] * radToDeg
                val curRoll = orientationAngles[2] * radToDeg

                rawYawDeg = curYaw
                rawPitchDeg = curPitch
                rawRollDeg = curRoll

                yaw = (curYaw * 100).toInt().coerceIn(-32768, 32767).toShort()
                pitch = (curPitch * 100).toInt().coerceIn(-32768, 32767).toShort()
                roll = (curRoll * 100).toInt().coerceIn(-32768, 32767).toShort()
            }
            Sensor.TYPE_GYROSCOPE -> {
                if (lastGyroTimestamp != 0L) {
                    val dt = (event.timestamp - lastGyroTimestamp) * 1.0e-9f
                    gyroDeltaX = event.values[2] * dt
                    gyroDeltaY = event.values[0] * dt
                }
                lastGyroTimestamp = event.timestamp
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
