package com.doorking.gate.fakes

import com.doorking.gate.core.auth.DEFAULT_SERVER_URL
import com.doorking.gate.core.auth.SessionStorage
import com.doorking.gate.core.auth.StoredAuthState
import com.doorking.gate.core.auth.ThemeMode
import com.doorking.gate.core.auth.normalizeServerUrl
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class FakeSessionStorage(
    initialServerUrl: String = DEFAULT_SERVER_URL,
    initialToken: String? = null,
    initialLastGate: String? = null,
) : SessionStorage {

    private val _state = MutableStateFlow(
        StoredAuthState(
            serverUrl = normalizeServerUrl(initialServerUrl),
            sessionToken = initialToken,
            lastGatePhone = initialLastGate,
            loaded = true,
        ),
    )
    override val state: StateFlow<StoredAuthState> = _state.asStateFlow()

    override fun currentServerUrl(): String = _state.value.serverUrl

    override fun currentSessionToken(): String? = _state.value.sessionToken

    override suspend fun awaitLoaded(): StoredAuthState = _state.value

    override suspend fun saveSessionToken(token: String) {
        _state.value = _state.value.copy(sessionToken = token, loaded = true)
    }

    override suspend fun clearSessionToken() {
        _state.value = _state.value.copy(
            sessionToken = null,
            lastGatePhone = null,
            biometricLockEnabled = false,
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
}
