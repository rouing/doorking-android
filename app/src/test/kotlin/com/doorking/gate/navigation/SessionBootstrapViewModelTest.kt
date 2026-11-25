package com.doorking.gate.navigation

import android.content.Context
import android.content.ContextWrapper
import com.doorking.gate.core.auth.GoogleSignInResult
import com.doorking.gate.core.auth.PasskeyAuthenticator
import com.doorking.gate.core.auth.PasskeyResult
import com.doorking.gate.core.auth.serverOriginOf
import com.doorking.gate.core.network.ApiClientFactory
import com.doorking.gate.core.network.AuthEvent
import com.doorking.gate.core.network.AuthEventBus
import com.doorking.gate.core.network.GateInfoDto
import com.doorking.gate.core.network.SessionResponse
import com.doorking.gate.fakes.FakeSessionStorage
import com.doorking.gate.feature.auth.ParsedInviteLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionBootstrapViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val noopPasskey = object : PasskeyAuthenticator {
        override suspend fun authenticate(activityContext: Context, requestJson: String): PasskeyResult =
            PasskeyResult.Cancelled
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun routesLoggedOutToSignIn() {
        val route = SessionBootstrapViewModel.resolveStartRoute(
            session = SessionResponse(loggedIn = false),
            lastGatePhone = null,
        )
        assertEquals(AppRoute.SignIn, route)
    }

    @Test
    fun routesMustChangePasswordFirst() {
        val route = SessionBootstrapViewModel.resolveStartRoute(
            session = SessionResponse(
                loggedIn = true,
                user = "alice",
                mustChangePassword = true,
                gate = GateInfoDto(phoneNumber = "2145550100", role = "admin"),
            ),
            lastGatePhone = "2145550100",
        )
        assertEquals(AppRoute.ForcedPasswordChange, route)
    }

    @Test
    fun routesResidentOnlyUserToResidentPortal() {
        val route = SessionBootstrapViewModel.resolveStartRoute(
            session = SessionResponse(
                loggedIn = true,
                user = "res_101",
                isResidentOnly = true,
                gate = GateInfoDto(phoneNumber = "2145550100", role = "resident"),
            ),
            lastGatePhone = null,
        )
        assertEquals(AppRoute.ResidentPortal("2145550100"), route)
    }

    @Test
    fun routesStaffWithOpenGateToStaffGate() {
        val route = SessionBootstrapViewModel.resolveStartRoute(
            session = SessionResponse(
                loggedIn = true,
                user = "alice",
                isResidentOnly = false,
                gate = GateInfoDto(phoneNumber = "2145550100", siteName = "Main", role = "admin"),
            ),
            lastGatePhone = null,
        )
        assertEquals(AppRoute.StaffGate("2145550100"), route)
    }

    @Test
    fun routesStaffWithoutOpenGateToGatePicker() {
        val route = SessionBootstrapViewModel.resolveStartRoute(
            session = SessionResponse(loggedIn = true, user = "alice", gate = null),
            lastGatePhone = null,
        )
        assertEquals(AppRoute.GatePicker, route)
    }

    @Test
    fun checkInviteDoesNotMutateServerUrlOrLeakBearerToken() = runTest(testDispatcher) {
        val primaryServer = MockWebServer()
        val inviteServer = MockWebServer()
        primaryServer.start()
        inviteServer.start()
        try {
            val primaryUrl = primaryServer.url("/").toString().trimEnd('/')
            val inviteUrl = inviteServer.url("/").toString().trimEnd('/')
            val existingToken = "dks_${"7".repeat(64)}"
            val storage = FakeSessionStorage(initialServerUrl = primaryUrl, initialToken = existingToken)
            val events = AuthEventBus()
            val api = ApiClientFactory.createApi(ApiClientFactory.createOkHttp(storage, events), "$primaryUrl/")

            primaryServer.enqueue(
                MockResponse().setResponseCode(200)
                    .addHeader("Content-Type", "application/json")
                    .setBody("""{"loggedIn":true,"user":"alice"}"""),
            )
            inviteServer.enqueue(
                MockResponse().setResponseCode(200)
                    .addHeader("Content-Type", "application/json")
                    .setBody("""{"username":"bob","isResident":true,"email":"bob@example.com"}"""),
            )

            val vm = SessionBootstrapViewModel(storage, api, events, noopPasskey)
            advanceUntilIdle()

            val res = vm.checkInvite(inviteUrl, "t".repeat(32))
            assertTrue(res.isSuccess)
            assertEquals("bob", res.getOrNull()?.username)
            // Original serverUrl and token must remain untouched during preview.
            assertEquals(primaryUrl, storage.currentServerUrl())
            assertEquals(existingToken, storage.currentSessionToken())
            val inviteReq = inviteServer.takeRequest()
            assertEquals("/api/invite/check", inviteReq.path)
            assertNull(inviteReq.getHeader("Authorization"))
        } finally {
            primaryServer.shutdown()
            inviteServer.shutdown()
        }
    }

    private fun kotlinx.coroutines.test.TestScope.awaitBootstrap(
        vm: SessionBootstrapViewModel,
        predicate: (BootstrapState) -> Boolean,
    ): BootstrapState {
        val deadline = System.currentTimeMillis() + 5_000L
        while (System.currentTimeMillis() < deadline) {
            advanceUntilIdle()
            val cur = vm.bootstrap.value
            if (predicate(cur)) return cur
            Thread.sleep(10)
        }
        advanceUntilIdle()
        return vm.bootstrap.value
    }

    @Test
    fun acceptInviteClearsOldSessionSwitchesServerAndDelaysNavigationUntilOnboardingCompletes() = runTest(testDispatcher) {
        val primaryServer = MockWebServer()
        val inviteServer = MockWebServer()
        primaryServer.start()
        inviteServer.start()
        try {
            val primaryUrl = primaryServer.url("/").toString().trimEnd('/')
            val inviteUrl = inviteServer.url("/").toString().trimEnd('/')
            val oldToken = "dks_${"8".repeat(64)}"
            val newToken = "dks_${"9".repeat(64)}"
            val storage = FakeSessionStorage(initialServerUrl = primaryUrl, initialToken = oldToken)
            val events = AuthEventBus()
            val api = ApiClientFactory.createApi(ApiClientFactory.createOkHttp(storage, events), "$primaryUrl/")

            primaryServer.enqueue(
                MockResponse().setResponseCode(200)
                    .addHeader("Content-Type", "application/json")
                    .setBody("""{"loggedIn":true,"user":"alice"}"""),
            )
            primaryServer.enqueue(
                MockResponse().setResponseCode(200)
                    .addHeader("Content-Type", "application/json")
                    .setBody("""{"ok":true}"""),
            )
            inviteServer.enqueue(
                MockResponse().setResponseCode(200)
                    .addHeader("Content-Type", "application/json")
                    .setBody("""{"ok":true,"sessionToken":"$newToken"}"""),
            )
            inviteServer.enqueue(
                MockResponse().setResponseCode(200)
                    .addHeader("Content-Type", "application/json")
                    .setBody("""{"loggedIn":true,"user":"bob","isResidentOnly":true,"gate":{"phoneNumber":"2145550100","role":"resident"}}"""),
            )

            var widgetSynced = 0
            val vm = SessionBootstrapViewModel(
                sessionStorage = storage,
                api = api,
                authEvents = events,
                passkeyAuthenticator = noopPasskey,
                onWidgetSync = { widgetSynced++ },
            )
            val bootstrapBeforeInvite = awaitBootstrap(vm) { it is BootstrapState.Ready }
            assertEquals("alice", (bootstrapBeforeInvite as BootstrapState.Ready).session?.user)

            val result = vm.acceptInvite(inviteUrl, "k".repeat(32), "correct-horse-battery")
            assertTrue(result.isSuccess)
            assertEquals("bob", result.getOrNull()?.user)
            assertTrue(result.getOrNull()?.loggedIn == true)
            assertEquals(inviteUrl, storage.currentServerUrl())
            assertEquals(newToken, storage.currentSessionToken())
            assertEquals(serverOriginOf(inviteUrl), storage.state.value.tokenServerOrigin)
            // Must NOT navigate away or sync widgets until post-invite security onboarding finishes.
            advanceUntilIdle()
            assertEquals(bootstrapBeforeInvite, vm.bootstrap.value)
            assertEquals(0, widgetSynced)
        } finally {
            primaryServer.shutdown()
            inviteServer.shutdown()
        }
    }

    @Test
    fun completeInviteOnboardingRefreshesSessionSyncsWidgetsAndTransitionsToReady() = runTest(testDispatcher) {
        val server = MockWebServer()
        server.start()
        try {
            val baseUrl = server.url("/").toString().trimEnd('/')
            val newToken = "dks_${"a".repeat(64)}"
            val storage = FakeSessionStorage(initialServerUrl = baseUrl)
            val events = AuthEventBus()
            val api = ApiClientFactory.createApi(ApiClientFactory.createOkHttp(storage, events), "$baseUrl/")

            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse = when (request.path) {
                    "/api/server-info" -> MockResponse().setResponseCode(200)
                        .addHeader("Content-Type", "application/json")
                        .setBody("""{"emailEnabled":true}""")
                    "/api/invite" -> MockResponse().setResponseCode(200)
                        .addHeader("Content-Type", "application/json")
                        .setBody("""{"ok":true,"sessionToken":"$newToken"}""")
                    "/api/session" -> MockResponse().setResponseCode(200)
                        .addHeader("Content-Type", "application/json")
                        .setBody("""{"loggedIn":true,"user":"bob","isResidentOnly":true,"gate":{"phoneNumber":"2145550100","role":"resident"}}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }

            var widgetSynced = 0
            val vm = SessionBootstrapViewModel(
                sessionStorage = storage,
                api = api,
                authEvents = events,
                passkeyAuthenticator = noopPasskey,
                onWidgetSync = { widgetSynced++ },
            )
            val initial = awaitBootstrap(vm) { it is BootstrapState.Ready }
            assertEquals(AppRoute.SignIn, (initial as BootstrapState.Ready).startRoute)

            assertTrue(vm.acceptInvite(baseUrl, "k".repeat(32), "correct-horse-battery").isSuccess)
            advanceUntilIdle()
            assertEquals(AppRoute.SignIn, (vm.bootstrap.value as BootstrapState.Ready).startRoute)
            assertEquals(0, widgetSynced)

            vm.completeInviteOnboarding()
            val ready = awaitBootstrap(vm) {
                it is BootstrapState.Ready && it.session?.loggedIn == true
            } as BootstrapState.Ready
            assertEquals(AppRoute.ResidentPortal("2145550100"), ready.startRoute)
            assertEquals(true, ready.session?.loggedIn)
            assertEquals("bob", ready.session?.user)
            assertEquals(1, widgetSynced)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun registerPasskeyAfterInviteCallsOptionsAndAddPasskey() = runTest(testDispatcher) {
        val server = MockWebServer()
        server.start()
        try {
            val baseUrl = server.url("/").toString().trimEnd('/')
            val storage = FakeSessionStorage(initialServerUrl = baseUrl, initialToken = "dks_${"b".repeat(64)}")
            val events = AuthEventBus()
            val api = ApiClientFactory.createApi(ApiClientFactory.createOkHttp(storage, events), "$baseUrl/")

            var capturedOptionsJson: String? = null
            val fakePasskey = object : PasskeyAuthenticator {
                override suspend fun authenticate(activityContext: Context, requestJson: String): PasskeyResult =
                    PasskeyResult.Cancelled

                override suspend fun register(activityContext: Context, requestJson: String): PasskeyResult {
                    capturedOptionsJson = requestJson
                    return PasskeyResult.Success("""{"id":"cred-1","type":"public-key"}""")
                }
            }

            server.enqueue(
                MockResponse().setResponseCode(200)
                    .addHeader("Content-Type", "application/json")
                    .setBody("""{"loggedIn":true,"user":"bob"}"""),
            )
            server.enqueue(
                MockResponse().setResponseCode(200)
                    .addHeader("Content-Type", "application/json")
                    .setBody("""{"challenge":"abc123","rp":{"id":"gate.example.com"}}"""),
            )
            server.enqueue(
                MockResponse().setResponseCode(201)
                    .addHeader("Content-Type", "application/json")
                    .setBody("""{"passkeys":[{"id":"pk-1","name":"Google Pixel 9"}]}"""),
            )

            val vm = SessionBootstrapViewModel(storage, api, events, fakePasskey)
            advanceUntilIdle()
            server.takeRequest() // consume initial /api/session

            val result = vm.registerPasskeyAfterInvite(ContextWrapper(null), label = "Google Pixel 9")
            assertTrue(result.isSuccess)
            assertEquals(true, result.getOrNull())
            assertTrue(capturedOptionsJson?.contains("abc123") == true)

            val optReq = server.takeRequest()
            assertEquals("POST", optReq.method)
            assertEquals("/api/me/passkeys/options", optReq.path)

            val saveReq = server.takeRequest()
            assertEquals("POST", saveReq.method)
            assertEquals("/api/me/passkeys", saveReq.path)
            val saveBody = saveReq.body.readUtf8()
            assertTrue(saveBody.contains("Google Pixel 9"))
            assertTrue(saveBody.contains("cred-1"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun registerPasskeyAfterInviteHandlesCancellationAndErrors() = runTest(testDispatcher) {
        val server = MockWebServer()
        server.start()
        try {
            val baseUrl = server.url("/").toString().trimEnd('/')
            val storage = FakeSessionStorage(initialServerUrl = baseUrl, initialToken = "dks_${"c".repeat(64)}")
            val events = AuthEventBus()
            val api = ApiClientFactory.createApi(ApiClientFactory.createOkHttp(storage, events), "$baseUrl/")

            var nextResult: PasskeyResult = PasskeyResult.Cancelled
            val fakePasskey = object : PasskeyAuthenticator {
                override suspend fun authenticate(activityContext: Context, requestJson: String): PasskeyResult =
                    PasskeyResult.Cancelled

                override suspend fun register(activityContext: Context, requestJson: String): PasskeyResult =
                    nextResult
            }

            server.enqueue(
                MockResponse().setResponseCode(200)
                    .addHeader("Content-Type", "application/json")
                    .setBody("""{"loggedIn":true,"user":"bob"}"""),
            )
            val vm = SessionBootstrapViewModel(storage, api, events, fakePasskey)
            advanceUntilIdle()
            server.takeRequest() // consume initial /api/session

            // 1. User cancels Credential Manager -> Result.success(false), no POST /api/me/passkeys sent.
            server.enqueue(
                MockResponse().setResponseCode(200)
                    .addHeader("Content-Type", "application/json")
                    .setBody("""{"challenge":"cancel-test"}"""),
            )
            val cancelled = vm.registerPasskeyAfterInvite(ContextWrapper(null))
            assertTrue(cancelled.isSuccess)
            assertFalse(cancelled.getOrThrow())
            assertEquals("/api/me/passkeys/options", server.takeRequest().path)
            assertEquals(1, server.requestCount - 1) // no extra request after options

            // 2. Credential Manager returns an error -> Result.failure with error message.
            nextResult = PasskeyResult.Error("No credential provider available")
            server.enqueue(
                MockResponse().setResponseCode(200)
                    .addHeader("Content-Type", "application/json")
                    .setBody("""{"challenge":"err-test"}"""),
            )
            val failed = vm.registerPasskeyAfterInvite(ContextWrapper(null))
            assertTrue(failed.isFailure)
            assertEquals("No credential provider available", failed.exceptionOrNull()?.message)
            assertEquals("/api/me/passkeys/options", server.takeRequest().path)

            // 3. Server rejects /api/me/passkeys/options -> Result.failure with server error body.
            server.enqueue(
                MockResponse().setResponseCode(400)
                    .addHeader("Content-Type", "application/json")
                    .setBody("""{"error":"Passkeys require HTTPS on this server"}"""),
            )
            val serverErr = vm.registerPasskeyAfterInvite(ContextWrapper(null))
            assertTrue(serverErr.isFailure)
            assertEquals("Passkeys require HTTPS on this server", serverErr.exceptionOrNull()?.message)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun ssoCallbackIgnoredWithoutPendingVerifierAndMapsErrorsAgainstAllowlist() = runTest(testDispatcher) {
        val server = MockWebServer()
        server.start()
        try {
            val baseUrl = server.url("/").toString().trimEnd('/')
            val storage = FakeSessionStorage(initialServerUrl = baseUrl)
            val events = AuthEventBus()
            val api = ApiClientFactory.createApi(ApiClientFactory.createOkHttp(storage, events), "$baseUrl/")

            server.enqueue(
                MockResponse().setResponseCode(200)
                    .addHeader("Content-Type", "application/json")
                    .setBody("""{"loggedIn":false}"""),
            )

            val vm = SessionBootstrapViewModel(storage, api, events, noopPasskey)
            advanceUntilIdle()

            // Unsolicited callback without pendingSsoVerifier must be ignored (no spoofed error banner).
            vm.handleSsoCallbackParams(code = null, errParam = "Call fake support 555-0199 now!")
            assertNull(vm.ssoError.value)

            // Start a real SSO flow, then deliver an untrusted error value -> mapped to safe allowlisted message.
            server.enqueue(
                MockResponse().setResponseCode(200)
                    .addHeader("Content-Type", "application/json")
                    .setBody("""{"url":"https://idp.example.com/authorize","nativeVerifier":"v-123"}"""),
            )
            assertTrue(vm.startSso(baseUrl, "google").isSuccess)
            vm.handleSsoCallbackParams(code = null, errParam = "Visit evil.example.com to reset your password")
            assertEquals("Single sign-in failed. Please try again.", vm.ssoError.value)

            // Known error codes map cleanly.
            assertEquals("Single sign-in was cancelled.", SessionBootstrapViewModel.mapSsoErrorMessage("access_denied"))
            assertEquals(
                "Single sign-in session expired. Try again.",
                SessionBootstrapViewModel.mapSsoErrorMessage("invalid_state"),
            )
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun ssoErrorCallbackDoesNotClearPendingVerifierSoRealCodeStillSucceeds() = runTest(testDispatcher) {
        val server = MockWebServer()
        server.start()
        try {
            val baseUrl = server.url("/").toString().trimEnd('/')
            val storage = FakeSessionStorage(initialServerUrl = baseUrl)
            val events = AuthEventBus()
            val api = ApiClientFactory.createApi(ApiClientFactory.createOkHttp(storage, events), "$baseUrl/")

            server.enqueue(
                MockResponse().setResponseCode(200)
                    .addHeader("Content-Type", "application/json")
                    .setBody("""{"loggedIn":false}"""),
            )

            val vm = SessionBootstrapViewModel(storage, api, events, noopPasskey)
            awaitBootstrap(vm) { it is BootstrapState.Ready }
            server.takeRequest() // initial /api/session

            server.enqueue(
                MockResponse().setResponseCode(200)
                    .addHeader("Content-Type", "application/json")
                    .setBody("""{"url":"https://idp.example.com/authorize","nativeVerifier":"v-keep-42"}"""),
            )
            assertTrue(vm.startSso(baseUrl, "google").isSuccess)
            server.takeRequest() // /api/sso/start

            // A spoofed error callback must NOT wipe pendingSsoVerifier.
            vm.handleSsoCallbackParams(code = null, errParam = "access_denied")
            assertEquals("Single sign-in was cancelled.", vm.ssoError.value)

            // The real code callback still exchanges successfully using the preserved verifier.
            val issuedToken = "dks_${"e".repeat(64)}"
            server.enqueue(
                MockResponse().setResponseCode(200)
                    .addHeader("Content-Type", "application/json")
                    .setBody("""{"ok":true,"sessionToken":"$issuedToken"}"""),
            )
            server.enqueue(
                MockResponse().setResponseCode(200)
                    .addHeader("Content-Type", "application/json")
                    .setBody("""{"loggedIn":true,"user":"alice"}"""),
            )
            vm.handleSsoCallbackParams(code = "real-code-99", errParam = null)
            assertNull(vm.ssoError.value)
            assertEquals(issuedToken, storage.currentSessionToken())
            val ready = vm.bootstrap.value as BootstrapState.Ready
            assertEquals("alice", ready.session?.user)

            val exchangeReq = server.takeRequest()
            assertEquals("/api/sso/native-exchange", exchangeReq.path)
            val body = exchangeReq.body.readUtf8()
            assertTrue(body.contains("real-code-99"))
            assertTrue(body.contains("v-keep-42"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun coldStartInviteWaitsForBootstrapAndPreservesSignedInUser() = runTest(testDispatcher) {
        val server = MockWebServer()
        server.start()
        try {
            val baseUrl = server.url("/").toString().trimEnd('/')
            val storage = FakeSessionStorage(
                initialServerUrl = baseUrl,
                initialToken = "dks_${"f".repeat(64)}",
            )
            val events = AuthEventBus()
            val api = ApiClientFactory.createApi(ApiClientFactory.createOkHttp(storage, events), "$baseUrl/")

            server.enqueue(
                MockResponse().setResponseCode(200)
                    .addHeader("Content-Type", "application/json")
                    .setBody("""{"loggedIn":true,"user":"alice"}"""),
            )

            val vm = SessionBootstrapViewModel(storage, api, events, noopPasskey)
            // Initial bootstrap is still Loading while /api/session is in flight.
            assertEquals(BootstrapState.Loading, vm.bootstrap.value)

            val inviteToken = "i".repeat(32)
            val inviteJob = launch {
                vm.handleParsedInvite(ParsedInviteLink("https://invite.example.com", inviteToken))
            }

            val settled = awaitBootstrap(vm) {
                it is BootstrapState.Ready && it.startRoute is AppRoute.AcceptInvite
            } as BootstrapState.Ready
            inviteJob.join()

            assertEquals(AppRoute.AcceptInvite("https://invite.example.com", inviteToken), settled.startRoute)
            assertEquals(true, settled.session?.loggedIn)
            assertEquals("alice", settled.session?.user)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun sessionExpiredEventTriggersWidgetSync() = runTest(testDispatcher) {
        val server = MockWebServer()
        server.start()
        try {
            val baseUrl = server.url("/").toString().trimEnd('/')
            val storage = FakeSessionStorage(initialServerUrl = baseUrl)
            val events = AuthEventBus()
            val api = ApiClientFactory.createApi(ApiClientFactory.createOkHttp(storage, events), "$baseUrl/")
            server.enqueue(
                MockResponse().setResponseCode(200)
                    .addHeader("Content-Type", "application/json")
                    .setBody("""{"loggedIn":false}"""),
            )

            var widgetSynced = 0
            SessionBootstrapViewModel(
                sessionStorage = storage,
                api = api,
                authEvents = events,
                passkeyAuthenticator = noopPasskey,
                onWidgetSync = { widgetSynced++ },
            )
            advanceUntilIdle()

            events.emit(AuthEvent.SessionExpired)
            advanceUntilIdle()
            assertEquals(1, widgetSynced)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun signInWithGoogleNativeSucceedsSavesTokenUpdatesBootstrapAndSyncsWidgets() = runTest(testDispatcher) {
        val server = MockWebServer()
        server.start()
        try {
            val baseUrl = server.url("/").toString().trimEnd('/')
            val storage = FakeSessionStorage(initialServerUrl = baseUrl)
            val events = AuthEventBus()
            val api = ApiClientFactory.createApi(ApiClientFactory.createOkHttp(storage, events), "$baseUrl/")

            var capturedClientId: String? = null
            var capturedNonce: String? = null
            var capturedHostedDomain: String? = null
            val fakeAuth = object : PasskeyAuthenticator {
                override suspend fun authenticate(activityContext: Context, requestJson: String): PasskeyResult =
                    PasskeyResult.Cancelled

                override suspend fun signInWithGoogle(
                    activityContext: Context,
                    serverClientId: String,
                    nonce: String,
                    hostedDomain: String?,
                ): GoogleSignInResult {
                    capturedClientId = serverClientId
                    capturedNonce = nonce
                    capturedHostedDomain = hostedDomain
                    return GoogleSignInResult.Success("google-id-token-jwt")
                }
            }

            server.enqueue(
                MockResponse().setResponseCode(200)
                    .addHeader("Content-Type", "application/json")
                    .setBody("""{"loggedIn":false}"""),
            )
            var widgetSynced = 0
            val vm = SessionBootstrapViewModel(
                sessionStorage = storage,
                api = api,
                authEvents = events,
                passkeyAuthenticator = fakeAuth,
                onWidgetSync = { widgetSynced++ },
            )
            awaitBootstrap(vm) { it is BootstrapState.Ready }
            server.takeRequest() // initial /api/session

            val issuedToken = "dks_${"d".repeat(64)}"
            server.enqueue(
                MockResponse().setResponseCode(200)
                    .addHeader("Content-Type", "application/json")
                    .setBody("""{"nonce":"nonce-abc","clientId":"web-cid.apps.googleusercontent.com","hostedDomain":"corp.example.com"}"""),
            )
            server.enqueue(
                MockResponse().setResponseCode(200)
                    .addHeader("Content-Type", "application/json")
                    .setBody("""{"ok":true,"sessionToken":"$issuedToken"}"""),
            )
            server.enqueue(
                MockResponse().setResponseCode(200)
                    .addHeader("Content-Type", "application/json")
                    .setBody("""{"loggedIn":true,"user":"alice","gate":{"phoneNumber":"2145550100","siteName":"Main","role":"admin"}}"""),
            )

            val result = vm.signInWithGoogleNative(baseUrl, "google", ContextWrapper(null))
            assertTrue(result.isSuccess)
            val outcome = result.getOrThrow()
            assertTrue(outcome is GoogleSsoOutcome.SignedIn)
            assertEquals("alice", (outcome as GoogleSsoOutcome.SignedIn).session.user)
            assertEquals("web-cid.apps.googleusercontent.com", capturedClientId)
            assertEquals("nonce-abc", capturedNonce)
            assertEquals("corp.example.com", capturedHostedDomain)
            assertEquals(issuedToken, storage.currentSessionToken())
            assertEquals(1, widgetSynced)

            val ready = vm.bootstrap.value as BootstrapState.Ready
            assertEquals(AppRoute.StaffGate("2145550100"), ready.startRoute)
            assertEquals("alice", ready.session?.user)

            val nonceReq = server.takeRequest()
            assertEquals("/api/sso/google-nonce", nonceReq.path)
            assertNull(nonceReq.getHeader("Authorization"))

            val tokenReq = server.takeRequest()
            assertEquals("/api/sso/google-id-token", tokenReq.path)
            assertEquals("bearer", tokenReq.getHeader("X-Client-Auth"))
            assertNull(tokenReq.getHeader("Authorization"))
            val tokenBody = tokenReq.body.readUtf8()
            assertTrue(tokenBody.contains("google-id-token-jwt"))
            assertTrue(tokenBody.contains("nonce-abc"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun signInWithGoogleNativeReturnsCancelledWithoutCallingIdTokenEndpoint() = runTest(testDispatcher) {
        val server = MockWebServer()
        server.start()
        try {
            val baseUrl = server.url("/").toString().trimEnd('/')
            val storage = FakeSessionStorage(initialServerUrl = baseUrl)
            val events = AuthEventBus()
            val api = ApiClientFactory.createApi(ApiClientFactory.createOkHttp(storage, events), "$baseUrl/")

            val fakeAuth = object : PasskeyAuthenticator {
                override suspend fun authenticate(activityContext: Context, requestJson: String): PasskeyResult =
                    PasskeyResult.Cancelled

                override suspend fun signInWithGoogle(
                    activityContext: Context,
                    serverClientId: String,
                    nonce: String,
                    hostedDomain: String?,
                ): GoogleSignInResult = GoogleSignInResult.Cancelled
            }

            server.enqueue(
                MockResponse().setResponseCode(200)
                    .addHeader("Content-Type", "application/json")
                    .setBody("""{"loggedIn":false}"""),
            )
            var widgetSynced = 0
            val vm = SessionBootstrapViewModel(
                sessionStorage = storage,
                api = api,
                authEvents = events,
                passkeyAuthenticator = fakeAuth,
                onWidgetSync = { widgetSynced++ },
            )
            awaitBootstrap(vm) { it is BootstrapState.Ready }
            server.takeRequest() // initial /api/session

            server.enqueue(
                MockResponse().setResponseCode(200)
                    .addHeader("Content-Type", "application/json")
                    .setBody("""{"nonce":"nonce-cancel","clientId":"web-cid.apps.googleusercontent.com"}"""),
            )

            val result = vm.signInWithGoogleNative(baseUrl, "google", ContextWrapper(null))
            assertTrue(result.isSuccess)
            assertEquals(GoogleSsoOutcome.Cancelled, result.getOrThrow())
            assertNull(storage.currentSessionToken())
            assertEquals(0, widgetSynced)
            assertEquals("/api/sso/google-nonce", server.takeRequest().path)
            assertEquals(2, server.requestCount) // initial session + nonce only
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun signInWithGoogleNativeFallsBackToBrowserOnUnsupportedServerOrCredentialFailure() = runTest(testDispatcher) {
        val server = MockWebServer()
        server.start()
        try {
            val baseUrl = server.url("/").toString().trimEnd('/')
            val storage = FakeSessionStorage(initialServerUrl = baseUrl)
            val events = AuthEventBus()
            val api = ApiClientFactory.createApi(ApiClientFactory.createOkHttp(storage, events), "$baseUrl/")

            var credentialCalls = 0
            val fakeAuth = object : PasskeyAuthenticator {
                override suspend fun authenticate(activityContext: Context, requestJson: String): PasskeyResult =
                    PasskeyResult.Cancelled

                override suspend fun signInWithGoogle(
                    activityContext: Context,
                    serverClientId: String,
                    nonce: String,
                    hostedDomain: String?,
                ): GoogleSignInResult {
                    credentialCalls++
                    return GoogleSignInResult.FallbackToBrowser("No Android OAuth client configured")
                }
            }

            server.enqueue(
                MockResponse().setResponseCode(200)
                    .addHeader("Content-Type", "application/json")
                    .setBody("""{"loggedIn":false}"""),
            )
            val vm = SessionBootstrapViewModel(storage, api, events, fakeAuth)
            awaitBootstrap(vm) { it is BootstrapState.Ready }
            server.takeRequest() // initial /api/session

            // 1. Older server returns 404 on /api/sso/google-nonce -> FallbackToBrowser without invoking Credential Manager.
            server.enqueue(
                MockResponse().setResponseCode(404)
                    .addHeader("Content-Type", "application/json")
                    .setBody("""{"error":"Not found"}"""),
            )
            val olderServer = vm.signInWithGoogleNative(baseUrl, "google", ContextWrapper(null))
            assertTrue(olderServer.isSuccess)
            assertEquals(GoogleSsoOutcome.FallbackToBrowser, olderServer.getOrThrow())
            assertEquals(0, credentialCalls)
            assertEquals("/api/sso/google-nonce", server.takeRequest().path)

            // 2. Server returns 400 -> FallbackToBrowser.
            server.enqueue(
                MockResponse().setResponseCode(400)
                    .addHeader("Content-Type", "application/json")
                    .setBody("""{"error":"Provider does not support native Google Sign-In"}"""),
            )
            val badReq = vm.signInWithGoogleNative(baseUrl, "google", ContextWrapper(null))
            assertTrue(badReq.isSuccess)
            assertEquals(GoogleSsoOutcome.FallbackToBrowser, badReq.getOrThrow())
            assertEquals(0, credentialCalls)
            assertEquals("/api/sso/google-nonce", server.takeRequest().path)

            // 3. Server returns nonce, Credential Manager returns FallbackToBrowser -> FallbackToBrowser.
            server.enqueue(
                MockResponse().setResponseCode(200)
                    .addHeader("Content-Type", "application/json")
                    .setBody("""{"nonce":"nonce-fb","clientId":"web-cid.apps.googleusercontent.com"}"""),
            )
            val cmFallback = vm.signInWithGoogleNative(baseUrl, "google", ContextWrapper(null))
            assertTrue(cmFallback.isSuccess)
            assertEquals(GoogleSsoOutcome.FallbackToBrowser, cmFallback.getOrThrow())
            assertEquals(1, credentialCalls)
            assertEquals("/api/sso/google-nonce", server.takeRequest().path)
            assertEquals(4, server.requestCount) // no /api/sso/google-id-token request sent
        } finally {
            server.shutdown()
        }
    }
}
