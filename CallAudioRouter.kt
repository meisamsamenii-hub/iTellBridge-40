package com.bridge.itell

import android.content.Context
import android.media.*
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.concurrent.thread

class CallAudioRouter(
    private val context: Context,
    private val remoteIp: String,
    private val isHostMode: Boolean,
    private val udpPort: Int = 50005
) {
    @Volatile private var isStreaming = false
    @Volatile var isMuted = false
    private var rxSocket: DatagramSocket? = null

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val sampleRate = 16000
    private val bufferSize = AudioRecord.getMinBufferSize(
        sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
    ).coerceAtLeast(1280)

    fun startCallBridge() {
        if (isStreaming) return
        isStreaming = true
        val hasRoot = isHostMode && checkRootAccess()

        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        // در گوشی میزبان بدون روت، اسپیکر روشن می‌شود تا صدای تماس وارد میکروفون شود
        // در گوشی دوم، پیش‌فرض روی گوشی (Earpiece) است تا مثل تماس عادی باشد
        audioManager.isSpeakerphoneOn = isHostMode && !hasRoot

        val audioSource = if (hasRoot) {
            MediaRecorder.AudioSource.VOICE_CALL
        } else {
            MediaRecorder.AudioSource.VOICE_COMMUNICATION
        }

        startUplink(audioSource)
        startDownlink()
    }

    fun setSpeaker(enabled: Boolean) {
        audioManager.isSpeakerphoneOn = enabled
    }

    private fun startUplink(audioSource: Int) = thread {
        var socket: DatagramSocket? = null
        var recorder: AudioRecord? = null
        try {
            socket = DatagramSocket()
            val address = InetAddress.getByName(remoteIp)
            recorder = AudioRecord(
                audioSource, sampleRate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize
            )

            if (AcousticEchoCanceler.isAvailable()) {
                AcousticEchoCanceler.create(recorder.audioSessionId)?.enabled = true
            }
            if (NoiseSuppressor.isAvailable()) {
                NoiseSuppressor.create(recorder.audioSessionId)?.enabled = true
            }
            if (AutomaticGainControl.isAvailable()) {
                AutomaticGainControl.create(recorder.audioSessionId)?.enabled = true
            }

            val buffer = ByteArray(bufferSize)
            recorder.startRecording()

            while (isStreaming) {
                val read = recorder.read(buffer, 0, buffer.size)
                if (read > 0 && !isMuted) {
                    socket.send(DatagramPacket(buffer, read, address, udpPort))
                }
            }
        } catch (_: Exception) {
        } finally {
            try { recorder?.stop() } catch (_: Exception) {}
            try { recorder?.release() } catch (_: Exception) {}
            try { socket?.close() } catch (_: Exception) {}
        }
    }

    private fun startDownlink() = thread {
        var player: AudioTrack? = null
        try {
            rxSocket = DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(udpPort))
            }
            @Suppress("DEPRECATION")
            player = AudioTrack(
                AudioManager.STREAM_VOICE_CALL, sampleRate,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
                bufferSize, AudioTrack.MODE_STREAM
            )
            val buffer = ByteArray(bufferSize)
            val packet = DatagramPacket(buffer, buffer.size)
            player.play()

            while (isStreaming) {
                rxSocket?.receive(packet)
                player.write(packet.data, 0, packet.length)
            }
        } catch (_: Exception) {
        } finally {
            try { player?.stop() } catch (_: Exception) {}
            try { player?.release() } catch (_: Exception) {}
            try { rxSocket?.close() } catch (_: Exception) {}
        }
    }

    fun stopCallBridge() {
        isStreaming = false
        try { rxSocket?.close() } catch (_: Exception) {}
        audioManager.isSpeakerphoneOn = false
        audioManager.mode = AudioManager.MODE_NORMAL
    }

    private fun checkRootAccess(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            process.waitFor() == 0
        } catch (_: Exception) {
            false
        }
    }
}
