/*
 * AFIF Phone — direct domain SMS screen.
 * GPLv3, part of AFIF Phone (based on Linphone).
 */
package org.linphone

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class AfifDomainMessagesActivity : Activity() {
    private val tick = Handler(Looper.getMainLooper())
    private lateinit var list: LinearLayout
    private lateinit var to: EditText
    private lateinit var text: EditText

    private val refresh = object : Runnable {
        override fun run() {
            render()
            tick.postDelayed(this, 1500)
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
            text = "Messages"
            textSize = 24f
            setTextColor(Color.WHITE)
        }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(list) }

        to = EditText(this).apply {
            hint = "To (number or extension)"
            inputType = InputType.TYPE_CLASS_PHONE
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(100, 116, 139))
        }
        text = EditText(this).apply {
            hint = "Message"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(100, 116, 139))
            minLines = 2
        }
        val send = Button(this).apply {
            this.text = "Send"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(16, 185, 129))
            setOnClickListener { sendMessage() }
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
        root.addView(to)
        root.addView(text)
        root.addView(send)
        root.addView(back)
        setContentView(root)
        AfifTheme.applyChat(root)
        tick.post(refresh)
    }

    private fun sendMessage() {
        val dest = to.text.toString().trim()
        val body = text.text.toString().trim()
        if (dest.isEmpty() || body.isEmpty()) return
        AfifTransport.setMode(this, AfifTransport.DOMAIN)
        AfifDomainService.start(this)
        AfifDomainService.sendSms(this, dest, body)
        text.setText("")
        render()
    }

    private fun render() {
        list.removeAllViews()
        val pad = (8 * resources.displayMetrics.density).toInt()
        for (m in AfifSmsStore.all(this)) {
            val row = TextView(this).apply {
                text = "${if (m.outgoing) "→" else "←"} ${m.address}\n${m.text}\n${AfifSmsStore.fmt(m.time)}"
                textSize = 14f
                setTextColor(Color.WHITE)
                setPadding(pad, pad, pad, pad)
                setBackgroundColor(
                    if (m.outgoing) Color.rgb(16, 70, 60) else Color.rgb(30, 41, 59)
                )
            }
            AfifTheme.applyBubble(row, m.outgoing)
            row.setOnClickListener {
                startActivity(
                    android.content.Intent(this, AfifDomainThreadActivity::class.java)
                        .putExtra("address", m.address)
                )
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
