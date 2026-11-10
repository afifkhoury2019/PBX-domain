package org.linphone

import org.linphone.core.Core

/**
 * Accounts created before the Jordan dial-plan default (see
 * ThirdPartySipAccountLoginViewModel's login()) was added have no
 * internationalPrefix set, so Linphone can never turn a locally-dialled number
 * like "0799031745" into "+962799031745" to match it against a saved phone
 * contact - incoming calls from a saved contact show only the raw number
 * instead of their name. This fixes it once, for every account that still
 * lacks a prefix, without touching anything else about the account.
 */
object AfifDialPlanMigration {
    fun applyIfNeeded(core: Core) {
        for (account in core.accountList) {
            val params = account.params
            if (!params.internationalPrefix.isNullOrEmpty()) continue
            try {
                val clone = params.clone()
                clone.internationalPrefix = "962"
                clone.internationalPrefixIsoCountryCode = "JO"
                account.params = clone
                org.linphone.core.tools.Log.i(
                    "[AFIF] Set default Jordan dial prefix on account ${params.identityAddress?.asStringUriOnly()}"
                )
            } catch (e: Throwable) {
                org.linphone.core.tools.Log.e("[AFIF] Dial plan migration failed: $e")
            }
        }
    }
}
