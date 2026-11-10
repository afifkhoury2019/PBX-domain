/*
 * AFIF Phone — SMS / in-app message watchdog.
 *
 * Two distinct delivery delays have been reported, and this file now fixes both without ever
 * touching the secure network (Tailscale) connection itself:
 *
 * 1) On some phones/carriers, simply having the secure network connected — even though it only
 *    carries AFIF Phone's own traffic — can delay ordinary carrier SMS delivered to the stock
 *    Android Messages app. We handle that by periodically nudging Android to re-validate the
 *    phone's own mobile/Wi-Fi network (ConnectivityManager.reportNetworkConnectivity), which never
 *    references the VPN at all.
 *
 * 2) AFIF Phone's own in-app messages (SIP MESSAGE, delivered over the secure network to/from the
 *    AFIF-PBX extension) can sit undelivered for a long time while the screen is off. Checking the
 *    PBX's own SIP server confirms calls (INVITE) and messages (MESSAGE) use the exact same UDP
 *    port, transport and registration — so this is not a blocked port. It's Android batching how
 *    often this app's background thread actually gets scheduled to drain its socket under Doze,
 *    and it drifts out the longer the phone sits idle. The user's own manual fix — disconnect the
 *    secure network, then reconnect it — works only as a side effect: it forces Linphone to
 *    immediately redo its SIP registration, which also flushes anything stuck. We can get that
 *    same flush, at the SIP layer only, by calling Core.refreshRegisters() periodically — a single
 *    lightweight REGISTER/200-OK round trip that normal SIP phones do routinely anyway. This never
 *    drops the secure network or the registration itself, so there is no window where an incoming
 *    call could be missed, unlike actually disconnecting the VPN.
 *
 * Both run on a short timer, screen on or off, for as long as the secure network is connected.
 *
 * GPLv3, part of AFIF Phone (based on Linphone).
 */
package org.linphone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper

object AfifSmsWatchdog {
    private const val NETWORK_NUDGE_PERIOD_MS = 2 * 60 * 1000L // stock-SMS nudge: every 2 minutes
    private const val NETWORK_NUDGE_MIN_GAP_MS = 60 * 1000L // never more than once a minute
    private const val SIP_REFRESH_PERIOD_MS = 45 * 1000L // in-app message fix: every 45 seconds
    private const val SIP_REFRESH_MIN_GAP_MS = 20 * 1000L // never more than once every 20 seconds

    private var lastNetworkNudge = 0L
    private var lastSipRefresh = 0L
    private var running = false
    private var appContext: Context? = null
    private val handler = Handler(Looper.getMainLooper())

    private val screenOnReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            nudgeNetworkIfDue("screen turned on")
            refreshSipRegistrationIfDue("screen turned on")
        }
    }

    private val networkNudgeTick = object : Runnable {
        override fun run() {
            nudgeNetworkIfDue("periodic check")
            handler.postDelayed(this, NETWORK_NUDGE_PERIOD_MS)
        }
    }

    private val sipRefreshTick = object : Runnable {
        override fun run() {
            refreshSipRegistrationIfDue("periodic check")
            handler.postDelayed(this, SIP_REFRESH_PERIOD_MS)
        }
    }

    /** Start watching. Safe to call repeatedly; only the first call does anything. */
    fun start(context: Context) {
        if (running) return
        running = true
        val app = context.applicationContext
        appContext = app
        try {
            app.registerReceiver(screenOnReceiver, IntentFilter(Intent.ACTION_SCREEN_ON))
        } catch (e: Exception) {
            org.linphone.core.tools.Log.e("[AFIF] SMS watchdog: can't watch screen-on: $e")
        }
        handler.removeCallbacks(networkNudgeTick)
        handler.removeCallbacks(sipRefreshTick)
        // Fire both right away on (re)start — important when this is reviving after the OEM
        // killed the whole process, so the gap isn't 5 min (alarm wake) + the tick period on top.
        handler.post(networkNudgeTick)
        handler.post(sipRefreshTick)
    }

    /** Stop watching (the user turned the secure network off). */
    fun stop(context: Context) {
        if (!running) return
        running = false
        handler.removeCallbacks(networkNudgeTick)
        handler.removeCallbacks(sipRefreshTick)
        try {
            context.applicationContext.unregisterReceiver(screenOnReceiver)
        } catch (_: Exception) {
        }
        appContext = null
    }

    /**
     * Ask Android to re-check the phone's own mobile/Wi-Fi network — never the secure network,
     * never anything that could touch AFIF Phone's own connection or an active call. Targets delays
     * to the stock Messages app's carrier SMS.
     */
    private fun nudgeNetworkIfDue(reason: String) {
        if (!running) return
        val context = appContext ?: return
        if (!AfifSecureNetwork.isEnabled(context)) return
        val now = System.currentTimeMillis()
        if (now - lastNetworkNudge < NETWORK_NUDGE_MIN_GAP_MS) return
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return
            val phoneNetwork = cm.allNetworks.firstOrNull { net ->
                val caps = cm.getNetworkCapabilities(net)
                caps != null &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            } ?: return
            lastNetworkNudge = now
            org.linphone.core.tools.Log.i("[AFIF] SMS watchdog: nudging phone network validation ($reason)")
            cm.reportNetworkConnectivity(phoneNetwork, true)
        } catch (e: Exception) {
            org.linphone.core.tools.Log.e("[AFIF] SMS watchdog: network nudge failed: $e")
        }
    }

    /**
     * Force a fresh SIP REGISTER on Linphone's own core thread. Targets delayed delivery of
     * AFIF Phone's own in-app messages over the secure network. Does not touch the VPN connection
     * itself — only asks the already-connected SIP stack to redo its registration, the same thing
     * it does routinely, just on demand. A single REGISTER/200-OK round trip, so there is no window
     * where an incoming call could be missed, unlike disconnecting the secure network.
     */
    private fun refreshSipRegistrationIfDue(reason: String) {
        if (!running) return
        val context = appContext ?: return
        if (!AfifSecureNetwork.isEnabled(context)) return
        if (!AfifSecureNetwork.isVpnConnected(context)) return
        val now = System.currentTimeMillis()
        if (now - lastSipRefresh < SIP_REFRESH_MIN_GAP_MS) return
        lastSipRefresh = now
        try {
            context.applicationContext as LinphoneApplication
            LinphoneApplication.coreContext.postOnCoreThread { core ->
                try {
                    // REGISTER and an active call's own SIP dialog are entirely separate in SIP —
                    // refreshing registration never touches a call's signaling or audio. We still
                    // skip it while a call is up, purely so this watchdog can never be a factor,
                    // even in theory, during the one moment that matters most.
                    if (core.callsNb > 0) {
                        org.linphone.core.tools.Log.i("[AFIF] SMS watchdog: skipping SIP refresh, call in progress")
                    } else {
                        org.linphone.core.tools.Log.i("[AFIF] SMS watchdog: refreshing SIP registration ($reason)")
                        core.refreshRegisters()
                    }
                } catch (e: Throwable) {
                    org.linphone.core.tools.Log.e("[AFIF] SMS watchdog: refreshRegisters failed: $e")
                }
            }
        } catch (e: Throwable) {
            org.linphone.core.tools.Log.e("[AFIF] SMS watchdog: can't reach core thread: $e")
        }
    }
}
