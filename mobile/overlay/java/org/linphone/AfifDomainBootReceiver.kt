/*
 * AFIF Phone — restart the direct domain service after reboot.
 * GPLv3, part of AFIF Phone (based on Linphone).
 */
package org.linphone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AfifDomainBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            AfifTransport.bootstrap(context)
        }
    }
}
