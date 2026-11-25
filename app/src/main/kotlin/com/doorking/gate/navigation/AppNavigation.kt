package com.doorking.gate.navigation

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.ui.NavDisplay
import com.doorking.gate.AppContainer
import com.doorking.gate.core.auth.BiometricKeyManager
import com.doorking.gate.core.auth.BiometricUnlockManager
import com.doorking.gate.core.auth.PasskeyAuthenticator
import com.doorking.gate.core.auth.PasskeyResult
import com.doorking.gate.core.auth.PendingStepUp
import com.doorking.gate.core.auth.SessionStorage
import com.doorking.gate.core.auth.ThemeMode
import com.doorking.gate.core.network.AcceptInviteRequest
import com.doorking.gate.core.network.ApiClientFactory
import com.doorking.gate.core.network.ApiErrorResponse
import com.doorking.gate.core.network.AuthEvent
import com.doorking.gate.core.network.AuthEventBus
import com.doorking.gate.core.network.ChangePasswordRequest
import com.doorking.gate.core.network.DoorkingApi
import com.doorking.gate.core.network.GateInfoDto
import com.doorking.gate.core.network.InviteCheckRequest
import com.doorking.gate.core.network.InviteCheckResponse
import com.doorking.gate.core.network.LoginRequest
import com.doorking.gate.core.network.NativeSsoExchangeRequest
import com.doorking.gate.core.network.PasskeyAssertionRequest
import com.doorking.gate.core.network.PasswordResetRequest
import com.doorking.gate.core.network.SessionResponse
import com.doorking.gate.core.network.BiometricChallengeRequest
import com.doorking.gate.core.network.RegisterBiometricKeyRequest
import com.doorking.gate.core.network.SsoStartRequest
import com.doorking.gate.core.network.StepUpBiometricSignedRequest
import com.doorking.gate.core.network.StepUpPasswordRequest
import com.doorking.gate.core.push.FirebaseHelper
import com.doorking.gate.core.ui.components.ErrorBanner
import com.doorking.gate.core.ui.components.FullScreenLoading
import com.doorking.gate.core.ui.components.PasswordOutlinedField
import com.doorking.gate.feature.auth.AcceptInviteScreen
import com.doorking.gate.feature.auth.ForcedPasswordChangeScreen
import com.doorking.gate.feature.auth.SignInScreen
import com.doorking.gate.feature.auth.parseInviteDeepLink
import com.doorking.gate.feature.gates.GatePickerScreen
import com.doorking.gate.feature.portal.ResidentPortalScreen
import com.doorking.gate.feature.staff.StaffGateShell
import com.doorking.gate.widget.GateWidgetUpdater
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/** Type-safe Navigation 3 top-level destinations. */
@Serializable
sealed interface AppRoute {
    @Serializable data object Splash : AppRoute
    @Serializable data object SignIn : AppRoute
    @Serializable data class AcceptInvite(val serverUrl: String, val token: String) : AppRoute
    @Serializable data object ForcedPasswordChange : AppRoute
    @Serializable data object GatePicker : AppRoute
    @Serializable data class ResidentPortal(val gatePhone: String) : AppRoute
    @Serializable data class StaffGate(val gatePhone: String) : AppRoute
}

sealed interface BootstrapState {
    data object Loading : BootstrapState
    data class Ready(val startRoute: AppRoute, val session: SessionResponse?) : BootstrapState
    data class OfflineError(val message: String) : BootstrapState
}

/**
 * Coordinates app startup session bootstrap, password / passkey / SSO sign-in, forced password changes,
 * passkey step-up verification, and global `401` / `403` auth events.
 */
class SessionBootstrapViewModel(
    private val sessionStorage: SessionStorage,
    private val api: DoorkingApi,
    private val authEvents: AuthEventBus,
    private val passkeyAuthenticator: PasskeyAuthenticator,
    private val ssoCallbacks: SharedFlow<Uri>? = null,
    private val inviteLinks: SharedFlow<Uri>? = null,
    private val onWidgetSync: () -> Unit = {},
    private val onBeforeSignOut: suspend () -> Unit = {},
) : ViewModel() {

    private val _bootstrap = MutableStateFlow<BootstrapState>(BootstrapState.Loading)
    val bootstrap: StateFlow<BootstrapState> = _bootstrap.asStateFlow()

    private val _ssoError = MutableStateFlow<String?>(null)
    val ssoError: StateFlow<String?> = _ssoError.asStateFlow()

    @Volatile
    private var pendingSsoVerifier: String? = null

    init {
        refreshSession()
        viewModelScope.launch {
            authEvents.events.collect { event ->
                when (event) {
                    AuthEvent.SessionExpired -> {
                        _bootstrap.value = BootstrapState.Ready(AppRoute.SignIn, null)
                    }
                    AuthEvent.MustChangePassword -> {
                        val current = (_bootstrap.value as? BootstrapState.Ready)?.session
                        _bootstrap.value = BootstrapState.Ready(AppRoute.ForcedPasswordChange, current)
                    }
                }
            }
        }
        if (ssoCallbacks != null) {
            viewModelScope.launch {
                ssoCallbacks.collect { uri -> handleSsoCallback(uri) }
            }
        }
        if (inviteLinks != null) {
            viewModelScope.launch {
                inviteLinks.collect { uri ->
                    val parsed = parseInviteDeepLink(uri) ?: return@collect
                    val currentSession = (_bootstrap.value as? BootstrapState.Ready)?.session
                    _bootstrap.value = BootstrapState.Ready(
                        AppRoute.AcceptInvite(parsed.serverUrl, parsed.token),
                        currentSession,
                    )
                }
            }
        }
    }

    private suspend fun handleSsoCallback(uri: Uri) {
        val errParam = uri.getQueryParameter("error")
        if (!errParam.isNullOrBlank()) {
            pendingSsoVerifier = null
            _ssoError.value = errParam
            return
        }
        val code = uri.getQueryParameter("code")
        val verifier = pendingSsoVerifier
        pendingSsoVerifier = null
        if (code.isNullOrBlank() || verifier.isNullOrBlank()) {
            _ssoError.value = "Single sign-in session could not be verified. Try again."
            return
        }
        _ssoError.value = null
        _bootstrap.value = BootstrapState.Loading
        runCatching {
            val res = api.exchangeNativeSso(NativeSsoExchangeRequest(code = code, verifier = verifier))
            if (!res.isSuccessful) error(parseErrorBody(res.errorBody()?.string(), res.code(), "SSO sign-in failed"))
            val token = res.body()?.sessionToken ?: error("Server did not return a native session token.")
            sessionStorage.saveSessionToken(token)
            val session = api.getSession()
            _bootstrap.value = BootstrapState.Ready(resolveStartRoute(session, session.gate?.phoneNumber), session)
            onWidgetSync()
        }.onFailure { err ->
            val info = runCatching { api.getSession() }.getOrNull()
            _ssoError.value = err.message ?: "SSO sign-in failed"
            _bootstrap.value = BootstrapState.Ready(AppRoute.SignIn, info)
        }
    }

    fun clearSsoError() {
        _ssoError.value = null
    }

    fun refreshSession() {
        viewModelScope.launch {
            _bootstrap.value = BootstrapState.Loading
            val stored = sessionStorage.awaitLoaded()
            if (stored.sessionToken.isNullOrBlank()) {
                val info = runCatching { api.getSession() }.getOrNull()
                _bootstrap.value = BootstrapState.Ready(AppRoute.SignIn, info)
                return@launch
            }
            runCatching { api.getSession() }
                .onSuccess { session ->
                    if (!session.loggedIn) {
                        sessionStorage.clearSessionToken()
                        _bootstrap.value = BootstrapState.Ready(AppRoute.SignIn, session)
                    } else {
                        _bootstrap.value = BootstrapState.Ready(resolveStartRoute(session, stored.lastGatePhone), session)
                    }
                }
                .onFailure { err ->
                    _bootstrap.value = BootstrapState.OfflineError(ApiClientFactory.parseErrorMessage(err))
                }
        }
    }

    suspend fun probeServer(serverUrl: String): Result<SessionResponse> {
        sessionStorage.setServerUrl(serverUrl)
        return runCatching { api.getSession() }
    }

    suspend fun signInPassword(serverUrl: String, identifier: String, password: String): Result<SessionResponse> {
        sessionStorage.setServerUrl(serverUrl)
        return runCatching {
            val response = api.signIn(LoginRequest(user = identifier.trim(), password = password))
            if (!response.isSuccessful) error(parseErrorBody(response.errorBody()?.string(), response.code(), "Sign-in failed"))
            val token = response.body()?.sessionToken
                ?: error("Server did not return a native session token. Update the server to the latest build.")
            sessionStorage.saveSessionToken(token)
            val session = api.getSession()
            _bootstrap.value = BootstrapState.Ready(resolveStartRoute(session, session.gate?.phoneNumber), session)
            onWidgetSync()
            session
        }
    }

    suspend fun signInPasskey(serverUrl: String, activityContext: Context): Result<SessionResponse?> {
        sessionStorage.setServerUrl(serverUrl)
        return runCatching {
            val optRes = api.getPasskeySignInOptions()
            if (!optRes.isSuccessful) error(parseErrorBody(optRes.errorBody()?.string(), optRes.code(), "Passkey sign-in unavailable"))
            val optionsJson = optRes.body()?.toString() ?: error("Empty passkey options from server")
            when (val auth = passkeyAuthenticator.authenticate(activityContext, optionsJson)) {
                PasskeyResult.Cancelled -> null
                is PasskeyResult.Error -> error(auth.message)
                is PasskeyResult.Success -> {
                    val assertionElement = ApiClientFactory.json.parseToJsonElement(auth.assertionJson)
                    val loginRes = api.signInWithPasskey(PasskeyAssertionRequest(response = assertionElement))
                    if (!loginRes.isSuccessful) error(parseErrorBody(loginRes.errorBody()?.string(), loginRes.code(), "Passkey sign-in refused"))
                    val token = loginRes.body()?.sessionToken
                        ?: error("Server did not return a native session token.")
                    sessionStorage.saveSessionToken(token)
                    val session = api.getSession()
                    _bootstrap.value = BootstrapState.Ready(resolveStartRoute(session, session.gate?.phoneNumber), session)
                    onWidgetSync()
                    session
                }
            }
        }
    }

    suspend fun requestPasswordReset(serverUrl: String, identifier: String): Result<String> {
        sessionStorage.setServerUrl(serverUrl)
        return runCatching {
            val res = api.requestPasswordReset(PasswordResetRequest(identifier = identifier))
            if (!res.isSuccessful) error(parseErrorBody(res.errorBody()?.string(), res.code(), "Could not send reset link"))
            res.body()?.message?.ifBlank { "If that account exists, a reset link is on its way." }
                ?: "If that account exists, a reset link is on its way."
        }
    }

    suspend fun startSso(serverUrl: String, providerId: String): Result<String> {
        sessionStorage.setServerUrl(serverUrl)
        _ssoError.value = null
        return runCatching {
            val res = api.startSso(SsoStartRequest(provider = providerId, native = true))
            if (!res.isSuccessful) error(parseErrorBody(res.errorBody()?.string(), res.code(), "Could not start SSO"))
            val body = res.body() ?: error("Server did not return an SSO redirect URL")
            pendingSsoVerifier = body.nativeVerifier
                ?: error("Server did not return native SSO verifier. Update the server to the latest build.")
            body.url
        }
    }

    suspend fun changePassword(currentPassword: String, newPassword: String): Result<Unit> = runCatching {
        val res = api.changePassword(ChangePasswordRequest(currentPassword = currentPassword, newPassword = newPassword))
        if (!res.isSuccessful) error(parseErrorBody(res.errorBody()?.string(), res.code(), "Password change failed"))
        val session = api.getSession()
        _bootstrap.value = BootstrapState.Ready(resolveStartRoute(session, session.gate?.phoneNumber), session)
    }

    suspend fun checkInvite(serverUrl: String, token: String): Result<InviteCheckResponse> = runCatching {
        sessionStorage.setServerUrl(serverUrl)
        val res = api.checkInvite(InviteCheckRequest(token = token))
        if (!res.isSuccessful) error(parseErrorBody(res.errorBody()?.string(), res.code(), "Invalid invitation link"))
        res.body() ?: error("Empty invitation response")
    }

    suspend fun acceptInvite(serverUrl: String, token: String, password: String): Result<Unit> = runCatching {
        sessionStorage.setServerUrl(serverUrl)
        val res = api.acceptInvite(AcceptInviteRequest(token = token, password = password))
        if (!res.isSuccessful) error(parseErrorBody(res.errorBody()?.string(), res.code(), "Could not accept invitation"))
        val sessionToken = res.body()?.sessionToken
            ?: error("Server did not return a native session token.")
        sessionStorage.saveSessionToken(sessionToken)
        val session = api.getSession()
        _bootstrap.value = BootstrapState.Ready(resolveStartRoute(session, session.gate?.phoneNumber), session)
        onWidgetSync()
    }

    fun cancelInvite() {
        refreshSession()
    }

    suspend fun completePasskeyStepUp(activityContext: Context): Result<Boolean> = runCatching {
        val optRes = api.getStepUpOptions()
        if (!optRes.isSuccessful) error(parseErrorBody(optRes.errorBody()?.string(), optRes.code(), "Step-up passkey unavailable"))
        val optionsJson = optRes.body()?.toString() ?: error("Empty step-up options")
        when (val auth = passkeyAuthenticator.authenticate(activityContext, optionsJson)) {
            PasskeyResult.Cancelled -> false
            is PasskeyResult.Error -> error(auth.message)
            is PasskeyResult.Success -> {
                val assertionElement = ApiClientFactory.json.parseToJsonElement(auth.assertionJson)
                val confirmRes = api.confirmStepUp(PasskeyAssertionRequest(response = assertionElement))
                if (!confirmRes.isSuccessful) error(parseErrorBody(confirmRes.errorBody()?.string(), confirmRes.code(), "Passkey confirmation refused"))
                true
            }
        }
    }

    suspend fun completePasswordStepUp(password: String): Result<Boolean> = runCatching {
        val confirmRes = api.confirmStepUpWithPassword(StepUpPasswordRequest(password = password))
        if (!confirmRes.isSuccessful) error(parseErrorBody(confirmRes.errorBody()?.string(), confirmRes.code(), "Password confirmation refused"))
        true
    }

    /**
     * Silently enrolls a hardware-backed ECDSA P-256 keypair in Android Keystore and registers its public key
     * on the server while the session is fresh/strong. Safe no-op on devices without strong biometrics or if
     * already registered and valid.
     */
    suspend fun ensureBiometricKeyRegistered(context: Context) {
        if (!BiometricUnlockManager.isStrongBiometricAvailable(context)) return
        val existingId = sessionStorage.state.value.biometricKeyId
        if (!existingId.isNullOrBlank() && BiometricKeyManager.initSigningCrypto() != null) return
        runCatching {
            val mat = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                BiometricKeyManager.generateFreshKey()
            }
            val res = api.registerBiometricKey(
                RegisterBiometricKeyRequest(
                    keyId = mat.keyId,
                    publicKeySpki = mat.publicKeySpkiBase64,
                    label = mat.label,
                ),
            )
            if (res.isSuccessful) {
                sessionStorage.setBiometricKeyId(mat.keyId)
            }
        }
    }

    suspend fun requestBiometricStepUpChallenge(): Result<Pair<String, String>> = runCatching {
        val keyId = sessionStorage.state.value.biometricKeyId?.takeIf { it.isNotBlank() }
            ?: error("Fingerprint step-up key not registered on this device")
        val res = api.getBiometricStepUpChallenge(BiometricChallengeRequest(keyId = keyId))
        if (!res.isSuccessful) {
            if (res.code() == 404) {
                sessionStorage.setBiometricKeyId(null)
            }
            error(parseErrorBody(res.errorBody()?.string(), res.code(), "Fingerprint challenge unavailable"))
        }
        val body = res.body() ?: error("Empty biometric challenge")
        if (body.challenge.isBlank()) error("Empty biometric challenge")
        keyId to body.challenge
    }

    suspend fun submitSignedBiometricStepUp(keyId: String, challenge: String, signatureBase64: String): Result<Boolean> = runCatching {
        val confirmRes = api.confirmStepUpWithBiometric(
            StepUpBiometricSignedRequest(
                biometricKeyId = keyId,
                challenge = challenge,
                signature = signatureBase64,
            ),
        )
        if (!confirmRes.isSuccessful) error(parseErrorBody(confirmRes.errorBody()?.string(), confirmRes.code(), "Fingerprint confirmation refused"))
        true
    }

    fun onGateSelected(gate: GateInfoDto) {
        val current = (_bootstrap.value as? BootstrapState.Ready)?.session
        val updatedSession = current?.copy(gate = gate)
        val dest = if (gate.role.equals("resident", ignoreCase = true)) {
            AppRoute.ResidentPortal(gate.phoneNumber)
        } else {
            AppRoute.StaffGate(gate.phoneNumber)
        }
        _bootstrap.value = BootstrapState.Ready(dest, updatedSession)
        onWidgetSync()
        // Refresh /api/session so gate-scoped fields like `hasPortalUnit` reflect the newly opened gate.
        viewModelScope.launch {
            runCatching { api.getSession() }.onSuccess { fresh ->
                if (fresh.loggedIn) {
                    val active = _bootstrap.value as? BootstrapState.Ready
                    if (active != null && active.startRoute == dest) {
                        _bootstrap.value = BootstrapState.Ready(dest, fresh)
                    }
                }
            }
        }
    }

    fun openResidentPortal(gatePhone: String) {
        val current = (_bootstrap.value as? BootstrapState.Ready)?.session
        _bootstrap.value = BootstrapState.Ready(AppRoute.ResidentPortal(gatePhone), current)
    }

    fun openStaffGate(gatePhone: String) {
        val current = (_bootstrap.value as? BootstrapState.Ready)?.session
        _bootstrap.value = BootstrapState.Ready(AppRoute.StaffGate(gatePhone), current)
    }

    fun returnToGatePicker() {
        viewModelScope.launch {
            runCatching { api.closeGate() }
            sessionStorage.setLastGatePhone(null)
            val current = (_bootstrap.value as? BootstrapState.Ready)?.session
            _bootstrap.value = BootstrapState.Ready(AppRoute.GatePicker, current?.copy(gate = null, hasPortalUnit = false))
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch {
            sessionStorage.setThemeMode(mode)
        }
    }

    fun cycleThemeMode() {
        viewModelScope.launch {
            val next = when (sessionStorage.state.value.themeMode) {
                ThemeMode.SYSTEM -> ThemeMode.LIGHT
                ThemeMode.LIGHT -> ThemeMode.DARK
                ThemeMode.DARK -> ThemeMode.SYSTEM
            }
            sessionStorage.setThemeMode(next)
        }
    }

    fun signOut() {
        viewModelScope.launch {
            runCatching { onBeforeSignOut() }
            runCatching { api.signOut() }
            sessionStorage.clearSessionToken()
            val info = runCatching { api.getSession() }.getOrNull()
            _bootstrap.value = BootstrapState.Ready(AppRoute.SignIn, info)
            onWidgetSync()
        }
    }

    companion object {
        private fun parseErrorBody(raw: String?, code: Int, fallback: String): String {
            if (!raw.isNullOrBlank()) {
                val parsed = runCatching { ApiClientFactory.json.decodeFromString<ApiErrorResponse>(raw) }.getOrNull()
                if (parsed != null && parsed.error.isNotBlank()) return parsed.error
            }
            return "$fallback (HTTP $code)"
        }

        fun resolveStartRoute(session: SessionResponse, lastGatePhone: String?): AppRoute {
            if (!session.loggedIn) return AppRoute.SignIn
            if (session.mustChangePassword) return AppRoute.ForcedPasswordChange
            val openPhone = session.gate?.phoneNumber ?: lastGatePhone
            if (session.isResidentOnly && !openPhone.isNullOrBlank()) {
                return AppRoute.ResidentPortal(openPhone)
            }
            if (!session.isResidentOnly && !openPhone.isNullOrBlank() && session.gate != null) {
                return AppRoute.StaffGate(openPhone)
            }
            return AppRoute.GatePicker
        }

        fun factory(container: AppContainer): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                SessionBootstrapViewModel(
                    sessionStorage = container.sessionStorage,
                    api = container.api,
                    authEvents = container.authEvents,
                    passkeyAuthenticator = container.passkeyAuthenticator,
                    ssoCallbacks = container.ssoCallbacks,
                    inviteLinks = container.inviteLinks,
                    onWidgetSync = { GateWidgetUpdater.enqueueSync(container.appContext) },
                    onBeforeSignOut = {
                        FirebaseHelper.unregisterCurrentDevice(container.appContext, container.api)
                    },
                ) as T
        }
    }
}

@Composable
fun DoorkingNavRoot(container: AppContainer) {
    val vm: SessionBootstrapViewModel = viewModel(factory = SessionBootstrapViewModel.factory(container))
    val bootstrap by vm.bootstrap.collectAsStateWithLifecycle()
    val ssoError by vm.ssoError.collectAsStateWithLifecycle()
    val storedAuth by container.sessionStorage.state.collectAsStateWithLifecycle()
    val pendingStepUp by container.stepUpCoordinator.pending.collectAsStateWithLifecycle()
    val backStack = remember { mutableStateListOf<AppRoute>(AppRoute.Splash) }

    LaunchedEffect(bootstrap) {
        when (val state = bootstrap) {
            BootstrapState.Loading -> {
                if (backStack.lastOrNull() != AppRoute.Splash) {
                    backStack.clear()
                    backStack.add(AppRoute.Splash)
                }
            }
            is BootstrapState.Ready -> {
                if (backStack.lastOrNull() != state.startRoute) {
                    backStack.clear()
                    backStack.add(state.startRoute)
                }
                if (state.session?.loggedIn == true) {
                    FirebaseHelper.registerCurrentDevice(
                        context = container.appContext,
                        api = container.api,
                        sessionStorage = container.sessionStorage,
                    )
                    vm.ensureBiometricKeyRegistered(container.appContext)
                }
            }
            is BootstrapState.OfflineError -> Unit
        }
    }

    if (pendingStepUp != null) {
        StepUpConfirmationDialog(
            pending = pendingStepUp!!,
            biometricKeyId = storedAuth.biometricKeyId,
            onRequestBiometricChallenge = { vm.requestBiometricStepUpChallenge() },
            onSubmitBiometricSignature = { keyId, challenge, sig -> vm.submitSignedBiometricStepUp(keyId, challenge, sig) },
            onConfirmPasskey = { ctx ->
                vm.completePasskeyStepUp(ctx).also { if (it.getOrDefault(false)) vm.ensureBiometricKeyRegistered(container.appContext) }
            },
            onConfirmPassword = { pw ->
                vm.completePasswordStepUp(pw).also { if (it.getOrDefault(false)) vm.ensureBiometricKeyRegistered(container.appContext) }
            },
            onResolve = container.stepUpCoordinator::resolve,
        )
    }

    if (bootstrap is BootstrapState.OfflineError) {
        val msg = (bootstrap as BootstrapState.OfflineError).message
        Scaffold { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .consumeWindowInsets(padding)
                    .padding(padding)
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                ErrorBanner(message = msg)
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Button(onClick = vm::refreshSession, modifier = Modifier.weight(1f)) {
                        Text("Retry")
                    }
                    OutlinedButton(onClick = vm::signOut, modifier = Modifier.weight(1f)) {
                        Text("Sign in")
                    }
                }
            }
        }
        return
    }

    val currentSession = (bootstrap as? BootstrapState.Ready)?.session

    NavDisplay(
        backStack = backStack,
        onBack = {
            if (backStack.size > 1) backStack.removeLastOrNull()
        },
        entryProvider = { key ->
            when (key) {
                AppRoute.Splash -> NavEntry(key) {
                    FullScreenLoading("Connecting to DoorKing…")
                }

                AppRoute.SignIn -> NavEntry(key) {
                    SignInScreen(
                        initialServerUrl = storedAuth.serverUrl,
                        initialSessionInfo = currentSession,
                        externalError = ssoError,
                        onClearExternalError = vm::clearSsoError,
                        onProbeServer = vm::probeServer,
                        onSignInPassword = vm::signInPassword,
                        onSignInPasskey = vm::signInPasskey,
                        onRequestPasswordReset = vm::requestPasswordReset,
                        onStartSso = vm::startSso,
                    )
                }

                is AppRoute.AcceptInvite -> NavEntry(key) {
                    AcceptInviteScreen(
                        serverUrl = key.serverUrl,
                        token = key.token,
                        onCheckInvite = vm::checkInvite,
                        onAcceptInvite = vm::acceptInvite,
                        onCancel = vm::cancelInvite,
                    )
                }

                AppRoute.ForcedPasswordChange -> NavEntry(key) {
                    ForcedPasswordChangeScreen(
                        username = currentSession?.user.orEmpty(),
                        onChangePassword = vm::changePassword,
                        onSignOut = vm::signOut,
                    )
                }

                AppRoute.GatePicker -> NavEntry(key) {
                    GatePickerScreen(
                        container = container,
                        session = currentSession,
                        themeMode = storedAuth.themeMode,
                        onCycleTheme = vm::cycleThemeMode,
                        onGateOpened = vm::onGateSelected,
                        onSignOut = vm::signOut,
                    )
                }

                is AppRoute.ResidentPortal -> NavEntry(key) {
                    ResidentPortalScreen(
                        container = container,
                        gatePhone = key.gatePhone,
                        session = currentSession,
                        themeMode = storedAuth.themeMode,
                        onSelectTheme = vm::setThemeMode,
                        onSwitchToStaff = if (currentSession?.isResidentOnly == false) {
                            { vm.openStaffGate(key.gatePhone) }
                        } else {
                            null
                        },
                        onSignOut = vm::signOut,
                    )
                }

                is AppRoute.StaffGate -> NavEntry(key) {
                    StaffGateShell(
                        container = container,
                        gatePhone = key.gatePhone,
                        session = currentSession,
                        themeMode = storedAuth.themeMode,
                        onSelectTheme = vm::setThemeMode,
                        onSwitchGate = vm::returnToGatePicker,
                        onOpenResidentPortal = { vm.openResidentPortal(key.gatePhone) },
                        onSignOut = vm::signOut,
                    )
                }
            }
        },
    )
}

@Composable
private fun StepUpConfirmationDialog(
    pending: PendingStepUp,
    biometricKeyId: String?,
    onRequestBiometricChallenge: suspend () -> Result<Pair<String, String>>,
    onSubmitBiometricSignature: suspend (keyId: String, challenge: String, signatureBase64: String) -> Result<Boolean>,
    onConfirmPasskey: suspend (Context) -> Result<Boolean>,
    onConfirmPassword: suspend (String) -> Result<Boolean>,
    onResolve: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val biometricAvailable = remember(context, biometricKeyId) {
        !biometricKeyId.isNullOrBlank() &&
            BiometricUnlockManager.isStrongBiometricAvailable(context) &&
            BiometricUnlockManager.findFragmentActivity(context) != null &&
            BiometricKeyManager.initSigningCrypto() != null
    }
    val hasPasskey = pending.error.hasPasskey
    val canUsePassword = pending.error.canUsePassword
    var usePasswordMode by remember(pending, biometricAvailable) { mutableStateOf(!biometricAvailable && !hasPasskey && canUsePassword) }
    var password by remember(pending) { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun submitPassword() {
        if (password.isBlank() || busy) return
        busy = true
        error = null
        scope.launch {
            onConfirmPassword(password)
                .onSuccess { ok ->
                    if (ok) onResolve(true) else busy = false
                }
                .onFailure { err ->
                    error = err.message ?: "Password confirmation failed"
                    busy = false
                }
        }
    }

    fun triggerBiometric() {
        val activity = BiometricUnlockManager.findFragmentActivity(context) ?: return
        if (busy) return
        busy = true
        error = null
        scope.launch {
            val cryptoSig = BiometricKeyManager.initSigningCrypto()
            if (cryptoSig == null) {
                error = "Hardware fingerprint key was reset. Confirm with passkey or password once to re-enable."
                busy = false
                return@launch
            }
            onRequestBiometricChallenge()
                .onFailure { err ->
                    error = err.message ?: "Could not start fingerprint confirmation"
                    busy = false
                }
                .onSuccess { (keyId, challenge) ->
                    BiometricUnlockManager.authenticateWithCrypto(
                        activity = activity,
                        signature = cryptoSig,
                        title = "Confirm it's you",
                        subtitle = "Scan your fingerprint to sign this verification",
                        onSuccess = { unlockedSig ->
                            scope.launch {
                                runCatching { BiometricKeyManager.signChallenge(unlockedSig, challenge) }
                                    .onFailure { err ->
                                        error = err.message ?: "Hardware signing failed"
                                        busy = false
                                    }
                                    .onSuccess { sigB64 ->
                                        onSubmitBiometricSignature(keyId, challenge, sigB64)
                                            .onSuccess { ok -> if (ok) onResolve(true) else busy = false }
                                            .onFailure { err ->
                                                error = err.message ?: "Fingerprint confirmation failed"
                                                busy = false
                                            }
                                    }
                            }
                        },
                        onError = { msg ->
                            error = msg
                            busy = false
                        },
                        onCancelled = {
                            busy = false
                        },
                    )
                }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onResolve(false) },
        icon = { Icon(Icons.Rounded.Fingerprint, contentDescription = null) },
        title = { Text(if (usePasswordMode) "Confirm with password" else "Confirm it's you") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = pending.error.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (usePasswordMode && canUsePassword) {
                    PasswordOutlinedField(
                        value = password,
                        onValueChange = { password = it },
                        label = "Current password",
                        enabled = !busy,
                        onDone = { submitPassword() },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (biometricAvailable && hasPasskey && !usePasswordMode) {
                    TextButton(
                        enabled = !busy,
                        onClick = {
                            busy = true
                            error = null
                            scope.launch {
                                onConfirmPasskey(context)
                                    .onSuccess { ok ->
                                        if (ok) onResolve(true) else busy = false
                                    }
                                    .onFailure { err ->
                                        error = err.message ?: "Passkey confirmation failed"
                                        busy = false
                                    }
                            }
                        },
                    ) {
                        Text("Use passkey instead")
                    }
                }
                if ((biometricAvailable || hasPasskey) && canUsePassword) {
                    TextButton(
                        enabled = !busy,
                        onClick = {
                            error = null
                            usePasswordMode = !usePasswordMode
                        },
                    ) {
                        Text(
                            if (usePasswordMode) {
                                if (biometricAvailable) "Use fingerprint instead" else "Use passkey instead"
                            } else {
                                "Use password instead"
                            },
                        )
                    }
                }
                if (error != null) ErrorBanner(error!!)
            }
        },
        confirmButton = {
            if (usePasswordMode && canUsePassword) {
                Button(
                    enabled = !busy && password.isNotBlank(),
                    onClick = { submitPassword() },
                ) {
                    if (busy) {
                        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                    } else {
                        Text("Confirm")
                    }
                }
            } else if (biometricAvailable) {
                Button(
                    enabled = !busy,
                    onClick = { triggerBiometric() },
                ) {
                    if (busy) {
                        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                    } else {
                        Text("Confirm with fingerprint")
                    }
                }
            } else if (hasPasskey) {
                Button(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        error = null
                        scope.launch {
                            onConfirmPasskey(context)
                                .onSuccess { ok ->
                                    if (ok) onResolve(true) else busy = false
                                }
                                .onFailure { err ->
                                    error = err.message ?: "Passkey confirmation failed"
                                    busy = false
                                }
                        }
                    },
                ) {
                    if (busy) {
                        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                    } else {
                        Text("Confirm with passkey")
                    }
                }
            }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = { onResolve(false) }) {
                Text(if (hasPasskey || canUsePassword) "Cancel" else "Close")
            }
        },
    )
}
