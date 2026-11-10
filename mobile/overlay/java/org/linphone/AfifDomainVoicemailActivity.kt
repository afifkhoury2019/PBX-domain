/*
 * AFIF Phone — direct domain voicemail list.
 * GPLv3, part of AFIF Phone (based on Linphone).
 */
package org.linphone

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class AfifDomainVoicemailActivity : Activity() {
    private val tick = Handler(Looper.getMainLooper())
    private lateinit var list: LinearLayout
    private lateinit var status: TextView

    private val refresh = object : Runnable {
        override fun run() {
            render()
            tick.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Color.rgb(7, 13, 29))
        }
        val title = TextView(this).apply {
            text = "Voicemail"
            textSize = 24f
            setTextColor(Color.WHITE)
        }
        status = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.rgb(148, 163, 184))
        }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(list) }
        val back = Button(this).apply {
            text = "Back"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(30, 41, 59))
            setOnClickListener { finish() }
        }
        root.addView(title)
        root.addView(status)
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        root.addView(back)
        setContentView(root)
        if (AfifTransport.isDomain(this)) AfifDomainService.start(this)
        AfifDomainService.requestVoicemails(this)
        tick.post(refresh)
    }

    private fun render() {
        status.text = AfifDomainService.voicemailError.ifBlank {
            if (AfifDomainService.voicemails.isEmpty()) "No voicemail"
            else "${AfifDomainService.voicemails.size} message(s) — tap to play"
        }
        list.removeAllViews()
        val pad = (10 * resources.displayMetrics.density).toInt()
        for (v in AfifDomainService.voicemails) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, pad, pad, pad)
                setBackgroundColor(if (v.heard) Color.rgb(15, 23, 42) else Color.rgb(30, 41, 59))
                addView(TextView(this@AfifDomainVoicemailActivity).apply {
                    text = v.from.ifBlank { "Unknown caller" }
                    setTextColor(Color.WHITE)
                    textSize = 16f
                })
                addView(TextView(this@AfifDomainVoicemailActivity).apply {
                    text = "${v.duration}s · ${v.time}"
                    setTextColor(Color.rgb(148, 163, 184))
                    textSize = 12f
                })
                setOnClickListener {
                    AfifDomainService.requestVoicemailAudio(this@AfifDomainVoicemailActivity, v.id)
                    status.text = "Loading voicemail…"
                }
            }
            list.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 4, 0, 4) })
        }
    }

    override fun onDestroy() {
        tick.removeCallbacks(refresh)
        super.onDestroy()
    }
}
