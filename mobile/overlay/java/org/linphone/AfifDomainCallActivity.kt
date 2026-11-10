/*
 * AFIF Phone — call screen for the direct domain transport.
 * GPLv3, part of AFIF Phone (based on Linphone).
 */
package org.linphone

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class AfifDomainCallActivity : Activity() {
    private val tick = Handler(Looper.getMainLooper())
    private lateinit var title: TextView
    private lateinit var status: TextView
    private lateinit var answer: Button
    private lateinit var decline: Button
    private lateinit var end: Button
    private lateinit var mute: Button
    private lateinit var speaker: Button

    private val refresh = object : Runnable {
        override fun run() {
            render()
            tick.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )
        val number = intent.getStringExtra("number").orEmpty()
        val name = intent.getStringExtra("name").orEmpty()
        setContentView(buildUi(if (name.isNotBlank()) name else number, number))
        render()
        tick.post(refresh)
    }

    override fun onNewIntent(intent: android.content.Intent?) {
        super.onNewIntent(intent)
        if (intent != null) setIntent(intent)
    }

    private fun buildUi(label: String, number: String): View {
        val pad = (24 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(pad, pad * 2, pad, pad)
            setBackgroundColor(Color.rgb(7, 13, 29))
        }
        title = TextView(this).apply {
            text = label
            textSize = 26f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        status = TextView(this).apply {
            text = number
            textSize = 15f
            setTextColor(Color.rgb(148, 163, 184))
            gravity = Gravity.CENTER
            setPadding(0, pad / 2, 0, pad)
        }
        answer = button("Answer", Color.rgb(16, 185, 129)) { AfifDomainService.answer(this) }
        decline = button("Decline", Color.rgb(239, 68, 68)) { hangupAndFinish() }
        end = button("End call", Color.rgb(239, 68, 68)) { hangupAndFinish() }
        mute = button("Mute", Color.rgb(30, 41, 59)) {
            AfifDomainService.setMuted(this, !AfifDomainService.muted)
            render()
        }
        speaker = button("Speaker", Color.rgb(30, 41, 59)) {
            AfifDomainService.setSpeaker(this, !AfifDomainService.speaker)
            render()
        }
        root.addView(title)
        root.addView(status)
        root.addView(answer, params())
        root.addView(decline, params())
        root.addView(end, params())
        root.addView(mute, params())
        root.addView(speaker, params())
        return root
    }

    private fun params(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            (52 * resources.displayMetrics.density).toInt()
        ).apply { setMargins(0, (8 * resources.displayMetrics.density).toInt(), 0, 0) }

    private fun button(label: String, color: Int, click: () -> Unit): Button =
        Button(this).apply {
            text = label
            setTextColor(Color.WHITE)
            setBackgroundColor(color)
            textSize = 16f
            setOnClickListener { click() }
        }

    private fun hangupAndFinish() {
        AfifDomainService.hangup(this)
        finish()
    }

    private fun render() {
        if (!AfifDomainService.inCall) {
            finish()
            return
        }
        val incoming = AfifDomainService.incoming
        val answered = AfifDomainService.answered
        status.text = when {
            answered -> "In call"
            incoming -> "Incoming call"
            AfifDomainService.connected -> "Calling…"
            else -> "Connecting…"
        }
        answer.visibility = if (incoming && !answered) View.VISIBLE else View.GONE
        decline.visibility = if (incoming && !answered) View.VISIBLE else View.GONE
        end.visibility = if (answered || !incoming) View.VISIBLE else View.GONE
        mute.visibility = if (answered) View.VISIBLE else View.GONE
        speaker.visibility = if (answered) View.VISIBLE else View.GONE
        mute.text = if (AfifDomainService.muted) "Unmute" else "Mute"
        speaker.text = if (AfifDomainService.speaker) "Earpiece" else "Speaker"
    }

    override fun onDestroy() {
        tick.removeCallbacks(refresh)
        super.onDestroy()
    }

    override fun onBackPressed() {
        if (AfifDomainService.inCall) hangupAndFinish() else super.onBackPressed()
    }
}
