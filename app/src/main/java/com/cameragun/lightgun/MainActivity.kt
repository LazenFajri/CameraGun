package com.cameragun.lightgun

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.os.*
import android.util.Log
import android.view.*
import android.widget.Button
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
    }

    private lateinit var textureView: TextureView
    private lateinit var cornerOverlay: CornerOverlayView
    private lateinit var tvBtStatus: TextView
    private lateinit var tvTrackingStatus: TextView
    private lateinit var tvCoords: TextView
    private lateinit var tvFps: TextView
    private lateinit var dotBt: View

    private val nativeBridge = NativeVisionBridge()
    private lateinit var bluetoothTransmitter: BluetoothTransmitter
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
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
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
        setupControllerButtons()

        sensorsManager = SensorsManager(this)
        nativeBridge.initVision(1920, 1080)

        bluetoothTransmitter = BluetoothTransmitter(this) { hMin, sMin, vMin, hMax, sMax, vMax, w, h ->
            nativeBridge.updateHsvBoundaries(hMin, sMin, vMin, hMax, sMax, vMax)
            nativeBridge.initVision(w, h)
        }

        if (allPermissionsGranted()) {
            startSystems()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, PERMISSIONS_REQUEST_CODE)
        }
    }

    private fun initViews() {
        textureView = findViewById(R.id.cameraPreview)
        cornerOverlay = findViewById(R.id.cornerOverlay)
        tvBtStatus = findViewById(R.id.tvBtStatus)
        tvTrackingStatus = findViewById(R.id.tvTrackingStatus)
        tvCoords = findViewById(R.id.tvCoords)
        tvFps = findViewById(R.id.tvFps)
        dotBt = findViewById(R.id.dotBt)
        textureView.surfaceTextureListener = this

        // Settings gear button
        findViewById<Button>(R.id.btnSettings).setOnClickListener {
            vibrateLight()
            // Future: open SettingsBottomSheet here
        }
    }

    private fun setupControllerButtons() {
        // Primary controls with haptic feedback
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
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun bindHapticButton(button: Button, bit: Int, heavy: Boolean = false) {
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createOneShot(15, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            vibrator?.vibrate(15)
        }
    }

    @Suppress("DEPRECATION")
    private fun vibrateHeavy() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            vibrator?.vibrate(40)
        }
    }

    private fun startSystems() {
        sensorsManager.start()
        bluetoothTransmitter.start()
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
        if (allPermissionsGranted()) openCamera()
    }
    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            val cameraId = manager.cameraIdList[0]
            val previewWidth = 1280
            val previewHeight = 720

            imageReader = ImageReader.newInstance(previewWidth, previewHeight, ImageFormat.YUV_420_888, 3)
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
            texture.setDefaultBufferSize(1280, 720)
            val surface = Surface(texture)
            val readerSurface = imageReader!!.surface

            val previewRequestBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
                addTarget(readerSurface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                set(CaptureRequest.LENS_FOCUS_DISTANCE, 0.0f)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, android.util.Range(60, 60))
            }

            cameraDevice?.createCaptureSession(
                listOf(surface, readerSurface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        try {
                            session.setRepeatingRequest(previewRequestBuilder.build(), null, backgroundHandler)
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
        val isLocked = nativeBridge.processFrameNV21(
            buffer, width, height, timestampSec, visionResults, visionCorners
        )

        val normX = visionResults[0]
        val normY = visionResults[1]
        val flags = visionResults[2].toInt()
        val confidence = visionResults[3].toInt()
        val currentButtons = buttonMask.get()
        val pitch = sensorsManager.pitch
        val roll = sensorsManager.roll
        val timestampMs = (SystemClock.elapsedRealtime() and 0xFFFF).toInt()

        bluetoothTransmitter.sendTelemetry(
            normX, normY, flags, currentButtons, pitch, roll, timestampMs, confidence
        )

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
        val connected = bluetoothTransmitter.isClientConnected
        tvBtStatus.text = if (connected) getString(R.string.bt_connected) else getString(R.string.bt_disconnected)
        val statusColor = if (connected) R.color.cyber_green else R.color.cyber_orange
        tvBtStatus.setTextColor(ContextCompat.getColor(this, statusColor))

        // Update dot indicator color
        val dotDrawable = dotBt.background
        if (dotDrawable is GradientDrawable) {
            dotDrawable.setColor(ContextCompat.getColor(this, statusColor))
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
        captureSession?.close()
        cameraDevice?.close()
        imageReader?.close()
    }
}
