/*
 * AFIF Phone — standalone AFIF Direct home: calls, messages, contacts, history.
 * GPLv3, part of AFIF Phone (based on Linphone).
 */
package org.linphone

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.provider.ContactsContract
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class AfifDomainMainActivity : Activity() {
    private lateinit var list: LinearLayout
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), 41)
        }
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Color.rgb(7, 13, 29))
        }
        val title = TextView(this).apply {
            text = "AFIF Direct"
            textSize = 26f
            setTextColor(Color.WHITE)
        }
        status = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.rgb(148, 163, 184))
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(action("Dialer") {
            startActivity(android.content.Intent(this, AfifDomainDialActivity::class.java))
        }, weight())
        row.addView(action("Messages") {
            startActivity(android.content.Intent(this, AfifDomainMessagesActivity::class.java))
        }, weight())
        row.addView(action("Connection") {
            AfifTransport.configure(this) { refresh() }
        }, weight())
        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row2.addView(action("Theme") { chooseTheme() }, weight())
        row2.addView(action("Voicemail") {
            startActivity(android.content.Intent(this, AfifDomainVoicemailActivity::class.java))
        }, weight())
        row2.addView(action("Info") { showInfo() }, weight())

        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(list) }
        root.addView(title)
        root.addView(status)
        root.addView(row)
        root.addView(row2)
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        setContentView(root)
        if (AfifTransport.isDomain(this)) {
            AfifDomainService.start(this)
            AfifBackground.checkDomain(this)
        }
        refresh()
    }

    private fun weight(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

    private fun action(label: String, run: () -> Unit): Button =
        Button(this).apply {
            text = label
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(30, 41, 59))
            setOnClickListener { run() }
        }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
    }

    private fun refresh() {
        status.text = when {
            !AfifTransport.isDomain(this) -> "Tailscale mode — switch to Domain in Connection"
            AfifDomainService.connected -> "Domain connected · ${AfifTransport.host(this)}"
            else -> "Domain connecting…"
        }
        list.removeAllViews()
        val title = TextView(this).apply {
            text = "Recent calls"
            textSize = 13f
            setTextColor(Color.rgb(148, 163, 184))
            setPadding(0, 16, 0, 6)
        }
        list.addView(title)
        for (c in AfifCallStore.all(this).reversed().take(20)) {
            list.addView(entry(
                (c.name.ifBlank { c.address }) + "  " + when {
                    c.outgoing -> "→"
                    c.answered -> "←"
                    else -> "missed"
                },
                c.address + if (c.duration > 0) " · ${c.duration}s" else ""
            ) {
                call(c.address)
            })
        }
        val contactsTitle = TextView(this).apply {
            text = "Contacts"
            textSize = 13f
            setTextColor(Color.rgb(148, 163, 184))
            setPadding(0, 16, 0, 6)
        }
        list.addView(contactsTitle)
        for ((name, number) in contacts()) {
            list.addView(entry(name, number) { call(number) })
        }
        if (list.childCount <= 2) {
            list.addView(TextView(this).apply {
                text = "No calls or contacts yet."
                setTextColor(Color.rgb(100, 116, 139))
                setPadding(0, 12, 0, 0)
            })
        }
    }

    private fun entry(title: String, sub: String, click: () -> Unit): View {
        val pad = (10 * resources.displayMetrics.density).toInt()
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Color.rgb(15, 23, 42))
            addView(TextView(this@AfifDomainMainActivity).apply {
                text = title
                setTextColor(Color.WHITE)
                textSize = 16f
            })
            addView(TextView(this@AfifDomainMainActivity).apply {
                text = sub
                setTextColor(Color.rgb(148, 163, 184))
                textSize = 12f
            })
            setOnClickListener { click() }
        }
    }

    private fun showInfo() {
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (_: Throwable) { "?" }
        val text = """
            Host: ${AfifTransport.host(this)}
            Extension: ${AfifTransport.extension(this)}
            Connected: ${AfifDomainService.connected}
            Last error: ${AfifDomainService.lastError.ifBlank { "none" }}
            App version: $version
            Transport: ${if (AfifTransport.isDomain(this)) "Domain WebSocket" else "Tailscale SIP"}
        """.trimIndent()
        android.app.AlertDialog.Builder(this)
            .setTitle("AFIF Direct diagnostics")
            .setMessage(text)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun chooseTheme() {
        val labels = AfifTheme.labels()
        android.app.AlertDialog.Builder(this)
            .setTitle("Call and message theme")
            .setSingleChoiceItems(labels, AfifTheme.index(this)) { d, which ->
                AfifTheme.setIndex(this, which)
                AfifTheme.setMessageIndex(this, which)
                d.dismiss()
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun call(number: String) {
        AfifTransport.setMode(this, AfifTransport.DOMAIN)
        AfifDomainService.start(this)
        AfifDomainService.dial(this, number)
    }

    private fun contacts(): List<Pair<String, String>> {
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED)
            return emptyList()
        val out = mutableListOf<Pair<String, String>>()
        try {
            contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                null, null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
            )?.use { c ->
                val ni = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val pi = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (c.moveToNext() && out.size < 200) {
                    val n = if (ni >= 0) c.getString(ni).orEmpty() else ""
                    val p = if (pi >= 0) c.getString(pi).orEmpty() else ""
                    if (p.isNotBlank()) out.add(n to p)
                }
            }
        } catch (_: Throwable) { }
        return out
    }
}
