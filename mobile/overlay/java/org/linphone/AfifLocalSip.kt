/*
 * AFIF Phone — point the existing Linphone SIP account at the local bridge.
 * GPLv3, part of AFIF Phone (based on Linphone).
 */
package org.linphone

import android.content.Context
import org.linphone.core.Factory
import org.linphone.core.TransportType

object AfifLocalSip {
    const val HOST = "127.0.0.1"
    const val PORT = 5070

    fun configure(context: Context) {
        val extension = AfifTransport.extension(context)
        val secret = AfifTransport.secret(context)
        if (extension.isBlank() || secret.isBlank()) return
        try {
            val core = LinphoneApplication.coreContext.core
            val identity = Factory.instance().createAddress("sip:$extension@$HOST")
            val server = Factory.instance().createAddress("sip:$HOST:$PORT;transport=udp")
            val existing = core.defaultAccount
            if (existing != null) {
                val p = existing.params
                p.identityAddress = identity
                p.serverAddress = server
                p.password = secret
                p.transport = TransportType.Udp
                p.isOutboundProxyEnabled = false
                existing.params = p
                existing.refreshRegister()
            } else {
                val p = core.createAccountParams()
                p.identityAddress = identity
                p.serverAddress = server
                p.password = secret
                p.transport = TransportType.Udp
                p.isOutboundProxyEnabled = false
                val account = core.createAccount(p)
                core.addAccount(account)
                core.defaultAccount = account
                core.refreshRegisters()
            }
        } catch (e: Throwable) {
            org.linphone.core.tools.Log.e("[AFIF] Local SIP config failed: $e")
        }
    }
}
