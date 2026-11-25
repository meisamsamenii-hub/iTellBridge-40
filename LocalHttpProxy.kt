package com.bridge.itell

import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

class LocalHttpProxy(private val port: Int = 8080) {
    @Volatile private var running = false
    private var serverSocket: ServerSocket? = null

    fun start() {
        if (running) return
        running = true
        thread {
            try {
                serverSocket = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(port))
                }
                while (running) {
                    val clientSocket = serverSocket!!.accept()
                    thread { handleClient(clientSocket) }
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun handleClient(client: Socket) {
        try {
            val clientIn = client.getInputStream()
            val clientOut = client.getOutputStream()
            val header = readHeader(clientIn)
            val firstLine = header.lineSequence().firstOrNull() ?: return

            if (firstLine.startsWith("CONNECT")) {
                val hostPort = firstLine.split(" ")[1].split(":")
                val host = hostPort[0]
                val targetPort = hostPort.getOrNull(1)?.toIntOrNull() ?: 443

                val remote = Socket(host, targetPort)
                clientOut.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
                clientOut.flush()

                val t1 = thread { pipe(clientIn, remote.getOutputStream()) }
                val t2 = thread { pipe(remote.getInputStream(), clientOut) }
                t1.join()
                t2.join()
                remote.close()
            }
        } catch (_: Exception) {
        } finally {
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun readHeader(input: InputStream): String {
        val sb = StringBuilder()
        var curr: Int
        while (input.read().also { curr = it } != -1) {
            sb.append(curr.toChar())
            if (sb.endsWith("\r\n\r\n")) break
        }
        return sb.toString()
    }

    private fun pipe(input: InputStream, output: OutputStream) {
        try {
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                output.write(buffer, 0, bytesRead)
                output.flush()
            }
        } catch (_: Exception) {
        }
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
    }
}
