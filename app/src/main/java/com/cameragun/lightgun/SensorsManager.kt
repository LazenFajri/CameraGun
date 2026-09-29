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

    // Center Reference (3D Orthonormal Basis — 100% Gimbal-Lock Free)
    @Volatile var hasCenter: Boolean = false
        private set

    private val centerForward = FloatArray(3)
    private val centerRight = FloatArray(3)
    private val centerUp = FloatArray(3)

    // GKHeart Deadzone & EMA Smoothing Filter State
    private var acceptedTargetU: Float = 0.5f
    private var acceptedTargetV: Float = 0.5f
    private var smoothedU: Float = 0.5f
    private var smoothedV: Float = 0.5f
    private var hasSmoothedAim: Boolean = false

    // Tuning Parameters (Identical to GKHeart architecture)
    var deadzone: Float = 0.008f       // 0.8% screen deadzone (diam tenang saat tangan diam)
    var smoothing: Float = 0.25f      // Exponential moving average filter
    var sweepH: Float = 44.0f         // Total horizontal angle sweep in degrees (16:9 monitor)
    var sweepV: Float = 26.0f         // Total vertical angle sweep in degrees
    var sensitivityX: Float = 1.0f
    var sensitivityY: Float = 1.0f

    // Current Display Rotation
    @Volatile var displayRotation: Int = Surface.ROTATION_90

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

    /**
     * Kunci posisi tengah monitor (GKHeart RECENTER)
     * Mengunci orientasi 3D ke tengah layar laptop/TV tanpa gimbal-lock.
     */
    @Synchronized
    fun setCenter() {
        if (rotationMatrix[0] == 0f && rotationMatrix[4] == 0f && rotationMatrix[8] == 0f) {
            return
        }

        // Vektor arah kamera belakang smartphone (-Z dari device frame) dalam world frame:
        centerForward[0] = -rotationMatrix[2]
        centerForward[1] = -rotationMatrix[5]
        centerForward[2] = -rotationMatrix[8]

        // Vektor Screen Right dan Screen Up sesuai orientasi rotasi HP:
        when (displayRotation) {
            Surface.ROTATION_90 -> {
                // Landscape (Port USB di kanan, kamera di kiri-atas)
                // Screen Right = -Hardware_Y; Screen Up = +Hardware_X
                centerRight[0] = -rotationMatrix[1]
                centerRight[1] = -rotationMatrix[4]
                centerRight[2] = -rotationMatrix[7]

                centerUp[0]    =  rotationMatrix[0]
                centerUp[1]    =  rotationMatrix[3]
                centerUp[2]    =  rotationMatrix[6]
            }
            Surface.ROTATION_270 -> {
                // Reverse Landscape (Port USB di kiri)
                // Screen Right = +Hardware_Y; Screen Up = -Hardware_X
                centerRight[0] =  rotationMatrix[1]
                centerRight[1] =  rotationMatrix[4]
                centerRight[2] =  rotationMatrix[7]

                centerUp[0]    = -rotationMatrix[0]
                centerUp[1]    = -rotationMatrix[3]
                centerUp[2]    = -rotationMatrix[6]
            }
            Surface.ROTATION_180 -> {
                centerRight[0] = -rotationMatrix[0]
                centerRight[1] = -rotationMatrix[3]
                centerRight[2] = -rotationMatrix[6]

                centerUp[0]    = -rotationMatrix[1]
                centerUp[1]    = -rotationMatrix[4]
                centerUp[2]    = -rotationMatrix[7]
            }
            else -> { // Surface.ROTATION_0 (Portrait)
                centerRight[0] =  rotationMatrix[0]
                centerRight[1] =  rotationMatrix[3]
                centerRight[2] =  rotationMatrix[6]

                centerUp[0]    =  rotationMatrix[1]
                centerUp[1]    =  rotationMatrix[4]
                centerUp[2]    =  rotationMatrix[7]
            }
        }

        normalize(centerForward)
        normalize(centerRight)
        normalize(centerUp)

        // Langsung set filter ke tepat tengah (0.5, 0.5)
        acceptedTargetU = 0.5f
        acceptedTargetV = 0.5f
        smoothedU = 0.5f
        smoothedV = 0.5f
        hasSmoothedAim = true
        hasCenter = true
    }

    @Synchronized
    fun resetCenter() {
        hasCenter = false
        hasSmoothedAim = false
        acceptedTargetU = 0.5f
        acceptedTargetV = 0.5f
        smoothedU = 0.5f
        smoothedV = 0.5f
    }

    private fun normalize(v: FloatArray) {
        val len = Math.sqrt((v[0] * v[0] + v[1] * v[1] + v[2] * v[2]).toDouble()).toFloat()
        if (len > 1e-6f) {
            v[0] /= len
            v[1] /= len
            v[2] /= len
        }
    }

    /**
     * Menghitung koordinat kursor monitor (0.0 s/d 1.0) berbasis 3D Vector Geometry.
     * Menggunakan Filter GKHeart (Deadzone + EMA Smoothing) sehingga kursor diam tenang di tengah
     * dan bergerak akurat mengikuti moncong kamera HP.
     */
    @Synchronized
    fun computeAimCoordinates(): Pair<Float, Float> {
        if (!hasCenter) {
            return Pair(0.5f, 0.5f)
        }

        // Vektor arah moncong kamera belakang saat ini (-Z)
        val curForwardX = -rotationMatrix[2]
        val curForwardY = -rotationMatrix[5]
        val curForwardZ = -rotationMatrix[8]

        // Proyeksi ke kerangka acuan kalibrasi center (Dot Products)
        val dx = curForwardX * centerRight[0] + curForwardY * centerRight[1] + curForwardZ * centerRight[2]
        val dy = curForwardX * centerUp[0]    + curForwardY * centerUp[1]    + curForwardZ * centerUp[2]
        val dz = curForwardX * centerForward[0] + curForwardY * centerForward[1] + curForwardZ * centerForward[2]

        // Sudut penyimpangan dalam derajat (atan2 bebas singularitas / gimbal-lock)
        val angleXDeg = Math.toDegrees(Math.atan2(dx.toDouble(), dz.toDouble())).toFloat()
        val angleYDeg = Math.toDegrees(Math.atan2(dy.toDouble(), dz.toDouble())).toFloat()

        // Rentang sapuan sudut layar monitor
        val effSweepH = sweepH / sensitivityX.coerceAtLeast(0.2f)
        val effSweepV = sweepV / sensitivityY.coerceAtLeast(0.2f)

        // Target normalisasi kursor (u: 0.0 kiri s/d 1.0 kanan; v: 0.0 atas s/d 1.0 bawah)
        val targetU = (0.5f + (angleXDeg / effSweepH)).coerceIn(0.0f, 1.0f)
        val targetV = (0.5f - (angleYDeg / effSweepV)).coerceIn(0.0f, 1.0f)

        // Filter GKHeart: Deadzone penstabil tremor tangan
        if (!hasSmoothedAim) {
            acceptedTargetU = targetU
            acceptedTargetV = targetV
            smoothedU = targetU
            smoothedV = targetV
            hasSmoothedAim = true
        } else {
            val deltaU = targetU - acceptedTargetU
            val deltaV = targetV - acceptedTargetV
            val dist = Math.hypot(deltaU.toDouble(), deltaV.toDouble()).toFloat()

            // Jika pergerakan lebih kecil dari deadzone, kursor TETAP DIAM (tidak goyang/geser)
            if (dist >= deadzone) {
                acceptedTargetU = targetU
                acceptedTargetV = targetV
            }

            // Exponential Moving Average untuk kehalusan tingkat tinggi 100Hz
            smoothedU = smoothedU * smoothing + acceptedTargetU * (1.0f - smoothing)
            smoothedV = smoothedV * smoothing + acceptedTargetV * (1.0f - smoothing)
        }

        val finalU = smoothedU.coerceIn(0.0f, 1.0f)
        val finalV = smoothedV.coerceIn(0.0f, 1.0f)

        return Pair(finalU, finalV)
    }

    /**
     * Deteksi Off-Screen Reload otomatis (saat moncong diarahkan ke bawah luar monitor)
     */
    fun isOffscreenReload(): Boolean {
        if (!hasCenter) return false
        val curForwardX = -rotationMatrix[2]
        val curForwardY = -rotationMatrix[5]
        val curForwardZ = -rotationMatrix[8]
        val dy = curForwardX * centerUp[0] + curForwardY * centerUp[1] + curForwardZ * centerUp[2]
        val dz = curForwardX * centerForward[0] + curForwardY * centerForward[1] + curForwardZ * centerForward[2]
        val angleYDeg = Math.toDegrees(Math.atan2(dy.toDouble(), dz.toDouble())).toFloat()
        return angleYDeg < -20.0f
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_GAME_ROTATION_VECTOR, Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)

                // Simpan orientasi legacy untuk kompatibilitas data packet
                SensorManager.getOrientation(rotationMatrix, orientationAngles)
                val radToDeg = (180.0 / Math.PI).toFloat()
                yaw = (orientationAngles[0] * radToDeg * 100).toInt().coerceIn(-32768, 32767).toShort()
                pitch = (orientationAngles[1] * radToDeg * 100).toInt().coerceIn(-32768, 32767).toShort()
                roll = (orientationAngles[2] * radToDeg * 100).toInt().coerceIn(-32768, 32767).toShort()
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
