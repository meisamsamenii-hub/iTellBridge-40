package com.bridge.itell

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

data class PairedBtDevice(val name: String, val address: String)

object BluetoothBridgeHelper {
    val APP_UUID: UUID = UUID.fromString("fa87c0d0-afac-11de-8a39-0800200c9a66")
    const val SERVICE_NAME = "iTellBridgeRFCOMM"

    fun getAdapter(context: Context): BluetoothAdapter? {
        return try {
            val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            bm?.adapter
        } catch (_: Exception) {
            null
        }
    }

    @SuppressLint("MissingPermission")
    fun getPairedDevices(context: Context): List<PairedBtDevice> {
        val result = mutableListOf<PairedBtDevice>()
        try {
            val adapter = getAdapter(context) ?: return result
            if (!adapter.isEnabled) return result
            val bonded = adapter.bondedDevices ?: return result
            for (d in bonded) {
                val n = d.name?.ifBlank { d.address } ?: d.address
                result.add(PairedBtDevice(n, d.address))
            }
        } catch (_: Exception) {
        }
        return result
    }
}

class BluetoothBridgeServer(
    private val context: Context,
    private val onClientConnected: (String) -> Unit,
    private val onPacketReceived: (String, String) -> Unit
) {
    @Volatile private var isRunning = false
    private var serverSocket: BluetoothServerSocket? = null
    private val clients = CopyOnWriteArrayList<PrintWriter>()

    @SuppressLint("MissingPermission")
    fun start() {
        if (isRunning) return
        isRunning = true
        thread {
            while (isRunning) {
                try {
                    val adapter = BluetoothBridgeHelper.getAdapter(context)
                    if (adapter == null || !adapter.isEnabled) {
                        Thread.sleep(4000)
                        continue
                    }
                    serverSocket = adapter.listenUsingRfcommWithServiceRecord(
                        BluetoothBridgeHelper.SERVICE_NAME,
                        BluetoothBridgeHelper.APP_UUID
                    )
                    while (isRunning) {
                        val socket: BluetoothSocket = serverSocket?.accept() ?: break
                        val remoteName = try {
                            socket.remoteDevice?.name ?: socket.remoteDevice?.address ?: "Bluetooth"
                        } catch (_: Exception) {
                            "Bluetooth"
                        }
                        val writer = PrintWriter(socket.outputStream, true)
                        clients.add(writer)
                        onClientConnected(remoteName)
                        thread { listenToClient(socket, writer, remoteName) }
                    }
                } catch (_: Exception) {
                    try { serverSocket?.close() } catch (_: Exception) {}
                    try { Thread.sleep(3000) } catch (_: Exception) {}
                }
            }
        }
    }

    private fun listenToClient(socket: BluetoothSocket, writer: PrintWriter, remoteName: String) {
        try {
            val reader = BufferedReader(InputStreamReader(socket.inputStream))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                line?.let { onPacketReceived(it, "BT:$remoteName") }
            }
        } catch (_: Exception) {
        } finally {
            clients.remove(writer)
            try { socket.close() } catch (_: Exception) {}
        }
    }

    fun sendToAllClients(encryptedPayload: String) {
        if (clients.isEmpty()) return
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
