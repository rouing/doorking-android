package com.doorking.gate.core.network

import com.doorking.gate.core.auth.DEFAULT_SERVER_URL
import com.doorking.gate.core.auth.SessionStorage
import kotlinx.serialization.json.Json
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
