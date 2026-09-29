package com.cameragun.lightgun

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

@SuppressLint("MissingPermission")
class BluetoothTransmitter(
    private val context: Context,
    private val onConfigReceived: (hMin: Int, sMin: Int, vMin: Int, hMax: Int, sMax: Int, vMax: Int, w: Int, h: Int) -> Unit
) {
    companion object {
        private const val TAG = "CameraGun-BLE"

        val SERVICE_UUID: UUID = UUID.fromString("e0a10001-1234-4a5b-9b8c-123456789abc")
        val TELEMETRY_CHAR_UUID: UUID = UUID.fromString("e0a10002-1234-4a5b-9b8c-123456789abc")
        val CONFIG_CHAR_UUID: UUID = UUID.fromString("e0a10003-1234-4a5b-9b8c-123456789abc")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private val bluetoothManager: BluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private var gattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var connectedDevice: BluetoothDevice? = null

    private var telemetryCharacteristic: BluetoothGattCharacteristic? = null

    var isClientConnected: Boolean = false
        private set

    var onStatusChanged: ((String, Boolean) -> Unit)? = null

    fun start(): Boolean {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            Log.e(TAG, "Bluetooth tidak aktif atau tidak didukung pada perangkat ini.")
            onStatusChanged?.invoke("BT OFF", false)
            return false
        }

        setupGattServer()
        startAdvertising()
        return true
    }

    private fun setupGattServer() {
        val serverCallback = object : BluetoothGattServerCallback() {
            override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    Log.i(TAG, "PC Client terhubung: ${device.address}")
                    connectedDevice = device
                    isClientConnected = true
                    onStatusChanged?.invoke("CONNECTED", true)
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Log.i(TAG, "PC Client terputus: ${device.address}")
                    connectedDevice = null
                    isClientConnected = false
                    onStatusChanged?.invoke("DISCONNECTED", false)
                }
            }

            override fun onCharacteristicWriteRequest(
                device: BluetoothDevice,
                requestId: Int,
                characteristic: BluetoothGattCharacteristic,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray?
            ) {
                if (characteristic.uuid == CONFIG_CHAR_UUID && value != null && value.size == 16) {
                    parseConfigPacket(value)
                }
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
                }
            }

            override fun onDescriptorWriteRequest(
                device: BluetoothDevice,
                requestId: Int,
                descriptor: BluetoothGattDescriptor,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray?
            ) {
                if (descriptor.uuid == CCCD_UUID) {
                    if (responseNeeded) {
                        gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
                    }
                    Log.i(TAG, "PC Client mengaktifkan notifikasi telemetry stream.")
                }
            }

            override fun onNotificationSent(device: BluetoothDevice?, status: Int) {
                // Notifikasi dikirim langsung realtime tanpa antrian
            }
        }

        gattServer = bluetoothManager.openGattServer(context, serverCallback)

        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)

        // Karakteristik Telemetry (Notify & Read)
        val telemetryChar = BluetoothGattCharacteristic(
            TELEMETRY_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        val cccd = BluetoothGattDescriptor(CCCD_UUID, BluetoothGattDescriptor.PERMISSION_WRITE)
        telemetryChar.addDescriptor(cccd)

        // Karakteristik Konfigurasi Warna Layar (Write)
        val configChar = BluetoothGattCharacteristic(
            CONFIG_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )

        service.addCharacteristic(telemetryChar)
        service.addCharacteristic(configChar)
        gattServer?.addService(service)
        telemetryCharacteristic = telemetryChar
    }

    private fun startAdvertising() {
        advertiser = bluetoothAdapter?.bluetoothLeAdvertiser
        if (advertiser == null) {
            Log.e(TAG, "BLE Advertiser tidak didukung pada hardware ini.")
            onStatusChanged?.invoke("ADV UNSUPPORTED", false)
            return
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()

        // Paket data utama: HANYA Service UUID (21 byte < limit 31 byte)
        // Jangan sertakan nama di sini karena jika nama HP panjang (>8 char) totalnya >31 byte!
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()

        // Scan response: Nama perangkat dikirim terpisah di buffer 31 byte tersendiri
        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .build()

        advertiser?.startAdvertising(settings, data, scanResponse, object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                Log.i(TAG, "BLE Advertising berhasil dimulai dengan nama: ${bluetoothAdapter?.name}")
                onStatusChanged?.invoke("WAITING PC...", false)
            }

            override fun onStartFailure(errorCode: Int) {
                Log.w(TAG, "Gagal memulai BLE Advertising dengan scanResponse: error $errorCode. Mencoba fallback data-only...")
                advertiser?.startAdvertising(settings, data, object : AdvertiseCallback() {
                    override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                        Log.i(TAG, "BLE Advertising fallback data-only berhasil dimulai.")
                        onStatusChanged?.invoke("WAITING PC...", false)
                    }

                    override fun onStartFailure(fallbackErr: Int) {
                        Log.e(TAG, "BLE Advertising fallback juga gagal: error $fallbackErr")
                        onStatusChanged?.invoke("ADV ERR: $fallbackErr", false)
                    }
                })
            }
        })
    }

    /**
     * Mengirim packet telemetry biner 16-byte ke PC
     */
    fun sendTelemetry(
        normX: Float,
        normY: Float,
        flags: Int,
        buttonMask: Int,
        pitch: Short,
        roll: Short,
        timestampMs: Int,
        confidence: Int
    ) {
        val device = connectedDevice ?: return
        val char = telemetryCharacteristic ?: return

        val buffer = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        val clampedX = (normX.coerceIn(0.0f, 1.0f) * 65535.0f).toInt()
        val clampedY = (normY.coerceIn(0.0f, 1.0f) * 65535.0f).toInt()

        buffer.put(0xAA.toByte())                    // 0: Header
        buffer.put(flags.toByte())                   // 1: Flags
        buffer.putShort(clampedX.toShort())          // 2-3: X
        buffer.putShort(clampedY.toShort())          // 4-5: Y
        buffer.putShort(buttonMask.toShort())        // 6-7: Buttons
        buffer.putShort(pitch)                       // 8-9: Gyro Pitch
        buffer.putShort(roll)                        // 10-11: Gyro Roll
        buffer.putShort((timestampMs and 0xFFFF).toShort()) // 12-13: Timestamp
        buffer.put(confidence.toByte())              // 14: Confidence

        val rawBytes = buffer.array()
        val crc = computeCrc8(rawBytes, 15)
        rawBytes[15] = crc                           // 15: Checksum

        try {
            char.value = rawBytes
            gattServer?.notifyCharacteristicChanged(device, char, false)
        } catch (e: Exception) {
            Log.w(TAG, "Gagal mengirim notifikasi BLE: ${e.message}")
        }
    }

    private fun parseConfigPacket(bytes: ByteArray) {
        val crcCalculated = computeCrc8(bytes, 15)
        if (bytes[0] == 0xBB.toByte() && bytes[15] == crcCalculated) {
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            buffer.get() // Skip Header
            val hMin = buffer.get().toInt() and 0xFF
            val sMin = buffer.get().toInt() and 0xFF
            val vMin = buffer.get().toInt() and 0xFF
            val hMax = buffer.get().toInt() and 0xFF
            val sMax = buffer.get().toInt() and 0xFF
            val vMax = buffer.get().toInt() and 0xFF
            val screenW = buffer.short.toInt() and 0xFFFF
            val screenH = buffer.short.toInt() and 0xFFFF
            
            Log.i(TAG, "Menerima update kalibrasi border PC: HSV=[$hMin..$hMax], Res=${screenW}x${screenH}")
            onConfigReceived(hMin, sMin, vMin, hMax, sMax, vMax, screenW, screenH)
        } else {
            Log.w(TAG, "Config packet checksum tidak cocok!")
        }
    }

    private fun computeCrc8(data: ByteArray, length: Int): Byte {
        var crc = 0x00
        for (i in 0 until length) {
            var extract = data[i].toInt() and 0xFF
            for (j in 8 downTo 1) {
                val sum = (crc xor extract) and 0x01
                crc = crc ushr 1
                if (sum != 0) {
                    crc = crc xor 0x8C
                }
                extract = extract ushr 1
            }
        }
        return crc.toByte()
    }

    fun stop() {
        try {
            advertiser?.stopAdvertising(object : AdvertiseCallback() {})
            gattServer?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error saat menutup BLE: ${e.message}")
        }
        connectedDevice = null
        isClientConnected = false
    }
}
