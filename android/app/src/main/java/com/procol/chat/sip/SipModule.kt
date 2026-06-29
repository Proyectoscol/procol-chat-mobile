package com.procol.chat.sip

import android.content.Intent
import android.os.Build
import com.facebook.react.bridge.*
import com.facebook.react.modules.core.DeviceEventManagerModule

/**
 * React Native bridge for the SIP native module.
 *
 * Communicates with SipService via Intents; receives events back via
 * SipEventBus (an in-process singleton) and forwards them to JS via
 * the RCTDeviceEventEmitter.
 */
class SipModule(private val reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext), SipEventBus.Listener {

    companion object {
        const val NAME = "SipModule"
    }

    override fun getName(): String = NAME

    override fun initialize() {
        super.initialize()
        SipEventBus.addListener(this)
    }

    override fun invalidate() {
        SipEventBus.removeListener(this)
        super.invalidate()
    }

    // ── JS → Native commands ─────────────────────────────────────────────────

    @ReactMethod
    fun register(credentialsJson: String, promise: Promise) {
        try {
            val intent = Intent(reactContext, SipService::class.java).apply {
                action = SipService.ACTION_REGISTER
                putExtra(SipService.EXTRA_CREDENTIALS_JSON, credentialsJson)
            }
            startService(intent)
            promise.resolve(null)
        } catch (e: Exception) {
            promise.reject("SIP_REGISTER_ERROR", e.message, e)
        }
    }

    @ReactMethod
    fun unregister(promise: Promise) {
        try {
            startService(Intent(reactContext, SipService::class.java).apply {
                action = SipService.ACTION_UNREGISTER
            })
            promise.resolve(null)
        } catch (e: Exception) {
            promise.reject("SIP_UNREGISTER_ERROR", e.message, e)
        }
    }

    @ReactMethod
    fun acceptCall(promise: Promise) {
        try {
            sendServiceCommand(SipService.ACTION_ACCEPT_CALL)
            promise.resolve(null)
        } catch (e: Exception) {
            promise.reject("SIP_ACCEPT_ERROR", e.message, e)
        }
    }

    @ReactMethod
    fun rejectCall(promise: Promise) {
        try {
            sendServiceCommand(SipService.ACTION_REJECT_CALL)
            promise.resolve(null)
        } catch (e: Exception) {
            promise.reject("SIP_REJECT_ERROR", e.message, e)
        }
    }

    @ReactMethod
    fun hangup(promise: Promise) {
        try {
            sendServiceCommand(SipService.ACTION_HANGUP)
            promise.resolve(null)
        } catch (e: Exception) {
            promise.reject("SIP_HANGUP_ERROR", e.message, e)
        }
    }

    @ReactMethod
    fun startCall(target: String, promise: Promise) {
        try {
            val intent = Intent(reactContext, SipService::class.java).apply {
                action = SipService.ACTION_START_CALL
                putExtra(SipService.EXTRA_CALL_TARGET, target)
            }
            reactContext.startService(intent)
            promise.resolve(null)
        } catch (e: Exception) {
            promise.reject("SIP_CALL_ERROR", e.message, e)
        }
    }

    @ReactMethod
    fun setMuted(muted: Boolean, promise: Promise) {
        try {
            val intent = Intent(reactContext, SipService::class.java).apply {
                action = SipService.ACTION_SET_MUTED
                putExtra(SipService.EXTRA_MUTED, muted)
            }
            reactContext.startService(intent)
            promise.resolve(null)
        } catch (e: Exception) {
            promise.reject("SIP_MUTE_ERROR", e.message, e)
        }
    }

    @ReactMethod
    fun setSpeaker(enabled: Boolean, promise: Promise) {
        try {
            val intent = Intent(reactContext, SipService::class.java).apply {
                action = SipService.ACTION_SET_SPEAKER
                putExtra(SipService.EXTRA_SPEAKER_ENABLED, enabled)
            }
            reactContext.startService(intent)
            promise.resolve(null)
        } catch (e: Exception) {
            promise.reject("SIP_SPEAKER_ERROR", e.message, e)
        }
    }

    @ReactMethod
    fun getState(promise: Promise) {
        val map = Arguments.createMap().apply {
            putString("registrationState", SipService.currentRegistrationState)
            putString("callState", SipService.currentCallState)
            putString("callerId", SipService.currentCallerId)
            putString("callerName", SipService.currentCallerName)
            putString("activeCallId", SipService.currentActiveCallId)
        }
        promise.resolve(map)
    }

    // Required by RCTEventEmitter conventions; not used here since we emit directly
    @ReactMethod
    fun addListener(eventName: String) {}

    @ReactMethod
    fun removeListeners(count: Double) {}

    // ── SipEventBus.Listener ─────────────────────────────────────────────────

    override fun onSipEvent(eventName: String, params: WritableMap?) {
        if (reactContext.hasActiveReactInstance()) {
            reactContext
                .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
                .emit(eventName, params)
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun startService(intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            reactContext.startForegroundService(intent)
        } else {
            reactContext.startService(intent)
        }
    }

    private fun sendServiceCommand(action: String) {
        val intent = Intent(reactContext, SipService::class.java).apply {
            this.action = action
        }
        reactContext.startService(intent)
    }
}
