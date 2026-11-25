package com.doorking.gate.core.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

@Serializable
data class ApiErrorResponse(
    val error: String = "Request failed",
    val stepUp: Boolean = false,
    val hasPasskey: Boolean = false,
    val canUsePassword: Boolean = false,
    val mustChangePassword: Boolean = false,
    val unknownCredential: Boolean = false,
)

@Serializable
data class OkResponse(
    val ok: Boolean = true,
)

@Serializable
data class LoginRequest(
    val user: String,
    val password: String,
)

@Serializable
data class LoginResponse(
    val ok: Boolean = true,
    val sessionToken: String? = null,
    val gates: Int? = null,
)

@Serializable
data class SsoProviderInfo(
    val id: String,
    val name: String,
)

@Serializable
data class GateConfigDto(
    val siteName: String = "",
    val memorySize: String = "",
    val areaCodes: String = "",
    val modelNumber: String = "",
    val antiPassBack: String = "N",
    val interface1816: String = "N",
)

@Serializable
data class GateInfoDto(
    val phoneNumber: String,
    val siteName: String = "",
    val addedAt: String = "",
    val role: String = "readonly",
)

@Serializable
data class HolidayItemDto(
    val name: String,
    val month: Int,
    val day: Int,
)

@Serializable
data class SessionResponse(
    val loggedIn: Boolean = false,
    val setup: Boolean = false,
    val user: String = "",
    val email: String = "",
    val pendingEmail: String = "",
    val phone: String = "",
    val dark: Boolean = false,
    val notifyAccess: Boolean = true,
    val notifyAlerts: Boolean = true,
    val emailEnabled: Boolean = false,
    val mustChangePassword: Boolean = false,
    val sso: List<SsoProviderInfo> = emptyList(),
    val hasPassword: Boolean = true,
    val passwordAllowed: Boolean = true,
    val hasPasskey: Boolean = false,
    val stepUpMinutes: Int = 180,
    val strongUntil: String? = null,
    val isResidentOnly: Boolean = false,
    val hasPortalUnit: Boolean = false,
    val gate: GateInfoDto? = null,
    val defaults: GateConfigDto? = null,
    val holidayList: List<HolidayItemDto> = emptyList(),
)

@Serializable
data class GatesReply(
    val gates: List<GateInfoDto> = emptyList(),
)

@Serializable
data class OpenGateRequest(
    val phoneNumber: String,
)

@Serializable
data class ChangePasswordRequest(
    @SerialName("current") val currentPassword: String? = null,
    @SerialName("password") val newPassword: String,
)

@Serializable
data class PasskeyRegistrationRequest(
    val name: String,
    val response: JsonElement,
)

@Serializable
data class PasswordResetRequest(
    val identifier: String,
)

@Serializable
data class InviteCheckRequest(
    val token: String,
)

@Serializable
data class InviteCheckResponse(
    val username: String,
    val email: String = "",
    val isResident: Boolean = false,
)

@Serializable
data class AcceptInviteRequest(
    val token: String,
    val password: String,
)

@Serializable
data class MessageResponse(
    val ok: Boolean = true,
    val message: String = "",
)

@Serializable
data class PasskeyAssertionRequest(
    val response: JsonElement,
)

@Serializable
data class StepUpResponse(
    val strongUntil: String = "",
)

@Serializable
data class SsoStartRequest(
    val provider: String,
    val native: Boolean = true,
)

@Serializable
data class SsoStartResponse(
    val url: String,
    val nativeVerifier: String? = null,
)

@Serializable
data class NativeSsoExchangeRequest(
    val code: String,
    val verifier: String,
)

@Serializable
data class AddGateRequest(
    val phoneNumber: String,
    val masterCode: String,
    val dksUser: String,
    val dksPassword: String,
    val gateConfig: GateConfigDto = GateConfigDto(),
)

@Serializable
data class GateAddStatusReply(
    val job: JobDto? = null,
)

@Serializable
data class ResidentRowDto(
    val directoryCode: String,
    val name: String = "",
    val type: String = "Resident",
    val hidden: Boolean = false,
    val areaCode: String = "",
    val areaCodeIndex: Int? = null,
    val phone: String = "",
    val entryCode: String = "",
    val securityLevel: JsonPrimitive? = null,
    val elevatorRef: JsonPrimitive? = null,
    val floor: JsonPrimitive? = null,
    val cards: List<String> = emptyList(),
    val note: String? = null,
    val email: String? = null,
    val portalEnabled: Boolean? = null,
    val portalInvited: Boolean? = null,
    val portalUsername: String? = null,
)

@Serializable
data class ConflictViewDto(
    val kind: String,
    val key: String,
    val label: String,
    val gate: String,
    val mine: String,
)

@Serializable
data class WorkingCopyCacheDto(
    val exists: Boolean = false,
    val edited: Boolean = false,
    val conflicts: List<ConflictViewDto> = emptyList(),
)

@Serializable
data class ResidentsReply(
    val rows: List<ResidentRowDto> = emptyList(),
    val unassignedCards: List<String> = emptyList(),
    val dirDigits: Int = 3,
    val cache: WorkingCopyCacheDto = WorkingCopyCacheDto(),
    val pulled: String = "",
    val saved: String = "",
    val grantAccessDigit: String? = null,
)

@Serializable
data class TransactionRowDto(
    val ts: Long,
    val minute: String = "",
    val date: String = "",
    val time: String = "",
    val eventType: String = "",
    val number: String? = null,
    val lookup: String? = null,
    val name: String = "",
    val access: String = "",
    val relay: String = "",
    val apb: String = "",
    val directoryCode: String = "",
    val camera: Boolean = false,
    val raw: String = "",
    val source: String? = null,
    val txKey: String? = null,
    val reported: Boolean? = null,
)

@Serializable
data class TransactionsPageDto(
    val updated: String = "",
    val rows: List<TransactionRowDto> = emptyList(),
    val next: Long? = null,
)

@Serializable
data class DataReply(
    val transactions: TransactionsPageDto? = null,
)

@Serializable
data class JobDto(
    val state: String = "idle",
    val what: String? = null,
    val phoneNumber: String? = null,
    val user: String? = null,
    val startedAt: String? = null,
    val finishedAt: String? = null,
    val log: List<String> = emptyList(),
    val error: String? = null,
    val adding: Boolean = false,
    val busyElsewhere: Boolean = false,
)

@Serializable
data class RelayDto(
    val relay: Int,
    val code: String = "0",
    val state: String = "",
    val name: String = "",
)

@Serializable
data class RelayTimerDto(
    val phone: String,
    val relay: Int,
    val releaseAt: String,
    val setBy: String = "",
    val createdAt: String = "",
)

@Serializable
data class RelaysReply(
    val checkedAt: String = "",
    val relays: List<RelayDto> = emptyList(),
    val timers: List<RelayTimerDto> = emptyList(),
)

@Serializable
data class SendFieldChangeDto(
    val field: String,
    val from: String = "",
    val to: String = "",
)

@Serializable
data class SendChangeDto(
    val key: String,
    val label: String,
    val change: String,
    val fields: List<SendFieldChangeDto> = emptyList(),
)

@Serializable
data class SendPreviewDto(
    val changedBlocks: Map<String, List<Int>> = emptyMap(),
    val residentCounts: List<Int> = emptyList(),
    val changes: List<SendChangeDto> = emptyList(),
)

@Serializable
data class PortalGateDto(
    val phoneNumber: String,
    val siteName: String = "",
    val adminPhone: String = "",
    val grantAccessDigit: String = "9",
)

@Serializable
data class PortalUnitSummaryDto(
    val directoryCode: String,
    val name: String = "",
)

@Serializable
data class PortalCardDto(
    val number: String,
    val device: String = "Card",
)

@Serializable
data class PortalUnitDto(
    val directoryCode: String,
    val name: String = "",
    val phone: String = "",
    val entryCode: String = "",
    val hidden: Boolean = false,
    val cards: List<PortalCardDto> = emptyList(),
    val email: String = "",
)

@Serializable
data class BuzzRelayLabelDto(
    val relay: Int,
    val name: String,
)

@Serializable
data class PortalCapabilitiesDto(
    val enabled: Boolean = false,
    val buzzEnabled: Boolean = false,
    val buzzRelays: List<Int> = emptyList(),
    val pulseSeconds: Int = 2,
    val maxRerollsPerDay: Int = 1,
    val autoSendReroll: Boolean = false,
    val showSnapshots: Boolean = true,
    val allowRequests: Boolean = true,
    val requirePasskeyForBuzz: Boolean = false,
    val buzzRelayLabels: List<BuzzRelayLabelDto> = emptyList(),
    val allBuzzRelayLabels: List<BuzzRelayLabelDto> = emptyList(),
    val hiddenRelays: List<Int> = emptyList(),
)

@Serializable
data class PortalRerollStatusDto(
    val usedToday: Int = 0,
    val maxPerDay: Int = 1,
    val remainingToday: Int = 0,
    val pendingSend: Boolean = false,
    val nextScheduledSend: String? = null,
)

@Serializable
data class PortalMeReply(
    val gate: PortalGateDto,
    val units: List<PortalUnitSummaryDto> = emptyList(),
    val unit: PortalUnitDto,
    val portal: PortalCapabilitiesDto,
    val reroll: PortalRerollStatusDto,
)

@Serializable
data class PortalStatusReply(
    val available: Boolean = true,
    val busyReason: String? = null,
    val percent: Int? = null,
    val phase: String? = null,
    val cooldownSeconds: Int = 0,
)

@Serializable
data class PortalActivityReply(
    val rows: List<TransactionRowDto> = emptyList(),
    val showSnapshots: Boolean = false,
)

@Serializable
data class BuzzRequest(
    val relay: Int,
    val code: String? = null,
)

@Serializable
data class BuzzResponse(
    val ok: Boolean = true,
    val alreadyOpen: Boolean = false,
)

@Serializable
data class PortalCodeRequest(
    val code: String? = null,
)

@Serializable
data class PortalRerollResponse(
    val entryCode: String,
    val syncedToGate: JsonElement? = null,
    val nextScheduledSend: String? = null,
    val remainingToday: Int = 0,
)

@Serializable
data class PortalReportRequest(
    val txSource: String = "gate",
    val txKey: String,
    val note: String = "",
    val code: String? = null,
)

@Serializable
data class ResidentRequestDto(
    val id: Long,
    val phone: String = "",
    val directoryCode: String = "",
    val residentName: String? = null,
    val username: String = "",
    val kind: String = "other",
    val note: String = "",
    val status: String = "pending",
    val createdAt: String = "",
    val reviewedBy: String? = null,
    val reviewedAt: String? = null,
    val reviewNote: String? = null,
)

@Serializable
data class PortalRequestsReply(
    val requests: List<ResidentRequestDto> = emptyList(),
)

@Serializable
data class CreateResidentRequestBody(
    val kind: String,
    val payload: Map<String, JsonPrimitive> = emptyMap(),
    val note: String = "",
    val code: String? = null,
)

@Serializable
data class ConflictDecisionRequest(
    val kind: String,
    val key: String,
    val choice: String,
)

@Serializable
data class ConfirmRequest(
    val confirm: Boolean = true,
)

@Serializable
data class DeviceRangeDto(
    val name: String = "",
    val from: String = "",
    val to: String = "",
)

@Serializable
data class ResidentPortalPrefsDto(
    val enabled: Boolean = false,
    val buzzEnabled: Boolean = false,
    val buzzRelays: List<Int> = emptyList(),
    val pulseSeconds: Int = 2,
    val maxRerollsPerDay: Int = 1,
    val autoSendReroll: Boolean = false,
    val showSnapshots: Boolean = true,
    val allowRequests: Boolean = true,
    val requirePasskeyForBuzz: Boolean = false,
)

@Serializable
data class GateTrackerDto(
    val enabled: Boolean = false,
    val tracker1: Boolean = false,
    val tracker2: Boolean = false,
)

@Serializable
data class GatePrefsDto(
    val relayLabels: Map<String, String> = emptyMap(),
    val deviceAssociation: List<DeviceRangeDto> = emptyList(),
    val gateConfig: GateConfigDto? = null,
    val tracker: GateTrackerDto? = null,
    val adminPhone: String = "",
    val requireStrongAdmin: Boolean = false,
    val residentPortal: ResidentPortalPrefsDto? = null,
)

@Serializable
data class CameraChannelDto(
    val channel: Int,
    val name: String = "",
)

@Serializable
data class CameraSettingsDto(
    val enabled: Boolean = true,
    val serial: String = "",
    val username: String = "",
    val mode: String = "snapshots",
    val clockOffset: Int = 0,
    val frames: Int = 3,
    val backfillHours: Int = 24,
    val keepDays: Int = 0,
    val channels: List<CameraChannelDto> = emptyList(),
)

@Serializable
data class CameraSettingsReply(
    val settings: CameraSettingsDto? = null,
)

@Serializable
data class SnapshotMinuteDto(
    val status: String = "",
    val files: List<String> = emptyList(),
    val error: String? = null,
    val at: String = "",
)

@Serializable
data class SnapshotProgressDto(
    val minutes: Int = 0,
    val minutesDone: Int = 0,
    val frames: Int = 0,
    val framesDone: Int = 0,
    val current: String? = null,
)

@Serializable
data class SnapshotRunDto(
    val state: String = "done",
    val minute: String? = null,
    val error: String? = null,
    val stopping: Boolean = false,
    val progress: SnapshotProgressDto? = null,
)

@Serializable
data class SnapshotsReply(
    val snapshots: Map<String, SnapshotMinuteDto> = emptyMap(),
    val run: SnapshotRunDto? = null,
)

@Serializable
data class SnapshotRunRequest(
    val minute: String? = null,
)

@Serializable
data class ResidentNoteRequest(
    val note: String,
)

@Serializable
data class PullArchiveEntryDto(
    val id: Long,
    val pulledAt: String = "",
    val actor: String = "",
    val residentsCount: Int = 0,
    val cardsCount: Int = 0,
)

@Serializable
data class PullArchiveReply(
    val pulls: List<PullArchiveEntryDto> = emptyList(),
)

@Serializable
data class PullArchiveDiffReply(
    val pull: PullArchiveEntryDto,
    val changes: List<SendChangeDto> = emptyList(),
)

@Serializable
data class DormantEntryDto(
    val directoryCode: String,
    val name: String = "",
    val type: String = "Resident",
    val entryCode: String = "",
    val cards: List<String> = emptyList(),
    val lastSeen: String? = null,
    val totalEvents: Int = 0,
)

@Serializable
data class UnknownCardActivityDto(
    val cardNumber: String,
    val uses: Int = 0,
    val admits: Int = 0,
    val denies: Int = 0,
    val lastSeen: String = "",
)

@Serializable
data class EntryCodeUsageDto(
    val entryCode: String,
    val directoryCode: String = "",
    val name: String = "",
    val uses: Int = 0,
    val distinctDays: Int = 0,
    val offHoursUses: Int = 0,
    val flagged: Boolean = false,
)

@Serializable
data class AuditTrafficDto(
    val totalEvents: Int = 0,
    val admits: Int = 0,
    val denies: Int = 0,
    val holds: Int = 0,
    val openCycles: Int = 0,
    val byHour: List<Int> = emptyList(),
)

@Serializable
data class AccessAuditReply(
    val days: Int = 90,
    val dormant: List<DormantEntryDto> = emptyList(),
    val unknownCards: List<UnknownCardActivityDto> = emptyList(),
    val entryCodes: List<EntryCodeUsageDto> = emptyList(),
    val traffic: AuditTrafficDto = AuditTrafficDto(),
)

@Serializable
data class ScheduleInputDto(
    val days: List<Int>,
    val time: String,
    val actions: List<String>,
    val enabled: Boolean = true,
)

@Serializable
data class ScheduleDto(
    val id: String,
    val days: List<Int> = emptyList(),
    val time: String = "",
    val actions: List<String> = emptyList(),
    val enabled: Boolean = true,
    val user: String = "",
    val lastRun: String? = null,
    val lastResult: String? = null,
    val lastError: String? = null,
    val nextRun: String? = null,
    val queued: Boolean = false,
)

@Serializable
data class SchedulesReply(
    val schedules: List<ScheduleDto> = emptyList(),
)

@Serializable
data class GateLoginDto(
    val dksUser: String = "",
    val dksPasswordSet: Boolean = false,
)

@Serializable
data class GateLoginUpdateRequest(
    val dksUser: String,
    val dksPassword: String = "",
    val masterCode: String = "",
)

@Serializable
data class GateUsersReply(
    val users: List<GateUserDto> = emptyList(),
)

@Serializable
data class ShareGateRequest(
    val username: String,
    val role: String,
)

@Serializable
data class CreateGateUserRequest(
    val username: String,
    val password: String = "",
    val email: String = "",
    val role: String,
)

@Serializable
data class UpdateRoleRequest(
    val role: String,
)

@Serializable
data class ApiKeyInfoDto(
    val id: String,
    val name: String,
    val role: String = "readonly",
    val scopes: List<String>? = null,
    val createdBy: String = "",
    val createdAt: String = "",
    val expiresAt: String? = null,
    val lastUsedAt: String? = null,
)

@Serializable
data class ApiKeysReply(
    val apiKeys: List<ApiKeyInfoDto> = emptyList(),
)

@Serializable
data class CreateApiKeyRequest(
    val name: String,
    val role: String,
    val expiresDays: Int = 0,
    val scopes: List<String>? = null,
)

@Serializable
data class CreateApiKeyResponse(
    val key: String,
    val apiKey: ApiKeyInfoDto,
)

@Serializable
data class WebhookInfoDto(
    val id: String,
    val name: String,
    val url: String,
    val events: List<String> = emptyList(),
    val enabled: Boolean = true,
    val createdBy: String = "",
    val createdAt: String = "",
)

@Serializable
data class WebhooksReply(
    val webhooks: List<WebhookInfoDto> = emptyList(),
    val availableEvents: List<String> = emptyList(),
)

@Serializable
data class CreateWebhookRequest(
    val name: String,
    val url: String,
    val events: List<String>,
    val enabled: Boolean = true,
)

@Serializable
data class LoginAuditEntryDto(
    val id: Long,
    val username: String? = null,
    val identifier: String = "",
    val role: String? = null,
    val method: String = "",
    val outcome: String = "",
    val reason: String? = null,
    val ip: String = "",
    val createdAt: String = "",
)

@Serializable
data class LoginAuditReply(
    val entries: List<LoginAuditEntryDto> = emptyList(),
)

@Serializable
data class SuspiciousReportDto(
    val id: Long,
    val directoryCode: String = "",
    val residentName: String? = null,
    val username: String = "",
    val txSummary: String = "",
    val note: String = "",
    val status: String = "open",
    val createdAt: String = "",
    val reviewNote: String? = null,
)

@Serializable
data class SuspiciousReportsReply(
    val reports: List<SuspiciousReportDto> = emptyList(),
)

@Serializable
data class ResolveReportRequest(
    val reviewNote: String = "",
)

@Serializable
data class ReviewResidentRequestBody(
    val decision: String,
    val apply: Boolean = false,
    val reviewNote: String = "",
)

@Serializable
data class LinkedUnitPhoneDto(
    val gatePhone: String = "",
    val directoryCode: String = "",
    val phone: String = "",
)

@Serializable
data class GatePhoneSyncDto(
    val updatedUnits: List<String> = emptyList(),
    val nextScheduledSend: String? = null,
)

@Serializable
data class PortalRelayOptionDto(
    val relay: Int,
    val name: String = "",
    val hidden: Boolean = false,
)

@Serializable
data class PortalGateRelaysDto(
    val phone: String = "",
    val siteName: String = "",
    val relays: List<PortalRelayOptionDto> = emptyList(),
)

@Serializable
data class MeProfileDto(
    val username: String = "",
    val email: String = "",
    val pendingEmail: String = "",
    val phone: String = "",
    val dark: Boolean = false,
    val notifyAccess: Boolean = true,
    val notifyAlerts: Boolean = true,
    val hiddenRelays: Map<String, List<Int>> = emptyMap(),
    val portalRelays: List<PortalGateRelaysDto> = emptyList(),
    val warning: String? = null,
    val linkedUnits: List<LinkedUnitPhoneDto> = emptyList(),
    val gatePhoneSync: GatePhoneSyncDto? = null,
)

@Serializable
data class PasskeyInfoDto(
    val id: String,
    val name: String,
    val site: String = "",
    val synced: Boolean = false,
    val createdAt: String = "",
    val lastUsedAt: String? = null,
)

@Serializable
data class PasskeysReply(
    val passkeys: List<PasskeyInfoDto> = emptyList(),
)

@Serializable
data class SettingsSaveReply(
    val changedBlocks: List<Int> = emptyList(),
)

@Serializable
data class InstructionsPdfRequest(
    val directoryCode: String? = null,
    val code: String? = null,
    val adminPhone: String? = null,
)

@Serializable
data class PullRequest(
    val what: String,
)

@Serializable
data class HistoryDetailDto(
    val key: String = "",
    val label: String = "",
    val change: String = "",
    val fields: List<SendFieldChangeDto> = emptyList(),
)

@Serializable
data class HistoryRevertedByDto(
    val id: String = "",
    val user: String = "",
    val time: String = "",
)

@Serializable
data class HistoryEventDto(
    val id: String,
    val time: String,
    val user: String = "",
    val action: String = "",
    val summary: String = "",
    val result: String? = null,
    val error: String? = null,
    val details: List<HistoryDetailDto> = emptyList(),
    val revertible: Boolean = false,
    val revertedBy: HistoryRevertedByDto? = null,
)

@Serializable
data class GateUserDto(
    val username: String,
    val email: String = "",
    val pendingEmail: String = "",
    val phone: String = "",
    val invited: Boolean = false,
    val role: String = "readonly",
    val grantedBy: String = "",
    val grantedAt: String = "",
    val lastSignIn: String? = null,
    val ssoProviders: List<String> = emptyList(),
)

@Serializable
data class HistoryReply(
    val events: List<HistoryEventDto> = emptyList(),
    val users: List<GateUserDto> = emptyList(),
)

@Serializable
data class GateSettingRowDto(
    val key: String,
    val group: String,
    val name: String,
    val offset: Int,
    val size: Int,
    val value: String,
    val gate: String,
    val editable: Boolean,
    val secret: Boolean = false,
    val note: String? = null,
    val maxLength: Int? = null,
)

@Serializable
data class SettingsReply(
    val pulled: String = "",
    val saved: String = "",
    val fields: List<GateSettingRowDto> = emptyList(),
)

@Serializable
data class GenericJsonBody(
    @SerialName("data") val raw: JsonElement? = null,
)

@Serializable
data class StepUpPasswordRequest(
    val password: String,
)

@Serializable
data class StepUpBiometricSignedRequest(
    val biometricKeyId: String,
    val challenge: String,
    val signature: String,
)

@Serializable
data class BiometricChallengeRequest(
    val keyId: String,
)

@Serializable
data class BiometricChallengeResponse(
    val challenge: String = "",
    val keyId: String = "",
)

@Serializable
data class RegisterBiometricKeyRequest(
    val keyId: String,
    val publicKeySpki: String,
    val label: String,
    val password: String? = null,
)

@Serializable
data class BiometricKeyInfoDto(
    val id: String,
    val label: String = "",
    val createdAt: String = "",
    val lastUsedAt: String? = null,
)

@Serializable
data class BiometricKeysReply(
    val keys: List<BiometricKeyInfoDto> = emptyList(),
)

@Serializable
data class FcmTokenInfoDto(
    val id: String,
    val label: String = "Android",
    val createdAt: String = "",
    val lastUsedAt: String? = null,
)

@Serializable
data class FcmTokensReply(
    val enabled: Boolean = false,
    val tokens: List<FcmTokenInfoDto> = emptyList(),
)

@Serializable
data class RegisterFcmRequest(
    val token: String,
    val label: String? = null,
)

@Serializable
data class UnregisterFcmRequest(
    val token: String? = null,
    val id: String? = null,
)

@Serializable
data class TestFcmReply(
    val enabled: Boolean = false,
    val sent: Int = 0,
    val failed: Int = 0,
    val removed: Int = 0,
)

@Serializable
data class WebPushSubDto(
    val id: String,
    val label: String = "Browser",
    val createdAt: String = "",
    val lastUsedAt: String? = null,
)

@Serializable
data class WebPushReply(
    val publicKey: String = "",
    val subscriptions: List<WebPushSubDto> = emptyList(),
)

@Serializable
data class TestPushReply(
    val sent: Int = 0,
    val failed: Int = 0,
    val removed: Int = 0,
)
