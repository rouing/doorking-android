package com.doorking.gate.core.network

import com.doorking.gate.core.auth.DEFAULT_SERVER_URL
import com.doorking.gate.core.auth.SessionStorage
import kotlinx.serialization.json.Json
import okhttp3.Dispatcher
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

object ApiClientFactory {

    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        encodeDefaults = true
    }

    fun createOkHttp(
        sessionStorage: SessionStorage,
        authEvents: AuthEventBus,
        debugLogging: Boolean = false,
        authInterceptor: DoorkingAuthInterceptor = DoorkingAuthInterceptor(sessionStorage, authEvents, json),
    ): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(authInterceptor)

        if (debugLogging) {
            val logger = HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
                redactHeader("Authorization")
                redactHeader("Cookie")
                redactHeader("Set-Cookie")
            }
            builder.addInterceptor(logger)
        }
        return builder.build()
    }

    /**
     * Dedicated OkHttpClient with its own Dispatcher and no interactive step-up coordinator so
     * step-up verification requests (`getStepUpOptions`, `confirmStepUp`, `getBiometricStepUpChallenge`)
     * can never deadlock behind `maxRequestsPerHost = 5` blocked calls on the primary client.
     */
    fun createStepUpOkHttp(
        sessionStorage: SessionStorage,
        authEvents: AuthEventBus,
        debugLogging: Boolean = false,
    ): OkHttpClient = createOkHttp(
        sessionStorage = sessionStorage,
        authEvents = authEvents,
        debugLogging = debugLogging,
        authInterceptor = DoorkingAuthInterceptor(
            sessionStorage = sessionStorage,
            authEvents = authEvents,
            json = json,
            stepUpCoordinator = null,
        ),
    )

    /**
     * Dedicated OkHttpClient derived from [baseClient] with its own [Dispatcher] and no interactive
     * step-up coordinator for Coil image loads and Media3 playback so media fetches share the
     * connection pool without starving or deadlocking interactive API calls.
     */
    fun createMediaOkHttp(
        baseClient: OkHttpClient,
        sessionStorage: SessionStorage,
        authEvents: AuthEventBus,
    ): OkHttpClient {
        val mediaInterceptor = DoorkingAuthInterceptor(
            sessionStorage = sessionStorage,
            authEvents = authEvents,
            json = json,
            stepUpCoordinator = null,
        )
        return baseClient.newBuilder()
            .dispatcher(Dispatcher())
            .apply {
                interceptors().removeAll { it is DoorkingAuthInterceptor }
                interceptors().add(0, mediaInterceptor)
            }
            .build()
    }

    /**
     * Dedicated OkHttpClient derived from [baseClient] with a 90-second read timeout for
     * long-running `testCameras` P2P diagnostics.
     */
    fun createCameraTestOkHttp(baseClient: OkHttpClient): OkHttpClient =
        baseClient.newBuilder()
            .readTimeout(90, TimeUnit.SECONDS)
            .build()

    fun createApi(
        okHttpClient: OkHttpClient,
        baseUrl: String = "$DEFAULT_SERVER_URL/",
    ): DoorkingApi {
        val normalized = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        return Retrofit.Builder()
            .baseUrl(normalized)
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(DoorkingApi::class.java)
    }

    fun parseErrorMessage(error: Throwable): String {
        if (error is HttpException) {
            val raw = runCatching { error.response()?.errorBody()?.string() }.getOrNull()
            if (!raw.isNullOrBlank()) {
                val parsed = runCatching { json.decodeFromString<ApiErrorResponse>(raw) }.getOrNull()
                if (parsed != null && parsed.error.isNotBlank()) return parsed.error
            }
            return "Server returned HTTP ${error.code()}"
        }
        return error.message?.takeIf { it.isNotBlank() } ?: "Unable to reach server"
    }
}
