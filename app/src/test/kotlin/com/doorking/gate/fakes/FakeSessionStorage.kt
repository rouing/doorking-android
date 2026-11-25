package com.doorking.gate.fakes

import com.doorking.gate.core.auth.DEFAULT_SERVER_URL
import com.doorking.gate.core.auth.SessionStorage
import com.doorking.gate.core.auth.StoredAuthState
import com.doorking.gate.core.auth.ThemeMode
import com.doorking.gate.core.auth.normalizeServerUrl
import com.doorking.gate.core.auth.serverOriginOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class FakeSessionStorage(
    initialServerUrl: String = DEFAULT_SERVER_URL,
    initialToken: String? = null,
    initialLastGate: String? = null,
    initialTokenServerOrigin: String? = initialToken?.let { serverOriginOf(initialServerUrl) },
    initialBiometricLockEnabled: Boolean = false,
    initialCrashlyticsEnabled: Boolean = false,
    initialPushOptIn: Boolean = false,
) : SessionStorage {

    private val _state = MutableStateFlow(
        StoredAuthState(
            serverUrl = normalizeServerUrl(initialServerUrl),
            sessionToken = initialToken,
            lastGatePhone = initialLastGate,
            biometricLockEnabled = initialBiometricLockEnabled,
            loaded = true,
            tokenServerOrigin = initialTokenServerOrigin,
            crashlyticsEnabled = initialCrashlyticsEnabled,
            pushOptIn = initialPushOptIn,
        ),
    )
    override val state: StateFlow<StoredAuthState> = _state.asStateFlow()

    override fun currentServerUrl(): String = _state.value.serverUrl

    override fun currentSessionToken(): String? = _state.value.sessionToken

    override suspend fun awaitLoaded(): StoredAuthState = _state.value

    override suspend fun saveSessionToken(token: String) {
        _state.value = _state.value.copy(
            sessionToken = token,
            tokenServerOrigin = serverOriginOf(_state.value.serverUrl),
            loaded = true,
        )
    }

    override suspend fun clearSessionToken() {
        _state.value = _state.value.copy(
            sessionToken = null,
            tokenServerOrigin = null,
            lastGatePhone = null,
            biometricLockEnabled = false,
            biometricKeyId = null,
            pushOptIn = false,
            loaded = true,
        )
    }

    override suspend fun setServerUrl(url: String) {
        _state.value = _state.value.copy(serverUrl = normalizeServerUrl(url), loaded = true)
    }

    override suspend fun setLastGatePhone(phone: String?) {
        _state.value = _state.value.copy(lastGatePhone = phone)
    }

    override suspend fun setThemeMode(mode: ThemeMode) {
        _state.value = _state.value.copy(themeMode = mode)
    }

    override suspend fun setBiometricLockEnabled(enabled: Boolean) {
        _state.value = _state.value.copy(biometricLockEnabled = enabled)
    }

    override suspend fun setBiometricKeyId(keyId: String?) {
        _state.value = _state.value.copy(biometricKeyId = keyId?.ifBlank { null })
    }

    override suspend fun setCrashlyticsEnabled(enabled: Boolean) {
        _state.value = _state.value.copy(crashlyticsEnabled = enabled)
    }

    override suspend fun setPushOptIn(enabled: Boolean) {
        _state.value = _state.value.copy(pushOptIn = enabled)
    }
}
