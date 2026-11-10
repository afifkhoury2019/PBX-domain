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
    val id: String,
    val address: String,
    val text: String,
    val time: Long,
    val outgoing: Boolean,
    val status: String
)

object AfifSmsStore {
    private const val PREFS = "afif_phone"
    private const val KEY = "domain_sms"
    private const val MAX = 500

    fun add(
        context: Context,
        address: String,
        text: String,
        time: Long,
        outgoing: Boolean,
        status: String = if (outgoing) "sending" else "received"
    ): String {
        val id = "$time-${(1000..9999).random()}"
        val list = all(context).toMutableList()
        list.add(AfifSms(id, address, text, time, outgoing, status))
        while (list.size > MAX) list.removeAt(0)
        save(context, list)
        return id
    }

    fun updateStatus(context: Context, id: String, status: String) {
        val list = all(context).toMutableList()
        val i = list.indexOfFirst { it.id == id }
        if (i >= 0) list[i] = list[i].copy(status = status)
        save(context, list)
    }

    fun all(context: Context): List<AfifSms> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "[]").orEmpty()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                AfifSms(
                    o.optString("id"),
                    o.optString("a"),
                    o.optString("t"),
                    o.optLong("m"),
                    o.optBoolean("o"),
                    o.optString("s", "received")
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

    private fun save(context: Context, list: List<AfifSms>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject()
                    .put("id", it.id)
                    .put("a", it.address)
                    .put("t", it.text)
                    .put("m", it.time)
                    .put("o", it.outgoing)
                    .put("s", it.status)
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, arr.toString()).apply()
    }
}
