package com.doorking.gate.core.push

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.doorking.gate.DoorkingApp
import com.doorking.gate.MainActivity
import com.doorking.gate.R
import com.doorking.gate.core.network.RegisterFcmRequest
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Receives Firebase Cloud Messaging push alerts and token rotations for `com.doorking.gate`.
 */
class DoorkingFirebaseMessagingService : FirebaseMessagingService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        val app = application as? DoorkingApp ?: return
        if (app.startupCrash != null) return
        scope.launch {
            val stored = app.container.sessionStorage.awaitLoaded()
            if (!stored.pushOptIn) return@launch
            if (stored.sessionToken.isNullOrBlank()) return@launch
            if (!FirebaseHelper.hasNotificationPermission(this@DoorkingFirebaseMessagingService)) return@launch
            runCatching {
                app.container.api.registerFcmToken(
                    RegisterFcmRequest(token = token, label = FirebaseHelper.defaultDeviceLabel()),
                )
            }
        }
    }

    @SuppressLint("MissingPermission")
    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)

        if (message.data["type"] == "passkey_approval") {
            val requestId = message.data["requestId"].orEmpty()
            if (!PASSKEY_APPROVAL_ID_RE.matches(requestId)) return
            val deviceLabel = message.data["deviceLabel"].orEmpty().ifBlank { "Another device" }
            val app = application as? DoorkingApp
            if (app != null && app.startupCrash == null) {
                app.container.passkeyApprovalRequests.tryEmit(requestId)
            }
            if (!FirebaseHelper.hasNotificationPermission(this)) return
            FirebaseHelper.ensureNotificationChannel(this)

            val tag = "passkey-approval-$requestId"
            val launchIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_PASSKEY_APPROVAL_REQUEST_ID, requestId)
            }
            val pendingIntent = PendingIntent.getActivity(
                this,
                tag.hashCode(),
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val title = "Approve new passkey?"
            val body = "$deviceLabel wants to add a passkey. Tap to verify."
            val notification = NotificationCompat.Builder(this, FirebaseHelper.CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_widget_gate)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()
            NotificationManagerCompat.from(this).notify(tag, tag.hashCode(), notification)
            return
        }

        if (!FirebaseHelper.hasNotificationPermission(this)) return
        FirebaseHelper.ensureNotificationChannel(this)

        val title = message.notification?.title
            ?: message.data["title"]
            ?: getString(R.string.app_name)
        val body = message.notification?.body
            ?: message.data["body"]
            ?: return
        val tag = message.data["tag"].orEmpty().ifBlank { "doorking-alert" }

        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            tag.hashCode(),
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(this, FirebaseHelper.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_widget_gate)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        NotificationManagerCompat.from(this).notify(tag, tag.hashCode(), notification)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PASSKEY_APPROVAL_REQUEST_ID = "passkey_approval_request_id"
        private val PASSKEY_APPROVAL_ID_RE = Regex("^[A-Za-z0-9_-]{8,128}$")
    }
}
