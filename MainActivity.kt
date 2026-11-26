package com.bridge.itell

import android.Manifest
import android.app.AlertDialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.*
import kotlin.concurrent.thread

data class SmsItem(val address: String, val body: String, val timestamp: Long, val isOutgoing: Boolean)
data class CallLogItem(val number: String, val name: String, val type: Int, val date: Long, val duration: Long)

class MainActivity : AppCompatActivity() {

    // پالت رنگی مدرن (Deep Slate & Neon Cyan/Emerald)
    private val colBg = Color.parseColor("#090E1A")
    private val colSurface = Color.parseColor("#111827")
    private val colCard = Color.parseColor("#1E293B")
    private val colCardLight = Color.parseColor("#273549")
    private val colAccent = Color.parseColor("#38BDF8")
    private val colGreen = Color.parseColor("#10B981")
    private val colRed = Color.parseColor("#EF4444")
    private val colAmber = Color.parseColor("#F59E0B")
    private val colPurple = Color.parseColor("#8B5CF6")
    private val colText = Color.parseColor("#F8FAFC")
    private val colSubText = Color.parseColor("#94A3B8")

    private val aesKey = "12345678901234567890123456789012".toByteArray()
    private lateinit var contactsManager: ContactsManager
    private lateinit var licenseManager: LicenseManager
    private lateinit var btDevicesContainer: LinearLayout
    private val discoveryManager = DiscoveryManager()
    private val discoveredHosts = LinkedHashMap<String, DiscoveredHost>()

    private var clientSocket: Socket? = null
    private var clientWriter: PrintWriter? = null
    @Volatile private var isConnected = false
    @Volatile private var shouldAutoReconnect = true
    @Volatile private var userManuallyDisconnected = false
    private var bluetoothHeadsetMode = true
    private var connectedHostIp = ""
    private var callAudioRouter: CallAudioRouter? = null

    private val allSms = mutableListOf<SmsItem>()
    private val callLogs = mutableListOf<CallLogItem>()
    private var openChatAddress: String? = null
    private var activeTab = 0
    private var selectedSimSlot = -1 // -1: پیش‌فرض، 0: سیم‌کارت ۱، 1: سیم‌کارت ۲
    private var sim1Label = "سیم‌کارت ۱"
    private var sim2Label = "سیم‌کارت ۲"
    private val simButtons = mutableListOf<TextView>()

    // تایمر تماس زنده
    private val uiHandler = Handler(Looper.getMainLooper())
    private var callStartTimeMs = 0L
    private var isCallTimerRunning = false
    private var isSpeakerOn = false
    private var isMicMuted = false

    // ویوهای سراسری
    private lateinit var rootFrame: FrameLayout
    private lateinit var statusBadge: TextView
    private lateinit var hostBatteryBar: ProgressBar
    private lateinit var hostMetaText: TextView
    private lateinit var contentContainer: FrameLayout
    private val navButtons = mutableListOf<TextView>()

    // صفحه تمام‌صفحه تماس (In-Call Screen)
    private lateinit var inCallOverlay: LinearLayout
    private lateinit var inCallAvatar: TextView
    private lateinit var inCallName: TextView
    private lateinit var inCallNumber: TextView
    private lateinit var inCallStatus: TextView
    private lateinit var btnInCallAnswer: TextView
    private lateinit var btnInCallMute: TextView
    private lateinit var btnInCallSpeaker: TextView

    // تب‌ها
    private lateinit var tabDashboard: View
    private lateinit var tabCalls: View
    private lateinit var tabSms: View
    private lateinit var tabContacts: View
    private lateinit var tabLogs: View

    // کنترل‌های داشبورد
    private lateinit var ipInput: EditText
    private lateinit var discoveredContainer: LinearLayout
    private lateinit var hostIpLabel: TextView
    private lateinit var logText: TextView

    // کنترل‌های تب تماس
    private lateinit var favoritesRow: LinearLayout
    private lateinit var callLogContainer: LinearLayout
    private lateinit var dialpadPanel: LinearLayout
    private lateinit var dialDisplay: EditText

    // کنترل‌های تب پیامک
    private lateinit var smsThreadsView: LinearLayout
    private lateinit var smsChatView: LinearLayout
    private lateinit var smsSearchInput: EditText
    private lateinit var smsThreadsContainer: LinearLayout
    private lateinit var chatHeaderTitle: TextView
    private lateinit var chatMessagesContainer: LinearLayout
    private lateinit var chatScroll: ScrollView
    private lateinit var chatInput: EditText

    // کنترل‌های تب مخاطبین
    private lateinit var contactSearchInput: EditText
    private lateinit var contactsListContainer: LinearLayout

    private val callTimerRunnable = object : Runnable {
        override fun run() {
            if (!isCallTimerRunning) return
            val elapsedSec = (System.currentTimeMillis() - callStartTimeMs) / 1000
            val min = elapsedSec / 60
            val sec = elapsedSec % 60
            inCallStatus.text = String.format(Locale.US, "در حال مکالمه • %02d:%02d", min, sec)
            uiHandler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyLockScreenFlags()
        contactsManager = ContactsManager(this)
        licenseManager = LicenseManager(this)
        createNotificationChannels()
        requestAllPermissions()

        rootFrame = FrameLayout(this).apply {
            setBackgroundColor(colBg)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }

        val mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        mainLayout.addView(buildHeader())

        contentContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }

        tabDashboard = buildDashboardTab()
        tabCalls = buildCallsTab()
        tabSms = buildSmsTab()
        tabContacts = buildContactsTab()
        tabLogs = buildLogsTab()

        contentContainer.addView(tabDashboard)
        contentContainer.addView(tabCalls)
        contentContainer.addView(tabSms)
        contentContainer.addView(tabContacts)
        contentContainer.addView(tabLogs)
        mainLayout.addView(contentContainer)
        mainLayout.addView(buildBottomNav())

        rootFrame.addView(mainLayout)

        // لایه تمام‌صفحه تماس
        inCallOverlay = buildInCallOverlay()
        inCallOverlay.visibility = View.GONE
        rootFrame.addView(inCallOverlay)

        if (!licenseManager.isActivated()) {
            rootFrame.addView(buildLicenseLockOverlay())
        } else {
            licenseManager.checkOnlineApproval { approved, msg ->
                if (!approved && msg.contains("غیرفعال")) {
                    runOnUiThread {
                        rootFrame.addView(buildLicenseLockOverlay())
                    }
                }
            }
        }
        setContentView(rootFrame)
        switchTab(0)
        updateSmsNavBadge()

        bluetoothHeadsetMode = getSharedPreferences("itell_cfg", Context.MODE_PRIVATE).getBoolean("bt_headset_mode", true)
        HostBridgeService.onHostLog = { msg -> appendLog(msg) }
        bindClientServiceCallbacks()
        startAutoDiscovery()
        handleIncomingCallIntent(intent)
    }

    private fun applyLockScreenFlags() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingCallIntent(intent)
    }

    private fun handleIncomingCallIntent(intent: Intent?) {
        val number = intent?.getStringExtra(ClientBridgeService.EXTRA_INCOMING_CALL_NUMBER)
        if (!number.isNullOrBlank()) {
            applyLockScreenFlags()
            showInCallScreen(number, isIncoming = true)
            intent.removeExtra(ClientBridgeService.EXTRA_INCOMING_CALL_NUMBER)
        }
    }

    private fun bindClientServiceCallbacks() {
        ClientBridgeService.onEventReceived = { json -> handleHostEvent(json) }
        ClientBridgeService.onLog = { msg -> appendLog(msg) }
        ClientBridgeService.onStateChanged = { connected, ip ->
            isConnected = connected
            connectedHostIp = if (connected) ip else connectedHostIp
            runOnUiThread {
                if (connected) {
                    updateBadge("● متصل", colGreen)
                } else if (!ClientBridgeService.userManuallyDisconnected) {
                    updateBadge("● تلاش مجدد...", colAmber)
                    hostMetaText.text = "اتصال قطع شد • سرویس پس‌زمینه در حال اتصال مجدد..."
                } else {
                    updateBadge("● آفلاین", colRed)
                    hostMetaText.text = "اتصال به صورت دستی قطع شد"
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        bindClientServiceCallbacks()
        updateSmsNavBadge()
        refreshSmsThreadsUI()
        isConnected = ClientBridgeService.isConnected
        connectedHostIp = ClientBridgeService.connectedHostIp
        if (isConnected) {
            updateBadge("● متصل", colGreen)
            sendEncryptedToHost(JSONObject().put("type", "CMD_PING").toString())
            sendEncryptedToHost(JSONObject().put("type", "CMD_SYNC_SMS").toString())
            sendEncryptedToHost(JSONObject().put("type", "CMD_SYNC_CALLLOG").toString())
        }
    }

    override fun onDestroy() {
        shouldAutoReconnect = false
        discoveryManager.stopClientDiscovery()
        isCallTimerRunning = false
        super.onDestroy()
    }

    private fun startBackgroundReconnectLoop() = thread {
        while (shouldAutoReconnect) {
            try {
                if (!isConnected && !userManuallyDisconnected && !HostBridgeService.isRunning) {
                    val savedIp = getSharedPreferences("itell_cfg", Context.MODE_PRIVATE)
                        .getString("host_ip", "") ?: ""
                    if (savedIp.isNotBlank()) {
                        connectToHostInternal(savedIp, isBackgroundRetry = true)
                    }
                }
            } catch (_: Exception) {}
            try { Thread.sleep(4000) } catch (_: Exception) {}
        }
    }

    private fun startAutoDiscovery() {
        discoveryManager.startClientDiscovery { host ->
            val myIp = HostBridgeService.getLocalIpAddress()
            if (host.ip == myIp && HostBridgeService.isRunning) return@startClientDiscovery
            discoveredHosts[host.ip] = host
            runOnUiThread { refreshDiscoveredHostsUI() }
        }
    }

    private fun buildHeader(): View {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(colSurface)
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }

        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleCol.addView(TextView(this).apply {
            text = "Samen Pro"
            textSize = 19f
            setTextColor(colText)
            setTypeface(null, Typeface.BOLD)
        })
        titleCol.addView(TextView(this).apply {
            text = "نسخه ۴.۴ • لینک کلیک‌پذیر + کپی خودکار رمز پویا"
            textSize = 11f
            setTextColor(colSubText)
        })

        statusBadge = TextView(this).apply {
            text = "● آفلاین"
            textSize = 12f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = roundedBg(colRed, 20)
        }

        header.addView(titleCol)
        header.addView(statusBadge)
        return header
    }

    // ==================== لایه تمام‌صفحه تماس (In-Call Screen) ====================
    private fun buildInCallOverlay(): LinearLayout {
        val overlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Color.parseColor("#0B132B"))
            setPadding(dp(24), dp(56), dp(24), dp(40))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            isClickable = true
            isFocusable = true
        }

        inCallAvatar = TextView(this).apply {
            text = "📞"
            textSize = 36f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = roundedBg(colCardLight, 60)
            layoutParams = LinearLayout.LayoutParams(dp(100), dp(100))
        }
        overlay.addView(inCallAvatar)

        inCallName = TextView(this).apply {
            text = "مخاطب ناشناس"
            textSize = 26f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(20), 0, dp(6))
        }
        overlay.addView(inCallName)

        inCallNumber = TextView(this).apply {
            text = ""
            textSize = 16f
            setTextColor(colSubText)
        }
        overlay.addView(inCallNumber)

        inCallStatus = TextView(this).apply {
            text = "تماس ورودی از سیم‌کارت اول..."
            textSize = 15f
            setTextColor(colAccent)
            setPadding(0, dp(14), 0, dp(32))
        }
        overlay.addView(inCallStatus)

        // ردیف کنترل‌های میوت / اسپیکر / پل صوتی
        val midRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, dp(36))
        }

        btnInCallMute = circleActionBtn("میوت", colCard) {
            isMicMuted = !isMicMuted
            callAudioRouter?.isMuted = isMicMuted
            btnInCallMute.background = roundedBg(if (isMicMuted) colAmber else colCard, 40)
            btnInCallMute.text = if (isMicMuted) "میوت ✓" else "میوت"
        }

        btnInCallSpeaker = circleActionBtn("اسپیکر", colCard) {
            isSpeakerOn = !isSpeakerOn
            callAudioRouter?.setSpeaker(isSpeakerOn)
            btnInCallSpeaker.background = roundedBg(if (isSpeakerOn) colAccent else colCard, 40)
            btnInCallSpeaker.text = if (isSpeakerOn) "اسپیکر ✓" else "اسپیکر"
        }

        val btnAudioSync = circleActionBtn("صدای زنده", colPurple) {
            if (callAudioRouter == null) {
                sendEncryptedToHost(JSONObject().put("type", "CMD_START_AUDIO").toString())
                startLocalAudioBridge()
            } else {
                sendEncryptedToHost(JSONObject().put("type", "CMD_STOP_AUDIO").toString())
                stopLocalAudioBridge()
            }
        }

        midRow.addView(btnInCallMute)
        midRow.addView(btnInCallSpeaker)
        midRow.addView(btnAudioSync)
        overlay.addView(midRow)

        // فضای خالی برای هدایت دکمه‌های اصلی به پایین
        overlay.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(1, 0, 1f)
        })

        // ردیف دکمه‌های پاسخ و قطع تماس
        val bottomRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        btnInCallAnswer = TextView(this).apply {
            text = "پاسخ"
            textSize = 17f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = roundedBg(colGreen, 45)
            layoutParams = LinearLayout.LayoutParams(dp(120), dp(64)).apply {
                setMargins(dp(16), 0, dp(16), 0)
            }
            setOnClickListener {
                sendEncryptedToHost(JSONObject().put("type", "CMD_ANSWER_CALL").toString())
                if (!bluetoothHeadsetMode) {
                    sendEncryptedToHost(JSONObject().put("type", "CMD_START_AUDIO").toString())
                    startLocalAudioBridge()
                }
                startCallTimer()
                btnInCallAnswer.visibility = View.GONE
            }
        }

        val btnEnd = TextView(this).apply {
            text = "قطع تماس"
            textSize = 17f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = roundedBg(colRed, 45)
            layoutParams = LinearLayout.LayoutParams(dp(120), dp(64)).apply {
                setMargins(dp(16), 0, dp(16), 0)
            }
            setOnClickListener {
                sendEncryptedToHost(JSONObject().put("type", "CMD_END_CALL").toString())
                sendEncryptedToHost(JSONObject().put("type", "CMD_STOP_AUDIO").toString())
                hideInCallScreen()
            }
        }

        bottomRow.addView(btnInCallAnswer)
        bottomRow.addView(btnEnd)
        overlay.addView(bottomRow)
        return overlay
    }

    private fun showInCallScreen(number: String, isIncoming: Boolean) {
        val resolved = contactsManager.resolveName(number)
        inCallName.text = resolved
        inCallNumber.text = number
        inCallAvatar.text = if (resolved.isNotEmpty() && resolved != number) resolved.take(1) else "📞"
        inCallStatus.text = if (isIncoming) "تماس ورودی از گوشی اول..." else "در حال شماره‌گیری از سیم‌کارت اول..."
        btnInCallAnswer.visibility = if (isIncoming) View.VISIBLE else View.GONE
        inCallOverlay.visibility = View.VISIBLE
        if (!isIncoming) startCallTimer()
    }

    private fun startCallTimer() {
        callStartTimeMs = System.currentTimeMillis()
        isCallTimerRunning = true
        uiHandler.removeCallbacks(callTimerRunnable)
        uiHandler.post(callTimerRunnable)
    }

    private fun hideInCallScreen() {
        isCallTimerRunning = false
        uiHandler.removeCallbacks(callTimerRunnable)
        stopLocalAudioBridge()
        inCallOverlay.visibility = View.GONE
    }

    // ==================== تب ۱: داشبورد و کشف خودکار ====================
    private fun buildDashboardTab(): View {
        val scroll = ScrollView(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(16))
        }

        // کارت وضعیت زنده گوشی میزبان
        val statusCard = cardContainer()
        statusCard.addView(sectionTitle("وضعیت گوشی میزبان (سیم‌کارت)"))
        hostMetaText = TextView(this).apply {
            text = "متصل نیست • برای اتصال یک دستگاه انتخاب کنید"
            textSize = 13f
            setTextColor(colSubText)
            setPadding(0, dp(6), 0, dp(8))
        }
        statusCard.addView(hostMetaText)

        hostBatteryBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(10))
        }
        statusCard.addView(hostBatteryBar)
        col.addView(statusCard)

        // کارت حالت هندزفری بلوتوثی
        val btCard = cardContainer()
        btCard.addView(sectionTitle("حالت صدای مکالمه"))
        val btToggleBtn = styledButton(
            if (bluetoothHeadsetMode) "🎧 حالت هندزفری بلوتوثی: روشن (پیشنهادی)" else "🔊 انتقال صدا روی شبکه: روشن",
            if (bluetoothHeadsetMode) colGreen else colPurple
        ) {}
        btToggleBtn.setOnClickListener {
            bluetoothHeadsetMode = !bluetoothHeadsetMode
            getSharedPreferences("itell_cfg", Context.MODE_PRIVATE).edit()
                .putBoolean("bt_headset_mode", bluetoothHeadsetMode).apply()
            btToggleBtn.text = if (bluetoothHeadsetMode) "🎧 حالت هندزفری بلوتوثی: روشن (پیشنهادی)" else "🔊 انتقال صدا روی شبکه: روشن"
            btToggleBtn.background = roundedBg(if (bluetoothHeadsetMode) colGreen else colPurple, 12)
        }
        btCard.addView(btToggleBtn)

        var autoSwitchOn = getSharedPreferences("itell_cfg", Context.MODE_PRIVATE).getBoolean("auto_switch_enabled", true)
        val autoSwitchBtn = styledButton(
            if (autoSwitchOn) "🔄 سوئیچ هوشمند وای‌فای ↔ بلوتوث: روشن" else "🔄 سوئیچ هوشمند وای‌فای ↔ بلوتوث: خاموش",
            if (autoSwitchOn) colAccent else colCardLight
        ) {}
        autoSwitchBtn.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, dp(8), 0, 0) }
        autoSwitchBtn.setOnClickListener {
            autoSwitchOn = !autoSwitchOn
            getSharedPreferences("itell_cfg", Context.MODE_PRIVATE).edit()
                .putBoolean("auto_switch_enabled", autoSwitchOn).apply()
            autoSwitchBtn.text = if (autoSwitchOn) "🔄 سوئیچ هوشمند وای‌فای ↔ بلوتوث: روشن" else "🔄 سوئیچ هوشمند وای‌فای ↔ بلوتوث: خاموش"
            autoSwitchBtn.background = roundedBg(if (autoSwitchOn) colAccent else colCardLight, 12)
            val svcIntent = Intent(this, ClientBridgeService::class.java).apply {
                action = ClientBridgeService.ACTION_SET_AUTO_SWITCH
                putExtra(ClientBridgeService.EXTRA_AUTO_SWITCH, autoSwitchOn)
            }
            try { startService(svcIntent) } catch (_: Exception) {}
        }
        btCard.addView(autoSwitchBtn)
        val dozeBtn = styledButton("🔓 رفع محدودیت باتری در قفل صفحه (توصیه می‌شود)", colAmber) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = android.net.Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (_: Exception) {
                    Toast.makeText(this, "از تنظیمات گوشی، باتری برنامه را روی Unrestricted بگذارید", Toast.LENGTH_LONG).show()
                }
            }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(8), 0, 0) }
        }
        btCard.addView(dozeBtn)
        val overlayBtn = styledButton("📱 فعال‌سازی نمایش تمام‌صفحه تماس (روی سایر برنامه‌ها)", colPurple) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    val intent = Intent(
                        android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                } catch (_: Exception) {
                    Toast.makeText(this, "از تنظیمات گوشی مجوز Display over other apps را فعال کنید", Toast.LENGTH_LONG).show()
                }
            }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(8), 0, 0) }
        }
        btCard.addView(overlayBtn)
        col.addView(btCard)

        // کارت کشف خودکار (Zero-Config)
        val btDirectCard = cardContainer()
        btDirectCard.addView(sectionTitle("🔵 اتصال مستقیم بلوتوث (بدون قطع با وای‌فای)"))
        btDirectCard.addView(TextView(this).apply {
            text = "ابتدا دو گوشی را در تنظیمات بلوتوث Pair کنید، سپس روی نام گوشی اول بزنید. با این روش می‌توانید همزمان به هر وای‌فایی وصل شوید:"
            textSize = 11f
            setTextColor(colSubText)
            setPadding(0, dp(4), 0, dp(8))
        })
        btDevicesContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        btDirectCard.addView(btDevicesContainer)
        btDirectCard.addView(styledButton("↻ بروزرسانی لیست دستگاه‌های بلوتوثی", colCardLight) {
            refreshPairedBtDevicesUI()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(6), 0, 0) }
        })
        col.addView(btDirectCard)
        refreshPairedBtDevicesUI()

        val discCard = cardContainer()
        discCard.addView(sectionTitle("دستگاه‌های آماده در شبکه (اتصال با یک لمس)"))
        discoveredContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        discoveredContainer.addView(TextView(this).apply {
            text = "در حال جستجوی خودکار گوشی میزبان روی هات‌اسپات/وای‌فای..."
            textSize = 12f
            setTextColor(colSubText)
        })
        discCard.addView(discoveredContainer)
        col.addView(discCard)

        // کارت ۱: راه‌اندازی به عنوان میزبان
        val hostCard = cardContainer()
        hostCard.addView(sectionTitle("حالت ۱: این گوشی دارای سیم‌کارت است (میزبان)"))
        hostIpLabel = TextView(this).apply {
            text = "IP محلی: ${HostBridgeService.getLocalIpAddress()} | پروکسی اینترنت: پورت 8080"
            textSize = 12f
            setTextColor(colAccent)
            setPadding(0, dp(6), 0, dp(10))
        }
        hostCard.addView(hostIpLabel)
        hostCard.addView(styledButton("فعال‌سازی سرویس میزبان", colAccent) {
            val intent = Intent(this, HostBridgeService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
            else startService(intent)
            hostIpLabel.text = "IP محلی: ${HostBridgeService.getLocalIpAddress()} | پروکسی: 8080"
            updateBadge("● میزبان فعال", colGreen)
            appendLog("سرویس میزبان با کشف خودکار فعال شد.")
        })
        col.addView(hostCard)

        // کارت ۲: اتصال دستی (در صورت نیاز)
        val manualCard = cardContainer()
        manualCard.addView(sectionTitle("حالت ۲: اتصال دستی به IP میزبان"))
        val savedIp = getSharedPreferences("itell_cfg", Context.MODE_PRIVATE)
            .getString("host_ip", "192.168.43.1") ?: "192.168.43.1"
        ipInput = styledInput("مثلاً 192.168.43.1").apply { setText(savedIp) }
        manualCard.addView(ipInput)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
        }
        row.addView(styledButton("اتصال", colGreen) {
            connectToHost(ipInput.text.toString().trim())
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(dp(4), 0, 0, 0)
            }
        })
        row.addView(styledButton("قطع", colRed) {
            userManuallyDisconnected = true
            disconnectClient()
            updateBadge("● آفلاین", colRed)
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.5f).apply {
                setMargins(0, 0, dp(4), 0)
            }
        })
        manualCard.addView(row)
        col.addView(manualCard)



        scroll.addView(col)
        return scroll
    }

    private fun refreshPairedBtDevicesUI() {
        btDevicesContainer.removeAllViews()
        val devices = BluetoothBridgeHelper.getPairedDevices(this)
        if (devices.isEmpty()) {
            btDevicesContainer.addView(TextView(this).apply {
                text = "دستگاه بلوتوثی جفت‌شده‌ای یافت نشد (بلوتوث را روشن و مجوز Nearby devices را تایید کنید)."
                textSize = 11f
                setTextColor(colSubText)
            })
            return
        }
        for (d in devices) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = roundedBg(colCardLight, 12)
                setPadding(dp(12), dp(8), dp(12), dp(8))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, dp(6)) }
            }
            val info = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            info.addView(TextView(this).apply {
                text = d.name
                textSize = 13f
                setTextColor(colText)
                setTypeface(null, Typeface.BOLD)
            })
            info.addView(TextView(this).apply {
                text = d.address
                textSize = 11f
                setTextColor(colSubText)
            })
            row.addView(info)
            row.addView(smallChip("اتصال بلوتوث", colAccent) {
                connectToHostBluetooth(d.address, d.name)
            })
            btDevicesContainer.addView(row)
        }
    }

    private fun connectToHostBluetooth(mac: String, name: String) {
        userManuallyDisconnected = false
        val svcIntent = Intent(this, ClientBridgeService::class.java).apply {
            action = ClientBridgeService.ACTION_CONNECT_BT
            putExtra(ClientBridgeService.EXTRA_BT_MAC, mac)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svcIntent)
        else startService(svcIntent)
        updateBadge("● اتصال بلوتوث...", colAmber)
        appendLog("شروع اتصال مستقیم بلوتوث به $name ($mac)")
    }

    private fun buildLicenseLockOverlay(): LinearLayout {
        val overlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(colBg)
            setPadding(dp(24), dp(36), dp(24), dp(36))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            isClickable = true
            isFocusable = true
        }

        val card = cardContainer()
        card.addView(TextView(this).apply {
            text = "🔒 فعال‌سازی Samen Pro"
            textSize = 20f
            setTextColor(colText)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
        })
        card.addView(TextView(this).apply {
            text = "برای استفاده از این برنامه، شناسه دستگاه زیر را برای مدیر ارسال کنید تا دسترسی شما تایید شود:"
            textSize = 13f
            setTextColor(colSubText)
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(14))
        })

        val myDeviceId = licenseManager.getDeviceId()
        val idBox = TextView(this).apply {
            text = myDeviceId
            textSize = 22f
            setTextColor(colAccent)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = roundedBg(colSurface, 12)
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        card.addView(idBox)

        card.addView(styledButton("📋 کپی شناسه دستگاه", colCardLight) {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("DeviceID", myDeviceId))
            Toast.makeText(this, "شناسه $myDeviceId کپی شد", Toast.LENGTH_SHORT).show()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(10), 0, dp(12)) }
        })

        val statusTxt = TextView(this).apply {
            text = "پس از تایید مدیر در پنل، روی دکمه بررسی تایید آنلاین بزنید:"
            textSize = 12f
            setTextColor(colAmber)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(10))
        }
        card.addView(statusTxt)

        card.addView(styledButton("🌐 بررسی تایید آنلاین (نیازمند اینترنت)", colGreen) {
            statusTxt.text = "در حال استعلام از پنل مدیریت..."
            licenseManager.checkOnlineApproval { approved, msg ->
                runOnUiThread {
                    statusTxt.text = msg
                    if (approved) {
                        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                        rootFrame.removeView(overlay)
                    }
                }
            }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dp(14)) }
        })

        val offlineInput = styledInput("یا کد فعال‌سازی آفلاین را وارد کنید (مثلاً XXXXX-XXXXX)")
        card.addView(offlineInput)

        card.addView(styledButton("🔑 فعال‌سازی با کد آفلاین", colPurple) {
            val code = offlineInput.text.toString().trim()
            if (licenseManager.activateWithOfflineKey(code)) {
                Toast.makeText(this, "برنامه با موفقیت فعال شد!", Toast.LENGTH_LONG).show()
                rootFrame.removeView(overlay)
            } else {
                statusTxt.text = "کد فعال‌سازی برای این دستگاه معتبر نیست!"
            }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(8), 0, 0) }
        })

        overlay.addView(card)
        return overlay
    }

    private fun refreshDiscoveredHostsUI() {
        discoveredContainer.removeAllViews()
        if (discoveredHosts.isEmpty()) {
            discoveredContainer.addView(TextView(this).apply {
                text = "گوشی میزبانی پیدا نشد؛ هات‌اسپات و سرویس میزبان گوشی اول را روشن کنید."
                textSize = 12f
                setTextColor(colSubText)
            })
            return
        }
        for (h in discoveredHosts.values) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = roundedBg(colCardLight, 12)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, dp(8)) }
            }
            val info = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            info.addView(TextView(this).apply {
                text = "${h.name} (${h.operator})"
                textSize = 14f
                setTextColor(colText)
                setTypeface(null, Typeface.BOLD)
            })
            info.addView(TextView(this).apply {
                text = "IP: ${h.ip}  •  باتری: ${h.battery}%"
                textSize = 12f
                setTextColor(colSubText)
            })
            row.addView(info)
            row.addView(smallChip("اتصال", colGreen) {
                ipInput.setText(h.ip)
                connectToHost(h.ip)
            })
            discoveredContainer.addView(row)
        }
    }

    // ==================== تب ۲: تماس (برگزیده‌ها + سابقه تماس + شماره‌گیر و USSD) ====================
    private fun buildCallsTab(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }

        // نوار انتخاب سیم‌کارت (برای گوشی اول دو سیم‌کارته)
        val simRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(8))
        }
        simButtons.clear()
        val simSpecs = listOf(Pair(-1, "پیش‌فرض"), Pair(0, sim1Label), Pair(1, sim2Label))
        for ((slot, label) in simSpecs) {
            val btn = TextView(this).apply {
                text = label
                textSize = 11f
                gravity = Gravity.CENTER
                setPadding(dp(6), dp(8), dp(6), dp(8))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(dp(3), 0, dp(3), 0)
                }
                setOnClickListener {
                    selectedSimSlot = slot
                    refreshSimSelectorUI()
                }
            }
            simButtons.add(btn)
            simRow.addView(btn)
        }
        refreshSimSelectorUI()
        root.addView(simRow)

        // ردیف مخاطبین برگزیده (Favorites)
        val favScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        favoritesRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, dp(10))
        }
        favScroll.addView(favoritesRow)
        root.addView(favScroll)

        // دکمه‌های نوار ابزار تماس
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(8))
        }
        topBar.addView(styledButton("صفحه‌کلید / کد USSD", colAccent) {
            dialpadPanel.visibility = if (dialpadPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(dp(4), 0, 0, 0)
            }
        })
        topBar.addView(styledButton("بروزرسانی تماس‌ها", colCardLight) {
            sendEncryptedToHost(JSONObject().put("type", "CMD_SYNC_CALLLOG").toString())
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.8f).apply {
                setMargins(0, 0, dp(4), 0)
            }
        })
        root.addView(topBar)

        // پنل صفحه‌کلید شماره‌گیر (قابل باز و بسته شدن)
        dialpadPanel = cardContainer().apply { visibility = View.VISIBLE }
        dialDisplay = styledInput("شماره یا کد دستوری (مثل *140*11#)").apply {
            textSize = 20f
            gravity = Gravity.CENTER
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        dialpadPanel.addView(dialDisplay)

        val keys = listOf(
            listOf("1", "2", "3"),
            listOf("4", "5", "6"),
            listOf("7", "8", "9"),
            listOf("*", "0", "#")
        )
        for (rk in keys) {
            val r = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutDirection = View.LAYOUT_DIRECTION_LTR
                setPadding(0, dp(4), 0, dp(4))
            }
            for (k in rk) {
                r.addView(TextView(this).apply {
                    text = k
                    textSize = 20f
                    setTextColor(colText)
                    gravity = Gravity.CENTER
                    setPadding(0, dp(10), 0, dp(10))
                    background = roundedBg(colCardLight, 12)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        setMargins(dp(4), 0, dp(4), 0)
                    }
                    setOnClickListener { dialDisplay.append(k) }
                })
            }
            dialpadPanel.addView(r)
        }

        val dialActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        dialActions.addView(styledButton("تماس / اجرای USSD", colGreen) {
            val num = dialDisplay.text.toString().trim()
            if (num.isNotEmpty()) makeRemoteCall(num)
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.5f).apply {
                setMargins(dp(4), 0, 0, 0)
            }
        })
        dialActions.addView(styledButton("⌫", colCardLight) {
            val cur = dialDisplay.text.toString()
            if (cur.isNotEmpty()) dialDisplay.setText(cur.dropLast(1))
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.5f).apply {
                setMargins(0, 0, dp(4), 0)
            }
        })
        dialpadPanel.addView(dialActions)
        root.addView(dialpadPanel)

        // لیست سابقه تماس‌ها
        val logScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        callLogContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        logScroll.addView(callLogContainer)
        root.addView(logScroll)

        refreshFavoritesUI()
        refreshCallLogUI()
        return root
    }

    private fun refreshFavoritesUI() {
        favoritesRow.removeAllViews()
        val favs = contactsManager.getContacts().filter { it.isFavorite }
        if (favs.isEmpty()) {
            favoritesRow.addView(TextView(this).apply {
                text = "مخاطبین برگزیده (★) اینجا نمایش داده می‌شوند"
                textSize = 11f
                setTextColor(colSubText)
                setPadding(dp(4), dp(4), dp(4), dp(4))
            })
            return
        }
        for (c in favs) {
            val chip = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = roundedBg(colCard, 14)
                setPadding(dp(12), dp(8), dp(12), dp(8))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(dp(6), 0, 0, 0) }
                setOnClickListener { makeRemoteCall(c.phone) }
            }
            chip.addView(TextView(this).apply {
                text = c.name.take(1)
                textSize = 16f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                background = roundedBg(colAccent, 24)
                layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
            })
            chip.addView(TextView(this).apply {
                text = c.name.take(10)
                textSize = 11f
                setTextColor(colText)
                setPadding(0, dp(4), 0, 0)
            })
            favoritesRow.addView(chip)
        }
    }

    private fun refreshCallLogUI() {
        runOnUiThread {
            callLogContainer.removeAllViews()
            if (callLogs.isEmpty()) {
                callLogContainer.addView(TextView(this).apply {
                    text = "پس از اتصال به گوشی اول، سابقه تماس‌ها اینجا نمایش داده می‌شود."
                    textSize = 12f
                    setTextColor(colSubText)
                    gravity = Gravity.CENTER
                    setPadding(dp(16), dp(20), dp(16), dp(20))
                })
                return@runOnUiThread
            }
            val fmt = SimpleDateFormat("MM/dd HH:mm", Locale.getDefault())
            for (item in callLogs) {
                val card = cardContainer()
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                val iconText = when (item.type) {
                    CallLog.Calls.INCOMING_TYPE -> "↙"
                    CallLog.Calls.OUTGOING_TYPE -> "↗"
                    CallLog.Calls.MISSED_TYPE -> "✕"
                    else -> "•"
                }
                val iconColor = when (item.type) {
                    CallLog.Calls.INCOMING_TYPE -> colGreen
                    CallLog.Calls.OUTGOING_TYPE -> colAccent
                    CallLog.Calls.MISSED_TYPE -> colRed
                    else -> colSubText
                }
                row.addView(TextView(this).apply {
                    text = iconText
                    textSize = 18f
                    setTextColor(iconColor)
                    setPadding(dp(10), 0, 0, 0)
                })
                val info = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                val displayName = item.name.ifBlank { contactsManager.resolveName(item.number) }
                info.addView(TextView(this).apply {
                    text = displayName
                    textSize = 14f
                    setTextColor(colText)
                    setTypeface(null, Typeface.BOLD)
                })
                info.addView(TextView(this).apply {
                    text = "${item.number} • ${fmt.format(Date(item.date))}"
                    textSize = 11f
                    setTextColor(colSubText)
                })
                row.addView(info)
                row.addView(smallChip("تماس", colGreen) { makeRemoteCall(item.number) })
                card.addView(row)
                callLogContainer.addView(card)
            }
        }
    }

    // ==================== تب ۳: پیام‌رسان واقعی (Threaded SMS + Pin + Search) ====================
    private fun buildSmsTab(): View {
        val frame = FrameLayout(this)

        // نمای ۱: لیست گفتگوها (Threads)
        smsThreadsView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        smsSearchInput = styledInput("جستجو در پیامک‌ها...").apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) = refreshSmsThreadsUI()
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            })
        }
        topBar.addView(smsSearchInput)
        topBar.addView(smallChip("+ جدید", colGreen) { showNewSmsDialog() })
        topBar.addView(smallChip("↻", colAccent) {
            sendEncryptedToHost(JSONObject().put("type", "CMD_SYNC_SMS").toString())
        })
        smsThreadsView.addView(topBar)

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            setPadding(0, dp(10), 0, 0)
        }
        smsThreadsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(smsThreadsContainer)
        smsThreadsView.addView(scroll)

        // نمای ۲: صفحه چت دوطرفه یک مخاطب
        smsChatView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }

        val chatHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(colCard)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        chatHeader.addView(smallChip("بازگشت", colCardLight) {
            openChatAddress = null
            smsChatView.visibility = View.GONE
            smsThreadsView.visibility = View.VISIBLE
            refreshSmsThreadsUI()
        })
        chatHeaderTitle = TextView(this).apply {
            text = ""
            textSize = 16f
            setTextColor(colText)
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(10), 0, dp(10), 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        chatHeader.addView(chatHeaderTitle)
        chatHeader.addView(smallChip("📌 پین", colAmber) {
            val addr = openChatAddress ?: return@smallChip
            val pinned = contactsManager.togglePinThread(addr)
            Toast.makeText(this, if (pinned) "مکالمه پین شد" else "پین برداشته شد", Toast.LENGTH_SHORT).show()
        })
        smsChatView.addView(chatHeader)

        chatScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        chatMessagesContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        chatScroll.addView(chatMessagesContainer)
        smsChatView.addView(chatScroll)

        val inputBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(colSurface)
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        chatInput = styledInput("پیامک خود را بنویسید...").apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        inputBar.addView(chatInput)
        inputBar.addView(smallChip("👤", colAccent) { showSelectContactForSmsDialog() })
        inputBar.addView(smallChip("ارسال", colGreen) {
            val addr = openChatAddress ?: return@smallChip
            val text = chatInput.text.toString().trim()
            if (text.isNotEmpty()) {
                val json = JSONObject().apply {
                    put("type", "CMD_SEND_SMS")
                    put("to", addr)
                    put("body", text)
                    put("simSlot", selectedSimSlot)
                }
                sendEncryptedToHost(json.toString())
                chatInput.setText("")
            }
        })
        smsChatView.addView(inputBar)

        frame.addView(smsThreadsView)
        frame.addView(smsChatView)
        refreshSmsThreadsUI()
        return frame
    }

    private fun showNewSmsDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(6))
        }
        val et = EditText(this).apply {
            hint = "شماره گیرنده (مثلاً 0912...)"
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            textDirection = View.TEXT_DIRECTION_LTR
            gravity = Gravity.LEFT
        }
        val btnPick = styledButton("👤 انتخاب از لیست مخاطبین", colAccent) {
            showSelectContactForSmsDialog(et)
        }
        layout.addView(et)
        layout.addView(btnPick)
        AlertDialog.Builder(this)
            .setTitle("گفتگوی پیامکی جدید")
            .setView(layout)
            .setPositiveButton("شروع") { _, _ ->
                val num = et.text.toString().trim()
                if (num.isNotEmpty()) openChatThread(num)
            }
            .setNegativeButton("لغو", null)
            .show()
    }

    private fun showSelectContactForSmsDialog(targetInput: EditText? = null) {
        val contacts = contactsManager.getContacts()
        if (contacts.isEmpty()) {
            Toast.makeText(this, "ابتدا در تب مخاطبین، لیست مخاطبین را بارگذاری کنید", Toast.LENGTH_SHORT).show()
            return
        }
        val names = contacts.map { "${it.name} (${it.phone})" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("انتخاب مخاطب")
            .setItems(names) { _, which ->
                val selectedPhone = contacts[which].phone
                if (targetInput != null) {
                    targetInput.setText(selectedPhone)
                } else {
                    openChatThread(selectedPhone)
                }
            }
            .setNegativeButton("لغو", null)
            .show()
    }

    private fun openChatThread(address: String) {
        openChatAddress = address
        clearUnreadCount(address)
        chatHeaderTitle.text = formatPhoneLtr(address)
        chatHeaderTitle.textDirection = View.TEXT_DIRECTION_LTR
        smsThreadsView.visibility = View.GONE
        smsChatView.visibility = View.VISIBLE
        refreshOpenChatUI()
    }

    private fun refreshSmsThreadsUI() {
        runOnUiThread {
            smsThreadsContainer.removeAllViews()
            val query = smsSearchInput.text.toString().trim()
            val grouped = allSms.groupBy {
                contactsManager.normalizePhone(it.address).ifBlank { it.address }
            }
            val sortedThreads = grouped.entries
                .map { entry ->
                    val latest = entry.value.maxByOrNull { it.timestamp }!!
                    val pinned = contactsManager.isThreadPinned(latest.address)
                    Triple(latest, entry.value.size, pinned)
                }
                .filter { (latest, _, _) ->
                    query.isBlank() ||
                        latest.address.contains(query, true) ||
                        contactsManager.resolveName(latest.address).contains(query, true) ||
                        latest.body.contains(query, true)
                }
                .sortedWith(compareByDescending<Triple<SmsItem, Int, Boolean>> { it.third }.thenByDescending { it.first.timestamp })

            if (sortedThreads.isEmpty()) {
                smsThreadsContainer.addView(TextView(this).apply {
                    text = "پیامکی یافت نشد. پس از اتصال، دکمه ↻ را بزنید."
                    textSize = 12f
                    setTextColor(colSubText)
                    gravity = Gravity.CENTER
                    setPadding(dp(16), dp(24), dp(16), dp(24))
                })
                return@runOnUiThread
            }

            val fmt = SimpleDateFormat("MM/dd HH:mm", Locale.getDefault())
            for ((latest, _, isPinned) in sortedThreads) {
                val unreadCnt = getUnreadCount(latest.address)
                val card = cardContainer().apply {
                    if (unreadCnt > 0) {
                        background = roundedBg(colCardLight, 16)
                    }
                }
                val headerRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                val pinPrefix = if (isPinned) "📌 " else ""
                headerRow.addView(TextView(this).apply {
                    text = "$pinPrefix${formatPhoneLtr(latest.address)}"
                    textDirection = View.TEXT_DIRECTION_LTR
                    gravity = Gravity.RIGHT
                    textSize = 14f
                    setTextColor(if (unreadCnt > 0) colGreen else if (isPinned) colAmber else colAccent)
                    setTypeface(null, Typeface.BOLD)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
                if (unreadCnt > 0) {
                    headerRow.addView(TextView(this).apply {
                        text = "● $unreadCnt جدید"
                        textSize = 10.5f
                        setTextColor(Color.WHITE)
                        setTypeface(null, Typeface.BOLD)
                        background = roundedBg(colGreen, 12)
                        setPadding(dp(8), dp(3), dp(8), dp(3))
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { setMargins(dp(6), 0, dp(6), 0) }
                    })
                }
                headerRow.addView(TextView(this).apply {
                    text = fmt.format(Date(latest.timestamp))
                    textSize = 11f
                    setTextColor(colSubText)
                })
                card.addView(headerRow)
                card.addView(TextView(this).apply {
                    text = latest.body.take(90)
                    textSize = 13f
                    setTextColor(colText)
                    setPadding(0, dp(6), 0, 0)
                })
                card.setOnClickListener { openChatThread(latest.address) }
                smsThreadsContainer.addView(card)
            }
        }
    }

    private fun refreshOpenChatUI() {
        val addr = openChatAddress ?: return
        val normTarget = contactsManager.normalizePhone(addr).ifBlank { addr }
        runOnUiThread {
            chatMessagesContainer.removeAllViews()
            val msgs = allSms.filter {
                val n = contactsManager.normalizePhone(it.address).ifBlank { it.address }
                n == normTarget
            }.sortedBy { it.timestamp }

            val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
            for (m in msgs) {
                val wrap = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = if (m.isOutgoing) Gravity.START else Gravity.END
                    setPadding(0, dp(4), 0, dp(4))
                }
                val bubble = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    background = roundedBg(if (m.isOutgoing) Color.parseColor("#065F46") else colCard, 14)
                    setPadding(dp(12), dp(8), dp(12), dp(8))
                }
                bubble.addView(TextView(this).apply {
                    autoLinkMask = android.text.util.Linkify.WEB_URLS or android.text.util.Linkify.PHONE_NUMBERS
                    linksClickable = true
                    setLinkTextColor(colAccent)
                    movementMethod = android.text.method.LinkMovementMethod.getInstance()
                    setTextIsSelectable(true)
                    text = m.body
                    textSize = 14f
                    setTextColor(colText)
                })
                if (!m.isOutgoing) {
                    val otpCode = OtpCopyReceiver.extractOtp(m.body)
                    if (!otpCode.isNullOrBlank()) {
                        bubble.addView(TextView(this).apply {
                            text = "📋 کپی کد ($otpCode)"
                            textSize = 12f
                            setTextColor(Color.WHITE)
                            setTypeface(null, Typeface.BOLD)
                            gravity = Gravity.CENTER
                            background = roundedBg(colGreen, 10)
                            setPadding(dp(10), dp(6), dp(10), dp(6))
                            layoutParams = LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                            ).apply { setMargins(0, dp(6), 0, 0) }
                            setOnClickListener {
                                OtpCopyReceiver.copyToClipboard(this@MainActivity, otpCode, showToast = true)
                            }
                        })
                    }
                }
                bubble.addView(TextView(this).apply {
                    text = fmt.format(Date(m.timestamp))
                    textSize = 10f
                    setTextColor(colSubText)
                    gravity = Gravity.END
                    setPadding(0, dp(4), 0, 0)
                })
                wrap.addView(bubble)
                chatMessagesContainer.addView(wrap)
            }
            chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    // ==================== تب ۴: مخاطبین ====================
    private fun buildContactsTab(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }

        val topCard = cardContainer()
        contactSearchInput = styledInput("جستجوی نام یا شماره مخاطب...").apply {
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) = refreshContactsUI(s?.toString() ?: "")
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            })
        }
        topCard.addView(contactSearchInput)

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        btnRow.addView(styledButton("+ افزودن", colGreen) { showAddContactDialog() }.apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(dp(3), 0, dp(3), 0)
            }
        })
        btnRow.addView(styledButton("از این گوشی", colAccent) {
            val c = contactsManager.importFromDevice()
            Toast.makeText(this, "$c مخاطب اضافه شد", Toast.LENGTH_SHORT).show()
            refreshContactsUI(contactSearchInput.text.toString())
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(dp(3), 0, dp(3), 0)
            }
        })
        btnRow.addView(styledButton("از گوشی اول", colPurple) {
            sendEncryptedToHost(JSONObject().put("type", "CMD_SYNC_CONTACTS").toString())
            Toast.makeText(this, "در حال دریافت مخاطبین گوشی اول...", Toast.LENGTH_SHORT).show()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(dp(3), 0, dp(3), 0)
            }
        })
        topCard.addView(btnRow)
        root.addView(topCard)

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        contactsListContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(contactsListContainer)
        root.addView(scroll)

        refreshContactsUI("")
        return root
    }

    private fun showAddContactDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(6))
        }
        val nameEt = EditText(this).apply { hint = "نام مخاطب" }
        val phoneEt = EditText(this).apply { hint = "شماره تلفن" }
        layout.addView(nameEt)
        layout.addView(phoneEt)
        AlertDialog.Builder(this)
            .setTitle("افزودن مخاطب جدید")
            .setView(layout)
            .setPositiveButton("ذخیره") { _, _ ->
                val n = nameEt.text.toString().trim()
                val p = phoneEt.text.toString().trim()
                if (n.isNotEmpty() && p.isNotEmpty()) {
                    contactsManager.addContact(n, p)
                    refreshContactsUI(contactSearchInput.text.toString())
                    refreshFavoritesUI()
                }
            }
            .setNegativeButton("لغو", null)
            .show()
    }

    private fun refreshContactsUI(query: String) {
        runOnUiThread {
            contactsListContainer.removeAllViews()
            val list = contactsManager.getContacts().filter {
                query.isBlank() || it.name.contains(query, true) || it.phone.contains(query, true)
            }
            if (list.isEmpty()) {
                contactsListContainer.addView(TextView(this).apply {
                    text = "مخاطبی ثبت نشده است."
                    setTextColor(colSubText)
                    gravity = Gravity.CENTER
                    setPadding(dp(16), dp(24), dp(16), dp(24))
                })
                return@runOnUiThread
            }
            for (c in list) {
                val card = cardContainer()
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                val info = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                info.addView(TextView(this).apply {
                    text = c.name
                    textSize = 14f
                    setTextColor(colText)
                    setTypeface(null, Typeface.BOLD)
                })
                info.addView(TextView(this).apply {
                    text = c.phone
                    textSize = 12f
                    setTextColor(colSubText)
                })
                row.addView(info)

                row.addView(smallChip(if (c.isFavorite) "★" else "☆", if (c.isFavorite) colAmber else colCardLight) {
                    contactsManager.toggleFavorite(c.phone)
                    refreshContactsUI(contactSearchInput.text.toString())
                    refreshFavoritesUI()
                })
                row.addView(smallChip("تماس", colGreen) { makeRemoteCall(c.phone) })
                row.addView(smallChip("پیامک", colAccent) {
                    switchTab(2)
                    openChatThread(c.phone)
                })
                card.addView(row)
                contactsListContainer.addView(card)
            }
        }
    }

    private fun refreshSimSelectorUI() {
        val labels = listOf("پیش‌فرض", sim1Label, sim2Label)
        val slots = listOf(-1, 0, 1)
        simButtons.forEachIndexed { idx, tv ->
            tv.text = labels[idx]
            if (selectedSimSlot == slots[idx]) {
                tv.setTextColor(Color.WHITE)
                tv.setTypeface(null, Typeface.BOLD)
                tv.background = roundedBg(colGreen, 10)
            } else {
                tv.setTextColor(colSubText)
                tv.setTypeface(null, Typeface.NORMAL)
                tv.background = roundedBg(colCard, 10)
            }
        }
    }

    private fun buildLogsTab(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(10))
        }
        topRow.addView(styledButton("🗑 پاک کردن گزارشات", colRed) {
            logText.text = "گزارشات پاک شد.\n"
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(dp(4), 0, 0, 0)
            }
        })
        topRow.addView(styledButton("📋 کپی گزارشات", colAccent) {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("SamenProLogs", logText.text.toString()))
            Toast.makeText(this, "گزارشات سیستم کپی شد", Toast.LENGTH_SHORT).show()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(0, 0, dp(4), 0)
            }
        })
        root.addView(topRow)

        val logCard = cardContainer().apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        logText = TextView(this).apply {
            text = "آماده...\n"
            textSize = 11f
            setTextColor(colSubText)
            setLineSpacing(dp(4).toFloat(), 1f)
        }
        scroll.addView(logText)
        logCard.addView(scroll)
        root.addView(logCard)
        return root
    }

    private fun formatPhoneLtr(raw: String): String {
        val resolved = contactsManager.resolveName(raw)
        val isRawNumber = resolved == raw || resolved.matches(Regex("^[+0-9\\s\\-()]+$"))
        return if (isRawNumber) "\u200E$resolved\u200E" else resolved
    }

    private fun getUnreadCount(address: String): Int {
        val norm = contactsManager.normalizePhone(address).ifBlank { address }
        return getSharedPreferences("itell_unread_v4", Context.MODE_PRIVATE).getInt(norm, 0)
    }

    private fun clearUnreadCount(address: String) {
        val norm = contactsManager.normalizePhone(address).ifBlank { address }
        getSharedPreferences("itell_unread_v4", Context.MODE_PRIVATE).edit().remove(norm).apply()
        updateSmsNavBadge()
    }

    private fun getTotalUnreadSmsCount(): Int {
        val all = getSharedPreferences("itell_unread_v4", Context.MODE_PRIVATE).all
        var total = 0
        for (v in all.values) {
            if (v is Int && v > 0) total += v
        }
        return total
    }

    private fun updateSmsNavBadge() {
        runOnUiThread {
            if (navButtons.size > 2) {
                val total = getTotalUnreadSmsCount()
                navButtons[2].text = if (total > 0) "پیامک ($total)" else "پیامک"
            }
        }
    }

    // ==================== ناوبری پایین ====================
    private fun buildBottomNav(): View {
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(colSurface)
            setPadding(dp(4), dp(8), dp(4), dp(10))
        }
        val labels = listOf("داشبورد", "تماس", "پیامک", "مخاطبین", "گزارشات")
        labels.forEachIndexed { idx, label ->
            val tv = TextView(this).apply {
                text = label
                textSize = 11.5f
                gravity = Gravity.CENTER
                setPadding(dp(2), dp(10), dp(2), dp(10))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(dp(4), 0, dp(4), 0)
                }
                setOnClickListener { switchTab(idx) }
            }
            navButtons.add(tv)
            nav.addView(tv)
        }
        return nav
    }

    private fun switchTab(index: Int) {
        activeTab = index
        tabDashboard.visibility = if (index == 0) View.VISIBLE else View.GONE
        tabCalls.visibility = if (index == 1) View.VISIBLE else View.GONE
        tabSms.visibility = if (index == 2) View.VISIBLE else View.GONE
        tabContacts.visibility = if (index == 3) View.VISIBLE else View.GONE
        tabLogs.visibility = if (index == 4) View.VISIBLE else View.GONE

        navButtons.forEachIndexed { i, tv ->
            if (i == index) {
                tv.setTextColor(Color.WHITE)
                tv.setTypeface(null, Typeface.BOLD)
                tv.background = roundedBg(colAccent, 12)
            } else {
                tv.setTextColor(colSubText)
                tv.setTypeface(null, Typeface.NORMAL)
                tv.background = null
            }
        }
    }

    // ==================== شبکه و پردازش رویدادها ====================
    private fun connectToHost(hostIp: String) {
        if (hostIp.isBlank()) return
        userManuallyDisconnected = false
        getSharedPreferences("itell_cfg", Context.MODE_PRIVATE).edit().putString("host_ip", hostIp).apply()
        val svcIntent = Intent(this, ClientBridgeService::class.java).apply {
            action = ClientBridgeService.ACTION_CONNECT
            putExtra(ClientBridgeService.EXTRA_HOST_IP, hostIp)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svcIntent)
        else startService(svcIntent)
        updateBadge("● در حال اتصال...", colAmber)
        appendLog("شروع سرویس اتصال پایدار به $hostIp")
    }

    private fun connectToHostInternal(hostIp: String, isBackgroundRetry: Boolean) {
        if (isConnected) return
        try {
            disconnectClient()
            val socket = Socket()
            socket.connect(java.net.InetSocketAddress(hostIp, 8888), 3500)
            socket.tcpNoDelay = true
            socket.keepAlive = true
            clientSocket = socket
            clientWriter = PrintWriter(socket.getOutputStream(), true)
            isConnected = true
            connectedHostIp = hostIp

            runOnUiThread {
                updateBadge("● متصل", colGreen)
                appendLog("متصل به گوشی میزبان ($hostIp)")
            }

            sendEncryptedToHost(JSONObject().put("type", "CMD_PING").toString())
            sendEncryptedToHost(JSONObject().put("type", "CMD_SYNC_SMS").toString())
            sendEncryptedToHost(JSONObject().put("type", "CMD_SYNC_CALLLOG").toString())

            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val plain = CryptoHelper.decrypt(line!!, aesKey)
                handleHostEvent(JSONObject(plain))
            }
        } catch (e: Exception) {
            if (!isBackgroundRetry) {
                runOnUiThread {
                    updateBadge("● آفلاین", colRed)
                    appendLog("خطای اتصال به $hostIp: ${e.message}")
                }
            }
        } finally {
            val wasConnected = isConnected
            isConnected = false
            runOnUiThread {
                if (!userManuallyDisconnected) {
                    updateBadge("● تلاش مجدد...", colAmber)
                    hostMetaText.text = "اتصال قطع شد • در حال تلاش خودکار برای اتصال مجدد..."
                } else {
                    updateBadge("● آفلاین", colRed)
                    hostMetaText.text = "اتصال به صورت دستی قطع شد"
                }
                if (wasConnected && !userManuallyDisconnected) {
                    showAlertNotification(401, "قطع ارتباط با گوشی اول", "در حال تلاش خودکار برای اتصال مجدد...", "alert_channel", null)
                }
            }
        }
    }

    private fun handleHostEvent(json: JSONObject) {
        when (json.optString("type")) {
            "HOST_STATUS" -> {
                val bat = json.optInt("battery", 0)
                val op = json.optString("operator", "سیم‌کارت")
                val model = json.optString("model", "Host")
                val ip = json.optString("ip", connectedHostIp)
                val simsArr = json.optJSONArray("sims")
                if (simsArr != null && simsArr.length() > 0) {
                    for (i in 0 until simsArr.length()) {
                        val s = simsArr.optJSONObject(i) ?: continue
                        val slot = s.optInt("slot", -1)
                        val carrier = s.optString("carrier", "")
                        if (slot == 0 && carrier.isNotBlank()) sim1Label = "سیم ۱: ${carrier.take(10)}"
                        if (slot == 1 && carrier.isNotBlank()) sim2Label = "سیم ۲: ${carrier.take(10)}"
                    }
                }
                runOnUiThread {
                    updateBadge("● متصل ($bat%)", colGreen)
                    hostBatteryBar.progress = bat.coerceIn(0, 100)
                    hostMetaText.text = "$model • اپراتور: $op • باتری: $bat% • پروکسی: $ip:8080"
                    refreshSimSelectorUI()
                }
            }
            "LOW_BATTERY" -> {
                val bat = json.optInt("battery", 15)
                showAlertNotification(402, "هشدار باتری گوشی اول", "شارژ گوشی میزبان به $bat% رسید!", "alert_channel", null)
            }
            "NEW_SMS" -> {
                val sender = json.optString("sender")
                val body = json.optString("body")
                val ts = json.optLong("timestamp", System.currentTimeMillis())
                allSms.add(0, SmsItem(sender, body, ts, false))
                val normSender = contactsManager.normalizePhone(sender).ifBlank { sender }
                val openNorm = openChatAddress?.let { contactsManager.normalizePhone(it).ifBlank { it } }
                if (activeTab == 2 && openNorm == normSender && smsChatView.visibility == View.VISIBLE) {
                    clearUnreadCount(sender)
                } else {
                    updateSmsNavBadge()
                }
                refreshSmsThreadsUI()
                refreshOpenChatUI()
                val name = contactsManager.resolveName(sender)
                appendLog("پیامک جدید از $name")
            }
            "SMS_LIST" -> {
                val arr = json.optJSONArray("items") ?: return
                allSms.clear()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    allSms.add(
                        SmsItem(
                            o.optString("sender"),
                            o.optString("body"),
                            o.optLong("timestamp", System.currentTimeMillis()),
                            o.optBoolean("outgoing", false)
                        )
                    )
                }
                refreshSmsThreadsUI()
                refreshOpenChatUI()
            }
            "SMS_SENT_OK" -> {
                val to = json.optString("to")
                val body = json.optString("body")
                val ts = json.optLong("timestamp", System.currentTimeMillis())
                allSms.add(0, SmsItem(to, body, ts, true))
                refreshSmsThreadsUI()
                refreshOpenChatUI()
            }
            "CALLLOG_LIST" -> {
                val arr = json.optJSONArray("items") ?: return
                callLogs.clear()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    callLogs.add(
                        CallLogItem(
                            o.optString("number"),
                            o.optString("name"),
                            o.optInt("callType", 1),
                            o.optLong("date", System.currentTimeMillis()),
                            o.optLong("duration", 0)
                        )
                    )
                }
                refreshCallLogUI()
            }
            "CONTACTS_LIST" -> {
                val arr = json.optJSONArray("items") ?: return
                val added = contactsManager.mergeFromHostJson(arr)
                refreshContactsUI(contactSearchInput.text.toString())
                refreshFavoritesUI()
                runOnUiThread {
                    Toast.makeText(this, "$added مخاطب از گوشی اول همگام‌سازی شد", Toast.LENGTH_SHORT).show()
                }
            }
            "INCOMING_CALL" -> {
                val number = json.optString("number")
                val name = contactsManager.resolveName(number)
                runOnUiThread { applyLockScreenFlags(); showInCallScreen(number, isIncoming = true) }
                appendLog("تماس ورودی: $name ($number)")
            }
            "CALL_ACTIVE" -> {
                runOnUiThread { startCallTimer() }
            }
            "CALL_ENDED" -> {
                runOnUiThread { hideInCallScreen() }
                sendEncryptedToHost(JSONObject().put("type", "CMD_SYNC_CALLLOG").toString())
            }
            "USSD_RESULT" -> {
                val code = json.optString("code")
                val response = json.optString("response")
                runOnUiThread {
                    AlertDialog.Builder(this)
                        .setTitle("پاسخ کد دستوری $code")
                        .setMessage(response)
                        .setPositiveButton("باشه", null)
                        .show()
                }
            }
            "SYSTEM_MSG" -> {
                val msg = json.optString("message")
                appendLog(msg)
                runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
            }
        }
    }

    private fun makeRemoteCall(number: String) {
        if (!isConnected) {
            Toast.makeText(this, "ابتدا به گوشی میزبان متصل شوید", Toast.LENGTH_SHORT).show()
            return
        }
        sendEncryptedToHost(JSONObject().put("type", "CMD_DIAL").put("number", number).put("simSlot", selectedSimSlot).toString())
        if (!(number.startsWith("*") && number.endsWith("#"))) {
            showInCallScreen(number, isIncoming = false)
            if (!bluetoothHeadsetMode) {
                sendEncryptedToHost(JSONObject().put("type", "CMD_START_AUDIO").toString())
                startLocalAudioBridge()
            }
        } else {
            Toast.makeText(this, "در حال اجرای کد دستوری $number ...", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startLocalAudioBridge() {
        if (connectedHostIp.isBlank()) return
        callAudioRouter?.stopCallBridge()
        callAudioRouter = CallAudioRouter(this, connectedHostIp, isHostMode = false).also { it.startCallBridge() }
    }

    private fun stopLocalAudioBridge() {
        callAudioRouter?.stopCallBridge()
        callAudioRouter = null
    }

    private fun disconnectClient() {
        val svcIntent = Intent(this, ClientBridgeService::class.java).apply {
            action = ClientBridgeService.ACTION_DISCONNECT
        }
        try { startService(svcIntent) } catch (_: Exception) {}
        try { clientSocket?.close() } catch (_: Exception) {}
        clientSocket = null
        clientWriter = null
        isConnected = false
    }

    private fun sendEncryptedToHost(plainJson: String) {
        ClientBridgeService.sendEncrypted(plainJson)
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel("call_channel", "تماس ورودی", NotificationManager.IMPORTANCE_HIGH))
            nm.createNotificationChannel(NotificationChannel("sms_channel", "پیامک جدید", NotificationManager.IMPORTANCE_HIGH))
            nm.createNotificationChannel(NotificationChannel("alert_channel", "هشدارهای سیستم", NotificationManager.IMPORTANCE_HIGH))
        }
    }

    private fun showAlertNotification(id: Int, title: String, content: String, channelId: String, otpCode: String?) {
        val builder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)

        if (!otpCode.isNullOrBlank()) {
            val copyIntent = Intent(this, OtpCopyReceiver::class.java).putExtra("OTP_CODE", otpCode)
            val piFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pi = PendingIntent.getBroadcast(this, id + 1000, copyIntent, piFlags)
            builder.addAction(android.R.drawable.ic_menu_edit, "کپی کد ($otpCode)", pi)
        }

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(id, builder.build())
    }

    private fun updateBadge(text: String, color: Int) {
        statusBadge.text = text
        statusBadge.background = roundedBg(color, 20)
    }

    private fun appendLog(msg: String) {
        val t = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        runOnUiThread {
            if (::logText.isInitialized) {
                if (logText.lineCount > 250) {
                    logText.text = "— گزارشات قدیمی‌تر پاک شد —\n"
                }
                logText.append("[$t] $msg\n")
            }
        }
    }

    // ==================== ابزارک‌های طراحی ====================
    private fun cardContainer(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = roundedBg(colCard, 16)
        setPadding(dp(14), dp(14), dp(14), dp(14))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 0, 0, dp(10)) }
    }

    private fun sectionTitle(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 14f
        setTextColor(colText)
        setTypeface(null, Typeface.BOLD)
    }

    private fun styledInput(hintText: String): EditText = EditText(this).apply {
        hint = hintText
        setHintTextColor(colSubText)
        setTextColor(colText)
        textSize = 14f
        background = roundedBg(colSurface, 10)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, dp(6), 0, 0) }
    }

    private fun styledButton(label: String, bgColor: Int, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = 13f
        setTextColor(Color.WHITE)
        setTypeface(null, Typeface.BOLD)
        gravity = Gravity.CENTER
        setPadding(dp(12), dp(11), dp(12), dp(11))
        background = roundedBg(bgColor, 12)
        setOnClickListener { onClick() }
    }

    private fun smallChip(label: String, bgColor: Int, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = 11f
        setTextColor(Color.WHITE)
        setTypeface(null, Typeface.BOLD)
        setPadding(dp(10), dp(6), dp(10), dp(6))
        background = roundedBg(bgColor, 10)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(dp(4), 0, 0, 0) }
        setOnClickListener { onClick() }
    }

    private fun circleActionBtn(label: String, bgColor: Int, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = 12f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        background = roundedBg(bgColor, 40)
        layoutParams = LinearLayout.LayoutParams(dp(84), dp(84)).apply {
            setMargins(dp(10), 0, dp(10), 0)
        }
        setOnClickListener { onClick() }
    }

    private fun roundedBg(color: Int, radiusDp: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun requestAllPermissions() {
        val perms = mutableListOf(
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.RECORD_AUDIO
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            perms.add(Manifest.permission.ANSWER_PHONE_CALLS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val needed = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), 300)
        }
    }
}
