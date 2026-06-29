package com.chatwoot.app.sip

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.chatwoot.app.MainActivity
import com.chatwoot.app.R
import com.facebook.react.bridge.Arguments

private const val TAG = "SipService"
private const val NOTIFICATION_ID = 1001
private const val CHANNEL_ID_PERSISTENT = "sip_persistent"
private const val CHANNEL_ID_CALL = "sip_incoming_call"

/**
 * Foreground Service that owns the PJSIP endpoint and SIP registration.
 *
 * Runs as long as the asesor is logged in with SIP credentials.
 * Survives:
 * - App backgrounded
 * - Activity destroyed
 * - JS engine paused
 *
 * Does NOT survive:
 * - Android Doze (battery optimization) on certain OEM devices (Xiaomi/Redmi)
 * - User swiping the app from "recientes" with battery saver active
 * - System memory pressure (rare with foreground priority)
 *
 * When the service is killed, the FCM push mechanism (Fase 2) acts as safety net.
 */
class SipService : Service(), SipEngine.Listener {

    companion object {
        const val ACTION_REGISTER = "sip.REGISTER"
        const val ACTION_UNREGISTER = "sip.UNREGISTER"
        const val ACTION_ACCEPT_CALL = "sip.ACCEPT_CALL"
        const val ACTION_REJECT_CALL = "sip.REJECT_CALL"
        const val ACTION_HANGUP = "sip.HANGUP"
        const val ACTION_START_CALL = "sip.START_CALL"
        const val ACTION_SET_MUTED = "sip.SET_MUTED"
        const val ACTION_SET_SPEAKER = "sip.SET_SPEAKER"

        const val EXTRA_CREDENTIALS_JSON = "credentials_json"
        const val EXTRA_CALL_TARGET = "call_target"
        const val EXTRA_MUTED = "muted"
        const val EXTRA_SPEAKER_ENABLED = "speaker_enabled"

        // State visible to SipModule.getState() without needing IPC
        @Volatile var currentRegistrationState: String = "idle"
        @Volatile var currentCallState: String = "idle"
        @Volatile var currentCallerId: String? = null
        @Volatile var currentCallerName: String? = null
        @Volatile var currentActiveCallId: String? = null
    }

    private var engine: SipEngine? = null

    // ── Service lifecycle ────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        Log.i(TAG, "SipService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Must call startForeground immediately regardless of the intent action
        startForeground()

        when (intent?.action) {
            ACTION_REGISTER -> {
                val json = intent.getStringExtra(EXTRA_CREDENTIALS_JSON) ?: return START_STICKY
                handleRegister(json)
            }
            ACTION_UNREGISTER -> handleUnregister()
            ACTION_ACCEPT_CALL -> engine?.acceptCall()
            ACTION_REJECT_CALL -> engine?.rejectCall()
            ACTION_HANGUP -> engine?.hangup()
            ACTION_START_CALL -> {
                val target = intent.getStringExtra(EXTRA_CALL_TARGET) ?: return START_STICKY
                engine?.startCall(target)
            }
            ACTION_SET_MUTED -> {
                val muted = intent.getBooleanExtra(EXTRA_MUTED, false)
                engine?.setMuted(muted)
            }
            ACTION_SET_SPEAKER -> {
                // Speaker routing via AudioManager is handled at the OS level;
                // PJSIP follows whatever AudioManager sets.
                val enabled = intent.getBooleanExtra(EXTRA_SPEAKER_ENABLED, false)
                val audioManager = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn = enabled
            }
            else -> Log.d(TAG, "onStartCommand: no action or unknown action: ${intent?.action}")
        }

        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "SipService destroying")
        engine?.stop()
        engine = null
        currentRegistrationState = "idle"
        currentCallState = "idle"
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Command handlers ─────────────────────────────────────────────────────

    private fun handleRegister(credentialsJson: String) {
        if (engine != null) {
            // PJSIP Endpoint is a process-wide singleton; creating a second one crashes.
            // This happens when JS hot-reloads and fires sipRegister again while already connected.
            Log.d(TAG, "handleRegister: engine already running, ignoring duplicate register request")
            return
        }

        currentRegistrationState = "registering"
        emitState("registering")

        val e = SipEngine(applicationContext, this)
        engine = e
        e.start(credentialsJson)
    }

    private fun handleUnregister() {
        currentRegistrationState = "unregistering"
        engine?.stop()
        engine = null
        currentRegistrationState = "idle"
        updatePersistentNotification("Desconectado")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ── SipEngine.Listener ───────────────────────────────────────────────────

    override fun onRegistered(extension: String) {
        currentRegistrationState = "registered"
        Log.i(TAG, "SIP registered: $extension")
        updatePersistentNotification("Conectado como $extension")
        SipEventBus.emit(SIP_EVENTS.REGISTERED, Arguments.createMap().apply {
            putString("extension", extension)
        })
    }

    override fun onUnregistered(extension: String) {
        currentRegistrationState = "idle"
        updatePersistentNotification("Desconectado")
        SipEventBus.emit(SIP_EVENTS.UNREGISTERED, Arguments.createMap().apply {
            putString("extension", extension)
        })
    }

    override fun onRegistrationFailed(reason: String, code: Int) {
        currentRegistrationState = "failed"
        updatePersistentNotification("Error de registro")
        SipEventBus.emit(SIP_EVENTS.REGISTRATION_FAILED, Arguments.createMap().apply {
            putString("reason", reason)
            putInt("code", code)
        })
    }

    override fun onIncomingCall(callId: String, callerId: String, callerName: String) {
        currentCallState = "ringing_in"
        currentCallerId = callerId
        currentCallerName = callerName
        currentActiveCallId = callId
        showIncomingCallNotification(callerName, callerId)
        SipEventBus.emit(SIP_EVENTS.INCOMING_CALL, Arguments.createMap().apply {
            putString("callId", callId)
            putString("callerId", callerId)
            putString("callerName", callerName)
        })
    }

    override fun onCallConnected(callId: String) {
        currentCallState = "active"
        cancelIncomingCallNotification()
        SipEventBus.emit(SIP_EVENTS.CALL_CONNECTED, Arguments.createMap().apply {
            putString("callId", callId)
        })
    }

    override fun onCallEnded(callId: String, reason: String, durationSeconds: Int) {
        currentCallState = "idle"
        currentCallerId = null
        currentCallerName = null
        currentActiveCallId = null
        cancelIncomingCallNotification()
        SipEventBus.emit(SIP_EVENTS.CALL_ENDED, Arguments.createMap().apply {
            putString("callId", callId)
            putString("reason", reason)
            putInt("durationSeconds", durationSeconds)
        })
    }

    override fun onCallFailed(callId: String, reason: String) {
        currentCallState = "idle"
        currentCallerId = null
        currentCallerName = null
        currentActiveCallId = null
        cancelIncomingCallNotification()
        SipEventBus.emit(SIP_EVENTS.CALL_FAILED, Arguments.createMap().apply {
            putString("callId", callId)
            putString("reason", reason)
        })
    }

    override fun onCallCancelled(callId: String) {
        currentCallState = "idle"
        currentCallerId = null
        currentCallerName = null
        currentActiveCallId = null
        cancelIncomingCallNotification()
        SipEventBus.emit(SIP_EVENTS.CALL_CANCELLED, Arguments.createMap().apply {
            putString("callId", callId)
        })
    }

    // ── Notifications ────────────────────────────────────────────────────────

    private fun startForeground() {
        val notification = buildPersistentNotification("Iniciando...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updatePersistentNotification(status: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildPersistentNotification(status))
    }

    private fun buildPersistentNotification(status: String): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pi = PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID_PERSISTENT)
            .setSmallIcon(R.drawable.ic_sip_service)
            .setContentTitle("Procol - Linea VoIP")
            .setContentText(status)
            .setContentIntent(pi)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun showIncomingCallNotification(callerName: String, callerId: String) {
        val acceptIntent = Intent(this, SipService::class.java).apply {
            action = ACTION_ACCEPT_CALL
        }
        val rejectIntent = Intent(this, SipService::class.java).apply {
            action = ACTION_REJECT_CALL
        }
        val fullScreenIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("incoming_call", true)
            putExtra("caller_name", callerName)
            putExtra("caller_id", callerId)
        }

        val acceptPi = PendingIntent.getService(
            this, 1, acceptIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val rejectPi = PendingIntent.getService(
            this, 2, rejectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val fullScreenPi = PendingIntent.getActivity(
            this, 3, fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID_CALL)
            .setSmallIcon(R.drawable.ic_sip_service)
            .setContentTitle("Llamada entrante")
            .setContentText(callerName.ifEmpty { callerId })
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setFullScreenIntent(fullScreenPi, true)
            .addAction(0, "Contestar", acceptPi)
            .addAction(0, "Rechazar", rejectPi)
            .setAutoCancel(false)
            .setOngoing(true)
            .build()

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID + 1, notification)
    }

    private fun cancelIncomingCallNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(NOTIFICATION_ID + 1)
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val persistentChannel = NotificationChannel(
                CHANNEL_ID_PERSISTENT,
                "Linea VoIP activa",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Indica que la linea SIP esta registrada"
                setShowBadge(false)
            }

            val callChannel = NotificationChannel(
                CHANNEL_ID_CALL,
                "Llamadas entrantes",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Notificaciones de llamadas entrantes"
                setShowBadge(true)
                enableVibration(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }

            nm.createNotificationChannel(persistentChannel)
            nm.createNotificationChannel(callChannel)
        }
    }

    // Alias for SipEventBus constants - avoids string literals in the class
    private object SIP_EVENTS {
        const val REGISTERED = "sip.registered"
        const val UNREGISTERED = "sip.unregistered"
        const val REGISTRATION_FAILED = "sip.registrationFailed"
        const val INCOMING_CALL = "sip.incomingCall"
        const val CALL_CONNECTED = "sip.callConnected"
        const val CALL_ENDED = "sip.callEnded"
        const val CALL_FAILED = "sip.callFailed"
        const val CALL_CANCELLED = "sip.callCancelled"
    }

    private fun emitState(state: String) {
        SipEventBus.emit("sip.stateChange", Arguments.createMap().apply {
            putString("state", state)
        })
    }
}
