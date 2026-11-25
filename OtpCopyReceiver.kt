package com.bridge.itell

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast

class OtpCopyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val code = intent.getStringExtra("OTP_CODE") ?: return
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("OTP", code))
        Toast.makeText(context, "کد $code کپی شد", Toast.LENGTH_SHORT).show()
    }

    companion object {
        fun extractOtp(text: String): String? {
            val normalized = text
                .replace('۰', '0').replace('۱', '1').replace('۲', '2')
                .replace('۳', '3').replace('۴', '4').replace('۵', '5')
                .replace('۶', '6').replace('۷', '7').replace('۸', '8')
                .replace('۹', '9')
            val keywords = listOf("کد", "رمز", "تایید", "تأیید", "پویا", "code", "otp", "pass", "pin")
            val hasKeyword = keywords.any { normalized.contains(it, ignoreCase = true) }
            if (!hasKeyword) return null
            val match = Regex("\\b\\d{4,8}\\b").find(normalized)
            return match?.value
        }
    }
}
