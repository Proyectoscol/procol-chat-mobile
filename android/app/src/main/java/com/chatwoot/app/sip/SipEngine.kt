package com.chatwoot.app.sip

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.pjsip.pjsua2.*

private const val TAG = "SipEngine"

/**
 * Thin wrapper around the PJSUA2 Java bindings.
 *
 * Lifetime: created once by SipService when it starts; destroyed when the service stops.
 * All public methods are called from the service thread (Android main thread).
 *
 * PJSIP WebSocket/WSS transport notes:
 * - Asterisk listens on wss://<host>:8089/ws (pjsip.webrtc.conf: transport=transport-wss)
 * - We create a PJSIP_TRANSPORT_TLS transport for outgoing TLS. The actual WebSocket
 *   upgrade happens when the outbound proxy param includes ;transport=wss - PJSIP 2.12+
 *   supports this natively in the WebSocket transport module.
 * - If the PJSIP build was compiled without PJSIP_HAS_TLS_TRANSPORT or without WebSocket
 *   support, the transport creation will throw and SipEngine will log a clear error.
 *
 * WebRTC/DTLS notes (must match pjsip.webrtc.conf.example):
 * - media_encryption=dtls  -> PJMEDIA_SRTP_MANDATORY + DTLS fingerprint in SDP
 * - use_avpf=yes           -> PJMEDIA_SDP_NEG_FLAG_ALLOW_ASYM_PTIME (RTP/SAVPF profile)
 * - ice_support=yes        -> ICE negotiation enabled in EpConfig
 * - rtcp_mux=yes           -> handled by PJSIP media layer automatically with WebRTC build
 * - dtls_setup=actpass     -> PJSIP offers actpass by default with DTLS; Asterisk accepts
 */
class SipEngine(
    private val context: Context,
    private val listener: Listener,
) {

    interface Listener {
        fun onRegistered(extension: String)
        fun onUnregistered(extension: String)
        fun onRegistrationFailed(reason: String, code: Int)
        fun onIncomingCall(callId: String, callerId: String, callerName: String)
        fun onCallConnected(callId: String)
        fun onCallEnded(callId: String, reason: String, durationSeconds: Int)
        fun onCallFailed(callId: String, reason: String)
    }

    private val endpoint = Endpoint()
    private var account: SipAccount? = null
    private var activeCall: SipCall? = null
    private var credentials: SipCredentials? = null

    data class SipCredentials(
        val extension: String,
        val password: String,
        val wssUrl: String,
        val domain: String,
        val iceServers: List<IceServerConfig>,
    )

    data class IceServerConfig(
        val urls: List<String>,
        val username: String?,
        val credential: String?,
    )

    // ── Lifecycle ────────────────────────────────────────────────────────────

    fun start(credentialsJson: String) {
        val creds = parseCredentials(credentialsJson)
        this.credentials = creds

        try {
            endpoint.libCreate()

            val epConfig = EpConfig().apply {
                // ICE: mandatory for WebRTC interop with Asterisk
                medConfig.iceConfig.enable = pj_constants_.PJ_TRUE

                // STUN server from ice_servers[0] (STUN entry)
                val stunUrl = creds.iceServers
                    .flatMap { it.urls }
                    .firstOrNull { it.startsWith("stun:") }
                if (stunUrl != null) {
                    val stunHost = stunUrl.removePrefix("stun:")
                    medConfig.stunServer.add(stunHost)
                }

                // Log level: in release builds reduce noise; in debug keep verbose
                logConfig.level = if (android.util.Log.isLoggable(TAG, android.util.Log.DEBUG)) 5 else 2
                logConfig.consoleLevel = logConfig.level
            }
            endpoint.libInit(epConfig)

            // WSS transport for connecting to Asterisk wss://<host>:8089/ws
            val tCfg = TransportConfig().apply {
                // Port 0 = let the OS choose an ephemeral local port
                port = 0
            }
            try {
                endpoint.transportCreate(pjsip_transport_type_e.PJSIP_TRANSPORT_WSS, tCfg)
                Log.i(TAG, "WSS transport created")
            } catch (e: Exception) {
                // Fallback: TLS transport. The outbound proxy ;transport=wss param may still
                // trigger WebSocket upgrade on some PJSIP builds.
                Log.w(TAG, "WSS transport unavailable, falling back to TLS: ${e.message}")
                endpoint.transportCreate(pjsip_transport_type_e.PJSIP_TRANSPORT_TLS, tCfg)
            }

            endpoint.libStart()
            Log.i(TAG, "PJSIP endpoint started")

            createAccount(creds)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start PJSIP endpoint", e)
            listener.onRegistrationFailed("Engine start failed: ${e.message}", -1)
        }
    }

    fun stop() {
        try {
            activeCall?.hangup(CallOpParam(true))
        } catch (_: Exception) {}
        activeCall = null

        try {
            account?.shutdown()
            account?.delete()
        } catch (_: Exception) {}
        account = null

        try {
            endpoint.libDestroy()
        } catch (_: Exception) {}
        Log.i(TAG, "PJSIP endpoint stopped")
    }

    // ── Call commands ────────────────────────────────────────────────────────

    fun acceptCall() {
        val call = activeCall ?: run {
            Log.w(TAG, "acceptCall: no active call")
            return
        }
        try {
            val creds = credentials ?: return
            val prm = CallOpParam(true).apply {
                opt.audioCount = 1
                opt.videoCount = 0
            }
            call.answer(prm)
            Log.i(TAG, "Call answered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to answer call", e)
        }
    }

    fun rejectCall() {
        val call = activeCall ?: return
        try {
            val prm = CallOpParam(true).apply {
                statusCode = pjsip_status_code.PJSIP_SC_BUSY_HERE
            }
            call.hangup(prm)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to reject call", e)
        }
        activeCall = null
    }

    fun hangup() {
        val call = activeCall ?: return
        try {
            call.hangup(CallOpParam(true))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to hang up", e)
        }
        activeCall = null
    }

    fun startCall(target: String) {
        val acc = account ?: run {
            Log.w(TAG, "startCall: not registered")
            return
        }
        val creds = credentials ?: return
        try {
            val call = SipCall(acc, -1, this)
            val destUri = "sip:${target}@${creds.domain}"
            val prm = CallOpParam(true).apply {
                opt.audioCount = 1
                opt.videoCount = 0
            }
            call.makeCall(destUri, prm)
            activeCall = call
            Log.i(TAG, "Outbound call started to $destUri")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start call to $target", e)
        }
    }

    fun setMuted(muted: Boolean) {
        val call = activeCall ?: return
        try {
            val info = call.info
            for (i in 0 until info.media.size().toInt()) {
                val media = info.media[i.toLong()]
                if (media.type == pjmedia_type.PJMEDIA_TYPE_AUDIO &&
                    media.status == pjsua_call_media_status.PJSUA_CALL_MEDIA_ACTIVE
                ) {
                    val audioMedia = call.getMedia(i.toLong()) as? AudioMedia ?: continue
                    val devMgr = endpoint.audDevManager()
                    if (muted) {
                        devMgr.captureDevMedia.stopTransmit(audioMedia)
                    } else {
                        devMgr.captureDevMedia.startTransmit(audioMedia)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "setMuted error", e)
        }
    }

    // ── Internal callbacks from SipAccount / SipCall ─────────────────────────

    internal fun onAccountRegistered(extension: String) {
        Log.i(TAG, "Registered as $extension")
        listener.onRegistered(extension)
    }

    internal fun onAccountUnregistered(extension: String) {
        Log.i(TAG, "Unregistered: $extension")
        listener.onUnregistered(extension)
    }

    internal fun onAccountRegistrationFailed(reason: String, code: Int) {
        Log.w(TAG, "Registration failed: $reason ($code)")
        listener.onRegistrationFailed(reason, code)
    }

    internal fun onCallIncoming(call: SipCall, callerId: String, callerName: String) {
        if (activeCall != null) {
            // Already on a call - busy
            try {
                call.hangup(CallOpParam(true).apply {
                    statusCode = pjsip_status_code.PJSIP_SC_BUSY_HERE
                })
            } catch (_: Exception) {}
            return
        }
        activeCall = call
        listener.onIncomingCall(call.id.toString(), callerId, callerName)
    }

    internal fun onCallConnected(callId: String) {
        listener.onCallConnected(callId)
    }

    internal fun onCallEnded(callId: String, reason: String, durationSeconds: Int) {
        if (activeCall?.id?.toString() == callId) {
            activeCall = null
        }
        listener.onCallEnded(callId, reason, durationSeconds)
    }

    internal fun onCallFailed(callId: String, reason: String) {
        if (activeCall?.id?.toString() == callId) {
            activeCall = null
        }
        listener.onCallFailed(callId, reason)
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private fun createAccount(creds: SipCredentials) {
        val acfg = AccountConfig().apply {
            idUri = "sip:${creds.extension}@${creds.domain}"

            regConfig.registrarUri = "sip:${creds.domain}"
            regConfig.timeoutSec = 300
            regConfig.retryIntervalSec = 10

            // Force WebSocket Secure transport via outbound proxy.
            // The wss_url from the credential endpoint is "wss://host:8089/ws".
            // Extract host:port from it to build the proxy URI.
            val proxyUri = buildProxyUri(creds.wssUrl, creds.domain)
            sipConfig.proxies.add(proxyUri)

            val authCred = AuthCredInfo("digest", "*", creds.extension, 0, creds.password)
            sipConfig.authCreds.add(authCred)

            // Disable session timers - FreePBX sends unwanted RE-INVITEs without this.
            // Matches useJsSipSession.js: session_timers: false
            call100relUse = pjsua_100rel_use.PJSUA_100REL_NOT_USED
            timerUse = pjsua_sip_timer_use.PJSUA_SIP_TIMER_INACTIVE
            timerMinSESec = 0
            timerSessExpiresSec = 0

            // WebRTC media: DTLS-SRTP mandatory (matches media_encryption=dtls in pjsip.conf)
            mediaConfig.srtpUse = pjmedia_srtp_use.PJMEDIA_SRTP_MANDATORY
            // DTLS does not require SIP to be over TLS (srtpSecureSignaling=0)
            mediaConfig.srtpSecureSignaling = 0

            // TURN server from ice_servers
            val turnEntry = creds.iceServers.firstOrNull { server ->
                server.urls.any { it.startsWith("turn:") }
            }
            if (turnEntry != null) {
                val turnUrl = turnEntry.urls.first { it.startsWith("turn:") }
                val turnHost = turnUrl.removePrefix("turn:")
                mediaConfig.turnEnabled = pj_constants_.PJ_TRUE
                mediaConfig.turnServer = turnHost
                if (turnEntry.username != null && turnEntry.credential != null) {
                    mediaConfig.turnUserName = turnEntry.username
                    mediaConfig.turnPassword = turnEntry.credential
                }
                Log.i(TAG, "TURN configured: $turnHost")
            } else {
                Log.w(TAG, "No TURN server in ice_servers - calls on 4G/5G may fail")
            }
        }

        val acc = SipAccount(this, creds.extension)
        acc.create(acfg)
        account = acc
        Log.i(TAG, "SIP account created for ${creds.extension}@${creds.domain}")
    }

    /**
     * Builds the PJSIP outbound proxy URI from the wss_url.
     *
     * Example:
     *   wss_url = "wss://pbx.procol.co:8089/ws"
     *   -> "<sip:pbx.procol.co:8089;transport=wss;lr>"
     */
    private fun buildProxyUri(wssUrl: String, fallbackDomain: String): String {
        return try {
            // Strip protocol and path
            val withoutProto = wssUrl.removePrefix("wss://").removePrefix("ws://")
            val hostPort = withoutProto.substringBefore("/")
            "<sip:${hostPort};transport=wss;lr>"
        } catch (_: Exception) {
            "<sip:${fallbackDomain};transport=wss;lr>"
        }
    }

    private fun parseCredentials(json: String): SipCredentials {
        val obj = JSONObject(json)
        val iceServers = mutableListOf<IceServerConfig>()
        val iceArr = obj.optJSONArray("ice_servers") ?: JSONArray()
        for (i in 0 until iceArr.length()) {
            val entry = iceArr.getJSONObject(i)
            val urlsArr = entry.getJSONArray("urls")
            val urls = (0 until urlsArr.length()).map { urlsArr.getString(it) }
            iceServers.add(
                IceServerConfig(
                    urls = urls,
                    username = entry.optString("username").takeIf { it.isNotEmpty() },
                    credential = entry.optString("credential").takeIf { it.isNotEmpty() },
                )
            )
        }
        return SipCredentials(
            extension = obj.getString("sip_extension"),
            password = obj.getString("sip_password"),
            wssUrl = obj.getString("wss_url"),
            domain = obj.getString("sip_domain"),
            iceServers = iceServers,
        )
    }
}
