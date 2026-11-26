package com.bridge.itell

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast

class OtpCopyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val code = intent.getStringExtra("OTP_CODE") ?: return
        copyToClipboard(context, code, showToast = true)
    }

    companion object {
        fun copyToClipboard(context: Context, code: String, showToast: Boolean = true) {
            Handler(Looper.getMainLooper()).post {
                try {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("OTP", code))
                    if (showToast) {
                        Toast.makeText(context.applicationContext, "کد $code به‌صورت خودکار کپی شد", Toast.LENGTH_SHORT).show()
                    }
                } catch (_: Exception) {
                }
            }
        }

        fun extractOtp(text: String): String? {
            val normalized = text
                .replace('۰', '0').replace('۱', '1').replace('۲', '2')
                .replace('۳', '3').replace('۴', '4').replace('۵', '5')
                .replace('۶', '6').replace('۷', '7').replace('۸', '8')
                .replace('۹', '9')
                .replace('٠', '0').replace('١', '1').replace('٢', '2')
                .replace('٣', '3').replace('٤', '4').replace('٥', '5')
                .replace('٦', '6').replace('٧', '7').replace('٨', '8')
                .replace('٩', '9')

            val keywords = listOf(
                "کد", "رمز", "تایید", "تأیید", "پویا", "ورود", "فعال", "اعتبارسنجی",
                "یکبارمصرف", "یکبار مصرف", "دوم", "code", "otp", "pass", "pin", "verify", "login", "token"
            )
            val hasKeyword = keywords.any { normalized.contains(it, ignoreCase = true) }
            if (!hasKeyword) return null

            val match = Regex("(?<!\\d)\\d{4,8}(?!\\d)").find(normalized)
            return match?.value
        }
    }
}
