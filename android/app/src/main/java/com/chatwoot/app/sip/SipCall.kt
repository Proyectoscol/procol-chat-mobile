package com.chatwoot.app.sip

import android.util.Log
import org.pjsip.pjsua2.*

private const val TAG = "SipCall"

/**
 * PJSUA2 Call subclass. Bridges PJSIP call events to SipEngine callbacks.
 *
 * Audio routing:
 * - Incoming audio from Asterisk arrives on the remote audio media stream.
 * - We connect it to the device speaker/earpiece via AudDevManager.
 * - Microphone is connected to the transmit leg.
 */
class SipCall(
    account: Account,
    callId: Int,
    private val engine: SipEngine,
) : Call(account, callId) {

    private var startTimestamp: Long = 0L

    override fun onCallState(prm: OnCallStateParam) {
        val info = try { info } catch (e: Exception) {
            Log.e(TAG, "onCallState: failed to get call info", e)
            return
        }
        val callIdStr = id.toString()
        Log.d(TAG, "onCallState: state=${info.stateText} lastStatus=${info.lastStatusCode}")

        when (info.state) {
            pjsip_inv_state.PJSIP_INV_STATE_CONFIRMED -> {
                startTimestamp = System.currentTimeMillis()
                engine.onCallConnected(callIdStr)
            }
            pjsip_inv_state.PJSIP_INV_STATE_DISCONNECTED -> {
                val durationSecs = if (startTimestamp > 0) {
                    ((System.currentTimeMillis() - startTimestamp) / 1000).toInt()
                } else 0
                val statusCode = info.lastStatusCode.swigValue()
                if (statusCode in 400..699) {
                    engine.onCallFailed(callIdStr, info.lastReason)
                } else {
                    engine.onCallEnded(callIdStr, info.lastReason, durationSecs)
                }
            }
            else -> {}
        }
    }

    override fun onCallMediaState(prm: OnCallMediaStateParam) {
        val info = try { info } catch (e: Exception) {
            Log.e(TAG, "onCallMediaState: failed to get call info", e)
            return
        }
        for (i in 0 until info.media.size().toInt()) {
            val media = info.media[i.toLong()]
            if (media.type == pjmedia_type.PJMEDIA_TYPE_AUDIO &&
                media.status == pjsua_call_media_status.PJSUA_CALL_MEDIA_ACTIVE
            ) {
                try {
                    val audioMedia = getMedia(i.toLong()) as? AudioMedia ?: continue
                    // Connect microphone -> call transmit
                    Endpoint.instance().audDevManager().captureDevMedia
                        .startTransmit(audioMedia)
                    // Connect call receive -> speaker
                    audioMedia.startTransmit(
                        Endpoint.instance().audDevManager().playbackDevMedia
                    )
                    Log.i(TAG, "Audio media active on stream $i")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to route audio on stream $i", e)
                }
            }
        }
    }
}
