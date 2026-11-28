package com.bridge.itell

import android.annotation.SuppressLint
import android.app.Activity
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.KeyEvent
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Telephony
import android.telecom.TelecomManager
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlin.concurrent.thread

class HostBridgeService : Service() {

    private var offlineServer: LocalOfflineServer? = null
    private var btServer: BluetoothBridgeServer? = null
    private var httpProxy: LocalHttpProxy? = null
    private var callAudioRouter: CallAudioRouter? = null
    private val discoveryManager = DiscoveryManager()
    private val aesKey = "12345678901234567890123456789012".toByteArray()
    @Volatile private var statusLoopRunning = false
    private var lowBatteryAlertSent = false

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var dynamicSmsReceiver: SmsReceiver? = null
    private var dynamicCallReceiver: CallReceiver? = null
    private var smsStatusReceiver: BroadcastReceiver? = null
    private var smsObserver: ContentObserver? = null
    private var lastObservedSmsId: Long = -1L

    companion object {
        @Volatile var isRunning = false
        private var instance: HostBridgeService? = null
        var onHostLog: ((String) -> Unit)? = null
        private var lastSmsKey: String = ""
        private var lastSmsTime: Long = 0L
        private var lastCallNumber: String = ""
        private var lastCallTime: Long = 0L

        fun sendUssdResponseToClients(text: String, interactive: Boolean) {
            val svc = instance ?: return
            val json = JSONObject().apply {
                put("type", "USSD_RESPONSE")
                put("text", text)
                put("interactive", interactive)
                put("timestamp", System.currentTimeMillis())
            }
            svc.sendEncryptedToClients(json.toString())
            onHostLog?.invoke("پاسخ USSD به گوشی دوم ارسال شد.")
        }

        fun forwardEvent(plainJson: String) {
            try {
                val obj = JSONObject(plainJson)
                val now = System.currentTimeMillis()
                when (obj.optString("type")) {
                    "NEW_SMS" -> {
                        val key = "${obj.optString("sender")}|${obj.optString("body")}"
                        if (key == lastSmsKey && (now - lastSmsTime) < 10000L) {
                            return
                        }
                        lastSmsKey = key
                        lastSmsTime = now
                    }
                    "INCOMING_CALL" -> {
                        val num = obj.optString("number")
                        if (num == lastCallNumber && (now - lastCallTime) < 5000L) {
                            return
                        }
                        if (num == "شماره ناشناس" && lastCallNumber != "شماره ناشناس" && (now - lastCallTime) < 5000L) {
                            return
                        }
                        lastCallNumber = num
                        lastCallTime = now
                    }
                }
            } catch (_: Exception) {
            }
            instance?.sendEncryptedToClients(plainJson)
        }

        fun getLocalIpAddress(): String {
            try {
                val interfaces = NetworkInterface.getNetworkInterfaces()
                while (interfaces.hasMoreElements()) {
                    val intf = interfaces.nextElement()
                    val addrs = intf.inetAddresses
                    while (addrs.hasMoreElements()) {
                        val addr = addrs.nextElement()
                        if (!addr.isLoopbackAddress && addr is Inet4Address) {
                            val ip = addr.hostAddress ?: ""
                            if (ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172.")) {
                                return ip
                            }
                        }
                    }
                }
            } catch (_: Exception) {
            }
            return "192.168.43.1"
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        isRunning = true
        getSharedPreferences("itell_cfg", Context.MODE_PRIVATE).edit().putBoolean("was_host_running", true).apply()
        acquireLocks()
        registerDynamicSmsReceiver()
        registerDynamicCallReceiver()
        registerSmsContentObserver()
        registerSmsStatusReceiver()
    }

    private fun registerSmsStatusReceiver() {
        try {
            smsStatusReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent == null) return
                    val msgId = intent.getLongExtra("msgId", 0L)
                    val phone = intent.getStringExtra("phone") ?: ""
                    when (intent.action) {
                        "com.bridge.itell.SMS_SENT_STATUS" -> {
                            val st = if (resultCode == Activity.RESULT_OK) "SENT" else "FAILED"
                            sendSmsStatusUpdate(msgId, phone, st)
                        }
                        "com.bridge.itell.SMS_DELIVERED_STATUS" -> {
                            if (resultCode == Activity.RESULT_OK) {
                                sendSmsStatusUpdate(msgId, phone, "DELIVERED")
                                onHostLog?.invoke("دلیوری پیامک به $phone تایید شد (✓✓)")
                            }
                        }
                    }
                }
            }
            val filter = IntentFilter().apply {
                addAction("com.bridge.itell.SMS_SENT_STATUS")
                addAction("com.bridge.itell.SMS_DELIVERED_STATUS")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(smsStatusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(smsStatusReceiver, filter)
            }
        } catch (_: Exception) {
        }
    }

    private fun sendSmsStatusUpdate(msgId: Long, phone: String, status: String) {
        val json = JSONObject().apply {
            put("type", "SMS_STATUS")
            put("msgId", msgId)
            put("to", phone)
            put("status", status)
        }
        sendEncryptedToClients(json.toString())
    }

    private fun registerDynamicCallReceiver() {
        try {
            dynamicCallReceiver = CallReceiver()
            val filter = IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(dynamicCallReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(dynamicCallReceiver, filter)
            }
        } catch (_: Exception) {
        }
    }

    private fun registerSmsContentObserver() {
        try {
            // Initialize lastObservedSmsId to current latest SMS
            contentResolver.query(
                Uri.parse("content://sms/inbox"),
                arrayOf("_id"),
                null, null, "_id DESC LIMIT 1"
            )?.use {
                if (it.moveToFirst()) lastObservedSmsId = it.getLong(0)
            }

            smsObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    super.onChange(selfChange)
                    thread {
                        try {
                            contentResolver.query(
                                Uri.parse("content://sms/inbox"),
                                arrayOf("_id", "address", "body", "date"),
                                null, null, "_id DESC LIMIT 1"
                            )?.use { c ->
                                if (c.moveToFirst()) {
                                    val id = c.getLong(0)
                                    if (id > lastObservedSmsId) {
                                        lastObservedSmsId = id
                                        val sender = c.getString(1) ?: "ناشناس"
                                        val body = c.getString(2) ?: ""
                                        val date = c.getLong(3)
                                        val payload = JSONObject().apply {
                                            put("type", "NEW_SMS")
                                            put("sender", sender)
                                            put("body", body)
                                            put("timestamp", date)
                                        }
                                        forwardEvent(payload.toString())
                                    }
                                }
                            }
                        } catch (_: Exception) {
                        }
                    }
                }
            }
            contentResolver.registerContentObserver(Uri.parse("content://sms"), true, smsObserver!!)
        } catch (_: Exception) {
        }
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireLocks() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "iTellBridge::HostWakeLock").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) {
        }
        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "iTellBridge::HostWifiLock").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) {
        }
    }

    private fun registerDynamicSmsReceiver() {
        try {
            dynamicSmsReceiver = SmsReceiver()
            val filter = IntentFilter(Telephony.Sms.Intents.SMS_RECEIVED_ACTION).apply {
                priority = 999
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(dynamicSmsReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(dynamicSmsReceiver, filter)
            }
        } catch (_: Exception) {
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundNotification()

        if (offlineServer == null) {
            offlineServer = LocalOfflineServer(
                port = 8888,
                onClientConnected = { clientIp ->
                    onHostLog?.invoke("گوشی دوم متصل شد: $clientIp")
                    sendHostStatus()
                },
                onPacketReceived = { encrypted, clientIp ->
                    handleIncomingCommand(encrypted, clientIp)
                }
            ).also { it.start() }
        }

        if (btServer == null) {
            btServer = BluetoothBridgeServer(
                context = this,
                onClientConnected = { btName ->
                    onHostLog?.invoke("گوشی دوم از طریق بلوتوث مستقیم متصل شد: $btName")
                    sendHostStatus()
                },
                onPacketReceived = { encrypted, clientTag ->
                    handleIncomingCommand(encrypted, clientTag)
                }
            ).also { it.start() }
        }

        if (httpProxy == null) {
            httpProxy = LocalHttpProxy(8080).also { it.start() }
        }

        discoveryManager.startHostBroadcast(
            getBattery = { getBatteryLevel() },
            getOperator = { getOperatorName() },
            getIp = { getLocalIpAddress() }
        )

        startStatusMonitorLoop()
        onHostLog?.invoke("میزبان v5.1 روی ${getLocalIpAddress()}:8888 و پروکسی 8080 فعال شد.")
        return START_STICKY
    }

    private fun startStatusMonitorLoop() {
        if (statusLoopRunning) return
        statusLoopRunning = true
        thread {
            while (statusLoopRunning) {
                try {
                    val bat = getBatteryLevel()
                    sendHostStatus()
                    if (bat in 1..15 && !lowBatteryAlertSent) {
                        lowBatteryAlertSent = true
                        val alert = JSONObject().apply {
                            put("type", "LOW_BATTERY")
                            put("battery", bat)
                        }
                        sendEncryptedToClients(alert.toString())
                    } else if (bat > 20) {
                        lowBatteryAlertSent = false
                    }
                    Thread.sleep(12000)
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun getBatteryLevel(): Int {
        return try {
            val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        } catch (_: Exception) {
            -1
        }
    }

    private fun getOperatorName(): String {
        return try {
            val tm = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            tm.networkOperatorName?.ifBlank { "سیم‌کارت" } ?: "سیم‌کارت"
        } catch (_: Exception) {
            "سیم‌کارت"
        }
    }

    @SuppressLint("MissingPermission")
    private fun getActiveSimsArray(): JSONArray {
        val arr = JSONArray()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            try {
                val sm = getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
                val list = sm?.activeSubscriptionInfoList ?: emptyList()
                for (info in list.sortedBy { it.simSlotIndex }) {
                    arr.put(JSONObject().apply {
                        put("slot", info.simSlotIndex)
                        put("subId", info.subscriptionId)
                        put("carrier", info.carrierName?.toString()?.ifBlank { "سیم‌کارت ${info.simSlotIndex + 1}" } ?: "سیم‌کارت ${info.simSlotIndex + 1}")
                    })
                }
            } catch (_: Exception) {
            }
        }
        return arr
    }

    @SuppressLint("MissingPermission")
    private fun getSubIdForSlot(simSlot: Int): Int {
        if (simSlot < 0 || Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP_MR1) return -1
        return try {
            val sm = getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
            val info = sm?.activeSubscriptionInfoList?.firstOrNull { it.simSlotIndex == simSlot }
            info?.subscriptionId ?: -1
        } catch (_: Exception) {
            -1
        }
    }

    private fun sendHostStatus() {
        val json = JSONObject().apply {
            put("type", "HOST_STATUS")
            put("battery", getBatteryLevel())
            put("operator", getOperatorName())
            put("ip", getLocalIpAddress())
            put("model", Build.MODEL ?: "Host")
            put("sims", getActiveSimsArray())
        }
        sendEncryptedToClients(json.toString())
    }

    private fun handleIncomingCommand(encryptedPayload: String, clientIp: String) {
        try {
            val json = JSONObject(CryptoHelper.decrypt(encryptedPayload, aesKey))
            when (json.getString("type")) {
                "CMD_SEND_SMS" -> sendSmsCompat(json.getString("to"), json.getString("body"), json.optInt("simSlot", -1))
                "CMD_SYNC_SMS" -> syncAllSms()
                "CMD_SYNC_CALLLOG" -> syncCallLog()
                "CMD_SYNC_CONTACTS" -> syncHostContacts()
                "CMD_DIAL" -> {
                    val number = json.getString("number")
                    val simSlot = json.optInt("simSlot", -1)
                    if (number.startsWith("*") && number.endsWith("#")) {
                        runUssdCode(number)
                    } else {
                        placeCallCompat(number, simSlot)
                    }
                }
                "CMD_ANSWER_CALL" -> answerCallCompat()
                "CMD_END_CALL" -> endCallCompat()
                "CMD_DELETE_CALLLOG" -> deleteCallLogCompat(json)
                "CMD_DELETE_SMS" -> deleteSmsCompat(json)
                "CMD_FIND_HOST" -> ringHostLoudly()
                "CMD_USSD_REPLY" -> {
                    val reply = json.optString("reply")
                    if (!UssdAccessibilityService.replyToUssd(reply)) {
                        sendFeedback("پنجره USSD روی گوشی اول فعال نیست یا مجوز Accessibility روشن نشده است.")
                    }
                }
                "CMD_USSD_CANCEL" -> UssdAccessibilityService.cancelUssd()
                "CMD_START_AUDIO" -> {
                    val targetIp = json.optString("ip", clientIp).ifBlank { clientIp }
                    callAudioRouter?.stopCallBridge()
                    callAudioRouter = CallAudioRouter(this, targetIp, isHostMode = true).also { it.startCallBridge() }
                    sendFeedback("کانال صوتی مکالمه متصل شد.")
                }
                "CMD_STOP_AUDIO" -> {
                    callAudioRouter?.stopCallBridge()
                    callAudioRouter = null
                }
                "CMD_PING" -> sendHostStatus()
            }
        } catch (e: Exception) {
            sendFeedback("خطا در اجرای دستور: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun runUssdCode(ussd: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val tm = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                tm.sendUssdRequest(
                    ussd,
                    object : TelephonyManager.UssdResponseCallback() {
                        override fun onReceiveUssdResponse(
                            telephonyManager: TelephonyManager?,
                            request: String?,
                            response: CharSequence?
                        ) {
                            val res = JSONObject().apply {
                                put("type", "USSD_RESULT")
                                put("code", ussd)
                                put("response", response?.toString() ?: "پاسخی دریافت نشد")
                            }
                            sendEncryptedToClients(res.toString())
                        }

                        override fun onReceiveUssdResponseFailed(
                            telephonyManager: TelephonyManager?,
                            request: String?,
                            failureCode: Int
                        ) {
                            val res = JSONObject().apply {
                                put("type", "USSD_RESULT")
                                put("code", ussd)
                                put("response", "اجرای کد USSD ناموفق بود (کد خطا: $failureCode)")
                            }
                            sendEncryptedToClients(res.toString())
                        }
                    },
                    Handler(Looper.getMainLooper())
                )
                return
            } catch (_: Exception) {
            }
        }
        placeCallCompat(ussd)
    }

    private fun sendSmsCompat(phone: String, message: String, simSlot: Int = -1) {
        try {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
                val err = "مجوز ارسال پیامک (SEND_SMS) روی گوشی اول فعال نیست! از تنظیمات گوشی اول ← Apps ← Samen Pro ← Permissions ← SMS را روی Allow بگذارید."
                onHostLog?.invoke(err)
                sendFeedback(err)
                return
            }
            val subId = getSubIdForSlot(simSlot)
            val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val baseMgr = getSystemService(SmsManager::class.java)
                if (subId >= 0) baseMgr.createForSubscriptionId(subId) else baseMgr
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1 && subId >= 0) {
                @Suppress("DEPRECATION")
                SmsManager.getSmsManagerForSubscriptionId(subId)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
            val msgId = System.currentTimeMillis()
            val parts = smsManager.divideMessage(message)
            val sentIntents = ArrayList<PendingIntent>()
            val deliveryIntents = ArrayList<PendingIntent>()
            val piFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            for (idx in parts.indices) {
                val reqCode = ((msgId % 100000).toInt() * 10) + idx
                val sIntent = Intent("com.bridge.itell.SMS_SENT_STATUS").apply {
                    setPackage(packageName)
                    putExtra("msgId", msgId)
                    putExtra("phone", phone)
                }
                val dIntent = Intent("com.bridge.itell.SMS_DELIVERED_STATUS").apply {
                    setPackage(packageName)
                    putExtra("msgId", msgId)
                    putExtra("phone", phone)
                }
                sentIntents.add(PendingIntent.getBroadcast(this, reqCode, sIntent, piFlags))
                deliveryIntents.add(PendingIntent.getBroadcast(this, reqCode + 500000, dIntent, piFlags))
            }
            smsManager.sendMultipartTextMessage(phone, null, parts, sentIntents, deliveryIntents)
            onHostLog?.invoke("پیامک به $phone در حال ارسال (با گزارش دلیوری)...")
            val res = JSONObject().apply {
                put("type", "SMS_SENT_OK")
                put("to", phone)
                put("body", message)
                put("timestamp", msgId)
                put("msgId", msgId)
            }
            sendEncryptedToClients(res.toString())
        } catch (e: Exception) {
            sendFeedback("خطا در ارسال پیامک: ${e.message}")
        }
    }

    private fun syncAllSms() {
        try {
            val arr = JSONArray()
            val cursor = contentResolver.query(
                Uri.parse("content://sms"),
                arrayOf("address", "body", "date", "type"),
                null, null, "date DESC"
            )
            cursor?.use {
                var count = 0
                while (it.moveToNext() && count < 60) {
                    val smsType = it.getInt(3)
                    val item = JSONObject().apply {
                        put("sender", it.getString(0) ?: "ناشناس")
                        put("body", it.getString(1) ?: "")
                        put("timestamp", it.getLong(2))
                        put("outgoing", smsType == 2)
                    }
                    arr.put(item)
                    count++
                }
            }
            val res = JSONObject().apply {
                put("type", "SMS_LIST")
                put("items", arr)
            }
            sendEncryptedToClients(res.toString())
        } catch (e: Exception) {
            sendFeedback("خطا در خواندن پیامک‌ها: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun syncCallLog() {
        try {
            val arr = JSONArray()
            val cursor = contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(
                    CallLog.Calls.NUMBER,
                    CallLog.Calls.CACHED_NAME,
                    CallLog.Calls.TYPE,
                    CallLog.Calls.DATE,
                    CallLog.Calls.DURATION
                ),
                null, null, CallLog.Calls.DATE + " DESC"
            )
            cursor?.use {
                var count = 0
                while (it.moveToNext() && count < 40) {
                    val item = JSONObject().apply {
                        put("number", it.getString(0) ?: "ناشناس")
                        put("name", it.getString(1) ?: "")
                        put("callType", it.getInt(2))
                        put("date", it.getLong(3))
                        put("duration", it.getLong(4))
                    }
                    arr.put(item)
                    count++
                }
            }
            val res = JSONObject().apply {
                put("type", "CALLLOG_LIST")
                put("items", arr)
            }
            sendEncryptedToClients(res.toString())
        } catch (e: Exception) {
            sendFeedback("خطا در خواندن سابقه تماس: ${e.message}")
        }
    }

    private fun syncHostContacts() {
        try {
            val arr = JSONArray()
            val seen = HashSet<String>()
            val cursor = contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                null, null, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
            )
            cursor?.use {
                val nIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val pIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (it.moveToNext() && arr.length() < 300) {
                    val name = if (nIdx >= 0) it.getString(nIdx) ?: "" else ""
                    val num = if (pIdx >= 0) it.getString(pIdx) ?: "" else ""
                    if (name.isNotBlank() && num.isNotBlank() && seen.add(num)) {
                        arr.put(JSONObject().put("name", name).put("phone", num))
                    }
                }
            }
            val res = JSONObject().apply {
                put("type", "CONTACTS_LIST")
                put("items", arr)
            }
            sendEncryptedToClients(res.toString())
        } catch (e: Exception) {
            sendFeedback("خطا در همگام‌سازی مخاطبین: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun placeCallCompat(number: String, simSlot: Int = -1) {
        try {
            val uri = Uri.parse("tel:" + Uri.encode(number))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    val tm = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
                    val extras = Bundle()
                    if (simSlot >= 0) {
                        val accounts = tm.callCapablePhoneAccounts
                        if (accounts != null && simSlot < accounts.size) {
                            extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, accounts[simSlot])
                        }
                    }
                    tm.placeCall(uri, extras)
                    val simLabel = if (simSlot >= 0) " (سیم‌کارت ${simSlot + 1})" else ""
                    onHostLog?.invoke("شماره‌گیری مستقیم$simLabel: $number")
                    return
                } catch (_: Exception) {
                }
            }
            val callIntent = Intent(Intent.ACTION_CALL, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (simSlot >= 0) {
                    putExtra("com.android.phone.extra.slot", simSlot)
                    putExtra("simSlot", simSlot)
                }
            }
            startActivity(callIntent)
            onHostLog?.invoke("شماره‌گیری: $number")
        } catch (e: Exception) {
            sendFeedback("خطا در شماره‌گیری: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun answerCallCompat() {
        var answered = false
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ANSWER_PHONE_CALLS) != PackageManager.PERMISSION_GRANTED) {
                    sendFeedback("مجوز پاسخ به تماس (Phone) روی گوشی اول فعال نیست! از تنظیمات گوشی اول مجوز Phone را Allow کنید.")
                } else {
                    val tm = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
                    tm.acceptRingingCall()
                    answered = true
                }
            }
        } catch (_: Exception) {
        }

        if (!answered) {
            if (CallNotificationHelper.answerCall(this)) {
                answered = true
            } else if (!CallNotificationHelper.isConnected() && Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                sendFeedback("در گوشی اول (اندروید ۵) دکمه «فعال‌سازی پاسخ و قطع تماس» را در داشبورد بزنید و تیک Samen Pro را روشن کنید.")
            }
        }

        if (!answered) {
            try {
                val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val now = SystemClock.uptimeMillis()
                am.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HEADSETHOOK, 0))
                am.dispatchMediaKeyEvent(KeyEvent(now, now + 50, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_HEADSETHOOK, 0))
                answered = true
            } catch (e: Exception) {
                sendFeedback("خطا در پاسخ تماس: ${e.message}")
            }
        }

        if (answered) {
            onHostLog?.invoke("تماس پاسخ داده شد (هدایت صدا به هندزفری بلوتوثی)")
            Handler(Looper.getMainLooper()).postDelayed({ routeCallToBluetoothHeadset() }, 600)
        }
    }

    private fun routeCallToBluetoothHeadset() {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.isSpeakerphoneOn = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val btDevice = am.availableCommunicationDevices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
                }
                if (btDevice != null) {
                    am.setCommunicationDevice(btDevice)
                }
            } else {
                @Suppress("DEPRECATION")
                if (am.isBluetoothScoAvailableOffCall) {
                    am.startBluetoothSco()
                    am.isBluetoothScoOn = true
                }
            }
        } catch (_: Exception) {
        }
    }

    @SuppressLint("MissingPermission")
    private fun endCallCompat() {
        var ended = false
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ANSWER_PHONE_CALLS) != PackageManager.PERMISSION_GRANTED) {
                    sendFeedback("مجوز قطع تماس (Phone) روی گوشی اول فعال نیست!")
                } else {
                    val tm = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
                    @Suppress("DEPRECATION")
                    ended = tm.endCall()
                }
            }
        } catch (_: Exception) {
        }

        if (!ended) {
            try {
                val tm = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                val m = Class.forName(tm.javaClass.name).getDeclaredMethod("getITelephony")
                m.isAccessible = true
                val telephonyInterface = m.invoke(tm)
                val endMethod = Class.forName(telephonyInterface.javaClass.name).getDeclaredMethod("endCall")
                endMethod.invoke(telephonyInterface)
                ended = true
            } catch (_: Exception) {
            }
        }

        if (!ended) {
            if (CallNotificationHelper.endCall(this)) {
                ended = true
            } else {
                sendFeedback("برای قطع تماس در اندروید ۵، در داشبورد گوشی اول دکمه «فعال‌سازی پاسخ و قطع تماس» را تایید کنید.")
            }
        }

        if (ended) {
            onHostLog?.invoke("تماس از گوشی دوم قطع شد.")
        }
    }

    @SuppressLint("MissingPermission")
    private fun deleteCallLogCompat(json: JSONObject) {
        try {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.WRITE_CALL_LOG) != PackageManager.PERMISSION_GRANTED) {
                onHostLog?.invoke("مجوز WRITE_CALL_LOG روی گوشی اول فعال نیست.")
                return
            }
            if (json.optBoolean("all", false)) {
                contentResolver.delete(CallLog.Calls.CONTENT_URI, null, null)
                onHostLog?.invoke("تمام سابقه تماس‌ها روی گوشی اول پاک شد.")
            } else {
                val arr = json.optJSONArray("items")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        val num = obj.optString("number")
                        val date = obj.optLong("date", 0L)
                        if (date > 0L) {
                            contentResolver.delete(
                                CallLog.Calls.CONTENT_URI,
                                "${CallLog.Calls.NUMBER}=? AND ${CallLog.Calls.DATE}=?",
                                arrayOf(num, date.toString())
                            )
                        } else if (num.isNotBlank()) {
                            contentResolver.delete(
                                CallLog.Calls.CONTENT_URI,
                                "${CallLog.Calls.NUMBER}=?",
                                arrayOf(num)
                            )
                        }
                    }
                    onHostLog?.invoke("تماس‌های انتخاب‌شده روی گوشی اول حذف شدند.")
                }
            }
        } catch (e: Exception) {
            onHostLog?.invoke("خطا در حذف سابقه تماس: ${e.message}")
        }
    }

    private fun deleteSmsCompat(json: JSONObject) {
        try {
            if (json.optBoolean("all", false)) {
                contentResolver.delete(Uri.parse("content://sms"), null, null)
            } else {
                val arr = json.optJSONArray("addresses")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val addr = arr.optString(i)
                        if (addr.isNotBlank()) {
                            contentResolver.delete(Uri.parse("content://sms"), "address=?", arrayOf(addr))
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun ringHostLoudly() {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            am.setStreamVolume(AudioManager.STREAM_ALARM, maxVol, 0)
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            val mp = MediaPlayer().apply {
                setDataSource(this@HostBridgeService, uri)
                @Suppress("DEPRECATION")
                setAudioStreamType(AudioManager.STREAM_ALARM)
                isLooping = true
                prepare()
                start()
            }
            onHostLog?.invoke("زنگ پیدا کردن گوشی اول فعال شد (۸ ثانیه)")
            sendFeedback("گوشی اول به مدت ۸ ثانیه با حداکثر صدا زنگ می‌زند...")
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    mp.stop()
                    mp.release()
                } catch (_: Exception) {
                }
            }, 8000L)
        } catch (e: Exception) {
            sendFeedback("خطا در پخش زنگ گوشی اول: ${e.message}")
        }
    }

    private fun sendFeedback(message: String) {
        val res = JSONObject().apply {
            put("type", "SYSTEM_MSG")
            put("message", message)
        }
        sendEncryptedToClients(res.toString())
    }

    fun sendEncryptedToClients(plainJson: String) {
        try {
            val encrypted = CryptoHelper.encrypt(plainJson, aesKey)
            offlineServer?.sendToAllClients(encrypted)
            btServer?.sendToAllClients(encrypted)
        } catch (_: Exception) {
        }
    }

    private fun startForegroundNotification() {
        val channelId = "bridge_service_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Samen Pro Host", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val notification = builder
            .setContentTitle("Samen Pro v5.1 (میزبان فعال)")
            .setContentText("IP: ${getLocalIpAddress()} | پورت 8888 | پروکسی 8080")
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .build()
        startForeground(101, notification)
    }

    override fun onDestroy() {
        statusLoopRunning = false
        discoveryManager.stopHostBroadcast()
        offlineServer?.stop()
        btServer?.stop()
        httpProxy?.stop()
        callAudioRouter?.stopCallBridge()
        try { dynamicSmsReceiver?.let { unregisterReceiver(it) } } catch (_: Exception) {}
        try { dynamicCallReceiver?.let { unregisterReceiver(it) } } catch (_: Exception) {}
        try { smsStatusReceiver?.let { unregisterReceiver(it) } } catch (_: Exception) {}
        try { smsObserver?.let { contentResolver.unregisterContentObserver(it) } } catch (_: Exception) {}
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        try { if (wifiLock?.isHeld == true) wifiLock?.release() } catch (_: Exception) {}
        isRunning = false
        getSharedPreferences("itell_cfg", Context.MODE_PRIVATE).edit().putBoolean("was_host_running", false).apply()
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
