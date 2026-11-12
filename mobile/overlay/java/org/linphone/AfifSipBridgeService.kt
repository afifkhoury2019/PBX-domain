/*
 * AFIF Phone — local SIP ↔ AFIF WebSocket bridge.
 * GPLv3, part of AFIF Phone (based on Linphone).
 *
 * Linphone keeps its full UI and SIP stack. It registers to 127.0.0.1:5070
 * instead of a remote PBX. This service translates the local SIP/RTP traffic
 * to the AFIF PBX WebSocket protocol, so the only external transport is
 * wss://pbx.powerchain.uk/register/{extension}.
 */
package org.linphone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class AfifSipBridgeService : Service() {
    companion object {
        const val ACTION_START = "org.linphone.AFIF_SIP_BRIDGE_START"
        const val ACTION_STOP = "org.linphone.AFIF_SIP_BRIDGE_STOP"

        @Volatile var connected = false
        @Volatile var lastError = ""
        @Volatile var registration = ""

        // Bound synchronously here, on the caller's thread, before the Service is even
        // dispatched - not just inside the Service's own onStartCommand(). Android queues
        // startForegroundService() and can take a few hundred ms to actually call onCreate()/
        // onStartCommand() on a cold start, and login() points Linphone's native SIP engine at
        // 127.0.0.1:5070 and triggers its first REGISTER right after calling start() here, on
        // the same thread, with no delay. If nothing was listening on that port yet, that first
        // REGISTER was simply dropped - the exact "registration failed, open AFIF Phone to
        // refresh" notification seen on first-time login. Binding the socket here, before start()
        // returns, guarantees it's already listening by the time that REGISTER is sent. The
        // Service instance adopts this socket in startSip() instead of creating its own.
        @Volatile private var preboundSip: DatagramSocket? = null

        fun ensureSipBound(): DatagramSocket? {
            preboundSip?.let { if (!it.isClosed) return it }
            return try {
                val s = DatagramSocket(null)
                s.reuseAddress = true
                s.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 5070))
                preboundSip = s
                s
            } catch (e: Throwable) {
                lastError = "Local SIP port: ${e.message}"
                null
            }
        }

        fun start(context: Context) {
            // See the matching guard in AfifDomainService.start() - these two services must
            // never both hold a registration for the same extension at once.
            AfifDomainService.stop(context)
            ensureSipBound()
            val i = Intent(context, AfifSipBridgeService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, AfifSipBridgeService::class.java).setAction(ACTION_STOP))
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
    private var ws: WebSocket? = null
    private var running = false
    private var reconnectScheduled = false

    private var sip: DatagramSocket? = null
    private var sipThread: Thread? = null
    private val dialogs = ConcurrentHashMap<String, Dialog>()
    private val contacts = ConcurrentHashMap<String, Contact>()

    private class Contact(val uri: String, val endpoint: InetSocketAddress)
    private class Dialog(
        val callId: String,
        val incoming: Boolean,
        val number: String,
        val remote: InetSocketAddress,
        val localTag: String,
        var remoteTag: String?,
        val request: Sip?,
        var localCSeq: Int = 1
    ) {
        var rtp: Rtp? = null
        var answered = false
        var toTag: String = randomTag()
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                running = true
                startForeground(1201, note("Starting local SIP bridge…"))
                if (sip == null) startSip()
                connect()
            }
            ACTION_STOP -> {
                running = false
                cleanup()
                stopForeground(true)
                stopSelf()
            }
        }
        return START_STICKY
    }

    // ------------------------------------------------------------ SIP socket

    private fun startSip() {
        // Normally already bound by start()'s ensureSipBound(), well before this runs. Falls
        // back to binding directly here too, in case the service was restarted by Android
        // itself (START_STICKY) without going through start().
        val s = ensureSipBound()
        if (s == null) {
            updateNote(lastError)
            return
        }
        try {
            sip = s
            sipThread = Thread {
                val buf = ByteArray(65535)
                while (sip === s && !s.isClosed) {
                    try {
                        val p = DatagramPacket(buf, buf.size)
                        s.receive(p)
                        val text = String(p.data, 0, p.length, Charsets.UTF_8)
                        onSip(text, InetSocketAddress(p.address, p.port))
                    } catch (_: Throwable) { }
                }
            }.also { it.start() }
            registration = "Local SIP listening on 127.0.0.1:5070"
            updateNote(registration)
        } catch (e: Throwable) {
            lastError = "Local SIP port: ${e.message}"
            updateNote(lastError)
        }
    }

    private fun onSip(text: String, remote: InetSocketAddress) {
        val msg = parseSip(text, remote) ?: return
        Log.i("AFIF-BRIDGE", "SIP <= ${msg.start} from ${remote.address}:${remote.port}")
        when {
            msg.start.startsWith("SIP/2.0") -> onResponse(msg)
            msg.start.startsWith("REGISTER") -> onRegister(msg, remote)
            msg.start.startsWith("INVITE") -> onInvite(msg, remote)
            msg.start.startsWith("ACK") -> {}
            msg.start.startsWith("BYE") -> onBye(msg)
            msg.start.startsWith("CANCEL") -> onCancel(msg)
            msg.start.startsWith("MESSAGE") -> onMessage(msg, remote)
            msg.start.startsWith("OPTIONS") -> respond(msg, 200, "OK")
        }
    }

    private fun onRegister(req: Sip, remote: InetSocketAddress) {
        val ext = uriUser(req.first("To")) ?: return
        val contact = req.first("Contact")?.substringAfter("<")?.substringBefore(">")
        val expires = req.first("Expires")?.toIntOrNull()
            ?: req.first("Contact")?.substringAfter("expires=")?.substringBefore(";")?.toIntOrNull()
            ?: 3600
        if (contact.isNullOrBlank() || expires == 0) contacts.remove(ext)
        else contacts[ext] = Contact(contact, remote)
        registration = "Registered $ext"
        updateNote(registration)
        val extra = mutableListOf<Pair<String, String>>()
        if (!contact.isNullOrBlank() && expires > 0) {
            extra.add("Contact" to "<$contact>;expires=$expires")
            extra.add("Expires" to expires.toString())
        }
        respond(req, 200, "OK", extra = extra)
    }

    private fun onInvite(req: Sip, remote: InetSocketAddress) {
        val callId = req.first("Call-ID") ?: return
        if (dialogs.containsKey(callId)) { respond(req, 486, "Busy Here"); return }
        val number = uriUser(req.first("To")) ?: uriUser(req.start) ?: ""
        val d = Dialog(callId, false, number, remote, randomTag(), tagOf(req.first("From")), req)
        dialogs[callId] = d
        Log.i("AFIF-BRIDGE", "INVITE $callId number=$number sdp=${req.body.replace("\r\n", "|")}")
        respond(req, 100, "Trying")
        respond(req, 180, "Ringing", toTag = d.toTag)
        send(JSONObject().put("type", "dial").put("number", number))
    }

    private fun onBye(msg: Sip) {
        Log.i("AFIF-BRIDGE", "BYE from Linphone")
        val id = msg.first("Call-ID") ?: return
        val d = dialogs.remove(id) ?: return
        d.rtp?.close()
        send(JSONObject().put("type", "hangup"))
        if (d.incoming) sendByeToLocal(d)
    }

    private fun onCancel(msg: Sip) {
        Log.i("AFIF-BRIDGE", "CANCEL from Linphone")
        val id = msg.first("Call-ID") ?: return
        val d = dialogs.remove(id) ?: return
        d.rtp?.close()
        send(JSONObject().put("type", "hangup"))
        respond(msg, 200, "OK")
    }

    private fun onResponse(msg: Sip) {
        val id = msg.first("Call-ID") ?: return
        val d = dialogs[id] ?: return
        val code = msg.start.split(" ").getOrNull(1)?.toIntOrNull() ?: return
        if (code == 200 && d.incoming && !d.answered) {
            d.remoteTag = tagOf(msg.first("To"))
            d.answered = true
            val sdp = parseSdp(msg.body)
            if (sdp != null) startRtp(d, sdp)
            sendAck(d, msg.first("Contact"))
            send(JSONObject().put("type", "answer"))
        }
    }

    // ------------------------------------------------------------ WebSocket

    private fun connect() {
        val host = AfifTransport.host(this)
        val ext = AfifTransport.extension(this)
        val secret = AfifTransport.secret(this)
        if (host.isBlank() || ext.isBlank() || secret.isBlank()) {
            lastError = "Domain host, extension or secret missing"
            updateNote(lastError)
            return
        }
        ws?.close(1000, "reconnect")
        val req = Request.Builder()
            .url("wss://$host/register/$ext")
            .header("Sec-WebSocket-Protocol", secret)
            .build()
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                connected = true
                lastError = ""
                updateNote("WebSocket connected")
            }

            override fun onMessage(webSocket: WebSocket, text: String) = control(text)
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val pcm = bytes.toByteArray()
                dialogs.values.firstOrNull { it.answered }?.rtp?.sendPcm(pcm)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                connected = false
                lastError = t.message ?: "WebSocket failed"
                updateNote(lastError)
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                connected = false
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        if (!running || reconnectScheduled) return
        reconnectScheduled = true
        main.postDelayed({ reconnectScheduled = false; if (running) connect() }, 5000)
    }

    private fun control(text: String) {
        try {
            val o = JSONObject(text)
            when (o.optString("type")) {
                "ring" -> incomingCall(
                    o.optString("callId"),
                    o.optString("number"),
                    o.optString("name")
                )
                "answered" -> outgoingAnswered()
                "hangup" -> remoteHangup()
                "sms" -> localSms(o.optString("from"), o.optString("text"))
            }
        } catch (_: Throwable) { }
    }

    private fun send(o: JSONObject) {
        ws?.send(o.toString())
    }

    // ------------------------------------------------------------ calls

    private fun incomingCall(id: String, number: String, name: String) {
        val ext = AfifTransport.extension(this)
        val contact = contacts[ext]
        if (contact == null) {
            send(JSONObject().put("type", "hangup").put("reason", "phone not registered"))
            return
        }
        val rtp = Rtp(0)
        val d = Dialog(id, true, number, contact.endpoint, randomTag(), randomTag(), null)
        d.rtp = rtp
        dialogs[id] = d
        sendInviteToLocal(d, contact.uri, number, rem = true).also {
            if (!it) { dialogs.remove(id); rtp.close() }
        }
    }

    private fun outgoingAnswered() {
        val d = dialogs.values.firstOrNull { !it.incoming && !it.answered } ?: return
        d.answered = true
        val req = d.request ?: return
        val rtp = d.rtp ?: Rtp(0).also { d.rtp = it }
        val remoteSdp = parseSdp(req.body)
        Log.i("AFIF-BRIDGE", "WS answered; localRtp=${rtp.port} remoteSdp=$remoteSdp")
        remoteSdp?.let { startRtp(d, it) }
        val body = sdp(rtp.port)
        respond(
            req, 200, "OK", body = body, toTag = d.toTag,
            extra = mutableListOf(
                "Contact" to "<sip:${AfifTransport.extension(this)}@127.0.0.1:5070>",
                "Content-Type" to "application/sdp"
            )
        )
    }

    private fun remoteHangup() {
        val d = dialogs.values.firstOrNull() ?: return
        if (d.answered) sendByeToLocal(d) else sendCancelToLocal(d)
        dialogs.remove(d.callId)
        d.rtp?.close()
    }

    private fun startRtp(d: Dialog, sdp: Sdp) {
        val rtp = d.rtp ?: Rtp(0).also { d.rtp = it }
        rtp.start(InetAddress.getByName("127.0.0.1"), sdp.port, sdp.payloadType) { pcm ->
            ws?.send(ByteString.of(*pcm))
        }
    }

    private fun sendInviteToLocal(d: Dialog, contact: String, number: String, rem: Boolean): Boolean {
        val rtp = d.rtp ?: return false
        val ext = AfifTransport.extension(this)
        val callId = d.callId
        val fromTag = d.localTag
        val body = sdp(rtp.port)
        val headers = mutableListOf(
            "Via" to "SIP/2.0/UDP 127.0.0.1:5070;branch=z9hG4bK${randomTag()}",
            "Max-Forwards" to "70",
            "From" to "<sip:$number@afif-pbx>;tag=$fromTag",
            "To" to "<$contact>",
            "Call-ID" to callId,
            "CSeq" to "1 INVITE",
            "Contact" to "<sip:$ext@127.0.0.1:5070>",
            "Content-Type" to "application/sdp",
            "User-Agent" to "AFIF-SIP-Bridge"
        )
        val packet = buildSip("INVITE $contact SIP/2.0", headers, body)
        return try {
            sip?.send(DatagramPacket(packet, packet.size, d.remote))
            d.localCSeq = 1
            true
        } catch (_: Throwable) { false }
    }

    private fun sendAck(d: Dialog, contact: String?) {
        val target = contact?.substringAfter("<")?.substringBefore(">") ?: d.remote.toString()
        val headers = mutableListOf(
            "Via" to "SIP/2.0/UDP 127.0.0.1:5070;branch=z9hG4bK${randomTag()}",
            "Max-Forwards" to "70",
            "From" to "<sip:${d.number}@afif-pbx>;tag=${d.localTag}",
            "To" to "<$target>${d.remoteTag?.let { ";tag=$it" } ?: ""}",
            "Call-ID" to d.callId,
            "CSeq" to "1 ACK",
            "Contact" to "<sip:${AfifTransport.extension(this)}@127.0.0.1:5070>"
        )
        val p = buildSip("ACK $target SIP/2.0", headers, "")
        try { sip?.send(DatagramPacket(p, p.size, d.remote)) } catch (_: Throwable) { }
    }

    private fun sendByeToLocal(d: Dialog) {
        val headers = mutableListOf(
            "Via" to "SIP/2.0/UDP 127.0.0.1:5070;branch=z9hG4bK${randomTag()}",
            "Max-Forwards" to "70",
            "From" to "<sip:${AfifTransport.extension(this)}@afif-pbx>;tag=${d.localTag}",
            "To" to "<sip:${d.number}@127.0.0.1>${d.remoteTag?.let { ";tag=$it" } ?: ""}",
            "Call-ID" to d.callId,
            "CSeq" to "${++d.localCSeq} BYE"
        )
        val p = buildSip("BYE sip:${d.number}@127.0.0.1 SIP/2.0", headers, "")
        try { sip?.send(DatagramPacket(p, p.size, d.remote)) } catch (_: Throwable) { }
    }

    private fun sendCancelToLocal(d: Dialog) {
        val headers = mutableListOf(
            "Via" to "SIP/2.0/UDP 127.0.0.1:5070;branch=z9hG4bK${randomTag()}",
            "Max-Forwards" to "70",
            "From" to "<sip:${d.number}@afif-pbx>;tag=${d.localTag}",
            "To" to "<sip:${AfifTransport.extension(this)}@127.0.0.1>",
            "Call-ID" to d.callId,
            "CSeq" to "1 CANCEL"
        )
        val p = buildSip("CANCEL sip:${AfifTransport.extension(this)}@127.0.0.1 SIP/2.0", headers, "")
        try { sip?.send(DatagramPacket(p, p.size, d.remote)) } catch (_: Throwable) { }
    }

    // ------------------------------------------------------------ SMS

    private fun onMessage(msg: Sip, remote: InetSocketAddress) {
        val to = uriUser(msg.first("To")) ?: ""
        if (to.isNotBlank() && msg.body.isNotBlank()) {
            send(JSONObject().put("type", "sms").put("to", to).put("text", msg.body))
        }
        respond(msg, 200, "OK")
    }

    private fun localSms(from: String, text: String) {
        val ext = AfifTransport.extension(this)
        val contact = contacts[ext] ?: return
        val headers = mutableListOf(
            "Via" to "SIP/2.0/UDP 127.0.0.1:5070;branch=z9hG4bK${randomTag()}",
            "Max-Forwards" to "70",
            "From" to "<sip:$from@afif-pbx>;tag=${randomTag()}",
            "To" to "<${contact.uri}>",
            "Call-ID" to "${System.currentTimeMillis()}@afif",
            "CSeq" to "1 MESSAGE",
            "Content-Type" to "text/plain; charset=utf-8"
        )
        val p = buildSip("MESSAGE ${contact.uri} SIP/2.0", headers, text)
        try { sip?.send(DatagramPacket(p, p.size, contact.endpoint)) } catch (_: Throwable) { }
    }

    // ------------------------------------------------------------ helpers

    private fun respond(
        req: Sip, code: Int, reason: String, body: String = "",
        toTag: String? = null,
        extra: MutableList<Pair<String, String>> = mutableListOf()
    ) {
        val headers = mutableListOf<Pair<String, String>>()
        req.all("Via").forEach { headers.add("Via" to it) }
        req.first("From")?.let { headers.add("From" to it) }
        var to = req.first("To") ?: ""
        val tag = toTag ?: if (code > 100 && req.start.startsWith("INVITE")) tagOf(to) ?: randomTag() else null
        if (tag != null && !to.contains("tag=")) to += ";tag=$tag"
        headers.add("To" to to)
        req.first("Call-ID")?.let { headers.add("Call-ID" to it) }
        req.first("CSeq")?.let { headers.add("CSeq" to it) }
        headers.addAll(extra)
        if (body.isNotEmpty()) headers.add("Content-Length" to body.toByteArray().size.toString())
        else headers.add("Content-Length" to "0")
        val p = buildSip("SIP/2.0 $code $reason", headers, body)
        Log.i("AFIF-BRIDGE", "SIP => $code $reason ${req.first("Call-ID")} body=${body.length}")
        try { sip?.send(DatagramPacket(p, p.size, req.remote)) } catch (_: Throwable) { }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel("afif_bridge", "AFIF SIP bridge", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun note(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 1201,
            Intent(this, AfifDomainMainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, "afif_bridge")
        else @Suppress("DEPRECATION") Notification.Builder(this)
        return b.setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentTitle("AFIF SIP bridge")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }

    private fun updateNote(text: String) {
        if (!running) return
        try { startForeground(1201, note(text)) } catch (_: Throwable) { }
    }

    private fun cleanup() {
        dialogs.values.forEach { it.rtp?.close() }
        dialogs.clear()
        ws?.close(1000, "stopped")
        ws = null
        try { sip?.close() } catch (_: Throwable) { }
        sip = null
        // Release the companion-level socket too (same underlying socket as sip, normally),
        // so a real stop actually frees 127.0.0.1:5070 instead of leaving it pre-bound forever.
        try { preboundSip?.close() } catch (_: Throwable) { }
        preboundSip = null
        connected = false
    }

    override fun onDestroy() {
        running = false
        cleanup()
        super.onDestroy()
    }
}

// ---------------------------------------------------------------- SIP utilities

private data class Sip(
    val start: String,
    val headers: List<Pair<String, String>>,
    val body: String,
    val remote: InetSocketAddress
) {
    fun first(name: String): String? = headers.firstOrNull { it.first.equals(name, true) }?.second
    fun all(name: String): List<String> = headers.filter { it.first.equals(name, true) }.map { it.second }
}

private data class Sdp(val port: Int, val payloadType: Int)
private data class RtpPort(val port: Int)

private fun parseSip(raw: String, remote: InetSocketAddress): Sip? {
    val idx = raw.indexOf("\r\n\r\n")
    val head = if (idx >= 0) raw.substring(0, idx) else raw
    val body = if (idx >= 0) raw.substring(idx + 4) else ""
    val lines = head.split("\r\n")
    if (lines.isEmpty()) return null
    val headers = mutableListOf<Pair<String, String>>()
    for (line in lines.drop(1)) {
        if (line.startsWith(" ") || line.startsWith("\t")) {
            if (headers.isNotEmpty()) headers[headers.lastIndex] = headers.last().first to (headers.last().second + " " + line.trim())
        } else {
            val c = line.indexOf(':')
            if (c > 0) headers.add(line.substring(0, c).trim() to line.substring(c + 1).trim())
        }
    }
    return Sip(lines[0], headers, body, remote)
}

private fun buildSip(start: String, headers: List<Pair<String, String>>, body: String): ByteArray {
    val sb = StringBuilder(start).append("\r\n")
    headers.forEach { sb.append(it.first).append(": ").append(it.second).append("\r\n") }
    sb.append("\r\n").append(body)
    return sb.toString().toByteArray(Charsets.UTF_8)
}

private fun uriUser(value: String?): String? {
    val s = value ?: return null
    val inner = if (s.contains("<")) s.substringAfter("<").substringBefore(">") else s
    val at = inner.indexOf('@')
    val sip = inner.indexOf("sip:")
    if (at < 0) return null
    return inner.substring(if (sip >= 0) sip + 4 else 0, at).substringBefore(";").ifBlank { null }
}

private fun tagOf(value: String?): String? =
    value?.substringAfter("tag=", "")?.substringBefore(";")?.ifBlank { null }

private fun randomTag(): String = Random.nextInt(100000, 999999).toString(16)

private fun sdp(port: Int): String = """
v=0
o=- 0 0 IN IP4 127.0.0.1
s=AFIF Bridge
c=IN IP4 127.0.0.1
t=0 0
m=audio $port RTP/AVP 0 8 101
a=rtpmap:0 PCMU/8000
a=rtpmap:8 PCMA/8000
a=rtpmap:101 telephone-event/8000
a=fmtp:101 0-16
a=ptime:20
a=sendrecv
""".trimIndent() + "\r\n"

private fun parseSdp(body: String): Sdp? {
    val port = Regex("m=audio (\\d+) RTP/AVP ([0-9 ]+)").find(body) ?: return null
    val p = port.groupValues[1].toIntOrNull() ?: return null
    val types = port.groupValues[2].trim().split(" ").mapNotNull { it.toIntOrNull() }
    val pt = when {
        types.contains(0) -> 0
        types.contains(8) -> 8
        else -> return null
    }
    return Sdp(p, pt)
}

private class Rtp(private val requestedPort: Int) {
    private var socket: DatagramSocket? = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
    private var remote: InetSocketAddress? = null
    private var payloadType = 0
    private var seq = Random.nextInt(1, 30000)
    private var ts = Random.nextInt(1, 100000)
    private var ssrc = Random.nextInt()
    private var listener: ((ByteArray) -> Unit)? = null
    private var thread: Thread? = null
    private var loggedFirst = false
    val port: Int get() = socket?.localPort ?: requestedPort

    fun start(host: InetAddress, remotePort: Int, pt: Int, onPcm: (ByteArray) -> Unit) {
        remote = InetSocketAddress(host, remotePort)
        payloadType = pt
        listener = onPcm
        if (thread != null) return
        val s = socket ?: return
        thread = Thread {
            val buf = ByteArray(2048)
            while (socket === s && !s.isClosed) {
                try {
                    val p = DatagramPacket(buf, buf.size)
                    s.receive(p)
                    val data = p.data.copyOfRange(0, p.length)
                    if (data.size > 12) decode(data)
                } catch (_: Throwable) { }
            }
        }.also { it.start() }
    }

    fun sendPcm(pcm: ByteArray) {
        val s = socket ?: return
        val r = remote ?: return
        val encoded = if (payloadType == 8) encodeAlaw(pcm) else encodeUlaw(pcm)
        val packet = ByteArray(12 + encoded.size)
        packet[0] = 0x80.toByte()
        packet[1] = payloadType.toByte()
        packet[2] = (seq shr 8).toByte(); packet[3] = seq.toByte()
        packet[4] = (ts shr 24).toByte(); packet[5] = (ts shr 16).toByte()
        packet[6] = (ts shr 8).toByte(); packet[7] = ts.toByte()
        packet[8] = (ssrc shr 24).toByte(); packet[9] = (ssrc shr 16).toByte()
        packet[10] = (ssrc shr 8).toByte(); packet[11] = ssrc.toByte()
        encoded.copyInto(packet, 12)
        seq = (seq + 1) and 0xFFFF
        ts = (ts + 160) and 0xFFFFFFFF.toInt()
        try { s.send(DatagramPacket(packet, packet.size, r)) } catch (_: Throwable) { }
    }

    private fun decode(packet: ByteArray) {
        val pt = packet[1].toInt() and 0x7F
        if (!loggedFirst) {
            loggedFirst = true
            Log.i("AFIF-BRIDGE", "RTP first packet pt=$pt bytes=${packet.size} from=$remote")
        }
        if (pt != 0 && pt != 8) return
        val pcm = if (pt == 8) decodeAlaw(packet.copyOfRange(12, packet.size))
        else decodeUlaw(packet.copyOfRange(12, packet.size))
        listener?.invoke(pcm)
    }

    fun close() {
        try { socket?.close() } catch (_: Throwable) { }
        socket = null
        thread = null
    }
}

private fun encodeUlaw(pcm: ByteArray): ByteArray {
    val out = ByteArray(pcm.size / 2)
    for (i in out.indices) {
        val sample = (pcm[i * 2].toInt() and 0xFF) or (pcm[i * 2 + 1].toInt() shl 8)
        out[i] = ulawEncode(sample.toShort())
    }
    return out
}

private fun decodeUlaw(bytes: ByteArray): ByteArray {
    val out = ByteArray(bytes.size * 2)
    for (i in bytes.indices) {
        val s = ulawDecode(bytes[i])
        out[i * 2] = (s.toInt() and 0xFF).toByte()
        out[i * 2 + 1] = ((s.toInt() shr 8) and 0xFF).toByte()
    }
    return out
}

private fun encodeAlaw(pcm: ByteArray): ByteArray {
    val out = ByteArray(pcm.size / 2)
    for (i in out.indices) {
        val sample = (pcm[i * 2].toInt() and 0xFF) or (pcm[i * 2 + 1].toInt() shl 8)
        out[i] = alawEncode(sample.toShort())
    }
    return out
}

private fun decodeAlaw(bytes: ByteArray): ByteArray {
    val out = ByteArray(bytes.size * 2)
    for (i in bytes.indices) {
        val s = alawDecode(bytes[i])
        out[i * 2] = (s.toInt() and 0xFF).toByte()
        out[i * 2 + 1] = ((s.toInt() shr 8) and 0xFF).toByte()
    }
    return out
}

private fun ulawEncode(sample: Short): Byte {
    var s = sample.toInt()
    val sign = if (s < 0) 0x80 else 0
    if (s < 0) s = -s
    if (s > 32635) s = 32635
    s += 0x84
    var exponent = 7
    var mask = 0x4000
    while (exponent > 0 && (s and mask) == 0) { exponent--; mask = mask shr 1 }
    val mantissa = (s shr (exponent + 3)) and 0x0F
    return (sign or (exponent shl 4) or mantissa).inv().toByte()
}

private fun ulawDecode(b: Byte): Short {
    val u = b.toInt().inv() and 0xFF
    val sign = u and 0x80
    val exponent = (u shr 4) and 0x07
    val mantissa = u and 0x0F
    var sample = ((mantissa shl 3) + 0x84) shl exponent
    sample -= 0x84
    return (if (sign != 0) -sample else sample).toShort()
}

private fun alawEncode(sample: Short): Byte {
    var s = sample.toInt()
    val sign = if (s >= 0) 0x80 else 0
    if (s < 0) s = -s - 1
    if (s > 32767) s = 32767
    var exponent = 7
    var mask = 0x4000
    while (exponent > 0 && (s and mask) == 0) { exponent--; mask = mask shr 1 }
    val mantissa = if (exponent == 0) (s shr 4) and 0x0F else (s shr (exponent + 3)) and 0x0F
    return (sign or (exponent shl 4) or mantissa).toByte()
}

private fun alawDecode(b: Byte): Short {
    val a = b.toInt() and 0xFF
    val sign = a and 0x80
    val exponent = (a shr 4) and 0x07
    var mantissa = a and 0x0F
    var sample = if (exponent == 0) (mantissa shl 4) + 8 else ((mantissa shl 3) + 0x108) shl (exponent - 1)
    if (sign == 0) sample = -sample
    return sample.toShort()
}
