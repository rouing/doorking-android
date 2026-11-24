package com.doorking.gate.core.network

import kotlinx.serialization.json.JsonObject
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Retrofit contract mirroring the `webapp/` Fastify REST API (`docs/openapi.json` + `web/routes/`).
 */
interface DoorkingApi {

    // --- Session & Auth ---

    @GET("api/session")
    suspend fun getSession(): SessionResponse

    @POST("api/session")
    suspend fun signIn(@Body body: LoginRequest): Response<LoginResponse>

    @DELETE("api/session")
    suspend fun signOut(): OkResponse

    @PUT("api/session/gate")
    suspend fun openGate(@Body body: OpenGateRequest): OkResponse

    @DELETE("api/session/gate")
    suspend fun closeGate(): OkResponse

    @GET("api/me")
    suspend fun getMe(): MeProfileDto

    @PUT("api/me")
    suspend fun updateMe(@Body body: JsonObject): Response<MeProfileDto>

    @POST("api/me/email/resend")
    suspend fun resendEmailVerification(@Body body: JsonObject = JsonObject(emptyMap())): Response<OkResponse>

    @GET("api/me/passkeys")
    suspend fun listPasskeys(): PasskeysReply

    @POST("api/me/passkeys/options")
    suspend fun getPasskeyRegistrationOptions(@Body body: JsonObject = JsonObject(emptyMap())): Response<JsonObject>

    @POST("api/me/passkeys")
    suspend fun addPasskey(@Body body: PasskeyRegistrationRequest): Response<PasskeysReply>

    @DELETE("api/me/passkeys/{id}")
    suspend fun deletePasskey(@Path("id") id: String): Response<OkResponse>

    // --- Android FCM Push Notifications ---

    @GET("api/me/fcm")
    suspend fun getFcmTokens(): FcmTokensReply

    @POST("api/me/fcm")
    suspend fun registerFcmToken(@Body body: RegisterFcmRequest): Response<FcmTokensReply>

    @POST("api/me/fcm/unregister")
    suspend fun unregisterFcmToken(@Body body: UnregisterFcmRequest): Response<FcmTokensReply>

    @DELETE("api/me/fcm/{id}")
    suspend fun deleteFcmToken(@Path("id") id: String): Response<FcmTokensReply>

    @POST("api/me/fcm/test")
    suspend fun sendTestFcm(@Body body: JsonObject = JsonObject(emptyMap())): Response<TestFcmReply>

    // --- Web Push Subscriptions (browsers, desktop PWAs) ---

    @GET("api/me/push")
    suspend fun getWebPushSubscriptions(): WebPushReply

    @DELETE("api/me/push/{id}")
    suspend fun deleteWebPushSubscription(@Path("id") id: String): Response<WebPushReply>

    @POST("api/me/push/test")
    suspend fun sendTestAllPush(@Body body: JsonObject = JsonObject(emptyMap())): Response<TestPushReply>

    @PUT("api/me/password")
    suspend fun changePassword(@Body body: ChangePasswordRequest): Response<OkResponse>

    @POST("api/password-reset/request")
    suspend fun requestPasswordReset(@Body body: PasswordResetRequest): Response<MessageResponse>

    @POST("api/invite/check")
    suspend fun checkInvite(@Body body: InviteCheckRequest): Response<InviteCheckResponse>

    @POST("api/invite")
    suspend fun acceptInvite(@Body body: AcceptInviteRequest): Response<LoginResponse>

    @POST("api/passkey/options")
    suspend fun getPasskeySignInOptions(@Body body: JsonObject = JsonObject(emptyMap())): Response<JsonObject>

    @POST("api/passkey/session")
    suspend fun signInWithPasskey(@Body body: PasskeyAssertionRequest): Response<LoginResponse>

    @POST("api/me/step-up/options")
    suspend fun getStepUpOptions(@Body body: JsonObject = JsonObject(emptyMap())): Response<JsonObject>

    @POST("api/me/step-up")
    suspend fun confirmStepUp(@Body body: PasskeyAssertionRequest): Response<StepUpResponse>

    @POST("api/me/step-up")
    suspend fun confirmStepUpWithPassword(@Body body: StepUpPasswordRequest): Response<StepUpResponse>

    @POST("api/sso/start")
    suspend fun startSso(@Body body: SsoStartRequest): Response<SsoStartResponse>

    @POST("api/sso/native-exchange")
    suspend fun exchangeNativeSso(@Body body: NativeSsoExchangeRequest): Response<LoginResponse>

    // --- Gates ---

    @GET("api/gates")
    suspend fun listGates(): GatesReply

    @POST("api/gates")
    suspend fun addGate(@Body body: AddGateRequest): Response<OkResponse>

    @GET("api/gate-add")
    suspend fun getGateAddJob(): GateAddStatusReply

    @DELETE("api/gates/{phone}")
    suspend fun leaveGate(@Path("phone") phone: String): OkResponse

    // --- Staff Gate Operations ---

    @GET("api/gates/{phone}/residents")
    suspend fun getResidents(@Path("phone") phone: String): ResidentsReply

    @POST("api/gates/{phone}/residents")
    suspend fun addResident(
        @Path("phone") phone: String,
        @Body body: JsonObject,
    ): Response<JsonObject>

    @PUT("api/gates/{phone}/residents/{code}")
    suspend fun updateResident(
        @Path("phone") phone: String,
        @Path("code") code: String,
        @Body body: JsonObject,
    ): Response<JsonObject>

    @DELETE("api/gates/{phone}/residents/{code}")
    suspend fun deleteResident(
        @Path("phone") phone: String,
        @Path("code") code: String,
    ): Response<JsonObject>

    @PUT("api/gates/{phone}/residents/{code}/note")
    suspend fun saveResidentNote(
        @Path("phone") phone: String,
        @Path("code") code: String,
        @Body body: ResidentNoteRequest,
    ): OkResponse

    @POST("api/gates/{phone}/residents/instructions.pdf")
    suspend fun downloadResidentInstructionsPdf(
        @Path("phone") phone: String,
        @Body body: InstructionsPdfRequest,
    ): Response<ResponseBody>

    @POST("api/gates/{phone}/working-copy/conflicts")
    suspend fun decideConflict(
        @Path("phone") phone: String,
        @Body body: ConflictDecisionRequest,
    ): Response<JsonObject>

    @GET("api/gates/{phone}/prefs")
    suspend fun getPrefs(@Path("phone") phone: String): GatePrefsDto

    @PUT("api/gates/{phone}/prefs")
    suspend fun updatePrefs(
        @Path("phone") phone: String,
        @Body body: JsonObject,
    ): Response<GatePrefsDto>

    @GET("api/gates/{phone}/cameras")
    suspend fun getCameras(@Path("phone") phone: String): CameraSettingsReply

    @PUT("api/gates/{phone}/cameras")
    suspend fun saveCameras(
        @Path("phone") phone: String,
        @Body body: JsonObject,
    ): Response<OkResponse>

    @DELETE("api/gates/{phone}/cameras")
    suspend fun deleteCameras(@Path("phone") phone: String): Response<OkResponse>

    @GET("api/gates/{phone}/snapshots")
    suspend fun getSnapshots(@Path("phone") phone: String): SnapshotsReply

    @POST("api/gates/{phone}/snapshots/run")
    suspend fun runSnapshots(
        @Path("phone") phone: String,
        @Body body: SnapshotRunRequest = SnapshotRunRequest(),
    ): Response<OkResponse>

    @POST("api/gates/{phone}/snapshots/stop")
    suspend fun stopSnapshots(
        @Path("phone") phone: String,
        @Body body: JsonObject = JsonObject(emptyMap()),
    ): Response<JsonObject>

    @GET("api/gates/{phone}/data")
    suspend fun getData(
        @Path("phone") phone: String,
        @Query("before") before: Long? = null,
    ): DataReply

    @GET("api/gates/{phone}/job")
    suspend fun getJob(@Path("phone") phone: String): JobDto

    @POST("api/gates/{phone}/pull")
    suspend fun startPull(
        @Path("phone") phone: String,
        @Body body: PullRequest,
    ): Response<OkResponse>

    @GET("api/gates/{phone}/send")
    suspend fun previewSend(@Path("phone") phone: String): SendPreviewDto

    @POST("api/gates/{phone}/send")
    suspend fun startSend(
        @Path("phone") phone: String,
        @Body body: ConfirmRequest = ConfirmRequest(true),
    ): OkResponse

    @DELETE("api/gates/{phone}/working-copy")
    suspend fun resetWorkingCopy(@Path("phone") phone: String): OkResponse

    @GET("api/gates/{phone}/relays")
    suspend fun getRelays(@Path("phone") phone: String): RelaysReply

    @PUT("api/gates/{phone}/relays")
    suspend fun setRelays(
        @Path("phone") phone: String,
        @Body body: JsonObject,
    ): Response<RelaysReply>

    @DELETE("api/gates/{phone}/relays/{relay}/timer")
    suspend fun cancelRelayTimer(
        @Path("phone") phone: String,
        @Path("relay") relay: Int,
    ): Response<RelaysReply>

    @GET("api/gates/{phone}/history")
    suspend fun getHistory(@Path("phone") phone: String): HistoryReply

    @POST("api/gates/{phone}/history/{id}/revert")
    suspend fun revertHistory(
        @Path("phone") phone: String,
        @Path("id") id: String,
        @Body body: JsonObject = JsonObject(emptyMap()),
    ): Response<OkResponse>

    @GET("api/gates/{phone}/pull-archive")
    suspend fun getPullArchive(@Path("phone") phone: String): PullArchiveReply

    @GET("api/gates/{phone}/pull-archive/{id}/diff")
    suspend fun diffPullArchive(
        @Path("phone") phone: String,
        @Path("id") id: Long,
    ): PullArchiveDiffReply

    @POST("api/gates/{phone}/pull-archive/{id}/restore")
    suspend fun restorePullArchive(
        @Path("phone") phone: String,
        @Path("id") id: Long,
        @Body body: JsonObject = JsonObject(emptyMap()),
    ): Response<OkResponse>

    @GET("api/gates/{phone}/audit")
    suspend fun getAudit(
        @Path("phone") phone: String,
        @Query("days") days: Int = 90,
    ): AccessAuditReply

    @GET("api/gates/{phone}/schedules")
    suspend fun getSchedules(@Path("phone") phone: String): SchedulesReply

    @POST("api/gates/{phone}/schedules")
    suspend fun createSchedule(
        @Path("phone") phone: String,
        @Body body: ScheduleInputDto,
    ): Response<ScheduleDto>

    @PUT("api/gates/{phone}/schedules/{id}")
    suspend fun updateSchedule(
        @Path("phone") phone: String,
        @Path("id") id: String,
        @Body body: ScheduleInputDto,
    ): Response<ScheduleDto>

    @DELETE("api/gates/{phone}/schedules/{id}")
    suspend fun deleteSchedule(
        @Path("phone") phone: String,
        @Path("id") id: String,
    ): Response<OkResponse>

    @POST("api/gates/{phone}/schedules/{id}/run")
    suspend fun runScheduleNow(
        @Path("phone") phone: String,
        @Path("id") id: String,
        @Body body: JsonObject = JsonObject(emptyMap()),
    ): Response<OkResponse>

    @GET("api/gates/{phone}/settings")
    suspend fun getSettings(@Path("phone") phone: String): SettingsReply

    @PUT("api/gates/{phone}/settings")
    suspend fun saveSettings(
        @Path("phone") phone: String,
        @Body body: Map<String, String>,
    ): Response<SettingsSaveReply>

    @GET("api/gates/{phone}/login")
    suspend fun getGateLogin(@Path("phone") phone: String): GateLoginDto

    @PUT("api/gates/{phone}/login")
    suspend fun updateGateLogin(
        @Path("phone") phone: String,
        @Body body: GateLoginUpdateRequest,
    ): Response<JsonObject>

    @GET("api/gates/{phone}/users")
    suspend fun getGateUsers(@Path("phone") phone: String): GateUsersReply

    @POST("api/gates/{phone}/users")
    suspend fun createGateUser(
        @Path("phone") phone: String,
        @Body body: CreateGateUserRequest,
    ): Response<GateUsersReply>

    @POST("api/gates/{phone}/share")
    suspend fun shareGate(
        @Path("phone") phone: String,
        @Body body: ShareGateRequest,
    ): Response<GateUsersReply>

    @PUT("api/gates/{phone}/users/{username}")
    suspend fun updateGateUserRole(
        @Path("phone") phone: String,
        @Path("username") username: String,
        @Body body: UpdateRoleRequest,
    ): Response<GateUsersReply>

    @DELETE("api/gates/{phone}/users/{username}")
    suspend fun removeGateUser(
        @Path("phone") phone: String,
        @Path("username") username: String,
    ): Response<GateUsersReply>

    @GET("api/gates/{phone}/api-keys")
    suspend fun getApiKeys(@Path("phone") phone: String): ApiKeysReply

    @POST("api/gates/{phone}/api-keys")
    suspend fun createApiKey(
        @Path("phone") phone: String,
        @Body body: CreateApiKeyRequest,
    ): Response<CreateApiKeyResponse>

    @DELETE("api/gates/{phone}/api-keys/{id}")
    suspend fun revokeApiKey(
        @Path("phone") phone: String,
        @Path("id") id: String,
    ): Response<ApiKeysReply>

    @GET("api/gates/{phone}/webhooks")
    suspend fun getWebhooks(@Path("phone") phone: String): WebhooksReply

    @POST("api/gates/{phone}/webhooks")
    suspend fun createWebhook(
        @Path("phone") phone: String,
        @Body body: CreateWebhookRequest,
    ): Response<JsonObject>

    @DELETE("api/gates/{phone}/webhooks/{id}")
    suspend fun deleteWebhook(
        @Path("phone") phone: String,
        @Path("id") id: String,
    ): Response<OkResponse>

    @POST("api/gates/{phone}/webhooks/{id}/test")
    suspend fun testWebhook(
        @Path("phone") phone: String,
        @Path("id") id: String,
        @Body body: JsonObject = JsonObject(emptyMap()),
    ): Response<JsonObject>

    @GET("api/gates/{phone}/login-audit")
    suspend fun getLoginAudit(@Path("phone") phone: String): LoginAuditReply

    @GET("api/gates/{phone}/suspicious-reports")
    suspend fun getSuspiciousReports(@Path("phone") phone: String): SuspiciousReportsReply

    @POST("api/gates/{phone}/suspicious-reports/{id}/resolve")
    suspend fun resolveSuspiciousReport(
        @Path("phone") phone: String,
        @Path("id") id: Long,
        @Body body: ResolveReportRequest,
    ): Response<OkResponse>

    @GET("api/gates/{phone}/resident-requests")
    suspend fun getAdminResidentRequests(@Path("phone") phone: String): PortalRequestsReply

    @POST("api/gates/{phone}/resident-requests/{id}/review")
    suspend fun reviewResidentRequest(
        @Path("phone") phone: String,
        @Path("id") id: Long,
        @Body body: ReviewResidentRequestBody,
    ): Response<OkResponse>

    @POST("api/gates/{phone}/delete-data")
    suspend fun deleteGateData(
        @Path("phone") phone: String,
        @Body body: ConfirmRequest = ConfirmRequest(true),
    ): Response<JsonObject>

    // --- Resident Self-Service Portal ---

    @GET("api/portal/gates/{phone}/me")
    suspend fun getPortalMe(
        @Path("phone") phone: String,
        @Query("code") code: String? = null,
    ): PortalMeReply

    @GET("api/portal/gates/{phone}/status")
    suspend fun getPortalStatus(@Path("phone") phone: String): PortalStatusReply

    @POST("api/portal/gates/{phone}/buzz")
    suspend fun portalBuzz(
        @Path("phone") phone: String,
        @Body body: BuzzRequest,
    ): Response<BuzzResponse>

    @POST("api/portal/gates/{phone}/reroll-code")
    suspend fun portalRerollCode(
        @Path("phone") phone: String,
        @Body body: PortalCodeRequest,
    ): Response<PortalRerollResponse>

    @GET("api/portal/gates/{phone}/activity")
    suspend fun getPortalActivity(
        @Path("phone") phone: String,
        @Query("code") code: String? = null,
    ): PortalActivityReply

    @POST("api/portal/gates/{phone}/activity/report")
    suspend fun reportSuspiciousActivity(
        @Path("phone") phone: String,
        @Body body: PortalReportRequest,
    ): Response<OkResponse>

    @GET("api/portal/gates/{phone}/requests")
    suspend fun getPortalRequests(@Path("phone") phone: String): PortalRequestsReply

    @POST("api/portal/gates/{phone}/requests")
    suspend fun createPortalRequest(
        @Path("phone") phone: String,
        @Body body: CreateResidentRequestBody,
    ): Response<JsonObject>

    @POST("api/portal/gates/{phone}/instructions.pdf")
    suspend fun downloadPortalInstructionsPdf(
        @Path("phone") phone: String,
        @Body body: PortalCodeRequest,
    ): Response<ResponseBody>
}
