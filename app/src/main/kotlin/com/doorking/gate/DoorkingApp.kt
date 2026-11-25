package com.doorking.gate

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.StrictMode
import android.os.Trace
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/**
 * Lightweight, test-friendly application container holding singletons shared across UI, Coil, and background workers.
 */
class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext
    val sessionStorage: SessionStorage = EncryptedSessionStore(context)
    val authEvents = AuthEventBus()
    val ssoCallbacks = MutableSharedFlow<Uri>(replay = 1, extraBufferCapacity = 2)
    val inviteLinks = MutableSharedFlow<Uri>(replay = 1, extraBufferCapacity = 2)
    val passkeyApprovalRequests = MutableSharedFlow<String>(replay = 1, extraBufferCapacity = 4)
    val passkeyAuthenticator: PasskeyAuthenticator = AndroidPasskeyManager()
    val stepUpCoordinator = UiStepUpCoordinator()
    val authInterceptor = DoorkingAuthInterceptor(
        sessionStorage = sessionStorage,
        authEvents = authEvents,
        json = ApiClientFactory.json,
        stepUpCoordinator = stepUpCoordinator,
    )
    val okHttpClient: OkHttpClient by lazy {
        ApiClientFactory.createOkHttp(
            sessionStorage = sessionStorage,
            authEvents = authEvents,
            debugLogging = BuildConfig.DEBUG,
            authInterceptor = authInterceptor,
        )
    }
    val api: DoorkingApi by lazy { ApiClientFactory.createApi(okHttpClient) }

    val stepUpOkHttpClient: OkHttpClient by lazy {
        ApiClientFactory.createStepUpOkHttp(
            sessionStorage = sessionStorage,
            authEvents = authEvents,
            debugLogging = BuildConfig.DEBUG,
        )
    }
    val stepUpApi: DoorkingApi by lazy { ApiClientFactory.createApi(stepUpOkHttpClient) }
}

class DoorkingApp : Application(), SingletonImageLoader.Factory {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    lateinit var container: AppContainer
        private set

    var startupCrash: String? = null
        private set

    override fun onCreate() {
        super.onCreate()
        if (getProcessName().endsWith(":crash")) return
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectAll()
                    .penaltyLog()
                    .build(),
            )
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder()
                    .detectAll()
                    .penaltyLog()
                    .build(),
            )
        }
        CrashStore.installHandler(this)
        try {
            Trace.beginSection("Doorking:AppContainer")
            try {
                container = AppContainer(this)
            } finally {
                Trace.endSection()
            }
            appScope.launch {
                FirebaseHelper.ensureNotificationChannel(this@DoorkingApp)
                val stored = container.sessionStorage.awaitLoaded()
                FirebaseHelper.setCrashlyticsOptIn(this@DoorkingApp, stored.crashlyticsEnabled)
            }
        } catch (t: Throwable) {
            FirebaseHelper.recordNonFatal(this, t)
            startupCrash = CrashStore.record(this, Thread.currentThread().name, t)
        }
    }

    /** Shares the authenticated OkHttpClient with Coil 3 with bounded memory and disk caches. */
    override fun newImageLoader(context: Context): ImageLoader =
        ImageLoader.Builder(context)
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(context, 0.15)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("coil_cache"))
                    .maxSizeBytes(32L * 1024L * 1024L)
                    .build()
            }
            .components {
                if (::container.isInitialized) {
                    add(OkHttpNetworkFetcherFactory(callFactory = { container.okHttpClient }))
                }
            }
            .build()
}
