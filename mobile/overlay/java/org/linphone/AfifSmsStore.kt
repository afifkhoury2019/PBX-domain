/*
 * AFIF Phone — tiny local SMS store for the direct domain transport.
 * GPLv3, part of AFIF Phone (based on Linphone).
 */
package org.linphone

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class AfifSms(
    val address: String,
    val text: String,
    val time: Long,
    val outgoing: Boolean
)

object AfifSmsStore {
    private const val PREFS = "afif_phone"
    private const val KEY = "domain_sms"
    private const val MAX = 500

    fun add(context: Context, address: String, text: String, time: Long, outgoing: Boolean) {
        val list = all(context).toMutableList()
        list.add(AfifSms(address, text, time, outgoing))
        while (list.size > MAX) list.removeAt(0)
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject()
                    .put("a", it.address)
                    .put("t", it.text)
                    .put("m", it.time)
                    .put("o", it.outgoing)
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, arr.toString()).apply()
    }

    fun all(context: Context): List<AfifSms> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "[]").orEmpty()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                AfifSms(
                    o.optString("a"),
                    o.optString("t"),
                    o.optLong("m"),
                    o.optBoolean("o")
                )
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun thread(context: Context, address: String): List<AfifSms> =
        all(context).filter { it.address.equals(address, ignoreCase = true) }

    fun conversations(context: Context): List<Pair<String, AfifSms>> =
        all(context)
            .groupBy { it.address }
            .mapNotNull { (address, list) -> list.maxByOrNull { it.time }?.let { address to it } }
            .sortedByDescending { it.second.time }

    fun fmt(time: Long): String =
        SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()).format(Date(time))
}
