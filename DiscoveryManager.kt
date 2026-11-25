package com.bridge.itell

import android.os.Build
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.concurrent.thread

data class DiscoveredHost(val ip: String, val name: String, val battery: Int, val operator: String)

class DiscoveryManager {
    companion object {
        const val DISCOVERY_PORT = 8889
    }

    @Volatile private var broadcasting = false
    @Volatile private var listening = false
    private var listenSocket: DatagramSocket? = null

    fun startHostBroadcast(getBattery: () -> Int, getOperator: () -> String, getIp: () -> String) {
        if (broadcasting) return
        broadcasting = true
        thread {
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket().apply { broadcast = true }
                val bcastAddr = InetAddress.getByName("255.255.255.255")
                while (broadcasting) {
                    val ip = getIp()
                    val payload = JSONObject().apply {
                        put("app", "ITELL_BRIDGE_V3")
                        put("ip", ip)
                        put("name", Build.MODEL ?: "Android Host")
                        put("battery", getBattery())
                        put("operator", getOperator())
                    }.toString().toByteArray(Charsets.UTF_8)

                    try {
                        socket.send(DatagramPacket(payload, payload.size, bcastAddr, DISCOVERY_PORT))
                    } catch (_: Exception) {
                    }
                    // ارسال روی ساب‌نت محلی (مثلاً 192.168.x.255) برای سازگاری با هات‌اسپات
                    try {
                        val parts = ip.split(".")
                        if (parts.size == 4) {
                            val subBcast = InetAddress.getByName("${parts[0]}.${parts[1]}.${parts[2]}.255")
                            socket.send(DatagramPacket(payload, payload.size, subBcast, DISCOVERY_PORT))
                        }
                    } catch (_: Exception) {
                    }
                    Thread.sleep(2500)
                }
            } catch (_: Exception) {
            } finally {
                try { socket?.close() } catch (_: Exception) {}
            }
        }
    }

    fun stopHostBroadcast() {
        broadcasting = false
    }

    fun startClientDiscovery(onHostFound: (DiscoveredHost) -> Unit) {
        if (listening) return
        listening = true
        thread {
            try {
                listenSocket = DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    bind(InetSocketAddress(DISCOVERY_PORT))
                }
                val buf = ByteArray(1024)
                while (listening) {
                    val packet = DatagramPacket(buf, buf.size)
                    listenSocket?.receive(packet)
                    val msg = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    try {
                        val json = JSONObject(msg)
                        if (json.optString("app") == "ITELL_BRIDGE_V3") {
                            val ip = json.optString("ip").ifBlank { packet.address?.hostAddress ?: "" }
                            if (ip.isNotBlank()) {
                                onHostFound(
                                    DiscoveredHost(
                                        ip = ip,
                                        name = json.optString("name", "گوشی میزبان"),
                                        battery = json.optInt("battery", -1),
                                        operator = json.optString("operator", "سیم‌کارت")
                                    )
                                )
                            }
                        }
                    } catch (_: Exception) {
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    fun stopClientDiscovery() {
        listening = false
        try { listenSocket?.close() } catch (_: Exception) {}
        listenSocket = null
    }
}
