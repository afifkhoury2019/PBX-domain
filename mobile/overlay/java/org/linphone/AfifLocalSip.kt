/*
 * AFIF Phone — point the existing Linphone SIP account at the local bridge.
 * GPLv3, part of AFIF Phone (based on Linphone).
 */
package org.linphone

import android.content.Context
import org.linphone.core.Account
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
            // A phone upgraded from the old setup can still have its direct SIP account
            // registered to the PBX alongside the local bridge. That makes the PBX ring
            // the same extension twice (sip:100 and ws:100); declining one leg leaves the
            // other one alive, which showed up as an immediate drop / stray Resume button.
            val keep = core.defaultAccount
            for (account in core.accountList.toList()) {
                if (account != keep) {
                    try { core.removeAccount(account) } catch (_: Throwable) {}
                }
            }
            rewriteFriendDomains(core)
        } catch (e: Throwable) {
            org.linphone.core.tools.Log.e("[AFIF] Local SIP config failed: $e")
        }
    }

    /**
     * Contacts imported from the old SIP account often carry a server domain such as
     * "buff.pbx". Linphone then sends the call straight to that domain instead of the
     * local bridge. Rewrite every non-local friend to the bridge so "My Home" and other
     * saved contacts use the WebSocket transport too.
     */
    private fun rewriteFriendDomains(core: Core) {
        try {
            for (friend in core.friendList) {
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
