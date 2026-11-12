/*
 * AFIF Phone — keep ringing with the screen off. GPLv3, part of AFIF Phone (based on Linphone).
 */
package org.linphone

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import org.linphone.LinphoneApplication.Companion.corePreferences

object AfifBackground {
    private const val PREFS = "afif_phone"
    private const val ASKED = "battery_asked_v1"

    fun isUnrestricted(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true

    /**
     * With "Run in background" on, ask once to be left out of battery optimisation, so Android doesn't stop
     * AFIF Phone and calls and messages still arrive when the phone is locked.
     */
    fun check(activity: Activity) {
        if (!corePreferences.keepServiceAlive || isUnrestricted(activity)) return
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(ASKED, false)) return
        activity.window.decorView.post {
            if (activity.isFinishing || activity.isDestroyed) return@post
            prefs.edit().putBoolean(ASKED, true).apply()
            AlertDialog.Builder(activity)
                .setTitle("Run in background")
                .setMessage(
                    "To receive calls and messages when the phone is locked and the screen is off, " +
                        "allow AFIF Phone to run without battery restrictions.\n\n" +
                        "You can switch this off any time in Settings → Advanced settings → Run in background."
                )
                .setPositiveButton("Allow") { _, _ -> request(activity) }
                .setNegativeButton("Not now", null)
                .show()
        }
    }

    /**
     * Same one-time battery-optimisation prompt as [check], but for Domain-mode users. Domain mode's
     * screens (AfifDomainMainActivity and friends) never touch Linphone's own AssistantActivity/MainActivity,
     * which is where [check] is wired in via apply.py - and `corePreferences.keepServiceAlive` is the legacy
     * "Run in background" toggle for the old SIP/Tailscale transport, which a Domain-only user never sees
     * either. Without this, Domain users are never asked for the exemption at all: Android is then free to
     * throttle or periodically kill the foreground service's socket (most aggressively on OEM skins with
     * strict battery/app-standby policies), which shows up as the phone's WebSocket registration repeatedly
     * dropping and reconnecting every few seconds - long enough to drop any call that's in progress when it
     * happens. Domain mode has no "off" state for this, so it is asked unconditionally (no keepServiceAlive
     * gate), but shares the same ASKED flag/prefs as [check] so a user who already answered either prompt
     * isn't asked a second time.
     */
    fun checkDomain(activity: Activity) {
        if (isUnrestricted(activity)) return
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(ASKED, false)) return
        activity.window.decorView.post {
            if (activity.isFinishing || activity.isDestroyed) return@post
            prefs.edit().putBoolean(ASKED, true).apply()
            AlertDialog.Builder(activity)
                .setTitle("Keep calls reliable")
                .setMessage(
                    "To stop calls and messages from dropping when the phone is locked or the screen is off, " +
                        "allow AFIF Phone to run without battery restrictions.\n\n" +
                        "Without this, Android may periodically cut the connection in the background, " +
                        "which can end a call a moment after it's answered."
                )
                .setPositiveButton("Allow") { _, _ -> request(activity) }
                .setNegativeButton("Not now", null)
                .show()
        }
    }

    fun request(activity: Activity) {
        try {
            activity.startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + activity.packageName))
            )
        } catch (e: Exception) {
            try {
                activity.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Exception) { }
        }
    }
}
