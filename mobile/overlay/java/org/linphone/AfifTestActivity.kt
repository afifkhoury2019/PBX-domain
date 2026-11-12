package org.linphone

import android.app.Activity
import android.os.Bundle
import org.linphone.core.Call
import org.linphone.core.Core
import org.linphone.core.CoreListenerStub

/** Temporary emulator entry point: configure the bridge and auto-answer an incoming call. */
class AfifTestActivity : Activity() {
    private val listener = object : CoreListenerStub() {
        override fun onCallStateChanged(core: Core, call: Call, state: Call.State?, message: String) {
            if (state == Call.State.IncomingReceived) {
                org.linphone.core.tools.Log.i("[AFIF TEST] incoming call, answering")
                call.accept()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ext = intent.getStringExtra("ext").orEmpty()
        val secret = intent.getStringExtra("secret").orEmpty()
        if (ext.isBlank() || secret.isBlank()) { finish(); return }
        AfifTransport.saveCredentials(this, ext, secret)
        AfifTransport.setMode(this, AfifTransport.DOMAIN)
        LinphoneApplication.coreContext.core.addListener(listener)
        org.linphone.core.tools.Log.i("[AFIF TEST] armed for incoming call on $ext")
    }

    override fun onDestroy() {
        try { LinphoneApplication.coreContext.core.removeListener(listener) } catch (_: Throwable) {}
        super.onDestroy()
    }
}