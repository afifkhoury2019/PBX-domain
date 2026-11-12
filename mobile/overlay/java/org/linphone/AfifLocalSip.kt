/*
 * AFIF Phone — point the existing Linphone SIP account at the local bridge.
 * GPLv3, part of AFIF Phone (based on Linphone).
 */
package org.linphone

import android.content.Context
import org.linphone.core.Core
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
                p.transport = TransportType.Udp
                p.isOutboundProxyEnabled = false
                existing.params = p
                core.addAuthInfo(
                    Factory.instance().createAuthInfo(extension, null, secret, null, null, HOST)
                )
                existing.refreshRegister()
                rewriteContactDomains(core)
            } else {
                val p = core.createAccountParams()
                p.identityAddress = identity
                p.serverAddress = server
                p.transport = TransportType.Udp
                p.isOutboundProxyEnabled = false
                val account = core.createAccount(p)
                core.addAccount(account)
                core.defaultAccount = account
                core.addAuthInfo(
                    Factory.instance().createAuthInfo(extension, null, secret, null, null, HOST)
                )
                core.refreshRegisters()
            }
        } catch (e: Throwable) {
            org.linphone.core.tools.Log.e("[AFIF] Local SIP config failed: $e")
        }
    }

    /**
     * Contacts imported from the old SIP account often carry a server domain such as
     * "buff.pbx". Linphone then sends the call straight to that domain instead of the
     * local bridge. Rewrite every non-local contact to the bridge so calls, including
     * "My Home", actually go through the WebSocket transport.
     */
    private fun rewriteContactDomains(core: Core) {
        try {
            for (friend in core.friends) {
                val address = friend.address ?: continue
                val user = address.username ?: continue
                val domain = address.domain.orEmpty()
                if (domain.isNotEmpty() && domain != HOST && domain != "$HOST:$PORT") {
                    friend.address = Factory.instance().createAddress("sip:$user@$HOST:$PORT")
                }
            }
        } catch (e: Throwable) {
            org.linphone.core.tools.Log.e("[AFIF] Contact domain rewrite failed: $e")
        }
    }
}