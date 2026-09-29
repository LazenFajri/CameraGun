package com.cameragun.lightgun

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

class NetworkTransmitter(
    private val context: Context,
    private val onConfigReceived: (hMin: Int, sMin: Int, vMin: Int, hMax: Int, sMax: Int, vMax: Int, w: Int, h: Int) -> Unit
) {
    companion object {
        private const val TAG = "CameraGun-Net"
        const val DEFAULT_PORT = 8765
    }

    private var socket: DatagramSocket? = null
    private var serverAddress: InetAddress? = null
    private var serverPort: Int = DEFAULT_PORT

    @Volatile
    var isConnected: Boolean = false
        private set

    @Volatile
    var connectedIp: String? = null
        private set

    var onStatusChanged: ((String, Boolean) -> Unit)? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var isRunning = false
    private var receiveThread: Thread? = null

    fun start(savedIp: String? = null) {
        isRunning = true
        Thread {
            try {
                socket = DatagramSocket()
                socket?.broadcast = true

                startReceiveLoop()

                if (!savedIp.isNullOrBlank()) {
                    connectDirect(savedIp, DEFAULT_PORT)
                } else {
                    autoDiscover()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Socket init error: ${e.message}")
            }
        }.start()
    }

    fun autoDiscover() {
        Thread {
            try {
                mainHandler.post { onStatusChanged?.invoke("Mencari PC di Wi-Fi...", false) }
                val msg = "CAMERAGUN_DISCOVER".toByteArray()
                val broadcastAddr = getBroadcastAddress() ?: InetAddress.getByName("255.255.255.255")
                val packet = DatagramPacket(msg, msg.size, broadcastAddr, DEFAULT_PORT)
                socket?.send(packet)
            } catch (e: Exception) {
                Log.w(TAG, "Auto-discover error: ${e.message}")
            }
        }.start()
    }

    fun connectDirect(ip: String, port: Int = DEFAULT_PORT) {
        Thread {
            try {
                var cleanIp = ip.trim()
                if (cleanIp.startsWith("cameragun://")) {
                    cleanIp = cleanIp.removePrefix("cameragun://")
                }
                if (cleanIp.startsWith("http://")) {
                    cleanIp = cleanIp.removePrefix("http://")
                }
                var targetPort = port
                if (cleanIp.contains(":")) {
                    val parts = cleanIp.split(":")
                    cleanIp = parts[0]
                    targetPort = parts[1].toIntOrNull() ?: port
                }

                serverAddress = InetAddress.getByName(cleanIp)
                serverPort = targetPort
                connectedIp = cleanIp
                isConnected = true
                mainHandler.post {
                    onStatusChanged?.invoke("Wi-Fi: Terhubung ke $connectedIp:$serverPort", true)
                }
                // Kirim paket handshake perkenalan
                val hello = "CAMERAGUN_DISCOVER".toByteArray()
                val packet = DatagramPacket(hello, hello.size, serverAddress, serverPort)
                socket?.send(packet)
            } catch (e: Exception) {
                Log.e(TAG, "Connect error: ${e.message}")
                mainHandler.post {
                    onStatusChanged?.invoke("Gagal koneksi ke $ip", false)
                }
            }
        }.start()
    }

    private fun startReceiveLoop() {
        receiveThread = Thread {
            val buf = ByteArray(1024)
            while (isRunning) {
                try {
                    val packet = DatagramPacket(buf, buf.size)
                    socket?.receive(packet)
                    val data = packet.data
                    val len = packet.length

                    if (len >= 20 && String(data, 0, len).startsWith("CAMERAGUN_SERVER_ACK")) {
                        // Format: CAMERAGUN_SERVER_ACK:<serverIp>:<serverPort>
                        val parts = String(data, 0, len).split(":")
                        if (parts.size >= 3) {
                            val ip = parts[1]
                            val port = parts[2].toIntOrNull() ?: DEFAULT_PORT
                            serverAddress = InetAddress.getByName(ip)
                            serverPort = port
                            connectedIp = ip
                            isConnected = true
                            mainHandler.post {
                                onStatusChanged?.invoke("Wi-Fi: Terhubung ke $ip:$port", true)
                            }
                        }
                    } else if (len == 16 && data[0] == 0xBB.toByte()) {
                        parseConfigPacket(data.copyOf(16))
                    }
                } catch (e: Exception) {
                    if (!isRunning) break
                }
            }
        }
        receiveThread?.isDaemon = true
        receiveThread?.start()
    }

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
        val sAddr = serverAddress ?: return
        if (!isConnected) return

        try {
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
            rawBytes[15] = computeCrc8(rawBytes, 15)     // 15: Checksum

            val packet = DatagramPacket(rawBytes, 16, sAddr, serverPort)
            socket?.send(packet)
        } catch (e: Exception) {
            Log.w(TAG, "SendTelemetry UDP error: ${e.message}")
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

            Log.i(TAG, "Menerima update kalibrasi border PC via Wi-Fi: HSV=[$hMin..$hMax]")
            mainHandler.post {
                onConfigReceived(hMin, sMin, vMin, hMax, sMax, vMax, screenW, screenH)
            }
        }
    }

    private fun computeCrc8(data: ByteArray, length: Int): Byte {
        var crc = 0x00
        for (i in 0 until length) {
            var extract = data[i].toInt() and 0xFF
            for (j in 8 downTo 1) {
                val sum = (crc xor extract) and 0x01
                crc = crc ushr 1
                if (sum != 0) crc = crc xor 0x8C
                extract = extract ushr 1
            }
        }
        return crc.toByte()
    }

    private fun getBroadcastAddress(): InetAddress? {
        try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val dhcp = wifi?.dhcpInfo ?: return null
            val broadcast = (dhcp.ipAddress and dhcp.netmask) or dhcp.netmask.inv()
            val quads = ByteArray(4)
            for (k in 0..3) quads[k] = (broadcast shr (k * 8) and 0xFF).toByte()
            return InetAddress.getByAddress(quads)
        } catch (e: Exception) {
            return null
        }
    }

    fun stop() {
        isRunning = false
        try {
            socket?.close()
        } catch (e: Exception) { }
        socket = null
        isConnected = false
    }
}
