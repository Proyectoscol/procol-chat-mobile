package com.procol.chat.sip

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

private const val TAG = "SipBootReceiver"

/**
 * Relaunches SipService after device reboot using saved credentials.
 * Requires RECEIVE_BOOT_COMPLETED permission (already in Manifest).
 */
class SipBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != "android.intent.action.QUICKBOOT_POWERON"
        ) return

        val credentialsJson = SipCredentialsStore.read(context)
        if (credentialsJson == null) {
            Log.d(TAG, "No saved credentials — skipping SIP restart after boot")
            return
        }

        Log.i(TAG, "Device booted — restarting SipService")
        val serviceIntent = Intent(context, SipService::class.java).apply {
            action = SipService.ACTION_REGISTER
            putExtra(SipService.EXTRA_CREDENTIALS_JSON, credentialsJson)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }
}
