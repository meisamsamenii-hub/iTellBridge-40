package com.bridge.itell

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import org.json.JSONObject

class CallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        @Suppress("DEPRECATION")
        val incomingNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)

        when (state) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                val num = if (!incomingNumber.isNullOrBlank()) incomingNumber else "شماره ناشناس"
                val payload = JSONObject().apply {
                    put("type", "INCOMING_CALL")
                    put("number", num)
                    put("timestamp", System.currentTimeMillis())
                }
                HostBridgeService.forwardEvent(payload.toString())
            }
            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                val payload = JSONObject().apply {
                    put("type", "CALL_ACTIVE")
                    put("number", incomingNumber ?: "")
                }
                HostBridgeService.forwardEvent(payload.toString())
            }
            TelephonyManager.EXTRA_STATE_IDLE -> {
                val payload = JSONObject().apply {
                    put("type", "CALL_ENDED")
                }
                HostBridgeService.forwardEvent(payload.toString())
            }
        }
    }
}
