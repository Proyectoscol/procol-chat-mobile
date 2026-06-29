package com.chatwoot.app.sip

import android.util.Log
import org.pjsip.pjsua2.*

private const val TAG = "SipAccount"

/**
 * PJSUA2 Account subclass. All callbacks fire on the PJSIP thread;
 * we delegate immediately to SipEngine which owns the lifecycle.
 */
class SipAccount(
    private val engine: SipEngine,
    private val extension: String,
) : Account() {

    override fun onRegState(prm: OnRegStateParam) {
        val info = try { info } catch (e: Exception) {
            Log.e(TAG, "onRegState: failed to get account info", e)
            return
        }
        val status = prm.code
        Log.d(TAG, "onRegState: status=$status active=${info.regIsActive}")

        when {
            info.regIsActive -> engine.onAccountRegistered(extension)
            status in 200..299 -> engine.onAccountUnregistered(extension)
            else -> engine.onAccountRegistrationFailed(prm.reason, status)
        }
    }

    override fun onIncomingCall(prm: OnIncomingCallParam) {
        Log.i(TAG, "Incoming call: callId=${prm.callId}")
        try {
            val call = SipCall(this, prm.callId, engine)
            // Send 180 Ringing immediately. Without this PJSIP auto-declines with 480.
            call.answer(CallOpParam().apply {
                statusCode = pjsip_status_code.PJSIP_SC_RINGING
            })
            val info = call.info
            val remoteUri = info.remoteUri
            val callerName = extractDisplayName(remoteUri)
            val callerId = extractUser(remoteUri)
            engine.onCallIncoming(call, callerId, callerName)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle incoming call", e)
        }
    }

    private fun extractDisplayName(uri: String): String =
        Regex("\"([^\"]+)\"").find(uri)?.groupValues?.get(1)
            ?: uri.substringBefore("<").trim().ifEmpty { extractUser(uri) }

    private fun extractUser(uri: String): String =
        Regex("sip:([^@>]+)").find(uri)?.groupValues?.get(1) ?: uri
}
