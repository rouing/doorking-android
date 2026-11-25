package com.doorking.gate.core.auth

import android.content.Context
import com.doorking.gate.BuildConfig
import com.doorking.gate.tile.GateRelayTileManager
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val DEFAULT_SERVER_URL = BuildConfig.DEFAULT_SERVER_URL

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class StoredAuthState(
    val serverUrl: String = DEFAULT_SERVER_URL,
    val sessionToken: String? = null,
    val lastGatePhone: String? = null,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val biometricLockEnabled: Boolean = false,
    val biometricKeyId: String? = null,
    val loaded: Boolean = false,
)

/**
 * Abstraction for persisting server URL, encrypted `dks_` session token, and theme preference.
 * Tests supply a fast in-memory fake without needing AndroidKeyStore or DataStore.
 */
interface SessionStorage {
    val state: StateFlow<StoredAuthState>
    fun currentServerUrl(): String
    fun currentSessionToken(): String?
    suspend fun awaitLoaded(): StoredAuthState
    suspend fun saveSessionToken(token: String)
    suspend fun clearSessionToken()
    suspend fun setServerUrl(url: String)
    suspend fun setLastGatePhone(phone: String?)
    suspend fun setThemeMode(mode: ThemeMode)
    suspend fun setBiometricLockEnabled(enabled: Boolean)
    suspend fun setBiometricKeyId(keyId: String?)
}

fun normalizeServerUrl(raw: String): String {
    val trimmed = raw.trim().trimEnd('/')
    if (trimmed.isEmpty()) return DEFAULT_SERVER_URL
    return if (trimmed.startsWith("https://") || trimmed.startsWith("http://")) trimmed else "https://$trimmed"
}

private val Context.authDataStore: DataStore<Preferences> by preferencesDataStore(name = "doorking_auth")

class EncryptedSessionStore(
    context: Context,
    private val cipher: TokenCipher = KeystoreTokenCipher(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : SessionStorage {

    private val appContext = context.applicationContext
    private val dataStore = appContext.authDataStore
    private val _state = MutableStateFlow(StoredAuthState())
    override val state: StateFlow<StoredAuthState> = _state.asStateFlow()

    init {
        scope.launch {
            dataStore.data.collect { prefs ->
                val encrypted = prefs[KEY_SESSION_TOKEN]
                val decrypted = encrypted?.let(cipher::decrypt)
                val server = normalizeServerUrl(prefs[KEY_SERVER_URL] ?: DEFAULT_SERVER_URL)
                val mode = prefs[KEY_THEME_MODE]?.let { name ->
                    ThemeMode.entries.firstOrNull { it.name == name }
                } ?: ThemeMode.SYSTEM
                _state.value = StoredAuthState(
                    serverUrl = server,
                    sessionToken = decrypted,
                    lastGatePhone = prefs[KEY_LAST_GATE],
                    themeMode = mode,
                    biometricLockEnabled = prefs[KEY_BIOMETRIC_LOCK] == true,
                    biometricKeyId = prefs[KEY_BIOMETRIC_KEY_ID],
                    loaded = true,
                )
            }
        }
    }

    override fun currentServerUrl(): String = _state.value.serverUrl

    override fun currentSessionToken(): String? = _state.value.sessionToken

    override suspend fun awaitLoaded(): StoredAuthState = state.first { it.loaded }

    override suspend fun saveSessionToken(token: String) {
        val encrypted = withContext(Dispatchers.IO) { cipher.encrypt(token) }
        dataStore.edit { it[KEY_SESSION_TOKEN] = encrypted }
        _state.value = _state.value.copy(sessionToken = token, loaded = true)
    }

    override suspend fun clearSessionToken() {
        dataStore.edit {
            it.remove(KEY_SESSION_TOKEN)
            it.remove(KEY_LAST_GATE)
            it.remove(KEY_BIOMETRIC_LOCK)
            it.remove(KEY_BIOMETRIC_KEY_ID)
        }
        BiometricKeyManager.deleteLocalKey()
        GateRelayTileManager.clearAll(appContext)
        _state.value = _state.value.copy(
            sessionToken = null,
            lastGatePhone = null,
            biometricLockEnabled = false,
            biometricKeyId = null,
            loaded = true,
        )
    }

    override suspend fun setServerUrl(url: String) {
        val normalized = normalizeServerUrl(url)
        dataStore.edit { it[KEY_SERVER_URL] = normalized }
        _state.value = _state.value.copy(serverUrl = normalized, loaded = true)
    }

    override suspend fun setLastGatePhone(phone: String?) {
        dataStore.edit {
            if (phone == null) it.remove(KEY_LAST_GATE) else it[KEY_LAST_GATE] = phone
        }
        _state.value = _state.value.copy(lastGatePhone = phone)
    }

    override suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { it[KEY_THEME_MODE] = mode.name }
        _state.value = _state.value.copy(themeMode = mode)
    }

    override suspend fun setBiometricLockEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_BIOMETRIC_LOCK] = enabled }
        _state.value = _state.value.copy(biometricLockEnabled = enabled)
    }

    override suspend fun setBiometricKeyId(keyId: String?) {
        dataStore.edit {
            if (keyId.isNullOrBlank()) it.remove(KEY_BIOMETRIC_KEY_ID) else it[KEY_BIOMETRIC_KEY_ID] = keyId
        }
        _state.value = _state.value.copy(biometricKeyId = keyId?.ifBlank { null })
    }

    private companion object {
        val KEY_SERVER_URL = stringPreferencesKey("server_url")
        val KEY_SESSION_TOKEN = stringPreferencesKey("session_token_enc")
        val KEY_LAST_GATE = stringPreferencesKey("last_gate_phone")
        val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        val KEY_BIOMETRIC_LOCK = booleanPreferencesKey("biometric_lock_enabled")
        val KEY_BIOMETRIC_KEY_ID = stringPreferencesKey("biometric_key_id")
    }
}
