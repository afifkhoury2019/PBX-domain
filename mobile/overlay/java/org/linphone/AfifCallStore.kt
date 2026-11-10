/*
 * AFIF Phone — local call history for the direct domain transport.
 * GPLv3, part of AFIF Phone (based on Linphone).
 */
package org.linphone

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class AfifCall(
    val id: String,
    val address: String,
    val name: String,
    val time: Long,
    val outgoing: Boolean,
    val answered: Boolean,
    val duration: Int
)

object AfifCallStore {
    private const val PREFS = "afif_phone"
    private const val KEY = "domain_calls"
    private const val MAX = 400

    fun add(context: Context, address: String, name: String, outgoing: Boolean, time: Long): String {
        val id = "$time-${(1000..9999).random()}"
        val list = all(context).toMutableList()
        list.add(AfifCall(id, address, name, time, outgoing, false, 0))
        while (list.size > MAX) list.removeAt(0)
        save(context, list)
        return id
    }

    fun markAnswered(context: Context, id: String) {
        val list = all(context).toMutableList()
        val i = list.indexOfFirst { it.id == id }
        if (i >= 0) list[i] = list[i].copy(answered = true)
        save(context, list)
    }

    fun markEnded(context: Context, id: String, duration: Int) {
        val list = all(context).toMutableList()
        val i = list.indexOfFirst { it.id == id }
        if (i >= 0) list[i] = list[i].copy(duration = duration)
        save(context, list)
    }

    fun all(context: Context): List<AfifCall> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "[]").orEmpty()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                AfifCall(
                    o.optString("id"),
                    o.optString("a"),
                    o.optString("n"),
                    o.optLong("t"),
                    o.optBoolean("o"),
                    o.optBoolean("ans"),
                    o.optInt("d")
                )
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun save(context: Context, list: List<AfifCall>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject()
                    .put("id", it.id)
                    .put("a", it.address)
                    .put("n", it.name)
                    .put("t", it.time)
                    .put("o", it.outgoing)
                    .put("ans", it.answered)
                    .put("d", it.duration)
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, arr.toString()).apply()
    }
}
