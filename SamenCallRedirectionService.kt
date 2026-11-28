package com.bridge.itell

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telecom.CallRedirectionService
import android.telecom.PhoneAccountHandle
import androidx.annotation.RequiresApi
import org.json.JSONObject

@RequiresApi(Build.VERSION_CODES.Q)
class SamenCallRedirectionService : CallRedirectionService() {

    override fun onPlaceCall(
        handle: Uri,
        initialPhoneAccount: PhoneAccountHandle,
        allowInteractiveResponse: Boolean
    ) {
        try {
            val number = handle.schemeSpecificPart?.trim() ?: ""
            val prefs = getSharedPreferences("itell_cfg", Context.MODE_PRIVATE)
            val redirectEnabled = prefs.getBoolean("call_redirect_enabled", true)

            if (!redirectEnabled || !ClientBridgeService.isConnected || number.isBlank()) {
                placeCallUnmodified()
                return
            }

            val cleanNum = number.replace(Regex("[^0-9+*#]"), "")
            if (cleanNum in setOf("110", "115", "125", "112", "911")) {
                placeCallUnmodified()
                return
            }

            // ابتدا تماس روی سیم‌کارت گوشی دوم را به شکل ایمن لغو کن
            cancelCall()

            // ارسال دستور تماس به گوشی اول در ترد پس‌زمینه
            val simSlot = prefs.getInt("selected_sim_slot", -1)
            val cmd = JSONObject()
                .put("type", "CMD_DIAL")
                .put("number", number)
                .put("simSlot", simSlot)
                .toString()
            ClientBridgeService.sendEncrypted(cmd)

            // باز کردن صفحه مکالمه با تاخیر کوتاه پس از اتمام تراکنش Telecom
            if (!(number.startsWith("*") && number.endsWith("#"))) {
                Handler(Looper.getMainLooper()).postDelayed({
                    try {
                        val launch = Intent(applicationContext, MainActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                            putExtra("EXTRA_DIRECT_DIAL_NUMBER", number)
                        }
                        applicationContext.startActivity(launch)
                    } catch (_: Exception) {
                    }
                }, 250L)
            }
        } catch (t: Throwable) {
            try {
                val msg = "CallRedirect: ${t.javaClass.simpleName}: ${t.message}"
                getSharedPreferences("itell_cfg", Context.MODE_PRIVATE).edit()
                    .putString("last_crash", msg).apply()
            } catch (_: Exception) {
            }
            try {
                placeCallUnmodified()
            } catch (_: Exception) {
            }
        }
    }
}
