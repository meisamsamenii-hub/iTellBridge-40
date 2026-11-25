package com.bridge.itell

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

class LocalOfflineServer(
    private val port: Int = 8888,
    private val onClientConnected: (String) -> Unit,
    private val onPacketReceived: (String, String) -> Unit
) {
    @Volatile private var isRunning = false
    private var serverSocket: ServerSocket? = null
    private val clients = CopyOnWriteArrayList<PrintWriter>()

    fun start() {
        if (isRunning) return
        isRunning = true
        thread {
            try {
                serverSocket = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(port))
                }
                while (isRunning) {
                    val client: Socket = serverSocket!!.accept()
                    client.tcpNoDelay = true
                    client.keepAlive = true
                    val remoteIp = client.inetAddress?.hostAddress ?: ""
                    val writer = PrintWriter(client.getOutputStream(), true)
                    clients.add(writer)
                    onClientConnected(remoteIp)
                    thread { listenToClient(client, writer, remoteIp) }
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun listenToClient(socket: Socket, writer: PrintWriter, remoteIp: String) {
        try {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                line?.let { onPacketReceived(it, remoteIp) }
            }
        } catch (_: Exception) {
        } finally {
            clients.remove(writer)
            try { socket.close() } catch (_: Exception) {}
        }
    }

    fun sendToAllClients(encryptedPayload: String) {
        thread {
            val dead = mutableListOf<PrintWriter>()
            for (writer in clients) {
                try {
                    writer.println(encryptedPayload)
                    if (writer.checkError()) dead.add(writer)
                } catch (_: Exception) {
                    dead.add(writer)
                }
            }
            clients.removeAll(dead.toSet())
        }
    }

    fun stop() {
        isRunning = false
        clients.clear()
        try { serverSocket?.close() } catch (_: Exception) {}
    }
}
