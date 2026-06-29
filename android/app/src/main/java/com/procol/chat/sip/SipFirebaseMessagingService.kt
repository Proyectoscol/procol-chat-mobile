package com.procol.chat.sip

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.procol.chat.MainActivity
import com.procol.chat.R

private const val TAG = "SipFcmService"
private const val NOTIF_ID_INCOMING = 2001
private const val CHANNEL_ID_CALL = "sip_incoming_call"

/**
 * Receives FCM data messages from the backend when a SIP call is incoming.
 *
 * Backend payload (data message, NOT notification):
 * {
 *   "type": "sip_incoming_call",
 *   "caller_id": "1000",
 *   "caller_name": "Juan Pérez"
 * }
 *
 * Flow:
 *  1. Backend AMI detects Newchannel for the extension
 *  2. Backend sends FCM push (data message, high priority)
 *  3. Android wakes device → calls onMessageReceived even when app is killed
 *  4. We show a CallStyle incoming-call notification immediately
 *  5. We start SipService with saved credentials so it re-registers
 *  6. Asterisk delivers the SIP INVITE once registered
 *  7. SipService.onIncomingCall fires → updates the notification via normal flow
 */
class SipFirebaseMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        Log.i(TAG, "FCM token refreshed: ${token.take(20)}...")
        FcmTokenStore.save(applicationContext, token)
        // Token is POSTed to backend from JS via sipGetFcmToken() in useSipAutoRegister
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        Log.i(TAG, "FCM message received: type=${data["type"]}")

        if (data["type"] != "sip_incoming_call") return

        val callerId = data["caller_id"] ?: ""
        val callerName = data["caller_name"] ?: callerId

        showIncomingCallNotification(callerName, callerId)
        startSipServiceIfNeeded()
    }

    private fun showIncomingCallNotification(callerName: String, callerId: String) {
        val fullScreenIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("incoming_call", true)
        }
        val fullScreenPi = PendingIntent.getActivity(
            this, 100, fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val acceptIntent = Intent(this, SipService::class.java).apply {
            action = SipService.ACTION_ACCEPT_CALL
        }
        val rejectIntent = Intent(this, SipService::class.java).apply {
            action = SipService.ACTION_REJECT_CALL
        }
        val acceptPi = PendingIntent.getService(
            this, 101, acceptIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val rejectPi = PendingIntent.getService(
            this, 102, rejectIntent,
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
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID_INCOMING, notification)
    }

    private fun startSipServiceIfNeeded() {
        val credentialsJson = SipCredentialsStore.read(applicationContext) ?: run {
            Log.w(TAG, "No saved credentials — cannot start SipService from FCM")
            return
        }

        Log.i(TAG, "Starting SipService from FCM push")
        val intent = Intent(applicationContext, SipService::class.java).apply {
            action = SipService.ACTION_REGISTER
            putExtra(SipService.EXTRA_CREDENTIALS_JSON, credentialsJson)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            applicationContext.startForegroundService(intent)
        } else {
            applicationContext.startService(intent)
        }
    }
}
