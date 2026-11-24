package com.doorking.gate

import android.app.Application
import android.content.Context
import android.net.Uri
import coil3.ImageLoader
import kotlinx.coroutines.flow.MutableSharedFlow
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.doorking.gate.core.auth.AndroidPasskeyManager
import com.doorking.gate.core.auth.EncryptedSessionStore
import com.doorking.gate.core.auth.PasskeyAuthenticator
import com.doorking.gate.core.auth.SessionStorage
import com.doorking.gate.core.auth.UiStepUpCoordinator
import com.doorking.gate.core.network.ApiClientFactory
import com.doorking.gate.core.network.AuthEventBus
import com.doorking.gate.core.network.DoorkingApi
import com.doorking.gate.core.network.DoorkingAuthInterceptor
import com.doorking.gate.core.push.FirebaseHelper
import okhttp3.OkHttpClient

/**
 * Lightweight, test-friendly application container holding singletons shared across UI, Coil, and background workers.
 */
class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext
    val sessionStorage: SessionStorage = EncryptedSessionStore(context)
    val authEvents = AuthEventBus()
    val ssoCallbacks = MutableSharedFlow<Uri>(extraBufferCapacity = 2)
    val inviteLinks = MutableSharedFlow<Uri>(replay = 1, extraBufferCapacity = 2)
    val passkeyAuthenticator: PasskeyAuthenticator = AndroidPasskeyManager()
    val stepUpCoordinator = UiStepUpCoordinator()
    val authInterceptor = DoorkingAuthInterceptor(
        sessionStorage = sessionStorage,
        authEvents = authEvents,
        json = ApiClientFactory.json,
        stepUpCoordinator = stepUpCoordinator,
    )
    val okHttpClient: OkHttpClient = ApiClientFactory.createOkHttp(
        sessionStorage = sessionStorage,
        authEvents = authEvents,
        debugLogging = BuildConfig.DEBUG,
        authInterceptor = authInterceptor,
    )
    val api: DoorkingApi = ApiClientFactory.createApi(okHttpClient)
}

class DoorkingApp : Application(), SingletonImageLoader.Factory {

    lateinit var container: AppContainer
        private set

    var startupCrash: String? = null
        private set

    override fun onCreate() {
        super.onCreate()
        if (getProcessName().endsWith(":crash")) return
        CrashStore.installHandler(this)
        FirebaseHelper.ensureNotificationChannel(this)
        FirebaseHelper.configureCrashlytics(this)
        try {
            container = AppContainer(this)
        } catch (t: Throwable) {
            FirebaseHelper.recordNonFatal(this, t)
            startupCrash = CrashStore.record(this, Thread.currentThread().name, t)
        }
    }

    /** Shares the authenticated OkHttpClient with Coil 3 so camera snapshots carry `Authorization: Bearer dks_...`. */
    override fun newImageLoader(context: Context): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                if (::container.isInitialized) {
                    add(OkHttpNetworkFetcherFactory(callFactory = { container.okHttpClient }))
                }
            }
            .build()
}
