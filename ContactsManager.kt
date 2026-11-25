package com.bridge.itell

import android.content.Context
import android.provider.ContactsContract
import org.json.JSONArray
import org.json.JSONObject

data class ContactItem(val name: String, val phone: String, val isFavorite: Boolean = false)

class ContactsManager(private val context: Context) {
    private val prefs = context.getSharedPreferences("itell_contacts_v3", Context.MODE_PRIVATE)
    private val keyContacts = "contacts_json"
    private val keyPinned = "pinned_threads_json"

    fun getContacts(): MutableList<ContactItem> {
        val list = mutableListOf<ContactItem>()
        val raw = prefs.getString(keyContacts, "[]") ?: "[]"
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    ContactItem(
                        name = obj.optString("name"),
                        phone = obj.optString("phone"),
                        isFavorite = obj.optBoolean("fav", false)
                    )
                )
            }
        } catch (_: Exception) {
        }
        return list
    }

    fun saveContacts(list: List<ContactItem>) {
        val arr = JSONArray()
        for (c in list) {
            val obj = JSONObject()
            obj.put("name", c.name)
            obj.put("phone", c.phone)
            obj.put("fav", c.isFavorite)
            arr.put(obj)
        }
        prefs.edit().putString(keyContacts, arr.toString()).apply()
    }

    fun addContact(name: String, phone: String, isFav: Boolean = false) {
        val list = getContacts()
        val clean = normalizePhone(phone)
        val existingFav = list.firstOrNull { normalizePhone(it.phone) == clean }?.isFavorite ?: isFav
        list.removeAll { normalizePhone(it.phone) == clean }
        list.add(0, ContactItem(name.trim(), phone.trim(), isFav || existingFav))
        saveContacts(list)
    }

    fun toggleFavorite(phone: String) {
        val list = getContacts()
        val clean = normalizePhone(phone)
        val updated = list.map {
            if (normalizePhone(it.phone) == clean) it.copy(isFavorite = !it.isFavorite) else it
        }
        saveContacts(updated)
    }

    fun deleteContact(phone: String) {
        val list = getContacts()
        val clean = normalizePhone(phone)
        list.removeAll { normalizePhone(it.phone) == clean }
        saveContacts(list)
    }

    fun mergeFromHostJson(arr: JSONArray): Int {
        val existing = getContacts()
        val map = existing.associateBy { normalizePhone(it.phone) }.toMutableMap()
        var added = 0
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val name = obj.optString("name").trim()
            val phone = obj.optString("phone").trim()
            val norm = normalizePhone(phone)
            if (name.isNotBlank() && norm.length >= 4 && !map.containsKey(norm)) {
                val item = ContactItem(name, phone, false)
                existing.add(item)
                map[norm] = item
                added++
            }
        }
        saveContacts(existing)
        return added
    }

    fun resolveName(phone: String): String {
        val clean = normalizePhone(phone)
        if (clean.length < 4) return phone
        val tail = clean.takeLast(10)
        val match = getContacts().firstOrNull {
            val c = normalizePhone(it.phone)
            c.isNotEmpty() && (c == clean || c.endsWith(tail) || clean.endsWith(c.takeLast(10)))
        }
        return match?.name ?: phone
    }

    fun importFromDevice(): Int {
        val existing = getContacts()
        val existingNumbers = existing.map { normalizePhone(it.phone) }.toMutableSet()
        var added = 0
        try {
            val cursor = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                null, null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
            )
            cursor?.use {
                val nameIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (it.moveToNext()) {
                    val name = if (nameIdx >= 0) it.getString(nameIdx) ?: "" else ""
                    val num = if (numIdx >= 0) it.getString(numIdx) ?: "" else ""
                    val norm = normalizePhone(num)
                    if (name.isNotBlank() && norm.length >= 4 && !existingNumbers.contains(norm)) {
                        existing.add(ContactItem(name.trim(), num.trim(), false))
                        existingNumbers.add(norm)
                        added++
                    }
                }
            }
            saveContacts(existing)
        } catch (_: Exception) {
        }
        return added
    }

    fun getPinnedThreads(): MutableList<String> {
        val result = mutableListOf<String>()
        val raw = prefs.getString(keyPinned, "[]") ?: "[]"
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) result.add(arr.getString(i))
        } catch (_: Exception) {
        }
        return result
    }

    fun togglePinThread(address: String): Boolean {
        val norm = normalizePhone(address).ifBlank { address }
        val pinned = getPinnedThreads()
        val isNowPinned: Boolean
        if (pinned.contains(norm)) {
            pinned.remove(norm)
            isNowPinned = false
        } else {
            if (pinned.size >= 5) pinned.removeAt(pinned.size - 1)
            pinned.add(0, norm)
            isNowPinned = true
        }
        val arr = JSONArray()
        pinned.forEach { arr.put(it) }
        prefs.edit().putString(keyPinned, arr.toString()).apply()
        return isNowPinned
    }

    fun isThreadPinned(address: String): Boolean {
        val norm = normalizePhone(address).ifBlank { address }
        return getPinnedThreads().contains(norm)
    }

    fun normalizePhone(phone: String): String {
        return phone.replace(Regex("[^0-9+]"), "")
    }
}
