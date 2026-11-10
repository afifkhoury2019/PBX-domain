/*
 * AFIF Phone — direct domain WebSocket call transport.
 * GPLv3, part of AFIF Phone (based on Linphone).
 *
 * One persistent wss://<public-host>/register/<extension> socket carries JSON
 * control messages and raw 8 kHz mono PCM audio. No VPN is required.
 */
package org.linphone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import okio.ByteString
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class AfifDomainService : Service() {
    companion object {
        const val ACTION_START = "org.linphone.AFIF_DOMAIN_START"
        const val ACTION_STOP = "org.linphone.AFIF_DOMAIN_STOP"
        const val ACTION_ANSWER = "org.linphone.AFIF_DOMAIN_ANSWER"
        const val ACTION_HANGUP = "org.linphone.AFIF_DOMAIN_HANGUP"
        const val ACTION_DIAL = "org.linphone.AFIF_DOMAIN_DIAL"
        const val ACTION_SMS = "org.linphone.AFIF_DOMAIN_SMS"
        const val ACTION_MUTE = "org.linphone.AFIF_DOMAIN_MUTE"
        const val ACTION_SPEAKER = "org.linphone.AFIF_DOMAIN_SPEAKER"

        @Volatile var connected = false
        @Volatile var answered = false
        @Volatile var inCall = false
        @Volatile var incoming = false
        @Volatile var muted = false
        @Volatile var speaker = true
        @Volatile var callNumber = ""
        @Volatile var callName = ""
        @Volatile var callId = ""
        @Volatile var lastError = ""

        fun start(context: Context) {
            val i = Intent(context, AfifDomainService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, AfifDomainService::class.java).setAction(ACTION_STOP))
        }

        fun answer(context: Context) {
            context.startService(Intent(context, AfifDomainService::class.java).setAction(ACTION_ANSWER))
        }

        fun hangup(context: Context) {
            context.startService(Intent(context, AfifDomainService::class.java).setAction(ACTION_HANGUP))
        }

        fun dial(context: Context, number: String) {
            val i = Intent(context, AfifDomainService::class.java)
                .setAction(ACTION_DIAL)
                .putExtra("number", number)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
        }

        fun sendSms(context: Context, to: String, text: String) {
            val i = Intent(context, AfifDomainService::class.java)
                .setAction(ACTION_SMS)
                .putExtra("to", to)
                .putExtra("text", text)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
        }

        fun setMuted(context: Context, on: Boolean) {
            context.startService(
                Intent(context, AfifDomainService::class.java)
                    .setAction(ACTION_MUTE).putExtra("on", on)
            )
        }

        fun setSpeaker(context: Context, on: Boolean) {
            context.startService(
                Intent(context, AfifDomainService::class.java)
                    .setAction(ACTION_SPEAKER).putExtra("on", on)
            )
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private var socket: WebSocket? = null
    private var running = false
    private var reconnectScheduled = false
    private var pendingDial: String? = null

    private var record: AudioRecord? = null
    private var track: AudioTrack? = null
    private var audioThread: Thread? = null
    private var ringtone: Ringtone? = null
    private var audioManager: AudioManager? = null
    private var recordId: String? = null
    private var callStartedAt = 0L

    override fun onCreate() {
        super.onCreate()
        createChannel()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                running = true
                startForeground(1001, ongoingNotification("Connecting…"))
                connect()
            }
            ACTION_STOP -> {
                running = false
                cleanupCall()
                socket?.close(1000, "stopped")
                socket = null
                stopForeground(true)
                stopSelf()
            }
            ACTION_ANSWER -> answerCall()
            ACTION_HANGUP -> hangup()
            ACTION_DIAL -> {
                val number = intent.getStringExtra("number").orEmpty().trim()
                if (number.isNotEmpty()) dial(number)
            }
            ACTION_SMS -> {
                val to = intent.getStringExtra("to").orEmpty().trim()
                val text = intent.getStringExtra("text").orEmpty()
                if (to.isNotEmpty() && text.isNotEmpty()) sendSmsNow(to, text)
            }
            ACTION_MUTE -> {
                muted = intent.getBooleanExtra("on", false)
                updateOngoing()
            }
            ACTION_SPEAKER -> {
                speaker = intent.getBooleanExtra("on", true)
                audioManager?.isSpeakerphoneOn = speaker
                updateOngoing()
            }
        }
        return START_STICKY
    }

    // ------------------------------------------------------------ socket

    private fun connect() {
        if (!running) return
        val host = AfifTransport.host(this)
        val extension = AfifTransport.extension(this)
        val secret = AfifTransport.secret(this)
        if (host.isEmpty() || extension.isEmpty() || secret.isEmpty()) {
            lastError = "Domain host, extension or secret is missing"
            notifyState()
            return
        }
        socket?.close(1000, "reconnect")
        val request = Request.Builder()
            .url("wss://$host/register/$extension")
            .header("Sec-WebSocket-Protocol", secret)
            .build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                connected = true
                lastError = ""
                pendingDial?.let { number ->
                    pendingDial = null
                    webSocket.send(JSONObject().put("type", "dial").put("number", number).toString())
                }
                notifyState()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleControl(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                track?.let {
                    val data = bytes.toByteArray()
                    if (data.isNotEmpty()) it.write(data, 0, data.size)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                connected = false
                lastError = t.message ?: "connection failed"
                notifyState()
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                connected = false
                if (inCall) endCall("connection closed")
                notifyState()
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        if (!running || reconnectScheduled) return
        reconnectScheduled = true
        main.postDelayed({
            reconnectScheduled = false
            if (running) connect()
        }, 5000)
    }

    private fun send(obj: JSONObject) {
        socket?.send(obj.toString())
    }

    private fun handleControl(text: String) {
        try {
            val o = JSONObject(text)
            when (o.optString("type")) {
                "ring" -> incomingCall(
                    o.optString("callId"),
                    o.optString("number"),
                    o.optString("name")
                )
                "answered" -> markAnswered()
                "hangup" -> endCall(o.optString("reason", "remote hangup"))
                "sms" -> incomingSms(o.optString("from"), o.optString("text"), o.optLong("time", System.currentTimeMillis()))
                "pong" -> {}
            }
        } catch (_: Throwable) {
            // Ignore malformed control frames.
        }
    }

    private fun sendSmsNow(to: String, text: String) {
        val now = System.currentTimeMillis()
        AfifSmsStore.add(this, to, text, now, true)
        if (connected) {
            send(JSONObject().put("type", "sms").put("to", to).put("text", text))
        } else {
            lastError = "Not connected — message stored, not sent"
        }
    }

    private fun incomingSms(from: String, text: String, time: Long) {
        AfifSmsStore.add(this, from, text, time, false)
        val open = Intent(this, AfifDomainMessagesActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(
            this, 1003, open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = notificationBuilder()
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle("SMS from $from")
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        try {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(1003, n)
        } catch (_: Throwable) { }
    }

    // ------------------------------------------------------------ calls

    private fun incomingCall(id: String, number: String, name: String) {
        if (inCall) {
            send(JSONObject().put("type", "busy"))
            return
        }
        inCall = true
        incoming = true
        answered = false
        callId = id
        callNumber = number
        callName = name
        lastError = ""
        callStartedAt = System.currentTimeMillis()
        recordId = AfifCallStore.add(this, number, name, false, callStartedAt)
        ringtone = try {
            RingtoneManager.getRingtone(this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))
                ?.also { it.play() }
        } catch (_: Throwable) { null }

        val full = Intent(this, AfifDomainCallActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra("incoming", true)
            .putExtra("number", number)
            .putExtra("name", name)
        val pi = PendingIntent.getActivity(
            this, 1002, full,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = notificationBuilder()
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentTitle(if (name.isNotBlank()) name else "Incoming call")
            .setContentText(number)
            .setCategory(Notification.CATEGORY_CALL)
            .setPriority(Notification.PRIORITY_HIGH)
            .setOngoing(true)
            .setFullScreenIntent(pi, true)
            .setContentIntent(pi)
            .build()
        try {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(1002, n)
            startActivity(full)
        } catch (_: Throwable) { }
        notifyState()
    }

    private fun dial(number: String) {
        if (inCall) return
        running = true
        startForeground(1001, ongoingNotification("Calling $number"))
        inCall = true
        incoming = false
        answered = false
        callId = ""
        callNumber = number
        callName = ""
        lastError = ""
        callStartedAt = System.currentTimeMillis()
        recordId = AfifCallStore.add(this, number, "", true, callStartedAt)
        if (connected) {
            send(JSONObject().put("type", "dial").put("number", number))
        } else {
            pendingDial = number
            connect()
        }
        val i = Intent(this, AfifDomainCallActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra("incoming", false)
            .putExtra("number", number)
        try { startActivity(i) } catch (_: Throwable) { }
        notifyState()
    }

    private fun answerCall() {
        if (!inCall || answered) return
        ringtone?.stop()
        ringtone = null
        send(JSONObject().put("type", "answer"))
        markAnswered()
    }

    private fun markAnswered() {
        if (!inCall) return
        answered = true
        recordId?.let { AfifCallStore.markAnswered(this, it) }
        ringtone?.stop()
        ringtone = null
        startAudio()
        updateOngoing()
        notifyState()
    }

    private fun hangup() {
        send(JSONObject().put("type", "hangup"))
        endCall("local hangup")
    }

    private fun endCall(reason: String) {
        if (!inCall && !answered) return
        cleanupCall()
        notifyState()
    }

    private fun cleanupCall() {
        ringtone?.stop()
        ringtone = null
        stopAudio()
        inCall = false
        incoming = false
        answered = false
        callId = ""
        callNumber = ""
        callName = ""
        val duration = if (callStartedAt > 0)
            ((System.currentTimeMillis() - callStartedAt) / 1000).toInt() else 0
        recordId?.let { AfifCallStore.markEnded(this, it, duration) }
        recordId = null
        callStartedAt = 0L
        lastError = reasonOrEmpty()
        main.post {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(1002)
            if (running) startForeground(1001, ongoingNotification(if (connected) "Ready" else "Connecting…"))
        }
    }

    private fun reasonOrEmpty(): String = lastError

    // ------------------------------------------------------------ audio

    private fun startAudio() {
        if (record != null) return
        try {
            audioManager?.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager?.isSpeakerphoneOn = speaker
            val min = AudioRecord.getMinBufferSize(
                8000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val buf = maxOf(min, 3200)
            val r = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                8000,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                buf
            )
            if (r.state != AudioRecord.STATE_INITIALIZED) {
                r.release()
                lastError = "Microphone unavailable"
                return
            }
            val t = AudioTrack(
                AudioManager.STREAM_VOICE_CALL,
                8000,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                buf,
                AudioTrack.MODE_STREAM
            )
            record = r
            track = t
            r.startRecording()
            t.play()
            audioThread = Thread {
                val frame = ByteArray(320)
                while (record === r && inCall) {
                    val n = try { r.read(frame, 0, frame.size) } catch (_: Throwable) { -1 }
                    if (n > 0 && !muted) {
                        socket?.send(ByteString.of(*frame.copyOf(n)))
                    }
                }
            }.also { it.start() }
        } catch (e: Throwable) {
            lastError = "Audio: ${e.message}"
        }
    }

    private fun stopAudio() {
        audioThread = null
        try { record?.stop() } catch (_: Throwable) { }
        try { record?.release() } catch (_: Throwable) { }
        try { track?.stop() } catch (_: Throwable) { }
        try { track?.release() } catch (_: Throwable) { }
        record = null
        track = null
        try {
            audioManager?.isSpeakerphoneOn = false
            audioManager?.mode = AudioManager.MODE_NORMAL
        } catch (_: Throwable) { }
    }

    // ------------------------------------------------------------ notifications

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val ch = NotificationChannel("afif_domain", "AFIF Direct calls", NotificationManager.IMPORTANCE_LOW)
        ch.description = "Direct domain WebSocket calls"
        nm.createNotificationChannel(ch)
    }

    private fun notificationBuilder(): Notification.Builder =
        if (Build.VERSION.SDK_INT >= 26)
            Notification.Builder(this, "afif_domain")
        else
            @Suppress("DEPRECATION") Notification.Builder(this)

    private fun ongoingNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 1001,
            Intent(this, AfifDomainDialActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return notificationBuilder()
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentTitle("AFIF Direct")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }

    private fun updateOngoing() {
        if (!running) return
        val text = when {
            !connected -> "Connecting…"
            inCall && answered -> "In call"
            inCall -> "Ringing…"
            else -> "Ready"
        }
        try { startForeground(1001, ongoingNotification(text)) } catch (_: Throwable) { }
    }

    private fun notifyState() {
        updateOngoing()
    }

    override fun onDestroy() {
        running = false
        cleanupCall()
        socket?.close(1000, "service stopped")
        socket = null
        super.onDestroy()
    }
}
