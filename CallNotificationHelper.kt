package com.bridge.itell

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaSessionManager
import android.os.Build
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.view.KeyEvent

class CallNotificationHelper : NotificationListenerService() {

    companion object {
        @Volatile
        private var instance: CallNotificationHelper? = null

        fun isConnected(): Boolean = instance != null

        fun answerCall(context: Context): Boolean {
            val svc = instance ?: return false
            // روش ۱: ارسال کلید هندزفری مستقیم به سرویس Telecom (بسیار مطمئن در اندروید ۵ تا ۸)
            if (dispatchHeadsetHookToTelecom(context, svc)) {
                return true
            }
            // روش ۲: کلیک روی دکمه پاسخ در نوتیفیکیشن تماس ورودی
            return clickCallNotificationAction(
                svc,
                listOf("answer", "accept", "pick", "پاسخ", "قبول", "تایید")
            )
        }

        fun endCall(context: Context): Boolean {
            val svc = instance ?: return false
            // روش ۱: کلیک روی دکمه قطع یا رد تماس در نوتیفیکیشن تماس
            if (clickCallNotificationAction(
                    svc,
                    listOf("end", "decline", "reject", "hang", "dismiss", "قطع", "رد", "پایان")
                )
            ) {
                return true
            }
            // روش ۲: ارسال کلید هندزفری به Telecom
            return dispatchHeadsetHookToTelecom(context, svc)
        }

        private fun dispatchHeadsetHookToTelecom(context: Context, svc: CallNotificationHelper): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return false
            return try {
                val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
                val comp = ComponentName(context, CallNotificationHelper::class.java)
                val controllers = msm.getActiveSessions(comp)
                for (controller in controllers) {
                    val pkg = controller.packageName ?: ""
                    if (pkg.contains("telecom") || pkg.contains("phone") || pkg.contains("incall") || pkg.contains("dialer")) {
                        val now = SystemClock.uptimeMillis()
                        controller.dispatchMediaButtonEvent(
                            KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HEADSETHOOK, 0)
                        )
                        controller.dispatchMediaButtonEvent(
                            KeyEvent(now, now + 60, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_HEADSETHOOK, 0)
                        )
                        return true
                    }
                }
                false
            } catch (_: Exception) {
                false
            }
        }

        private fun clickCallNotificationAction(svc: CallNotificationHelper, keywords: List<String>): Boolean {
            return try {
                val notifs = svc.activeNotifications ?: return false
                for (sbn in notifs) {
                    val pkg = sbn.packageName?.lowercase() ?: ""
                    if (pkg.contains("incall") || pkg.contains("phone") || pkg.contains("dialer") || pkg.contains("telecom")) {
                        val actions = sbn.notification?.actions ?: continue
                        for (act in actions) {
                            val title = act.title?.toString()?.lowercase() ?: ""
                            if (keywords.any { title.contains(it) }) {
                                act.actionIntent?.send()
                                return true
                            }
                        }
                    }
                }
                false
            } catch (_: Exception) {
                false
            }
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        instance = null
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }
}
