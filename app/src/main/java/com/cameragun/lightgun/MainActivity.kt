package com.cameragun.lightgun

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.os.*
import android.util.Log
import android.view.*
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicInteger

class MainActivity : AppCompatActivity(), TextureView.SurfaceTextureListener {

    companion object {
        private const val TAG = "CameraGun-Main"
        private const val PERMISSIONS_REQUEST_CODE = 101
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        private const val PREFS_NAME = "cameragun_prefs"
        private const val KEY_PORTRAIT = "pref_is_portrait"
        private const val KEY_PLAYER_2 = "pref_is_player_2"
        private const val PREVIEW_WIDTH = 1280
        private const val PREVIEW_HEIGHT = 720
    }

    private lateinit var textureView: TextureView
    private lateinit var cornerOverlay: CornerOverlayView
    private lateinit var tvBtStatus: TextView
    private lateinit var tvTrackingStatus: TextView
    private lateinit var tvCoords: TextView
    private lateinit var tvFps: TextView
    private lateinit var dotBt: View
    private lateinit var btnPlayerRole: Button

    private var isPlayer2: Boolean = false

    private val nativeBridge = NativeVisionBridge()
    private lateinit var bluetoothTransmitter: BluetoothTransmitter
    private lateinit var networkTransmitter: NetworkTransmitter
    private lateinit var sensorsManager: SensorsManager

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private var vibrator: Vibrator? = null

    private val buttonMask = AtomicInteger(0)
    private var frameCount = 0
    private var lastFpsTimestamp = SystemClock.elapsedRealtime()

    private val visionResults = FloatArray(4)
    private val visionCorners = FloatArray(8)
    private var nv21Buffer: ByteArray? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Read orientation & player role preferences
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val isPortrait = prefs.getBoolean(KEY_PORTRAIT, false)
        isPlayer2 = prefs.getBoolean(KEY_PLAYER_2, false)

        requestedOrientation = if (isPortrait) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }

        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        )
        setContentView(R.layout.activity_main)

        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

        initViews()
        updateOrientationUI(isPortrait)
        updatePlayerRoleUI()
        setupControllerButtons()

        sensorsManager = SensorsManager(this)
        nativeBridge.initVision(1920, 1080)

        networkTransmitter = NetworkTransmitter(this) { hMin, sMin, vMin, hMax, sMax, vMax, w, h ->
            nativeBridge.updateHsvBoundaries(hMin, sMin, vMin, hMax, sMax, vMax)
            nativeBridge.initVision(w, h)
        }
        networkTransmitter.onStatusChanged = { _, _ ->
            runOnUiThread {
                updateBtStatusUI()
            }
        }

        bluetoothTransmitter = BluetoothTransmitter(this) { hMin, sMin, vMin, hMax, sMax, vMax, w, h ->
            nativeBridge.updateHsvBoundaries(hMin, sMin, vMin, hMax, sMax, vMax)
            nativeBridge.initVision(w, h)
            runOnUiThread {
                val btnBorderColor = findViewById<Button>(R.id.btnBorderColor) ?: return@runOnUiThread
                val (name, colorRes) = when {
                    sMax <= 65 || vMin >= 150 -> Pair("WHITE", R.color.white)
                    hMin in 60..120 -> Pair("CYAN", R.color.cyber_cyan)
                    hMin in 20..95 -> Pair("GREEN", R.color.cyber_green)
                    hMin in 130..180 -> Pair("MAGENTA", R.color.cyber_magenta)
                    else -> Pair("CUSTOM", R.color.cyber_cyan)
                }
                btnBorderColor.text = "🎨 $name"
                btnBorderColor.setTextColor(ContextCompat.getColor(this, colorRes))
            }
        }
        bluetoothTransmitter.onStatusChanged = { statusText, isConnected ->
            runOnUiThread {
                updateBtStatusUI()
            }
        }

        if (allPermissionsGranted()) {
            startSystems()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, PERMISSIONS_REQUEST_CODE)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun initViews() {
        textureView = findViewById(R.id.cameraPreview)
        cornerOverlay = findViewById(R.id.cornerOverlay)
        tvBtStatus = findViewById(R.id.tvBtStatus)
        tvTrackingStatus = findViewById(R.id.tvTrackingStatus)
        tvCoords = findViewById(R.id.tvCoords)
        tvFps = findViewById(R.id.tvFps)
        dotBt = findViewById(R.id.dotBt)
        textureView.surfaceTextureListener = this

        // Tap to focus on preview screen
        textureView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                triggerAutoFocus()
            }
            false
        }

        // Orientation toggle button (Landscape <-> Portrait)
        findViewById<Button>(R.id.btnToggleOrientation)?.setOnClickListener {
            vibrateLight()
            val currentOrientation = resources.configuration.orientation
            val newIsPortrait = currentOrientation != Configuration.ORIENTATION_PORTRAIT
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_PORTRAIT, newIsPortrait).apply()
            requestedOrientation = if (newIsPortrait) {
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            } else {
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
            updateOrientationUI(newIsPortrait)
            textureView.post {
                configureTransform(textureView.width, textureView.height)
            }
        }

        // Color preset quick toggle button (CYAN -> GREEN -> MAGENTA -> WHITE)
        val colorNames = arrayOf("CYAN", "GREEN", "MAGENTA", "WHITE")
        val colorValues = arrayOf(
            intArrayOf(70, 40, 50, 115, 255, 255),    // CYAN
            intArrayOf(35, 40, 50, 95, 255, 255),     // GREEN
            intArrayOf(140, 40, 50, 180, 255, 255),   // MAGENTA
            intArrayOf(0, 0, 160, 180, 55, 255)       // WHITE
        )
        val colorTextColors = arrayOf(
            R.color.cyber_cyan,
            R.color.cyber_green,
            R.color.cyber_magenta,
            R.color.white
        )
        var currentColorIndex = 0

        val btnBorderColor = findViewById<Button>(R.id.btnBorderColor)
        btnBorderColor?.setOnClickListener {
            vibrateLight()
            currentColorIndex = (currentColorIndex + 1) % colorNames.size
            val cName = colorNames[currentColorIndex]
            val vals = colorValues[currentColorIndex]
            nativeBridge.updateHsvBoundaries(vals[0], vals[1], vals[2], vals[3], vals[4], vals[5])
            btnBorderColor.text = "🎨 $cName"
            btnBorderColor.setTextColor(ContextCompat.getColor(this, colorTextColors[currentColorIndex]))
        }

        // Player Role toggle button (1P <-> 2P)
        btnPlayerRole = findViewById(R.id.btnPlayerRole)
        btnPlayerRole.setOnClickListener {
            vibrateLight()
            isPlayer2 = !isPlayer2
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_PLAYER_2, isPlayer2).apply()
            updatePlayerRoleUI()
        }

        // Wi-Fi Pairing Dialog Button
        findViewById<Button>(R.id.btnWifi)?.setOnClickListener {
            vibrateLight()
            showWifiDialog()
        }

        // Tutorial / Help button & modal overlay
        val layoutTutorial = findViewById<View>(R.id.layoutTutorial)
        findViewById<Button>(R.id.btnHelp)?.setOnClickListener {
            vibrateLight()
            layoutTutorial?.visibility = View.VISIBLE
        }
        findViewById<Button>(R.id.btnCloseTutorial)?.setOnClickListener {
            vibrateLight()
            layoutTutorial?.visibility = View.GONE
        }
        findViewById<Button>(R.id.btnGotIt)?.setOnClickListener {
            vibrateLight()
            layoutTutorial?.visibility = View.GONE
        }
    }

    private fun updatePlayerRoleUI() {
        if (::btnPlayerRole.isInitialized) {
            if (isPlayer2) {
                btnPlayerRole.text = "🎯 2P"
                btnPlayerRole.setTextColor(ContextCompat.getColor(this, R.color.cyber_magenta))
            } else {
                btnPlayerRole.text = "🎯 1P"
                btnPlayerRole.setTextColor(ContextCompat.getColor(this, R.color.cyber_cyan))
            }
        }
        if (::cornerOverlay.isInitialized) {
            cornerOverlay.setPlayerRole(isPlayer2)
        }
    }

    private fun updateOrientationUI(isPortrait: Boolean) {
        val landscapeControls = findViewById<View>(R.id.landscapeControls)
        val portraitControls = findViewById<View>(R.id.portraitControls)
        val btnToggle = findViewById<Button>(R.id.btnToggleOrientation)

        if (isPortrait) {
            landscapeControls?.visibility = View.GONE
            portraitControls?.visibility = View.VISIBLE
            btnToggle?.text = "🖥️ LAND"
        } else {
            landscapeControls?.visibility = View.VISIBLE
            portraitControls?.visibility = View.GONE
            btnToggle?.text = "📱 PORT"
        }
    }

    private fun setupControllerButtons() {
        // Landscape Controls (Gamepad Style)
        bindHapticButton(findViewById(R.id.btnTriggerL2), 1 shl 0, heavy = true)
        bindHapticButton(findViewById(R.id.btnCross), 1 shl 1)
        bindHapticButton(findViewById(R.id.btnCircle), 1 shl 2)
        bindHapticButton(findViewById(R.id.btnSquare), 1 shl 3)
        bindHapticButton(findViewById(R.id.btnTriangle), 1 shl 4)
        bindHapticButton(findViewById(R.id.btnDpadUp), 1 shl 5)
        bindHapticButton(findViewById(R.id.btnDpadDown), 1 shl 6)
        bindHapticButton(findViewById(R.id.btnDpadLeft), 1 shl 7)
        bindHapticButton(findViewById(R.id.btnDpadRight), 1 shl 8)
        bindHapticButton(findViewById(R.id.btnReload), 1 shl 9, heavy = true)
        bindHapticButton(findViewById(R.id.btnOptions), 1 shl 10)
        bindHapticButton(findViewById(R.id.btnShare), 1 shl 11)
        bindHapticButton(findViewById(R.id.btnTriggerR2), 1 shl 12, heavy = true)

        // Portrait Controls (Pistol Grip Style)
        bindHapticButton(findViewById(R.id.port_btnTriggerL2), 1 shl 0, heavy = true)
        bindHapticButton(findViewById(R.id.port_btnCross), 1 shl 1)
        bindHapticButton(findViewById(R.id.port_btnCircle), 1 shl 2)
        bindHapticButton(findViewById(R.id.port_btnSquare), 1 shl 3)
        bindHapticButton(findViewById(R.id.port_btnTriangle), 1 shl 4)
        bindHapticButton(findViewById(R.id.port_btnDpadUp), 1 shl 5)
        bindHapticButton(findViewById(R.id.port_btnDpadDown), 1 shl 6)
        bindHapticButton(findViewById(R.id.port_btnDpadLeft), 1 shl 7)
        bindHapticButton(findViewById(R.id.port_btnDpadRight), 1 shl 8)
        bindHapticButton(findViewById(R.id.port_btnReload), 1 shl 9, heavy = true)
        bindHapticButton(findViewById(R.id.port_btnOptions), 1 shl 10)
        bindHapticButton(findViewById(R.id.port_btnShare), 1 shl 11)
        bindHapticButton(findViewById(R.id.port_btnTriggerR2), 1 shl 12, heavy = true)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun bindHapticButton(button: Button?, bit: Int, heavy: Boolean = false) {
        button ?: return
        button.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    buttonMask.updateAndGet { it or bit }
                    if (heavy) vibrateHeavy() else vibrateLight()
                    v.isPressed = true
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    buttonMask.updateAndGet { it and bit.inv() }
                    v.isPressed = false
                    true
                }
                else -> false
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun vibrateLight() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(15, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                vibrator?.vibrate(15)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "vibrateLight error: ${e.message}")
        }
    }

    @Suppress("DEPRECATION")
    private fun vibrateHeavy() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                vibrator?.vibrate(40)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "vibrateHeavy error: ${e.message}")
        }
    }

    private fun showWifiDialog() {
        val builder = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
        builder.setTitle("📶 KONEKSI WI-FI PC SERVER")

        val input = EditText(this).apply {
            val savedIp = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString("wifi_server_ip", "")
            setText(if (networkTransmitter.connectedIp != null) networkTransmitter.connectedIp else savedIp)
            hint = "Contoh: 192.168.1.15:8765"
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.cyber_cyan))
            setPadding(30, 30, 30, 30)
        }
        builder.setView(input)

        builder.setPositiveButton("HUBUNGKAN") { _, _ ->
            val ipText = input.text.toString().trim()
            if (ipText.isNotEmpty()) {
                getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().putString("wifi_server_ip", ipText).apply()
                networkTransmitter.connectDirect(ipText)
            }
        }

        builder.setNeutralButton("CARI OTOMATIS") { _, _ ->
            networkTransmitter.autoDiscover()
        }

        builder.setNegativeButton("BATAL", null)
        builder.show()
    }

    private fun startSystems() {
        sensorsManager.start()
        bluetoothTransmitter.start()
        val savedWifiIp = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString("wifi_server_ip", null)
        networkTransmitter.start(savedWifiIp)
        startBackgroundThread()
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            Log.e(TAG, "Error stopping background thread", e)
        }
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        configureTransform(width, height)
        if (allPermissionsGranted()) openCamera()
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        configureTransform(width, height)
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val isPortrait = newConfig.orientation == Configuration.ORIENTATION_PORTRAIT
        updateOrientationUI(isPortrait)
        textureView.post {
            configureTransform(textureView.width, textureView.height)
        }
    }

    /**
     * Mengatur Matrix Transform pada TextureView agar rasio kamera 16:9 tetap proporsional
     * (tidak gepeng / terdistorsi) pada layar HP ultra-wide (20:9 atau 21:9),
     * baik dalam mode Landscape maupun Portrait.
     */
    private fun configureTransform(viewWidth: Int, viewHeight: Int) {
        if (viewWidth == 0 || viewHeight == 0) return

        val matrix = Matrix()
        val rotation = windowManager.defaultDisplay.rotation
        val viewRect = RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
        val bufferRect = RectF(0f, 0f, PREVIEW_HEIGHT.toFloat(), PREVIEW_WIDTH.toFloat())
        val centerX = viewRect.centerX()
        val centerY = viewRect.centerY()

        if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
            val scale = maxOf(
                viewHeight.toFloat() / PREVIEW_HEIGHT,
                viewWidth.toFloat() / PREVIEW_WIDTH
            )
            matrix.postScale(scale, scale, centerX, centerY)
            matrix.postRotate((90 * (rotation - 2)).toFloat(), centerX, centerY)
        } else if (rotation == Surface.ROTATION_180) {
            matrix.postRotate(180f, centerX, centerY)
        } else {
            // Mode Portrait (ROTATION_0)
            val scale = maxOf(
                viewWidth.toFloat() / PREVIEW_HEIGHT,
                viewHeight.toFloat() / PREVIEW_WIDTH
            )
            matrix.postScale(scale, scale, centerX, centerY)
        }
        textureView.setTransform(matrix)
    }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            val cameraId = manager.cameraIdList[0]

            imageReader = ImageReader.newInstance(PREVIEW_WIDTH, PREVIEW_HEIGHT, ImageFormat.YUV_420_888, 3)
            imageReader?.setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                processCameraFrame(image)
                image.close()
            }, backgroundHandler)

            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    createCameraPreviewSession()
                }
                override fun onDisconnected(camera: CameraDevice) { camera.close(); cameraDevice = null }
                override fun onError(camera: CameraDevice, error: Int) { camera.close(); cameraDevice = null }
            }, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open camera: ${e.message}")
        }
    }

    private fun createCameraPreviewSession() {
        try {
            val texture = textureView.surfaceTexture ?: return
            texture.setDefaultBufferSize(PREVIEW_WIDTH, PREVIEW_HEIGHT)
            val surface = Surface(texture)
            val readerSurface = imageReader!!.surface

            val previewRequestBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
                addTarget(readerSurface)

                // Continuous Auto-Focus (Aktif & Tajam Otomatis pada monitor)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                // Auto Exposure & Auto White Balance
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
            }

            cameraDevice?.createCaptureSession(
                listOf(surface, readerSurface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        try {
                            session.setRepeatingRequest(previewRequestBuilder.build(), null, backgroundHandler)
                            runOnUiThread {
                                configureTransform(textureView.width, textureView.height)
                            }
                        } catch (e: CameraAccessException) {
                            Log.e(TAG, "Camera preview failed: ${e.message}")
                        }
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "Camera session config failed")
                    }
                }, backgroundHandler
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error creating camera session: ${e.message}")
        }
    }

    /**
     * Tap to focus manual pada area layar monitor
     */
    private fun triggerAutoFocus() {
        try {
            val session = captureSession ?: return
            val requestBuilder = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)?.apply {
                val surface = Surface(textureView.surfaceTexture ?: return)
                addTarget(surface)
                val readerSurf = imageReader?.surface ?: return
                addTarget(readerSurf)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
            } ?: return
            session.capture(requestBuilder.build(), null, backgroundHandler)
        } catch (e: Exception) {
            Log.w(TAG, "AutoFocus trigger failed: ${e.message}")
        }
    }

    private fun processCameraFrame(image: Image) {
        val width = image.width
        val height = image.height
        val totalNv21Bytes = width * height * 3 / 2

        if (nv21Buffer == null || nv21Buffer!!.size != totalNv21Bytes) {
            nv21Buffer = ByteArray(totalNv21Bytes)
        }
        val buffer = nv21Buffer!!
        yuv420ToNv21(image, buffer)

        val timestampSec = (SystemClock.elapsedRealtimeNanos() / 1_000_000_000.0).toFloat()
        val rotation = windowManager.defaultDisplay.rotation
        val rotationDegrees = when (rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        val isLocked = nativeBridge.processFrameNV21(
            buffer, width, height, timestampSec, rotationDegrees, visionResults, visionCorners
        )

        val normX: Float
        val normY: Float

        if (isLocked) {
            normX = visionResults[0]
            normY = visionResults[1]
        } else {
            // Gyroscope Hybrid Dead-Reckoning:
            // Saat kamera kehilangan border layar sesaat, gunakan delta gyro agar bidikan tetap mengalir mulus
            normX = (visionResults[0] + sensorsManager.gyroDeltaX * 0.35f).coerceIn(0f, 1f)
            normY = (visionResults[1] - sensorsManager.gyroDeltaY * 0.35f).coerceIn(0f, 1f)
        }

        var flags = visionResults[2].toInt()
        if (isPlayer2) {
            flags = flags or (1 shl 3) // LightgunFlags.PLAYER_2
        }
        val confidence = visionResults[3].toInt()
        val currentButtons = buttonMask.get()
        val pitch = sensorsManager.pitch
        val roll = sensorsManager.roll
        val timestampMs = (SystemClock.elapsedRealtime() and 0xFFFF).toInt()

        // Kirim via Wi-Fi UDP (Ultra-low latency 1ms, no BLE throttle)
        if (networkTransmitter.isConnected) {
            networkTransmitter.sendTelemetry(
                normX, normY, flags, currentButtons, pitch, roll, timestampMs, confidence
            )
        }

        // Kirim juga via BLE jika terhubung
        if (bluetoothTransmitter.isClientConnected) {
            bluetoothTransmitter.sendTelemetry(
                normX, normY, flags, currentButtons, pitch, roll, timestampMs, confidence
            )
        }

        // Update UI
        frameCount++
        val now = SystemClock.elapsedRealtime()
        if (now - lastFpsTimestamp >= 1000) {
            val fps = frameCount
            frameCount = 0
            lastFpsTimestamp = now
            runOnUiThread {
                tvFps.text = "$fps FPS"
                updateBtStatusUI()
            }
        }

        runOnUiThread {
            cornerOverlay.updateCorners(visionCorners, isLocked)
            if (isLocked) {
                tvTrackingStatus.text = getString(R.string.tracking_locked)
                tvTrackingStatus.setTextColor(ContextCompat.getColor(this, R.color.cyber_green))
            } else {
                tvTrackingStatus.text = getString(R.string.tracking_lost)
                tvTrackingStatus.setTextColor(ContextCompat.getColor(this, R.color.cyber_orange))
            }
            tvCoords.text = String.format("X: %.3f | Y: %.3f", normX, normY)
        }
    }

    private fun updateBtStatusUI() {
        val wifiConnected = networkTransmitter.isConnected
        val btConnected = bluetoothTransmitter.isClientConnected

        if (wifiConnected) {
            tvBtStatus.text = "📶 WI-FI: OK"
            tvBtStatus.setTextColor(ContextCompat.getColor(this, R.color.cyber_green))
            val dotDrawable = dotBt.background
            if (dotDrawable is GradientDrawable) {
                dotDrawable.setColor(ContextCompat.getColor(this, R.color.cyber_green))
            }
        } else if (btConnected) {
            tvBtStatus.text = "● BT: OK"
            tvBtStatus.setTextColor(ContextCompat.getColor(this, R.color.cyber_green))
            val dotDrawable = dotBt.background
            if (dotDrawable is GradientDrawable) {
                dotDrawable.setColor(ContextCompat.getColor(this, R.color.cyber_green))
            }
        } else {
            tvBtStatus.text = "SEARCHING..."
            tvBtStatus.setTextColor(ContextCompat.getColor(this, R.color.cyber_orange))
            val dotDrawable = dotBt.background
            if (dotDrawable is GradientDrawable) {
                dotDrawable.setColor(ContextCompat.getColor(this, R.color.cyber_orange))
            }
        }
    }

    private fun yuv420ToNv21(image: Image, nv21: ByteArray) {
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        val width = image.width
        val height = image.height

        val yRowStride = yPlane.rowStride
        val yPixelStride = yPlane.pixelStride
        var pos = 0

        if (yPixelStride == 1 && yRowStride == width) {
            yBuffer.get(nv21, 0, width * height)
            pos = width * height
        } else {
            for (row in 0 until height) {
                yBuffer.position(row * yRowStride)
                yBuffer.get(nv21, pos, width)
                pos += width
            }
        }

        val uvHeight = height / 2
        val uvWidth = width / 2
        val vRowStride = vPlane.rowStride
        val vPixelStride = vPlane.pixelStride
        val uRowStride = uPlane.rowStride
        val uPixelStride = uPlane.pixelStride

        for (row in 0 until uvHeight) {
            val vRowStart = row * vRowStride
            val uRowStart = row * uRowStride
            for (col in 0 until uvWidth) {
                nv21[pos++] = vBuffer.get(vRowStart + col * vPixelStride)
                nv21[pos++] = uBuffer.get(uRowStart + col * uPixelStride)
            }
        }
    }

    private fun allPermissionsGranted(): Boolean {
        return REQUIRED_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSIONS_REQUEST_CODE && allPermissionsGranted()) {
            startSystems()
            if (textureView.isAvailable) openCamera()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopBackgroundThread()
        sensorsManager.stop()
        bluetoothTransmitter.stop()
        networkTransmitter.stop()
        captureSession?.close()
        cameraDevice?.close()
        imageReader?.close()
    }
}
