package com.bridge.itell

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import kotlin.concurrent.thread

class LicenseManager(private val context: Context) {

    companion object {
        const val LICENSE_JSON_URL = "https://raw.githubusercontent.com/meisamsamenii-hub/iTellBridge-40/main/licenses.json"
        private const val SECRET_SALT = "ITB_PRO_SAMENI_2026_SECRET_KEY_9941"
    }

    private val prefs = context.getSharedPreferences("itell_license_v4", Context.MODE_PRIVATE)

    @SuppressLint("HardwareIds")
    fun getDeviceId(): String {
        val rawId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "UNKNOWN"
        val fingerprint = "$rawId|${Build.BRAND}|${Build.MODEL}"
        val hash = sha256Hex(fingerprint).uppercase(Locale.US)
        return "ITB-${hash.substring(0, 4)}-${hash.substring(4, 8)}"
    }

    fun getDeviceModel(): String = "${Build.MANUFACTURER} ${Build.MODEL}"

    fun isActivated(): Boolean {
        val savedId = prefs.getString("activated_device_id", "") ?: ""
        val savedSig = prefs.getString("activated_sig", "") ?: ""
        val myId = getDeviceId()
        return savedId == myId && savedSig == computeOfflineKey(myId)
    }

    fun computeOfflineKey(deviceId: String): String {
        val hash = sha256Hex("${deviceId.trim().uppercase(Locale.US)}|$SECRET_SALT").uppercase(Locale.US)
        return "${hash.substring(0, 5)}-${hash.substring(5, 10)}"
    }

    fun activateWithOfflineKey(inputKey: String): Boolean {
        val myId = getDeviceId()
        val expected = computeOfflineKey(myId)
        val cleanInput = inputKey.trim().uppercase(Locale.US)
        if (cleanInput == expected) {
            saveActivation(myId, expected)
            return true
        }
        return false
    }

    private fun saveActivation(deviceId: String, sig: String) {
        prefs.edit()
            .putString("activated_device_id", deviceId)
            .putString("activated_sig", sig)
            .apply()
    }

    fun revokeLocalActivation() {
        prefs.edit().clear().apply()
    }

    fun checkOnlineApproval(onResult: (approved: Boolean, message: String) -> Unit) = thread {
        val myId = getDeviceId()
        try {
            val url = URL("$LICENSE_JSON_URL?t=${System.currentTimeMillis()}")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                requestMethod = "GET"
                setRequestProperty("Cache-Control", "no-cache")
            }
            val code = conn.responseCode
            if (code != 200) {
                onResult(false, "خطا در ارتباط با سرور تایید (کد $code). اتصال اینترنت را بررسی کنید.")
                return@thread
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()

            val json = JSONObject(body)
            val revokedArr = json.optJSONArray("revoked")
            if (revokedArr != null) {
                for (i in 0 until revokedArr.length()) {
                    if (revokedArr.optString(i).equals(myId, ignoreCase = true)) {
                        revokeLocalActivation()
                        onResult(false, "دسترسی این دستگاه توسط مدیر غیرفعال شده است.")
                        return@thread
                    }
                }
            }

            val approvedArr = json.optJSONArray("approved")
            var isApproved = false
            if (approvedArr != null) {
                for (i in 0 until approvedArr.length()) {
                    if (approvedArr.optString(i).equals(myId, ignoreCase = true)) {
                        isApproved = true
                        break
                    }
                }
            }

            if (isApproved) {
                saveActivation(myId, computeOfflineKey(myId))
                onResult(true, "دستگاه شما با موفقیت تایید و فعال شد!")
            } else {
                onResult(false, "شناسه دستگاه شما ($myId) هنوز توسط مدیر تایید نشده است.")
            }
        } catch (e: Exception) {
            onResult(false, "عدم دسترسی به اینترنت یا سرور تایید. می‌توانید از کد فعال‌سازی آفلاین استفاده کنید.")
        }
    }

    private fun sha256Hex(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append(String.format(Locale.US, "%02x", b))
        }
        return sb.toString()
    }
}
