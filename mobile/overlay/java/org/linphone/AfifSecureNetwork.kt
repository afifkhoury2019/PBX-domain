/*
 * AFIF Phone — first-run choice for the built-in secure network (Tailscale engine).
 * GPLv3, part of AFIF Phone (based on Linphone).
 */
package org.linphone

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.widget.CompoundButton

object AfifSecureNetwork {
    private const val PREFS = "afif_phone"
    private const val ASKED = "secure_network_asked"
    private const val CHOSEN = "secure_network_enabled"

    fun open(activity: Activity) {
        try {
            activity.startActivity(Intent(activity, com.tailscale.ipn.MainActivity::class.java))
        } catch (e: Exception) {
            org.linphone.core.tools.Log.e("[AFIF] Can't open secure network: $e")
        }
    }

    fun isVpnConnected(context: Context): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return false
            cm.getNetworkCapabilities(network)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        } catch (_: Throwable) {
            false
        }
    }

    /** PBX address: real Tailscale peer IPv4 when available, MagicDNS/LAN otherwise. */
    fun pbxDomain(context: Context): String {
        if (!isVpnConnected(context)) return "192.168.0.224:5060"
        return try {
            val peer = com.tailscale.ipn.ui.notifier.Notifier.netmap.value?.Peers?.firstOrNull {
                it.Hostinfo.Hostname.equals("afif-pbx", true) ||
                    it.Name.trimEnd('.').startsWith("afif-pbx.") ||
                    it.ComputedName?.startsWith("afif-pbx") == true
            }
            val ip = peer?.primaryIPv4Address
            if (!ip.isNullOrBlank()) "$ip:5060" else "afif-pbx:5060"
        } catch (_: Throwable) {
            "afif-pbx:5060"
        }
    }

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(CHOSEN, false)

    /**
     * Switch the secure network on or off for AFIF Phone.
     * On: connect, and show the secure network screen so the user can sign in the first time.
     */
    fun setEnabled(activity: Activity, on: Boolean, openScreen: Boolean = true) {
        activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(CHOSEN, on).putBoolean(ASKED, true).apply()
        try {
            val service = Intent(activity, com.tailscale.ipn.IPNService::class.java)
            if (on) {
                service.action = "com.tailscale.ipn.START_VPN"
                activity.startForegroundService(service)
            } else {
                service.action = "com.tailscale.ipn.STOP_VPN"
                activity.startService(service)
            }
        } catch (e: Exception) {
            org.linphone.core.tools.Log.e("[AFIF] Direct VPN start failed, using broadcast: $e")
            try {
                activity.sendBroadcast(
                    Intent(if (on) "com.tailscale.ipn.CONNECT_VPN" else "com.tailscale.ipn.DISCONNECT_VPN")
                        .setPackage(activity.packageName)
                )
            } catch (e2: Exception) {
                org.linphone.core.tools.Log.e("[AFIF] Secure network switch failed: $e2")
            }
        }
        if (on) AfifSmsWatchdog.start(activity) else AfifSmsWatchdog.stop(activity)
        if (on && openScreen) open(activity)
    }

    /** A "Connect through the secure network (Tailscale)" check box wired to the setting. */
    fun bind(activity: Activity, box: CompoundButton?) {
        if (box == null) return
        box.text = "Connect through the secure network (Tailscale)"
        box.isChecked = isEnabled(activity)
        box.setOnCheckedChangeListener { _, on -> setEnabled(activity, on) }
    }

    /** Compact switch used in the drawer. */
    fun bindToggle(activity: Activity, box: CompoundButton?) {
        if (box == null) return
        box.isChecked = isEnabled(activity)
        box.setOnCheckedChangeListener { _, on -> setEnabled(activity, on, false) }
    }

    /** Ask once, on first setup, whether to connect the secure network. */
    fun askOnce(activity: Activity) {
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(ASKED, false)) {
            if (isEnabled(activity)) AfifSmsWatchdog.start(activity)
            AfifBackground.check(activity)
            return
        }
        activity.window.decorView.post {
            if (activity.isFinishing || activity.isDestroyed || prefs.getBoolean(ASKED, false)) return@post
            prefs.edit().putBoolean(ASKED, true).apply()
            AlertDialog.Builder(activity)
                .setTitle("Secure network")
                .setMessage(
                    "Do you want to connect AFIF Phone to your secure network (Tailscale)?\n\n" +
                        "Choose this to reach your AFIF-PBX extension when you are away from home. " +
                        "At home on the same Wi-Fi you don't need it.\n\n" +
                        "You can change this any time from the menu → Secure network."
                )
                .setPositiveButton("Connect") { _, _ -> setEnabled(activity, true) }
                .setNegativeButton("Not now") { _, _ ->
                    prefs.edit().putBoolean(CHOSEN, false).apply()
                    AfifBackground.check(activity)
                }
                .setCancelable(true)
                .show()
        }
    }
}
