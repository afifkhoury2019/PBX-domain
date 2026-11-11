/*
 * AFIF Phone — transport selection: Tailscale SIP or direct domain WebSocket.
 * GPLv3, part of AFIF Phone (based on Linphone).
 */
package org.linphone

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast

object AfifTransport {
    private const val PREFS = "afif_phone"
    private const val MODE = "transport_mode"          // 0 = Tailscale, 1 = Domain
    private const val HOST = "domain_host"
    private const val EXT = "domain_extension"
    private const val SECRET = "domain_secret"

    const val TAILSCALE = 0
    const val DOMAIN = 1

    fun labels(): Array<String> = arrayOf("Tailscale (SIP)", "Domain (no VPN)")

    fun index(context: Context): Int {
        val mode = prefs(context).getInt(MODE, DOMAIN)
        return if (mode == DOMAIN) 1 else 0
    }

    fun isDomain(context: Context): Boolean = index(context) == 1

    fun host(context: Context): String =
        prefs(context).getString(HOST, "pbx.powerchain.uk").orEmpty().trim()
            .removePrefix("https://").removePrefix("http://").trimEnd('/')

    fun extension(context: Context): String {
        val saved = prefs(context).getString(EXT, "").orEmpty().trim()
        if (saved.isNotEmpty()) return saved
        return try {
            LinphoneApplication.coreContext.core.defaultAccount?.params?.identityAddress?.username ?: "100"
        } catch (_: Throwable) {
            "100"
        }
    }

    fun secret(context: Context): String =
        prefs(context).getString(SECRET, "").orEmpty().trim()

    /** Credentials entered in the third-party setup; host is always the AFIF domain. */
    fun saveCredentials(context: Context, extension: String, secret: String) {
        prefs(context).edit()
            .putString(HOST, "pbx.powerchain.uk")
            .putString(EXT, extension.trim())
            .putString(SECRET, secret.trim())
            .apply()
    }

    /**
     * Called from the settings spinner. Selecting Domain asks for the PBX
     * extension secret the first time, because the same secret is used by the
     * PBX WebSocket registration.
     */
    fun setIndex(activity: Activity, position: Int) {
        if (position == 1) {
            if (secret(activity).isEmpty()) {
                configure(activity) { setMode(activity, DOMAIN) }
            } else {
                setMode(activity, DOMAIN)
            }
        } else {
            setMode(activity, TAILSCALE)
        }
    }

    fun setMode(context: Context, mode: Int) {
        prefs(context).edit().putInt(MODE, mode).apply()
        if (mode == DOMAIN) {
            AfifDomainService.stop(context)
            AfifSipBridgeService.start(context)
            AfifLocalSip.configure(context)
            Toast.makeText(context, "Domain mode: local SIP bridge", Toast.LENGTH_SHORT).show()
        } else {
            AfifDomainService.stop(context)
            AfifSipBridgeService.stop(context)
            Toast.makeText(context, "Tailscale mode: SIP over the secure network", Toast.LENGTH_SHORT).show()
        }
    }

    /** Start the direct service after a reboot/launch when Domain is already selected. */
    fun bootstrap(context: Context) {
        if (isDomain(context) && secret(context).isNotEmpty()) {
            AfifSipBridgeService.start(context)
            AfifLocalSip.configure(context)
        }
    }

    /** Dialog for the public host, extension, and SIP secret used by the WebSocket. */
    fun configure(activity: Activity, onSaved: () -> Unit) {
        val pad = (16 * activity.resources.displayMetrics.density).toInt()
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
        }
        fun field(hint: String, value: String, password: Boolean = false): EditText =
            EditText(activity).apply {
                this.hint = hint
                setText(value)
                inputType = if (password)
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                else InputType.TYPE_CLASS_TEXT
                box.addView(this)
            }

        val host = field("Public host (e.g. pbx.powerchain.uk)", host(activity))
        val ext = field("Extension", extension(activity))
        val secret = field("SIP / WebSocket secret", secret(activity), password = true)

        AlertDialog.Builder(activity)
            .setTitle("Domain connection")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                val h = host.text.toString().trim()
                val e = ext.text.toString().trim()
                val s = secret.text.toString().trim()
                if (h.isEmpty() || e.isEmpty() || s.isEmpty()) {
                    Toast.makeText(activity, "Host, extension and secret are required", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                prefs(activity).edit()
                    .putString(HOST, h)
                    .putString(EXT, e)
                    .putString(SECRET, s)
                    .apply()
                onSaved()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
