package com.bridge.itell

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != "android.intent.action.QUICKBOOT_POWERON") {
            return
        }
        val prefs = context.getSharedPreferences("itell_cfg", Context.MODE_PRIVATE)
        val wasHost = prefs.getBoolean("was_host_running", false)
        val savedIp = prefs.getString("host_ip", "") ?: ""
        val savedBt = prefs.getString("bt_mac", "") ?: ""

        try {
            if (wasHost) {
                val hostIntent = Intent(context, HostBridgeService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(hostIntent)
                } else {
                    context.startService(hostIntent)
                }
            } else if (savedIp.isNotBlank() || savedBt.isNotBlank()) {
                val clientIntent = Intent(context, ClientBridgeService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(clientIntent)
                } else {
                    context.startService(clientIntent)
                }
            }
        } catch (_: Exception) {
        }
    }
}
