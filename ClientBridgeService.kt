package com.bridge.itell

import android.annotation.SuppressLint
import android.app.*
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread

class ClientBridgeService : Service() {

    private val aesKey = "12345678901234567890123456789012".toByteArray()
    private var clientSocket: Socket? = null
    private var btSocket: BluetoothSocket? = null
    private var clientWriter: PrintWriter? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var ringtonePlayer: MediaPlayer? = null
    private var callVibrator: Vibrator? = null
    @Volatile private var hasPendingIncomingCall = false
    @Volatile private var pendingIncomingNumber = ""
    private var wifiLock: WifiManager.WifiLock? = null
    @Volatile private var loopRunning = false
    @Volatile private var preferIpSwitch = false
    private val bgDiscovery = DiscoveryManager()

    companion object {
        const val ACTION_CONNECT = "com.bridge.itell.ACTION_CONNECT"
        const val ACTION_CONNECT_BT = "com.bridge.itell.ACTION_CONNECT_BT"
        const val ACTION_DISCONNECT = "com.bridge.itell.ACTION_DISCONNECT"
        const val ACTION_SET_AUTO_SWITCH = "com.bridge.itell.ACTION_SET_AUTO_SWITCH"
        const val EXTRA_HOST_IP = "extra_host_ip"
        const val EXTRA_BT_MAC = "extra_bt_mac"
        const val EXTRA_AUTO_SWITCH = "extra_auto_switch"
        const val EXTRA_INCOMING_CALL_NUMBER = "extra_incoming_call_number"

        @Volatile var isRunning = false
        @Volatile var isConnected = false
        @Volatile var connectedHostIp = ""
        @Volatile var targetHostIp = ""
        @Volatile var targetBtMac = ""
        @Volatile var connectionMode = "IP"
        @Volatile var activeTransport = "NONE"
        @Volatile var autoSwitchEnabled = true
        @Volatile var userManuallyDisconnected = false

        private var instance: ClientBridgeService? = null
        var onEventReceived: ((JSONObject) -> Unit)? = null
        var onStateChanged: ((Boolean, String) -> Unit)? = null
        var onLog: ((String) -> Unit)? = null

        fun sendEncrypted(plainJson: String) {
            instance?.sendEncryptedInternal(plainJson)
        }

        fun stopRingtone() {
            instance?.stopRingtoneInternal()
        }

        fun getTotalUnreadSms(context: Context): Int {
            val all = context.getSharedPreferences("itell_unread_v4", Context.MODE_PRIVATE).all
            var total = 0
            for (v in all.values) {
                if (v is Int && v > 0) total += v
            }
            return total
        }

        fun getMissedCallsCount(context: Context): Int {
            return context.getSharedPreferences("itell_missed_v4", Context.MODE_PRIVATE).getInt("count", 0)
        }

        fun clearMissedCalls(context: Context) {
            context.getSharedPreferences("itell_missed_v4", Context.MODE_PRIVATE).edit().putInt("count", 0).apply()
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(302)
            updateAppIconBadge(context)
        }

        fun updateAppIconBadge(context: Context) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val unreadSms = getTotalUnreadSms(context)
            val missedCalls = getMissedCallsCount(context)
            if (unreadSms <= 0) {
                nm.cancel(201)
            }
            if (missedCalls <= 0) {
                nm.cancel(302)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        isRunning = true
        createNotificationChannels()
        acquireLocks()
        val prefs = getSharedPreferences("itell_cfg", Context.MODE_PRIVATE)
        autoSwitchEnabled = prefs.getBoolean("auto_switch_enabled", true)
        targetHostIp = prefs.getString("host_ip", "") ?: ""
        targetBtMac = prefs.getString("bt_mac", "") ?: ""
        startBackgroundHostDiscovery()
    }

    private fun startBackgroundHostDiscovery() {
        bgDiscovery.startClientDiscovery { host ->
            if (HostBridgeService.isRunning && host.ip == HostBridgeService.getLocalIpAddress()) {
                return@startClientDiscovery
            }
            if (host.ip.isBlank()) return@startClientDiscovery
            val prevIp = targetHostIp
            targetHostIp = host.ip
            if (prevIp != host.ip) {
                getSharedPreferences("itell_cfg", Context.MODE_PRIVATE).edit()
                    .putString("host_ip", host.ip).apply()
            }
            // اگر الان روی بلوتوث هستیم و وای‌فای/هات‌اسپات گوشی اول پیدا شد، خودکار به وای‌فای سوئیچ کن
            if (autoSwitchEnabled && !userManuallyDisconnected && isConnected && activeTransport == "BT") {
                preferIpSwitch = true
                onLog?.invoke("وای‌فای/هات‌اسپات گوشی اول (${host.ip}) پیدا شد ← سوئیچ خودکار به وای‌فای...")
                stopRingtoneInternal()
        disconnectSocket()
            }
        }
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireLocks() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "iTellBridge::ClientWakeLock").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) {
        }
        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "iTellBridge::ClientWifiLock").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) {
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    102,
                    buildForegroundNotification("در حال اتصال به گوشی میزبان..."),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(102, buildForegroundNotification("در حال اتصال به گوشی میزبان..."))
            }
        } catch (_: Exception) {
            try {
                startForeground(102, buildForegroundNotification("در حال اتصال به گوشی میزبان..."))
            } catch (_: Exception) {
            }
        }

        when (intent?.action) {
            ACTION_SET_AUTO_SWITCH -> {
                autoSwitchEnabled = intent.getBooleanExtra(EXTRA_AUTO_SWITCH, true)
                getSharedPreferences("itell_cfg", Context.MODE_PRIVATE).edit()
                    .putBoolean("auto_switch_enabled", autoSwitchEnabled).apply()
                onLog?.invoke(if (autoSwitchEnabled) "سوئیچ هوشمند وای‌فای ↔ بلوتوث فعال شد" else "سوئیچ هوشمند غیرفعال شد")
            }
            ACTION_DISCONNECT -> {
                userManuallyDisconnected = true
                disconnectSocket()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_CONNECT -> {
                val ip = intent.getStringExtra(EXTRA_HOST_IP)?.trim() ?: ""
                if (ip.isNotBlank()) {
                    userManuallyDisconnected = false
                    connectionMode = "IP"
                    targetHostIp = ip
                    getSharedPreferences("itell_cfg", Context.MODE_PRIVATE).edit()
                        .putString("conn_mode", "IP")
                        .putString("host_ip", ip).apply()
                    disconnectSocket()
                    startConnectionLoop()
                }
            }
            ACTION_CONNECT_BT -> {
                val mac = intent.getStringExtra(EXTRA_BT_MAC)?.trim() ?: ""
                if (mac.isNotBlank()) {
                    userManuallyDisconnected = false
                    connectionMode = "BT"
                    targetBtMac = mac
                    getSharedPreferences("itell_cfg", Context.MODE_PRIVATE).edit()
                        .putString("conn_mode", "BT")
                        .putString("bt_mac", mac).apply()
                    disconnectSocket()
                    startConnectionLoop()
                }
            }
            else -> {
                val prefs = getSharedPreferences("itell_cfg", Context.MODE_PRIVATE)
                connectionMode = prefs.getString("conn_mode", "IP") ?: "IP"
                targetHostIp = prefs.getString("host_ip", "") ?: ""
                targetBtMac = prefs.getString("bt_mac", "") ?: ""
                if (!userManuallyDisconnected && (targetHostIp.isNotBlank() || targetBtMac.isNotBlank())) {
                    startConnectionLoop()
                }
            }
        }
        return START_STICKY
    }

    private fun startConnectionLoop() {
        if (loopRunning) return
        loopRunning = true
        thread {
            while (loopRunning && !userManuallyDisconnected) {
                if (!isConnected) {
                    if (autoSwitchEnabled) {
                        var ipTriedAndConnected = false
                        if (targetHostIp.isNotBlank()) {
                            preferIpSwitch = false
                            ipTriedAndConnected = connectOnce(targetHostIp, 2000)
                        }
                        if (!ipTriedAndConnected && !userManuallyDisconnected && !preferIpSwitch && targetBtMac.isNotBlank()) {
                            connectOnceBluetooth(targetBtMac)
                        }
                    } else {
                        if (connectionMode == "BT" && targetBtMac.isNotBlank()) {
                            connectOnceBluetooth(targetBtMac)
                        } else if (targetHostIp.isNotBlank()) {
                            connectOnce(targetHostIp, 3500)
                        }
                    }
                }
                try {
                    Thread.sleep(if (preferIpSwitch) 600 else 3000)
                } catch (_: Exception) {
                }
            }
            loopRunning = false
        bgDiscovery.stopClientDiscovery()
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectOnceBluetooth(btMac: String) {
        try {
            disconnectSocket()
            val adapter = BluetoothBridgeHelper.getAdapter(this) ?: return
            if (!adapter.isEnabled) return
            val device = adapter.getRemoteDevice(btMac)
            val sock = device.createRfcommSocketToServiceRecord(BluetoothBridgeHelper.APP_UUID)
            sock.connect()

            btSocket = sock
            clientWriter = PrintWriter(sock.outputStream, true)
            isConnected = true
            activeTransport = "BT"
            connectedHostIp = "BT:$btMac"

            updateForegroundNotification("متصل از طریق بلوتوث مستقیم (وای‌فای آزاد)")
            onStateChanged?.invoke(true, "بلوتوث مستقیم")
            onLog?.invoke("اتصال بلوتوث مستقیم به $btMac برقرار شد")

            sendEncryptedInternal(JSONObject().put("type", "CMD_PING").toString())
            sendEncryptedInternal(JSONObject().put("type", "CMD_SYNC_SMS").toString())
            sendEncryptedInternal(JSONObject().put("type", "CMD_SYNC_CALLLOG").toString())

            val reader = BufferedReader(InputStreamReader(sock.inputStream))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val plain = CryptoHelper.decrypt(line!!, aesKey)
                val json = JSONObject(plain)
                handleBackgroundEvent(json)
                onEventReceived?.invoke(json)
            }
        } catch (_: Exception) {
            onLog?.invoke("تلاش مجدد برای اتصال بلوتوث مستقیم...")
        } finally {
            val wasConnected = isConnected
            val switchingToWifi = preferIpSwitch
            isConnected = false
            activeTransport = "NONE"
            disconnectSocket()
            if (!userManuallyDisconnected && !switchingToWifi) {
                updateForegroundNotification("بلوتوث قطع شد • تلاش خودکار برای اتصال مجدد...")
                onStateChanged?.invoke(false, "بلوتوث")
                if (wasConnected) {
                    showSimpleAlert(401, "قطع ارتباط بلوتوث", "در حال تلاش خودکار برای اتصال مجدد...", "alert_channel", null)
                }
            }
        }
    }

    private fun connectOnce(hostIp: String, timeoutMs: Int = 3500): Boolean {
        var didConnect = false
        try {
            disconnectSocket()
            val socket = Socket()
            socket.connect(InetSocketAddress(hostIp, 8888), timeoutMs)
            socket.soTimeout = 30000
            socket.tcpNoDelay = true
            socket.keepAlive = true

            clientSocket = socket
            clientWriter = PrintWriter(socket.getOutputStream(), true)
            isConnected = true
            didConnect = true
            activeTransport = "IP"
            connectedHostIp = hostIp

            updateForegroundNotification("متصل روی وای‌فای/هات‌اسپات ($hostIp)")
            onStateChanged?.invoke(true, "وای‌فای ($hostIp)")
            onLog?.invoke("سرویس پایدار به $hostIp متصل شد")

            sendEncryptedInternal(JSONObject().put("type", "CMD_PING").toString())
            sendEncryptedInternal(JSONObject().put("type", "CMD_SYNC_SMS").toString())
            sendEncryptedInternal(JSONObject().put("type", "CMD_SYNC_CALLLOG").toString())

            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val plain = CryptoHelper.decrypt(line!!, aesKey)
                val json = JSONObject(plain)
                handleBackgroundEvent(json)
                onEventReceived?.invoke(json)
            }
        } catch (e: Exception) {
            onLog?.invoke("تلاش مجدد برای اتصال به $hostIp...")
        } finally {
            val wasConnected = isConnected
            isConnected = false
            activeTransport = "NONE"
            disconnectSocket()
            if (!userManuallyDisconnected) {
                val hasBtFallback = autoSwitchEnabled && targetBtMac.isNotBlank()
                if (hasBtFallback) {
                    updateForegroundNotification("وای‌فای قطع شد • سوئیچ خودکار به بلوتوث...")
                    onStateChanged?.invoke(false, hostIp)
                    if (wasConnected) {
                        onLog?.invoke("وای‌فای/هات‌اسپات قطع شد ← انتقال خودکار به بلوتوث مستقیم...")
                    }
                } else {
                    updateForegroundNotification("اتصال قطع شد • تلاش خودکار برای اتصال مجدد...")
                    onStateChanged?.invoke(false, hostIp)
                    if (wasConnected) {
                        showSimpleAlert(401, "قطع ارتباط با گوشی اول", "در حال تلاش خودکار برای اتصال مجدد...", "alert_channel", null)
                    }
                }
            }
        }
        return didConnect
    }

    private fun startRingtoneInternal() {
        stopRingtoneInternal()
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ringtonePlayer = MediaPlayer().apply {
                setDataSource(this@ClientBridgeService, uri)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                } else {
                    @Suppress("DEPRECATION")
                    setAudioStreamType(AudioManager.STREAM_RING)
                }
                isLooping = true
                prepare()
                start()
            }
        } catch (_: Exception) {
        }
        try {
            @Suppress("DEPRECATION")
            val vib = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            callVibrator = vib
            val pattern = longArrayOf(0, 800, 600)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vib?.vibrate(VibrationEffect.createWaveform(pattern, 0))
            } else {
                @Suppress("DEPRECATION")
                vib?.vibrate(pattern, 0)
            }
        } catch (_: Exception) {
        }
    }

    private fun stopRingtoneInternal() {
        try {
            ringtonePlayer?.stop()
            ringtonePlayer?.release()
        } catch (_: Exception) {
        }
        ringtonePlayer = null
        try {
            callVibrator?.cancel()
        } catch (_: Exception) {
        }
        callVibrator = null
    }

    private fun handleBackgroundEvent(json: JSONObject) {
        val cm = ContactsManager(this)
        when (json.optString("type")) {
            "INCOMING_CALL" -> {
                val number = json.optString("number")
                val name = cm.resolveName(number)
                hasPendingIncomingCall = true
                pendingIncomingNumber = number
                startRingtoneInternal()
                wakeScreenAndLaunchInCall(number, name)
            }
            "CALL_ACTIVE" -> {
                hasPendingIncomingCall = false
                stopRingtoneInternal()
            }
            "CALL_ENDED" -> {
                stopRingtoneInternal()
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.cancel(301)
                if (hasPendingIncomingCall) {
                    hasPendingIncomingCall = false
                    val missedPrefs = getSharedPreferences("itell_missed_v4", Context.MODE_PRIVATE)
                    val newMissed = missedPrefs.getInt("count", 0) + 1
                    missedPrefs.edit().putInt("count", newMissed).apply()
                    val name = cm.resolveName(pendingIncomingNumber)
                    showSimpleAlert(302, "تماس از دست رفته ($newMissed)", "$name ($pendingIncomingNumber)", "call_channel", null, newMissed)
                }
            }
            "NEW_SMS" -> {
                val sender = json.optString("sender")
                val body = json.optString("body")
                val norm = cm.normalizePhone(sender).ifBlank { sender }
                val unreadPrefs = getSharedPreferences("itell_unread_v4", Context.MODE_PRIVATE)
                val prev = unreadPrefs.getInt(norm, 0)
                unreadPrefs.edit().putInt(norm, prev + 1).apply()
                val totalUnread = getTotalUnreadSms(this)
                val name = cm.resolveName(sender)
                val otp = OtpCopyReceiver.extractOtp(body)
                if (!otp.isNullOrBlank()) {
                    OtpCopyReceiver.copyToClipboard(this, otp, showToast = true)
                }
                showSimpleAlert(201, "پیامک از $name", body, "sms_channel", otp, totalUnread)
            }
            "LOW_BATTERY" -> {
                val bat = json.optInt("battery", 15)
                showSimpleAlert(402, "هشدار باتری گوشی اول", "شارژ گوشی میزبان به $bat% رسید!", "alert_channel", null)
            }
        }
    }

    @SuppressLint("WakelockTimeout")
    private fun wakeScreenAndLaunchInCall(number: String, displayName: String) {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            @Suppress("DEPRECATION")
            val screenLock = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                "iTellBridge::IncomingCallWake"
            )
            screenLock.acquire(15000L)
        } catch (_: Exception) {
        }

        val fullScreenIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_INCOMING_CALL_NUMBER, number)
        }

        val piFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val fullScreenPi = PendingIntent.getActivity(this, 3010, fullScreenIntent, piFlags)

        val notif = NotificationCompat.Builder(this, "call_channel")
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setContentTitle("تماس ورودی: $displayName")
            .setContentText(number)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setOngoing(true)
            .setAutoCancel(true)
            .setFullScreenIntent(fullScreenPi, true)
            .setContentIntent(fullScreenPi)
            .build()

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(301, notif)

        try {
            startActivity(fullScreenIntent)
        } catch (_: Exception) {
        }
    }

    private fun showSimpleAlert(id: Int, title: String, content: String, channelId: String, otpCode: String?, badgeCount: Int = 1) {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val piFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val openPi = PendingIntent.getActivity(this, id + 500, openIntent, piFlags)

        val builder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setContentIntent(openPi)
            .setNumber(badgeCount)
            .setAutoCancel(true)

        if (!otpCode.isNullOrBlank()) {
            val copyIntent = Intent(this, OtpCopyReceiver::class.java).putExtra("OTP_CODE", otpCode)
            val copyPi = PendingIntent.getBroadcast(this, id + 1000, copyIntent, piFlags)
            builder.addAction(android.R.drawable.ic_menu_edit, "کپی کد ($otpCode)", copyPi)
        }

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(id, builder.build())
    }

    private fun sendEncryptedInternal(plainJson: String) = thread {
        try {
            val encrypted = CryptoHelper.encrypt(plainJson, aesKey)
            clientWriter?.println(encrypted)
        } catch (_: Exception) {
        }
    }

    private fun disconnectSocket() {
        try { clientSocket?.close() } catch (_: Exception) {}
        try { btSocket?.close() } catch (_: Exception) {}
        btSocket = null
        clientSocket = null
        clientWriter = null
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel("client_service_channel", "اتصال پایدار کلاینت", NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(NotificationChannel("call_channel", "تماس ورودی", NotificationManager.IMPORTANCE_HIGH))
            nm.createNotificationChannel(NotificationChannel("sms_channel", "پیامک جدید", NotificationManager.IMPORTANCE_HIGH))
            nm.createNotificationChannel(NotificationChannel("alert_channel", "هشدارهای سیستم", NotificationManager.IMPORTANCE_HIGH))
        }
    }

    private fun buildForegroundNotification(text: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val piFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pi = PendingIntent.getActivity(this, 1020, openIntent, piFlags)
        return NotificationCompat.Builder(this, "client_service_channel")
            .setContentTitle("Samen Pro (متصل به میزبان)")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun updateForegroundNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(102, buildForegroundNotification(text))
    }

    override fun onDestroy() {
        loopRunning = false
        bgDiscovery.stopClientDiscovery()
        isConnected = false
        isRunning = false
        disconnectSocket()
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        try { if (wifiLock?.isHeld == true) wifiLock?.release() } catch (_: Exception) {}
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
