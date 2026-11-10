package org.linphone

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * AFIF Phone watchdog.
 *
 * XOS/Infinix can kill the keep-alive service even when battery optimisation is
 * disabled. The Tank app survives by scheduling an AlarmManager wake-up that
 * starts its foreground service again. This is the same approach adapted to
 * the Linphone core.
 *
 * AfifSmsWatchdog's own periodic nudge runs on a plain in-process Handler, so if the OEM
 * kills the whole app process (not just the keep-alive service), that nudge loop dies with
 * it and never restarts itself. AlarmManager wake-ups survive a process kill because Android
 * itself re-launches the app to deliver them, so this receiver re-arms AfifSmsWatchdog here
 * too, exactly the way it already revives the keep-alive service for calls. Still never
 * touches the secure network.
 */
class AfifWatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        try {
            context.applicationContext as LinphoneApplication
            LinphoneApplication.coreContext.startKeepAliveService()
            AfifDiagnostics.event(context, "watchdog restarted keep-alive service")
        } catch (e: Throwable) {
            AfifDiagnostics.event(context, "watchdog start failed: ${e.message}")
        }
        try {
            if (AfifSecureNetwork.isEnabled(context)) {
                AfifSmsWatchdog.start(context)
                AfifDiagnostics.event(context, "watchdog re-armed SMS nudge loop")
            }
        } catch (e: Throwable) {
            AfifDiagnostics.event(context, "watchdog SMS re-arm failed: ${e.message}")
        }
        AfifWatchdog.schedule(context)
    }
}

object AfifWatchdog {
    private const val REQUEST_CODE = 0xAF1F
    private const val INTERVAL_MS = 5 * 60 * 1000L

    fun schedule(context: Context) {
        try {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, AfifWatchdogReceiver::class.java)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val pi = PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags)
            val at = System.currentTimeMillis() + INTERVAL_MS
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                am.set(AlarmManager.RTC_WAKEUP, at, pi)
            }
        } catch (_: Throwable) {
        }
    }

    fun cancel(context: Context) {
        try {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, AfifWatchdogReceiver::class.java)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val pi = PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags)
            am.cancel(pi)
            pi.cancel()
        } catch (_: Throwable) {
        }
    }
}
