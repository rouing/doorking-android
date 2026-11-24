package com.doorking.gate.core.push

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.doorking.gate.BuildConfig
import com.doorking.gate.core.auth.SessionStorage
import com.doorking.gate.core.network.DoorkingApi
import com.doorking.gate.core.network.RegisterFcmRequest
import com.doorking.gate.core.network.UnregisterFcmRequest
import com.google.firebase.FirebaseApp
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.firebase.messaging.FirebaseMessaging
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Safe Firebase Cloud Messaging & Crashlytics helper.
 *
 * Guards every Firebase SDK call with `FirebaseApp.getApps(context).isNotEmpty()` so local unit tests
 * and open-source builds without `google-services.json` degrade cleanly without crashing.
 */
object FirebaseHelper {

    const val CHANNEL_ID = "doorking_alerts"
    private const val TAG = "FirebaseHelper"

    fun isAvailable(context: Context): Boolean = runCatching {
        FirebaseApp.getApps(context.applicationContext).isNotEmpty()
    }.getOrDefault(false)

    fun ensureNotificationChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val existing = manager.getNotificationChannel(CHANNEL_ID)
        if (existing != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Gate Alerts",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "DoorKing gate alerts, security notices, and resident portal updates"
        }
        manager.createNotificationChannel(channel)
    }

    fun configureCrashlytics(context: Context) {
        if (!isAvailable(context)) return
        runCatching {
            val crashlytics = FirebaseCrashlytics.getInstance()
            crashlytics.isCrashlyticsCollectionEnabled = !BuildConfig.DEBUG
            crashlytics.setCustomKey("app_version", BuildConfig.VERSION_NAME)
            crashlytics.setCustomKey("version_code", BuildConfig.VERSION_CODE)
        }.onFailure { err ->
            Log.d(TAG, "Crashlytics init skipped: ${err.message}")
        }
    }

    fun recordNonFatal(context: Context, throwable: Throwable) {
        if (!isAvailable(context)) return
        runCatching {
            FirebaseCrashlytics.getInstance().recordException(throwable)
        }
    }

    fun hasNotificationPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    fun defaultDeviceLabel(): String =
        "${Build.MANUFACTURER} ${Build.MODEL}".trim().ifBlank { "Android" }.take(60)

    suspend fun fetchFcmToken(context: Context): String? {
        if (!isAvailable(context)) return null
        return runCatching {
            suspendCancellableCoroutine<String?> { cont ->
                FirebaseMessaging.getInstance().token
                    .addOnSuccessListener { token ->
                        if (cont.isActive) cont.resume(token?.takeIf { it.isNotBlank() })
                    }
                    .addOnFailureListener { err ->
                        Log.d(TAG, "FCM token fetch failed: ${err.message}")
                        if (cont.isActive) cont.resume(null)
                    }
            }
        }.getOrNull()
    }

    /**
     * Registers this device's FCM token with the server when signed in (no-op if signed out or Firebase unavailable).
     */
    suspend fun registerCurrentDevice(
        context: Context,
        api: DoorkingApi,
        sessionStorage: SessionStorage,
        requirePermission: Boolean = true,
    ): Boolean {
        if (!isAvailable(context)) return false
        if (requirePermission && !hasNotificationPermission(context)) return false
        val token = sessionStorage.awaitLoaded().sessionToken
        if (token.isNullOrBlank()) return false
        val fcmToken = fetchFcmToken(context) ?: return false
        return runCatching {
            val res = api.registerFcmToken(RegisterFcmRequest(token = fcmToken, label = defaultDeviceLabel()))
            res.isSuccessful
        }.getOrDefault(false)
    }

    /**
     * Best-effort unregister on sign-out before the server bearer session is revoked.
     */
    suspend fun unregisterCurrentDevice(
        context: Context,
        api: DoorkingApi,
    ) {
        if (!isAvailable(context)) return
        val fcmToken = fetchFcmToken(context) ?: return
        runCatching {
            api.unregisterFcmToken(UnregisterFcmRequest(token = fcmToken))
        }
    }
}
