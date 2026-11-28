package com.bridge.itell

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class UssdAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        private var instance: UssdAccessibilityService? = null

        @Volatile
        private var lastTextSent: String = ""

        @Volatile
        private var lastSentTimeMs: Long = 0L

        fun isConnected(): Boolean = instance != null

        fun replyToUssd(replyText: String): Boolean {
            val svc = instance ?: return false
            return svc.performUssdReply(replyText)
        }

        fun cancelUssd(): Boolean {
            val svc = instance ?: return false
            return svc.performUssdDismiss()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString()?.lowercase() ?: ""
        val cls = event.className?.toString()?.lowercase() ?: ""

        val isPhonePkg = pkg.contains("phone") || pkg.contains("telecom") || pkg.contains("incall")
        val isDialog = cls.contains("alertdialog") || cls.contains("dialog") || cls.contains("ussd") || cls.contains("progress")
        if (!isPhonePkg && !isDialog) return

        val root = rootInActiveWindow ?: event.source ?: return
        val texts = mutableListOf<String>()
        var hasEditText = false
        var hasSendOrOkBtn = false

        fun traverse(node: AccessibilityNodeInfo?) {
            if (node == null) return
            val nodeCls = node.className?.toString()?.lowercase() ?: ""
            if (nodeCls.contains("edittext")) {
                hasEditText = true
            }
            if (nodeCls.contains("button")) {
                val btnTxt = node.text?.toString()?.trim()?.lowercase() ?: ""
                if (btnTxt in setOf("ok", "send", "cancel", "dismiss", "تایید", "تأیید", "ارسال", "لغو", "بستن", "باشه")) {
                    hasSendOrOkBtn = true
                }
            } else {
                val t = node.text?.toString()?.trim()
                if (!t.isNullOrBlank() && t.length > 1) {
                    if (!texts.contains(t)) texts.add(t)
                }
            }
            for (i in 0 until node.childCount) {
                traverse(node.getChild(i))
            }
        }

        try {
            traverse(root)
        } catch (_: Exception) {
            return
        }

        if (!hasSendOrOkBtn && !hasEditText) return
        val combined = texts.joinToString("\n").trim()
        if (combined.isBlank()) return
        val lower = combined.lowercase()
        if (lower.contains("ussd code running") || lower.contains("در حال اجرا") || lower.contains("please wait")) {
            return
        }

        val now = System.currentTimeMillis()
        if (combined == lastTextSent && (now - lastSentTimeMs) < 3500L) return
        lastTextSent = combined
        lastSentTimeMs = now

        HostBridgeService.sendUssdResponseToClients(combined, hasEditText)
    }

    private fun performUssdReply(replyText: String): Boolean {
        val root = rootInActiveWindow ?: return false
        var editNode: AccessibilityNodeInfo? = null
        var sendNode: AccessibilityNodeInfo? = null

        fun findNodes(node: AccessibilityNodeInfo?) {
            if (node == null) return
            val cls = node.className?.toString()?.lowercase() ?: ""
            if (cls.contains("edittext") && editNode == null) {
                editNode = node
            }
            if (cls.contains("button")) {
                val txt = node.text?.toString()?.trim()?.lowercase() ?: ""
                if (txt.contains("send") || txt.contains("ok") || txt.contains("ارسال") || txt.contains("تایید") || txt.contains("تأیید")) {
                    sendNode = node
                }
            }
            for (i in 0 until node.childCount) {
                findNodes(node.getChild(i))
            }
        }

        return try {
            findNodes(root)
            val ed = editNode ?: return false
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, replyText)
            }
            ed.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            sendNode?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun performUssdDismiss(): Boolean {
        val root = rootInActiveWindow ?: return false
        var dismissNode: AccessibilityNodeInfo? = null

        fun findDismiss(node: AccessibilityNodeInfo?) {
            if (node == null) return
            val cls = node.className?.toString()?.lowercase() ?: ""
            if (cls.contains("button")) {
                val txt = node.text?.toString()?.trim()?.lowercase() ?: ""
                if (txt.contains("cancel") || txt.contains("ok") || txt.contains("dismiss") || txt.contains("لغو") || txt.contains("تایید") || txt.contains("تأیید") || txt.contains("بستن") || txt.contains("باشه")) {
                    dismissNode = node
                }
            }
            for (i in 0 until node.childCount) {
                findDismiss(node.getChild(i))
            }
        }

        return try {
            findDismiss(root)
            dismissNode?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
        } catch (_: Exception) {
            false
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }
}
