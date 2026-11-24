package com.doorking.gate.feature.staff

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.doorking.gate.AppContainer
import com.doorking.gate.core.auth.SessionStorage
import com.doorking.gate.core.network.ApiClientFactory
import com.doorking.gate.core.network.ApiErrorResponse
import com.doorking.gate.core.network.CameraSettingsDto
import com.doorking.gate.core.network.ConflictDecisionRequest
import com.doorking.gate.core.network.DoorkingApi
import com.doorking.gate.core.network.GatePrefsDto
import com.doorking.gate.core.network.InstructionsPdfRequest
import com.doorking.gate.core.network.JobDto
import com.doorking.gate.core.network.PullRequest
import com.doorking.gate.core.network.RelayDto
import com.doorking.gate.core.network.RelayTimerDto
import com.doorking.gate.core.network.ResidentNoteRequest
import com.doorking.gate.core.network.ResidentRowDto
import com.doorking.gate.core.network.ResidentsReply
import com.doorking.gate.core.network.SendPreviewDto
import com.doorking.gate.core.network.SnapshotMinuteDto
import com.doorking.gate.core.network.SnapshotRunRequest
import com.doorking.gate.core.network.TransactionRowDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.security.SecureRandom

data class StaffGateUiState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val residentsReply: ResidentsReply? = null,
    val transactions: List<TransactionRowDto> = emptyList(),
    val txNextBefore: Long? = null,
    val loadingOlderTx: Boolean = false,
    val prefs: GatePrefsDto = GatePrefsDto(),
    val cameras: CameraSettingsDto? = null,
    val snapshots: Map<String, SnapshotMinuteDto> = emptyMap(),
    val job: JobDto? = null,
    val relays: List<RelayDto> = emptyList(),
    val relayTimers: List<RelayTimerDto> = emptyList(),
    val relaysCheckedAt: String = "",
    val relaysBusy: Boolean = false,
    val sendPreview: SendPreviewDto? = null,
    val sendPreviewBusy: Boolean = false,
    val editBusy: Boolean = false,
    val bannerMessage: String? = null,
    val error: String? = null,
)

class StaffGateViewModel(
    val gatePhone: String,
    private val api: DoorkingApi,
    private val sessionStorage: SessionStorage,
    private val pollIntervalMs: Long = 2_000L,
) : ViewModel() {

    private val _uiState = MutableStateFlow(StaffGateUiState())
    val uiState: StateFlow<StaffGateUiState> = _uiState.asStateFlow()

    private var jobPoll: Job? = null

    init {
        loadAll(initial = true)
    }

    fun serverUrl(): String = sessionStorage.currentServerUrl()

    fun loadAll(initial: Boolean = false) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                loading = initial,
                refreshing = !initial,
                error = null,
            )
            runCatching {
                val res = runCatching { api.getResidents(gatePhone) }.getOrNull()
                val data = runCatching { api.getData(gatePhone) }.getOrNull()
                val prefs = runCatching { api.getPrefs(gatePhone) }.getOrDefault(GatePrefsDto())
                val cams = runCatching { api.getCameras(gatePhone).settings }.getOrNull()
                val snaps = runCatching { api.getSnapshots(gatePhone).snapshots }.getOrDefault(emptyMap())
                val job = runCatching { api.getJob(gatePhone) }.getOrNull()
                LoadedGateBundle(res, data?.transactions?.rows.orEmpty(), data?.transactions?.next, prefs, cams, snaps, job)
            }
                .onSuccess { b ->
                    _uiState.value = _uiState.value.copy(
                        loading = false,
                        refreshing = false,
                        residentsReply = b.residents,
                        transactions = b.transactions,
                        txNextBefore = b.txNextBefore,
                        prefs = b.prefs,
                        cameras = b.cameras,
                        snapshots = b.snapshots,
                        job = b.job,
                    )
                    if (b.job?.state == "running") startJobPolling()
                }
                .onFailure { err ->
                    _uiState.value = _uiState.value.copy(
                        loading = false,
                        refreshing = false,
                        error = ApiClientFactory.parseErrorMessage(err),
                    )
                }
        }
    }

    fun loadOlderTransactions() {
        val before = _uiState.value.txNextBefore ?: return
        if (_uiState.value.loadingOlderTx) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(loadingOlderTx = true)
            runCatching { api.getData(gatePhone, before = before).transactions }
                .onSuccess { page ->
                    val merged = _uiState.value.transactions + page?.rows.orEmpty()
                    _uiState.value = _uiState.value.copy(
                        loadingOlderTx = false,
                        transactions = merged,
                        txNextBefore = page?.next,
                    )
                }
                .onFailure { err ->
                    _uiState.value = _uiState.value.copy(
                        loadingOlderTx = false,
                        error = ApiClientFactory.parseErrorMessage(err),
                    )
                }
        }
    }

    fun startPull(what: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(error = null)
            runCatching {
                val res = api.startPull(gatePhone, PullRequest(what))
                if (!res.isSuccessful) error(parseError(res.errorBody()?.string(), res.code(), "Pull failed"))
            }
                .onSuccess {
                    _uiState.value = _uiState.value.copy(bannerMessage = "Pulling $what from gate…")
                    startJobPolling()
                }
                .onFailure { err ->
                    _uiState.value = _uiState.value.copy(error = err.message ?: "Could not start pull")
                }
        }
    }

    fun loadSendPreview() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(sendPreviewBusy = true, error = null)
            runCatching { api.previewSend(gatePhone) }
                .onSuccess { preview ->
                    _uiState.value = _uiState.value.copy(sendPreviewBusy = false, sendPreview = preview)
                }
                .onFailure { err ->
                    _uiState.value = _uiState.value.copy(
                        sendPreviewBusy = false,
                        error = ApiClientFactory.parseErrorMessage(err),
                    )
                }
        }
    }

    fun dismissSendPreview() {
        _uiState.value = _uiState.value.copy(sendPreview = null)
    }

    fun startSend() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(error = null, sendPreview = null)
            runCatching { api.startSend(gatePhone) }
                .onSuccess {
                    _uiState.value = _uiState.value.copy(bannerMessage = "Sending changes to gate…")
                    startJobPolling()
                }
                .onFailure { err ->
                    _uiState.value = _uiState.value.copy(error = ApiClientFactory.parseErrorMessage(err))
                }
        }
    }

    fun resetWorkingCopy() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(error = null)
            runCatching { api.resetWorkingCopy(gatePhone) }
                .onSuccess {
                    _uiState.value = _uiState.value.copy(bannerMessage = "Working copy reset to last pull.")
                    loadAll(initial = false)
                }
                .onFailure { err ->
                    _uiState.value = _uiState.value.copy(error = ApiClientFactory.parseErrorMessage(err))
                }
        }
    }

    fun decideConflict(kind: String, key: String, take: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(error = null)
            runCatching {
                val res = api.decideConflict(gatePhone, ConflictDecisionRequest(kind = kind, key = key, choice = take))
                if (!res.isSuccessful) error(parseError(res.errorBody()?.string(), res.code(), "Could not resolve conflict"))
            }
                .onSuccess { loadAll(initial = false) }
                .onFailure { err -> _uiState.value = _uiState.value.copy(error = err.message ?: "Conflict update failed") }
        }
    }

    fun refreshRelays() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(relaysBusy = true, error = null)
            runCatching { api.getRelays(gatePhone) }
                .onSuccess { reply ->
                    _uiState.value = _uiState.value.copy(
                        relaysBusy = false,
                        relays = reply.relays,
                        relayTimers = reply.timers,
                        relaysCheckedAt = reply.checkedAt,
                    )
                }
                .onFailure { err ->
                    _uiState.value = _uiState.value.copy(
                        relaysBusy = false,
                        error = ApiClientFactory.parseErrorMessage(err),
                    )
                }
        }
    }

    fun toggleRelay(relay: Int, holdOpen: Boolean, holdMinutes: Int) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(relaysBusy = true, error = null)
            val body = buildJsonObject {
                put(relay.toString(), JsonPrimitive(holdOpen))
                if (holdOpen && holdMinutes > 0) {
                    put("holdMinutes", JsonPrimitive(holdMinutes))
                }
            }
            runCatching {
                val res = api.setRelays(gatePhone, body)
                if (!res.isSuccessful) error(parseError(res.errorBody()?.string(), res.code(), "Relay change failed"))
                res.body()
            }
                .onSuccess { reply ->
                    val label = _uiState.value.prefs.relayLabels[relay.toString()] ?: "Relay $relay"
                    _uiState.value = _uiState.value.copy(
                        relaysBusy = false,
                        relays = reply?.relays ?: _uiState.value.relays,
                        relayTimers = reply?.timers ?: _uiState.value.relayTimers,
                        relaysCheckedAt = reply?.checkedAt ?: _uiState.value.relaysCheckedAt,
                        bannerMessage = if (holdOpen) "$label held open." else "$label released.",
                    )
                }
                .onFailure { err ->
                    _uiState.value = _uiState.value.copy(
                        relaysBusy = false,
                        error = err.message ?: "Relay command failed",
                    )
                }
        }
    }

    fun cancelRelayTimer(relay: Int) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(relaysBusy = true, error = null)
            runCatching {
                val res = api.cancelRelayTimer(gatePhone, relay)
                if (!res.isSuccessful) error(parseError(res.errorBody()?.string(), res.code(), "Could not cancel timer"))
                res.body()
            }
                .onSuccess { reply ->
                    _uiState.value = _uiState.value.copy(
                        relaysBusy = false,
                        relayTimers = reply?.timers ?: _uiState.value.relayTimers.filterNot { it.relay == relay },
                        bannerMessage = "Auto-release timer cancelled.",
                    )
                }
                .onFailure { err ->
                    _uiState.value = _uiState.value.copy(
                        relaysBusy = false,
                        error = err.message ?: "Timer cancel failed",
                    )
                }
        }
    }

    fun requestSnapshot(minute: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(error = null)
            runCatching {
                val res = api.runSnapshots(gatePhone, SnapshotRunRequest(minute = minute))
                if (!res.isSuccessful) error(parseError(res.errorBody()?.string(), res.code(), "Could not fetch clip"))
            }
                .onSuccess {
                    _uiState.value = _uiState.value.copy(bannerMessage = "Fetching camera footage for $minute…")
                    // Poll while the recorder fetches the clip so the playable video appears automatically.
                    repeat(30) {
                        delay(2_000L)
                        val reply = runCatching { api.getSnapshots(gatePhone) }.getOrNull() ?: return@repeat
                        _uiState.value = _uiState.value.copy(snapshots = reply.snapshots)
                        if (reply.run?.state != "running") return@onSuccess
                    }
                }
                .onFailure { err ->
                    _uiState.value = _uiState.value.copy(error = err.message ?: "Camera fetch failed")
                }
        }
    }

    fun saveResident(
        existing: ResidentRowDto?,
        isVendor: Boolean,
        directoryCode: String,
        name: String,
        areaCode: String,
        phone: String,
        entryCode: String,
        note: String,
        onSaved: () -> Unit,
    ) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(editBusy = true, error = null)
            val payload = buildJsonObject {
                if (existing == null && directoryCode.isNotBlank()) put("directoryCode", JsonPrimitive(directoryCode.trim()))
                put("name", JsonPrimitive(name.trim()))
                if (existing == null) put("type", JsonPrimitive(if (isVendor) "Vendor" else "Resident"))
                if (!isVendor) {
                    put("areaCode", JsonPrimitive(areaCode.filter(Char::isDigit)))
                    put("phone", JsonPrimitive(phone.trim()))
                }
                put("entryCode", JsonPrimitive(entryCode.filter(Char::isDigit)))
            }
            runCatching {
                val res = if (existing == null) {
                    api.addResident(gatePhone, payload)
                } else {
                    api.updateResident(gatePhone, existing.directoryCode, payload)
                }
                if (!res.isSuccessful) error(parseError(res.errorBody()?.string(), res.code(), "Save failed"))
                val targetCode = existing?.directoryCode ?: directoryCode.trim()
                if (targetCode.isNotBlank() && note.trim() != existing?.note.orEmpty().trim()) {
                    runCatching { api.saveResidentNote(gatePhone, targetCode, ResidentNoteRequest(note.trim())) }
                }
            }
                .onSuccess {
                    _uiState.value = _uiState.value.copy(editBusy = false, bannerMessage = "Saved to working copy.")
                    onSaved()
                    loadAll(initial = false)
                }
                .onFailure { err ->
                    _uiState.value = _uiState.value.copy(
                        editBusy = false,
                        error = err.message ?: "Could not save entry",
                    )
                }
        }
    }

    fun deleteResident(directoryCode: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(editBusy = true, error = null)
            runCatching {
                val res = api.deleteResident(gatePhone, directoryCode)
                if (!res.isSuccessful) error(parseError(res.errorBody()?.string(), res.code(), "Delete failed"))
            }
                .onSuccess {
                    _uiState.value = _uiState.value.copy(editBusy = false, bannerMessage = "Deleted unit $directoryCode in working copy.")
                    loadAll(initial = false)
                }
                .onFailure { err ->
                    _uiState.value = _uiState.value.copy(editBusy = false, error = err.message ?: "Delete failed")
                }
        }
    }

    suspend fun downloadWelcomePdf(row: ResidentRowDto): Result<Pair<String, ByteArray>> = runCatching {
        val res = api.downloadResidentInstructionsPdf(
            gatePhone,
            InstructionsPdfRequest(
                directoryCode = row.directoryCode,
                adminPhone = _uiState.value.prefs.adminPhone.ifBlank { null },
            ),
        )
        if (!res.isSuccessful) error(parseError(res.errorBody()?.string(), res.code(), "Could not generate PDF"))
        val bytes = withContext(Dispatchers.IO) { res.body()?.bytes() } ?: error("Empty PDF response")
        "ResidentInstructions-${row.directoryCode}.pdf" to bytes
    }

    fun clearBanner() {
        _uiState.value = _uiState.value.copy(bannerMessage = null, error = null)
    }

    /**
     * Fetches the latest gate job snapshot immediately and ensures live polling is active whenever
     * a job is running (e.g. after returning from background or opening the Gate/Progress dialog).
     */
    fun refreshJob() {
        viewModelScope.launch {
            val job = try {
                api.getJob(gatePhone)
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                null
            } ?: return@launch
            val prevState = _uiState.value.job?.state
            _uiState.value = _uiState.value.copy(job = job)
            if (job.state == "running") {
                if (jobPoll?.isActive != true) startJobPolling()
            } else if (prevState == "running" && job.state == "done") {
                loadAll(initial = false)
            }
        }
    }

    private fun startJobPolling() {
        jobPoll?.cancel()
        jobPoll = viewModelScope.launch {
            while (isActive) {
                val job = try {
                    api.getJob(gatePhone)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (_: Throwable) {
                    null
                }
                if (job != null) {
                    val prevState = _uiState.value.job?.state
                    _uiState.value = _uiState.value.copy(job = job)
                    if (job.state != "running") {
                        if (job.state == "done" && prevState == "running") loadAll(initial = false)
                        break
                    }
                }
                delay(pollIntervalMs)
            }
        }
    }

    private fun parseError(raw: String?, code: Int, fallback: String): String {
        if (!raw.isNullOrBlank()) {
            val parsed = runCatching { ApiClientFactory.json.decodeFromString<ApiErrorResponse>(raw) }.getOrNull()
            if (parsed != null && parsed.error.isNotBlank()) return parsed.error
        }
        return "$fallback (HTTP $code)"
    }

    private data class LoadedGateBundle(
        val residents: ResidentsReply?,
        val transactions: List<TransactionRowDto>,
        val txNextBefore: Long?,
        val prefs: GatePrefsDto,
        val cameras: CameraSettingsDto?,
        val snapshots: Map<String, SnapshotMinuteDto>,
        val job: JobDto?,
    )

    companion object {
        private val RNG = SecureRandom()

        /** Generates a non-weak, non-colliding 4-digit entry code (mirrors `generateSafeEntryCode` in `src/shared.ts`). */
        fun generateSafeEntryCode(existingCodes: Collection<String>): String {
            val taken = existingCodes.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            repeat(500) {
                val code = RNG.nextInt(10_000).toString().padStart(4, '0')
                if (code !in taken && !isWeakEntryCode(code)) return code
            }
            return (1000..9999).first { it.toString() !in taken }.toString()
        }

        fun isWeakEntryCode(code: String): Boolean {
            if (code.length != 4 || !code.all(Char::isDigit)) return true
            if (code.toSet().size == 1) return true // 0000, 1111, ...
            if (code in setOf("1234", "2345", "3456", "4567", "5678", "6789", "9876", "8765", "7654", "6543", "5432", "4321", "0123", "1999", "2000", "2024", "2025", "2026")) {
                return true
            }
            if (code[0] == code[2] && code[1] == code[3]) return true // 1212, 4545
            return false
        }

        fun factory(container: AppContainer, gatePhone: String): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    StaffGateViewModel(
                        gatePhone = gatePhone,
                        api = container.api,
                        sessionStorage = container.sessionStorage,
                    ) as T
            }
    }
}
