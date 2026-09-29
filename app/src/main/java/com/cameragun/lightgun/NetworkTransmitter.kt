package com.cameragun.lightgun

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors

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
    private var discoveryThread: Thread? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    // High-priority single-threaded sender executor to guarantee zero UI thread blocking & no NetworkOnMainThreadException
    private val sendExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "CameraGun-UdpSend").apply { priority = Thread.MAX_PRIORITY }
    }

    fun start(savedIp: String? = null) {
        if (isRunning) return
        isRunning = true

        acquireMulticastLock()

        Thread {
            try {
                socket = DatagramSocket()
                socket?.broadcast = true
                socket?.reuseAddress = true

                startReceiveLoop()

                if (!savedIp.isNullOrBlank()) {
                    connectDirect(savedIp, DEFAULT_PORT)
                }

                startDiscoveryHeartbeat(savedIp)
            } catch (e: Exception) {
                Log.e(TAG, "Socket init error: ${e.message}")
            }
        }.start()
    }

    private fun acquireMulticastLock() {
        try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            multicastLock = wifi?.createMulticastLock("CameraGunMulticast")?.apply {
                setReferenceCounted(true)
                acquire()
            }
            Log.i(TAG, "WifiManager MulticastLock acquired.")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire MulticastLock: ${e.message}")
        }
    }

    private fun startDiscoveryHeartbeat(savedIp: String?) {
        discoveryThread = Thread {
            var attempt = 0
            while (isRunning) {
                try {
                    // Send discovery if not yet connected, or periodically every 15s to keep NAT port alive
                    if (!isConnected || attempt % 10 == 0) {
                        sendDiscoveryPing(savedIp)
                    }
                    attempt++
                    Thread.sleep(1500)
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    Log.w(TAG, "Discovery ping error: ${e.message}")
                }
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    private fun sendDiscoveryPing(targetDirectIp: String? = null) {
        val msg = "CAMERAGUN_DISCOVER".toByteArray()
        val s = socket ?: return

        // 1. Send to Global Broadcast
        try {
            val globalBcast = InetAddress.getByName("255.255.255.255")
            s.send(DatagramPacket(msg, msg.size, globalBcast, DEFAULT_PORT))
        } catch (_: Exception) {}

        // 2. Send to Local Subnet Broadcast
        try {
            getBroadcastAddress()?.let { bcast ->
                s.send(DatagramPacket(msg, msg.size, bcast, DEFAULT_PORT))
            }
        } catch (_: Exception) {}

        // 3. Send directly to saved IP if available
        if (!targetDirectIp.isNullOrBlank()) {
            try {
                var clean = targetDirectIp.trim()
                if (clean.contains(":")) clean = clean.split(":")[0]
                val ipAddr = InetAddress.getByName(clean)
                s.send(DatagramPacket(msg, msg.size, ipAddr, DEFAULT_PORT))
            } catch (_: Exception) {}
        }

        // 4. Send to serverAddress if already resolved
        serverAddress?.let { sAddr ->
            try {
                s.send(DatagramPacket(msg, msg.size, sAddr, serverPort))
            } catch (_: Exception) {}
        }
    }

    fun autoDiscover() {
        sendExecutor.execute {
            try {
                mainHandler.post { onStatusChanged?.invoke("Mencari PC di Wi-Fi...", false) }
                sendDiscoveryPing(connectedIp)
            } catch (e: Exception) {
                Log.w(TAG, "Manual auto-discover error: ${e.message}")
            }
        }
    }

    fun scanSubnet(onProgress: ((current: Int, total: Int) -> Unit)? = null) {
        Thread {
            try {
                mainHandler.post { onStatusChanged?.invoke("Memindai subnet LAN...", false) }
                val localIp = getLocalIpv4Address()
                if (localIp == null || !localIp.startsWith("192.168.")) {
                    autoDiscover()
                    return@Thread
                }

                val prefix = localIp.substringBeforeLast(".") + "."
                val msg = "CAMERAGUN_DISCOVER".toByteArray()
                val s = socket ?: return@Thread

                for (i in 1..254) {
                    if (!isRunning || isConnected) break
                    try {
                        val target = InetAddress.getByName("$prefix$i")
                        s.send(DatagramPacket(msg, msg.size, target, DEFAULT_PORT))
                    } catch (_: Exception) {}

                    if (i % 25 == 0) {
                        mainHandler.post { onProgress?.invoke(i, 254) }
                        Thread.sleep(10)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Subnet scan error: ${e.message}")
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

                    if (len >= 20) {
                        val text = String(data, 0, len)
                        if (text.startsWith("CAMERAGUN_SERVER_ACK") || text.startsWith("CAMERAGUN_SERVER_ANNOUNCE")) {
                            // Format: CAMERAGUN_SERVER_ACK:<serverIp>:<serverPort> or CAMERAGUN_SERVER_ANNOUNCE:<serverIp>:<serverPort>
                            val parts = text.split(":")
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

                                // Send ACK back to confirm connection
                                val ackPkt = "CAMERAGUN_DISCOVER".toByteArray()
                                socket?.send(DatagramPacket(ackPkt, ackPkt.size, serverAddress, serverPort))
                            }
                            continue
                        }
                    }

                    if (len == 16 && data[0] == 0xBB.toByte()) {
                        parseConfigPacket(data.copyOf(16))
                    }
                } catch (e: Exception) {
                    if (!isRunning) break
                }
            }
        }.apply {
            isDaemon = true
            start()
        }
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
        val s = socket ?: return

        val clampedX = (normX.coerceIn(0.0f, 1.0f) * 65535.0f).toInt()
        val clampedY = (normY.coerceIn(0.0f, 1.0f) * 65535.0f).toInt()

        val buffer = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
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

        sendExecutor.execute {
            try {
                val packet = DatagramPacket(rawBytes, 16, sAddr, serverPort)
                s.send(packet)
            } catch (e: Exception) {
                // Ignore transient packet send errors
            }
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
            val dhcp = wifi?.dhcpInfo
            if (dhcp != null && dhcp.netmask != 0) {
                val broadcast = (dhcp.ipAddress and dhcp.netmask) or dhcp.netmask.inv()
                val quads = ByteArray(4)
                for (k in 0..3) quads[k] = (broadcast shr (k * 8) and 0xFF).toByte()
                return InetAddress.getByAddress(quads)
            }

            // Fallback: Check network interfaces
            for (iface in NetworkInterface.getNetworkInterfaces()) {
                if (iface.isLoopback || !iface.isUp) continue
                for (addr in iface.interfaceAddresses) {
                    addr.broadcast?.let { return it }
                }
            }
        } catch (_: Exception) {}
        return null
    }

    private fun getLocalIpv4Address(): String? {
        try {
            for (iface in NetworkInterface.getNetworkInterfaces()) {
                if (iface.isLoopback || !iface.isUp) continue
                for (addr in iface.inetAddresses) {
                    val host = addr.hostAddress ?: continue
                    if (!addr.isLoopbackAddress && host.contains(".") && !host.startsWith("127.")) {
                        return host
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }

    fun stop() {
        isRunning = false
        try {
            discoveryThread?.interrupt()
            discoveryThread = null
            socket?.close()
        } catch (_: Exception) {}
        socket = null
        isConnected = false

        try {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
            }
        } catch (_: Exception) {}
        multicastLock = null
    }
}
