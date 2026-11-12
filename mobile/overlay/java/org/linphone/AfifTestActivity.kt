package org.linphone

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import org.linphone.core.Factory

/** Temporary automation entry point for the emulator bridge test. */
class AfifTestActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ext = intent.getStringExtra("ext").orEmpty()
        val secret = intent.getStringExtra("secret").orEmpty()
        val number = intent.getStringExtra("number") ?: "*43"
        if (ext.isBlank() || secret.isBlank()) { finish(); return }
        AfifTransport.saveCredentials(this, ext, secret)
        AfifTransport.setMode(this, AfifTransport.DOMAIN)
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                val core = LinphoneApplication.coreContext.core
                val address = Factory.instance().createAddress("sip:$number@127.0.0.1:5070")
                core.invite(address)
            } catch (e: Throwable) {
                org.linphone.core.tools.Log.e("[AFIF TEST] invite failed: $e")
            }
        }, 4000)
    }
}
