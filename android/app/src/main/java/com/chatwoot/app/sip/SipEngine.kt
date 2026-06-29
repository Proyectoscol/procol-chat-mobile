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
 * Media encryption notes:
 * - This PJSIP AAR build is compiled with OpenSSL, enabling DTLS-SRTP (PJMEDIA_SRTP_HAS_DTLS=1).
 * - DTLS-SRTP handles UDP/TLS/RTP/SAVPF, which is what Asterisk sends with webrtc=yes.
 * - SDES handles RTP/SAVP (Asterisk without webrtc=yes). Both keying methods are available.
 * - The endpoint for extension 9001 uses media_encryption=dtls in pjsip.conf (Asterisk side).
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
        fun onCallCancelled(callId: String)
    }

    private val endpoint = Endpoint()
    private var account: SipAccount? = null
    private var activeCall: SipCall? = null
    private var credentials: SipCredentials? = null
    private var tlsAvailable = false

    data class SipCredentials(
        val extension: String,
        val password: String,
        val wssUrl: String,
        val domain: String,
        val iceServers: List<IceServerConfig>,
        // TLS fields - used when Asterisk exposes standard SIP/TLS (port 5061).
        // Takes precedence over wssUrl when present.
        val tlsHost: String? = null,
        val tlsPort: Int? = null,
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

            val epConfig = EpConfig()

            // STUN server from ice_servers[0] (STUN entry) - configured at UA level
            val stunUrl = creds.iceServers
                .flatMap { it.urls }
                .firstOrNull { it.startsWith("stun:") }
            if (stunUrl != null) {
                val stunHost = stunUrl.removePrefix("stun:")
                val sv = StringVector()
                sv.add(stunHost)
                epConfig.uaConfig.setStunServer(sv)
                Log.i(TAG, "STUN: $stunHost")
            }

            // Level 5 = verbose. Written to file only (pure C I/O, no JNI - safe).
            // Do NOT use a custom LogWriter: PJSIP worker threads call utilLogWrite via
            // SWIG/JNI and the bridge crashes (SIGSEGV, vtable corruption) in 2.14.1.
            val logPath = context.filesDir.absolutePath + "/pjsip.log"
            epConfig.logConfig.level = 5
            epConfig.logConfig.consoleLevel = 0
            epConfig.logConfig.filename = logPath
            epConfig.logConfig.fileFlags = 0
            Log.i(TAG, "PJSIP log -> $logPath")

            // Disable software AEC - Speex AEC xruns on Android cause overcancellation
            // of the mic signal, making TX audio sound silent on the far end.
            // Android hardware AEC (AudioEffect) runs in the driver and is unaffected.
            epConfig.medConfig.ecTailLen = 0
            Log.i(TAG, "AEC disabled (ecTailLen=0) - rely on hardware AEC")

            endpoint.libInit(epConfig)

            // Transport setup: prefer TLS (required for WSS to Asterisk), fallback to UDP.
            // When TLS is unavailable (PJSIP built without OpenSSL), we use UDP on port 5060
            // and skip the WSS proxy - Asterisk must also accept plain UDP/TCP on 5060.
            val tCfg = TransportConfig()
            tCfg.port = 0
            // Disable SSL server cert verification - Asterisk VPS2 uses a self-signed cert.
            // Android's trust store won't have it, so TLS handshake would fail otherwise.
            // This is acceptable for SIP over TLS in enterprise/private deployments.
            tCfg.tlsConfig.verifyServer = false
            tCfg.tlsConfig.verifyClient = false
            tCfg.tlsConfig.requireClientCert = false
            try {
                endpoint.transportCreate(pjsip_transport_type_e.PJSIP_TRANSPORT_TLS, tCfg)
                tlsAvailable = true
                Log.i(TAG, "TLS transport created (verifyServer=false)")
            } catch (e: Exception) {
                Log.w(TAG, "TLS transport failed - FULL ERROR:\n${e.message}")
                Log.w(TAG, "TLS falling back to UDP - wss proxy will be skipped")
                try {
                    endpoint.transportCreate(pjsip_transport_type_e.PJSIP_TRANSPORT_UDP, tCfg)
                    Log.i(TAG, "UDP transport created")
                } catch (e2: Exception) {
                    endpoint.transportCreate(pjsip_transport_type_e.PJSIP_TRANSPORT_TCP, tCfg)
                    Log.i(TAG, "TCP transport created")
                }
            }

            endpoint.libStart()
            Log.i(TAG, "PJSIP endpoint started (tlsAvailable=$tlsAvailable)")

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
                statusCode = pjsip_status_code.PJSIP_SC_OK
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
            for (i in 0 until info.media.size) {
                val media = info.media[i]
                if (media.type == pjmedia_type.PJMEDIA_TYPE_AUDIO &&
                    media.status == pjsua_call_media_status.PJSUA_CALL_MEDIA_ACTIVE
                ) {
                    val audioMedia = AudioMedia.typecastFromMedia(call.getMedia(i.toLong()))
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

    internal fun onCallCancelled(callId: String) {
        if (activeCall?.id?.toString() == callId) {
            activeCall = null
        }
        listener.onCallCancelled(callId)
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private fun createAccount(creds: SipCredentials) {
        val acfg = AccountConfig().apply {
            idUri = "sip:${creds.extension}@${creds.domain}"

            regConfig.registrarUri = "sip:${creds.domain}"
            regConfig.timeoutSec = 300
            regConfig.retryIntervalSec = 10

            // Proxy URI selection:
            // 1. TLS (sip_tls_host:sip_tls_port) - standard SIP/TLS port 5061. Preferred.
            //    PJSIP's TLS transport connects here directly without WebSocket upgrade.
            // 2. WSS (wss_url) - requires WebSocket transport which PJSIP 2.14 lacks.
            //    Kept for reference; will error with PJSIP_EUNSUPTRANSPORT if attempted.
            if (tlsAvailable) {
                val proxyUri = if (creds.tlsHost != null && creds.tlsPort != null) {
                    "<sip:${creds.tlsHost}:${creds.tlsPort};transport=tls;lr>"
                } else {
                    buildProxyUri(creds.wssUrl, creds.domain)
                }
                sipConfig.proxies.add(proxyUri)
                Log.i(TAG, "SIP proxy: $proxyUri")
            } else {
                Log.w(TAG, "TLS unavailable - registering direct to ${creds.domain}:5060 (no proxy)")
            }

            val authCred = AuthCredInfo("digest", "*", creds.extension, 0, creds.password)
            sipConfig.authCreds.add(authCred)

            // Disable session timers - FreePBX sends unwanted RE-INVITEs without this.
            // Matches useJsSipSession.js: session_timers: false
            val callCfg = callConfig
            callCfg.setPrackUse(pjsua_100rel_use.PJSUA_100REL_NOT_USED)
            // PJSIP_SIP_TIMER_INACTIVE stops us from initiating re-INVITEs.
            // Do NOT set timerMinSE or timerSessExpires to 0 - PJSIP asserts min_se >= 90
            // even when inactive, and crashes on incoming INVITE (sip_timer.c:639).
            callCfg.setTimerUse(pjsua_sip_timer_use.PJSUA_SIP_TIMER_INACTIVE)
            setCallConfig(callCfg)

            // SRTP: DTLS-SRTP first, SDES as fallback.
            // DTLS_SRTP = 1 is indexed first so pjmedia_transport_srtp_create() creates the
            // DTLS keying before SDES. Required for Asterisk webrtc=yes (UDP/TLS/RTP/SAVPF).
            val mediaCfg = mediaConfig
            mediaCfg.setSrtpUse(pjmedia_srtp_use.PJMEDIA_SRTP_MANDATORY)
            mediaCfg.setSrtpSecureSignaling(0)
            val srtpOpt = mediaCfg.getSrtpOpt()
            val keyings = IntVector(intArrayOf(
                pjmedia_srtp_keying_method.PJMEDIA_SRTP_KEYING_DTLS_SRTP,
                pjmedia_srtp_keying_method.PJMEDIA_SRTP_KEYING_SDES,
            ))
            srtpOpt.setKeyings(keyings)
            mediaCfg.setSrtpOpt(srtpOpt)
            Log.i(TAG, "SRTP keyings set: DTLS_SRTP first, count=${mediaCfg.getSrtpOpt().getKeyings().size}")
            mediaCfg.setRtcpMuxEnabled(true)
            setMediaConfig(mediaCfg)

            // ICE + TURN: configured in AccountNatConfig
            val natCfg = natConfig
            natCfg.setIceEnabled(true)
            val turnEntry = creds.iceServers.firstOrNull { server ->
                server.urls.any { it.startsWith("turn:") }
            }
            if (turnEntry != null) {
                val turnUrl = turnEntry.urls.first { it.startsWith("turn:") }
                val turnHost = turnUrl.removePrefix("turn:")
                natCfg.setTurnEnabled(true)
                natCfg.setTurnServer(turnHost)
                if (turnEntry.username != null && turnEntry.credential != null) {
                    natCfg.setTurnUserName(turnEntry.username)
                    natCfg.setTurnPassword(turnEntry.credential)
                }
                Log.i(TAG, "TURN configured: $turnHost")
            } else {
                Log.w(TAG, "No TURN server in ice_servers - calls on 4G/5G may fail")
            }
            setNatConfig(natCfg)
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
            wssUrl = obj.optString("wss_url", ""),
            domain = obj.getString("sip_domain"),
            iceServers = iceServers,
            tlsHost = obj.optString("sip_tls_host").takeIf { it.isNotEmpty() },
            tlsPort = if (obj.has("sip_tls_port")) obj.getInt("sip_tls_port") else null,
        )
    }
}
