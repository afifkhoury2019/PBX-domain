/*
 * AFIF Phone — small dialer for the direct domain transport.
 * GPLv3, part of AFIF Phone (based on Linphone).
 */
package org.linphone

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

class AfifDomainDialActivity : Activity() {
    private lateinit var number: EditText
    private lateinit var info: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (24 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(pad, pad * 2, pad, pad)
            setBackgroundColor(Color.rgb(7, 13, 29))
        }
        val title = TextView(this).apply {
            text = "AFIF Direct"
            textSize = 28f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        info = TextView(this).apply {
            text = if (AfifTransport.isDomain(this@AfifDomainDialActivity))
                "Direct domain · no VPN" else "Switch Connection to Domain in Settings"
            textSize = 14f
            setTextColor(Color.rgb(148, 163, 184))
            gravity = Gravity.CENTER
            setPadding(0, pad / 2, 0, pad)
        }
        number = EditText(this).apply {
            hint = "Number or extension"
            inputType = InputType.TYPE_CLASS_PHONE
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(100, 116, 139))
        }
        val call = Button(this).apply {
            text = "Call"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(16, 185, 129))
            setOnClickListener { placeCall() }
        }
        val config = Button(this).apply {
            text = "Connection settings"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(30, 41, 59))
            setOnClickListener {
                AfifTransport.configure(this@AfifDomainDialActivity) { refreshInfo() }
            }
        }
        val messages = Button(this).apply {
            text = "Messages"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(30, 41, 59))
            setOnClickListener {
                startActivity(android.content.Intent(this@AfifDomainDialActivity, AfifDomainMessagesActivity::class.java))
            }
        }
        val close = Button(this).apply {
            text = "Close"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(30, 41, 59))
            setOnClickListener { finish() }
        }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, (10 * resources.displayMetrics.density).toInt(), 0, 0) }
        root.addView(title)
        root.addView(info)
        root.addView(number, lp)
        root.addView(call, lp)
        root.addView(messages, lp)
        root.addView(config, lp)
        root.addView(close, lp)
        setContentView(root)
    }

    private fun refreshInfo() {
        info.text = if (AfifTransport.isDomain(this)) "Direct domain · no VPN"
        else "Switch Connection to Domain in Settings"
    }

    private fun placeCall() {
        val text = number.text.toString().trim()
        if (text.isEmpty()) return
        if (AfifTransport.secret(this).isEmpty()) {
            AfifTransport.configure(this) { startCall(text) }
        } else {
            startCall(text)
        }
    }

    private fun startCall(number: String) {
        AfifTransport.setMode(this, AfifTransport.DOMAIN)
        AfifDomainService.start(this)
        AfifDomainService.dial(this, number)
    }
}
