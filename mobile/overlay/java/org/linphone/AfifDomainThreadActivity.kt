/*
 * AFIF Phone — per-contact SMS thread for the direct domain transport.
 * GPLv3, part of AFIF Phone (based on Linphone).
 */
package org.linphone

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class AfifDomainThreadActivity : Activity() {
    private val tick = Handler(Looper.getMainLooper())
    private lateinit var address: String
    private lateinit var list: LinearLayout
    private lateinit var input: EditText

    private val refresh = object : Runnable {
        override fun run() {
            render()
            tick.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        address = intent.getStringExtra("address").orEmpty()
        val pad = (14 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Color.rgb(7, 13, 29))
        }
        val title = TextView(this).apply {
            text = address
            textSize = 22f
            setTextColor(Color.WHITE)
        }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(list) }
        input = EditText(this).apply {
            hint = "Message"
            inputType = InputType.TYPE_CLASS_TEXT
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(100, 116, 139))
            minLines = 2
        }
        val send = Button(this).apply {
            text = "Send"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(16, 185, 129))
            setOnClickListener { send() }
        }
        val back = Button(this).apply {
            text = "Back"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(30, 41, 59))
            setOnClickListener { finish() }
        }
        root.addView(title)
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        root.addView(input)
        root.addView(send)
        root.addView(back)
        setContentView(root)
        AfifTheme.applyChat(root)
        tick.post(refresh)
    }

    private fun send() {
        val body = input.text.toString().trim()
        if (body.isEmpty() || address.isEmpty()) return
        AfifTransport.setMode(this, AfifTransport.DOMAIN)
        AfifDomainService.start(this)
        AfifDomainService.sendSms(this, address, body)
        input.setText("")
        render()
    }

    private fun render() {
        list.removeAllViews()
        val pad = (8 * resources.displayMetrics.density).toInt()
        for (m in AfifSmsStore.thread(this, address)) {
            val row = TextView(this).apply {
                text = "${if (m.outgoing) "→" else "←"} ${m.text}\n${AfifSmsStore.fmt(m.time)}" +
                    if (m.outgoing) " · ${m.status}" else ""
                textSize = 14f
                setTextColor(Color.WHITE)
                setPadding(pad, pad, pad, pad)
            }
            AfifTheme.applyBubble(row, m.outgoing)
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
