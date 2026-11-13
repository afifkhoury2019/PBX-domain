package org.linphone

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * AFIF Phone diagnostics.
 *
 * Keeps a small event log, writes a crash report when the process dies, and
 * uploads pending reports to the AFIF-PBX /client-log endpoint on the next
 * launch. It also asks Android for the battery-optimisation exemption and the
 * notification permission needed for calls/messages on the lock screen.
 */
object AfifDiagnostics {
    private const val TAG = "[AFIF Diagnostics]"
    private const val ENDPOINT = "https://pbx.powerchain.uk/client-log"
    private const val MAX_EVENT_BYTES = 64 * 1024
    private const val CRASH_PREFIX = "afif-crash-"

    @Volatile
    private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        event(app, "app start")

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val report = buildString {
                    appendLine("AFIF Phone crash")
                    appendLine("time=" + SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date()))
                    appendLine("device=" + Build.MANUFACTURER + " " + Build.MODEL + " / Android " + Build.VERSION.RELEASE)
                    appendLine("thread=" + thread.name)
                    appendLine(error.stackTraceToString())
                }
                File(app.filesDir, CRASH_PREFIX + System.currentTimeMillis() + ".txt")
                    .writeText(report)
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, error)
        }

        Thread { uploadPending(app) }.start()
    }

    fun event(context: Context, message: String) {
        try {
            val f = File(context.filesDir, "afif-events.log")
            if (f.length() > MAX_EVENT_BYTES) f.writeText("")
            f.appendText(
                SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()) +
                    " " + message + "\n"
            )
        } catch (_: Throwable) {
        }
    }

    fun requestBatteryExemption(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        try {
            val pm = activity.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (pm.isIgnoringBatteryOptimizations(activity.packageName)) return
            activity.startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + activity.packageName)
                )
            )
        } catch (_: Throwable) {
        }
    }

    fun requestNotificationPermission(activity: Activity) {
        if (Build.VERSION.SDK_INT < 33) return
        try {
            if (activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
            }
        } catch (_: Throwable) {
        }
    }

    private fun uploadPending(context: Context) {
        val files = context.filesDir.listFiles { f -> f.name.startsWith(CRASH_PREFIX) } ?: return
        for (file in files.sortedBy { it.lastModified() }) {
            val body = try {
                file.readText()
            } catch (_: Throwable) {
                continue
            }
            if (post(context, body)) {
                try { file.delete() } catch (_: Throwable) {}
            }
        }
        uploadEventLog(context, "startup")
    }

    /**
     * Send the AFIF event log (including the bridge SIP transcript) to the PBX
     * even when the phone is outside the LAN. Used after calls and from the
     * watchdog so the failure can be inspected remotely.
     */
    fun uploadEventLog(context: Context, reason: String) {
        Thread {
            try {
                val f = File(context.filesDir, "afif-events.log")
                if (!f.exists() || f.length() < 8) return@Thread
                val body = "AFIF Phone event log\nreason=$reason\n" +
                    "device=" + Build.MANUFACTURER + " " + Build.MODEL +
                    " / Android " + Build.VERSION.RELEASE + "\n\n" + f.readText()
                post(context, body)
            } catch (_: Throwable) {
            }
        }.start()
    }

    private fun post(context: Context, body: String): Boolean {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 5000
                readTimeout = 5000
                doOutput = true
                setRequestProperty("Content-Type", "text/plain; charset=utf-8")
                setRequestProperty("X-AFIF-Client", "afif-phone")
                // Cloudflare's browser-integrity rule rejects the default Dalvik UA.
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) AFIFPhone/1.0")
                setRequestProperty("X-AFIF-Extension", AfifTransport.extension(context))
                setRequestProperty("X-AFIF-Secret", AfifTransport.secret(context))
            }
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            connection.responseCode in 200..299
        } catch (_: Throwable) {
            false
        } finally {
            connection?.disconnect()
        }
    }
}
