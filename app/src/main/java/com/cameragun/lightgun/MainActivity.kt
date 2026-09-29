package com.cameragun.lightgun

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.os.*
import android.util.Log
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
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

    private val nativeBridge = NativeVisionBridge()
    private lateinit var bluetoothTransmitter: BluetoothTransmitter
    private lateinit var sensorsManager: SensorsManager

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private val buttonMask = AtomicInteger(0)
    private var frameCount = 0
    private var lastFpsTimestamp = SystemClock.elapsedRealtime()

    // Vision Reusable Buffers
    private val visionResults = FloatArray(4)
    private val visionCorners = FloatArray(8)
    private var nv21Buffer: ByteArray? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )
        setContentView(R.layout.activity_main)

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

        textureView.surfaceTextureListener = this
    }

    private fun setupControllerButtons() {
        bindTouchButton(findViewById(R.id.btnTriggerL2), 1 shl 0)
        bindTouchButton(findViewById(R.id.btnCross), 1 shl 1)
        bindTouchButton(findViewById(R.id.btnCircle), 1 shl 2)
        bindTouchButton(findViewById(R.id.btnSquare), 1 shl 3)
        bindTouchButton(findViewById(R.id.btnTriangle), 1 shl 4)
        bindTouchButton(findViewById(R.id.btnDpadUp), 1 shl 5)
        bindTouchButton(findViewById(R.id.btnDpadDown), 1 shl 6)
        bindTouchButton(findViewById(R.id.btnDpadLeft), 1 shl 7)
        bindTouchButton(findViewById(R.id.btnDpadRight), 1 shl 8)
        bindTouchButton(findViewById(R.id.btnReload), 1 shl 9)
        bindTouchButton(findViewById(R.id.btnOptions), 1 shl 10)
        bindTouchButton(findViewById(R.id.btnShare), 1 shl 11)
        bindTouchButton(findViewById(R.id.btnTriggerR2), 1 shl 12)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun bindTouchButton(button: Button, bit: Int) {
        button.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    buttonMask.updateAndGet { it or bit }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    buttonMask.updateAndGet { it and bit.inv() }
                    true
                }
                else -> false
            }
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
        if (allPermissionsGranted()) {
            openCamera()
        }
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            val cameraId = manager.cameraIdList[0] // Back camera
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

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cameraDevice = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    cameraDevice = null
                }
            }, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Gagal membuka kamera: ${e.message}")
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
                
                // Kunci Auto-Exposure dan Focus ke Infinity untuk stabilitas deteksi border layar
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                set(CaptureRequest.LENS_FOCUS_DISTANCE, 0.0f) // Focus infinity
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
                            Log.e(TAG, "Gagal memulai request preview kamera: ${e.message}")
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "Konfigurasi kamera gagal")
                    }
                },
                backgroundHandler
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error saat inisialisasi session kamera: ${e.message}")
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

        // Kirim packet BLE ke Windows Driver
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
                tvFps.text = "FPS: $fps"
                tvBtStatus.text = if (bluetoothTransmitter.isClientConnected) {
                    getString(R.string.bt_connected)
                } else {
                    getString(R.string.bt_disconnected)
                }
                tvBtStatus.setTextColor(if (bluetoothTransmitter.isClientConnected) 0xFF00FF66.toInt() else 0xFFFFAA00.toInt())
            }
        }

        runOnUiThread {
            cornerOverlay.updateCorners(visionCorners, isLocked)
            tvTrackingStatus.text = if (isLocked) getString(R.string.tracking_locked) else getString(R.string.tracking_lost)
            tvTrackingStatus.setTextColor(if (isLocked) 0xFF00FF66.toInt() else 0xFFFF3333.toInt())
            tvCoords.text = String.format("X: %.3f | Y: %.3f", normX, normY)
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

        // 1. Salin bidang Y
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

        // 2. Interleave bidang V dan U menjadi NV21 (VU order)
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
                val vVal = vBuffer.get(vRowStart + col * vPixelStride)
                val uVal = uBuffer.get(uRowStart + col * uPixelStride)
                nv21[pos++] = vVal
                nv21[pos++] = uVal
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
        if (requestCode == PERMISSIONS_REQUEST_CODE) {
            if (allPermissionsGranted()) {
                startSystems()
                if (textureView.isAvailable) openCamera()
            }
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
