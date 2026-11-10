package org.linphone

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.constraintlayout.widget.ConstraintLayout
import java.net.HttpURLConnection
import java.net.URL

/** Voice-effect selector. The PBX applies the selected effect to the SIP uplink. */
object AfifVoiceEffect {
    private const val PREFS = "afif_phone"
    private const val KEY = "voice_effect_v1"
    private const val CHIP_ID = 0x7F0F0002

    val effects = listOf("Off", "Robot", "Deep", "Chipmunk", "Echo", "Baby", "Woman", "Old man")
    private val keys = listOf("natural", "robot", "deep", "chipmunk", "echo", "baby", "woman", "oldman")

    fun current(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY, 0)
            .coerceIn(0, effects.size - 1)

    fun label(context: Context): String = effects[current(context)]

    fun setIndex(activity: Activity, index: Int) {
        val safe = index.coerceIn(0, effects.size - 1)
        activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY, safe).apply()
        send(activity, keys[safe])
    }

    fun install(activity: Activity, root: android.view.View) {
        if (root !is ViewGroup) return
        if (root.findViewById<TextView>(CHIP_ID) != null) return
        val chip = TextView(activity).apply {
            id = CHIP_ID
            text = "Voice: ${effects[current(activity)]}"
            textSize = 12f
            setTextColor(Color.WHITE)
            setPadding(28, 12, 28, 12)
            background = GradientDrawable().apply {
                cornerRadius = 40f
                setColor(Color.argb(170, 4, 14, 28))
                setStroke(2, Color.argb(120, 255, 255, 255))
            }
            setOnClickListener { choose(activity, this) }
        }
        if (root is ConstraintLayout) {
            val lp = ConstraintLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.topToTop = ConstraintLayout.LayoutParams.PARENT_ID
            lp.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
            lp.topMargin = 84
            lp.marginEnd = 24
            root.addView(chip, lp)
        } else {
            root.addView(chip, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
    }

    private fun choose(activity: Activity, chip: TextView) {
        val checked = current(activity)
        AlertDialog.Builder(activity)
            .setTitle("Voice effect")
            .setSingleChoiceItems(effects.toTypedArray(), checked) { dialog, which ->
                activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putInt(KEY, which).apply()
                chip.text = "Voice: ${effects[which]}"
                send(activity, keys[which])
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .setMessage("The PBX applies this effect to your SIP audio on the next call.")
            .show()
    }

    private fun send(activity: Activity, effect: String) {
        val primary = AfifSecureNetwork.pbxDomain(activity).substringBefore(":")
        val extension = try {
            LinphoneApplication.coreContext.core.defaultAccount?.params?.identityAddress?.username ?: "100"
        } catch (_: Throwable) {
            "100"
        }
        // Tailscale MagicDNS may be disabled. Try the current PBX domain, the
        // tailnet name, the known tailnet address and finally the LAN address.
        val hosts = linkedSetOf(primary, "afif-pbx", "100.81.150.69", "192.168.0.224", "pbx.powerchain.uk")
            .filter { it.isNotBlank() }
        Thread {
            var lastError = "no route"
            for (host in hosts) {
                var connection: HttpURLConnection? = null
                try {
                    val secure = host.contains("powerchain.uk")
                    val query = "extension=" + java.net.URLEncoder.encode(extension, "UTF-8") +
                        "&effect=" + java.net.URLEncoder.encode(effect, "UTF-8")
                    val url = if (secure) "https://$host/client-voice?$query"
                        else "http://$host:8090/client-voice?$query"
                    connection = (URL(url).openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = 5000
                        readTimeout = 5000
                    }
                    val code = connection.responseCode
                    if (code in 200..299) {
                        activity.runOnUiThread {
                            Toast.makeText(activity, "Voice effect: $effect", Toast.LENGTH_SHORT).show()
                        }
                        return@Thread
                    }
                    lastError = "HTTP $code from $host"
                } catch (e: Exception) {
                    lastError = "$host: ${e.message}"
                } finally {
                    connection?.disconnect()
                }
            }
            val message = lastError
            activity.runOnUiThread {
                Toast.makeText(activity, "Voice effect failed: $message", Toast.LENGTH_LONG).show()
            }
        }.start()
    }
}
