package com.doorking.gate.feature.portal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.doorking.gate.AppContainer
import com.doorking.gate.core.network.ApiClientFactory
import com.doorking.gate.core.network.ApiErrorResponse
import com.doorking.gate.core.network.BuzzRequest
import com.doorking.gate.core.network.CreateResidentRequestBody
import com.doorking.gate.core.network.DoorkingApi
import com.doorking.gate.core.network.PortalCodeRequest
import com.doorking.gate.core.network.PortalMeReply
import com.doorking.gate.core.network.PortalReportRequest
import com.doorking.gate.core.network.PortalStatusReply
import com.doorking.gate.core.network.ResidentRequestDto
import com.doorking.gate.core.network.TransactionRowDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive

data class ResidentPortalUiState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val selectedUnitCode: String? = null,
    val me: PortalMeReply? = null,
    val status: PortalStatusReply = PortalStatusReply(),
    val activity: List<TransactionRowDto> = emptyList(),
    val showSnapshots: Boolean = false,
    val requests: List<ResidentRequestDto> = emptyList(),
    val buzzingRelay: Int? = null,
    val rerollBusy: Boolean = false,
    val pdfBusy: Boolean = false,
    val requestBusy: Boolean = false,
    val bannerMessage: String? = null,
    val error: String? = null,
)

class ResidentPortalViewModel(
    private val gatePhone: String,
    private val api: DoorkingApi,
    private val pollIntervalMs: Long = 3_000L,
    private val cooldownTickMs: Long = 1_000L,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ResidentPortalUiState())
    val uiState: StateFlow<ResidentPortalUiState> = _uiState.asStateFlow()

    private var statusPollJob: Job? = null
    private var cooldownTickJob: Job? = null
    private var statusPollingEnabled: Boolean = false
    private var hasLoadedOnce: Boolean = false

    init {
        loadAll(initial = true)
    }

    fun setStatusPollingEnabled(enabled: Boolean) {
        val wasEnabled = statusPollingEnabled
        statusPollingEnabled = enabled
        if (!enabled) {
            statusPollJob?.cancel()
            statusPollJob = null
            cooldownTickJob?.cancel()
            cooldownTickJob = null
            return
        }
        if (!wasEnabled) {
            if (hasLoadedOnce) {
                startStatusPolling(immediateRefresh = true)
            }
        } else {
            val current = _uiState.value.status
            syncCooldownTicker(current)
            if (needsStatusPolling(current) && statusPollJob?.isActive != true) {
                startStatusPolling(immediateRefresh = false)
            }
        }
    }

    fun selectUnit(directoryCode: String) {
        if (directoryCode == _uiState.value.selectedUnitCode) return
        _uiState.value = _uiState.value.copy(selectedUnitCode = directoryCode, bannerMessage = null)
        loadAll(initial = false, codeOverride = directoryCode)
    }

    fun loadAll(initial: Boolean = false, codeOverride: String? = _uiState.value.selectedUnitCode) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                loading = initial,
                refreshing = !initial,
                error = null,
            )
            runCatchingNonFatal {
                val me = api.getPortalMe(gatePhone, codeOverride)
                val activeCode = codeOverride ?: me.unit.directoryCode
                coroutineScope {
                    val statusDeferred = async {
                        runCatchingNonFatal { api.getPortalStatus(gatePhone) }.getOrDefault(PortalStatusReply())
                    }
                    val actDeferred = async {
                        runCatchingNonFatal { api.getPortalActivity(gatePhone, activeCode) }.getOrNull()
                    }
                    val reqsDeferred = async {
                        runCatchingNonFatal { api.getPortalRequests(gatePhone).requests }.getOrDefault(emptyList())
                    }
                    Triple(me, statusDeferred.await(), Pair(actDeferred.await(), reqsDeferred.await()))
                }
            }
                .onSuccess { (me, status, extra) ->
                    val (act, reqs) = extra
                    hasLoadedOnce = true
                    _uiState.value = _uiState.value.copy(
                        loading = false,
                        refreshing = false,
                        selectedUnitCode = me.unit.directoryCode,
                        me = me,
                        status = status,
                        activity = act?.rows.orEmpty(),
                        showSnapshots = act?.showSnapshots == true,
                        requests = reqs,
                        error = null,
                    )
                    syncCooldownTicker(status)
                    if (statusPollingEnabled && needsStatusPolling(status) && statusPollJob?.isActive != true) {
                        startStatusPolling(immediateRefresh = false)
                    }
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

    fun buzz(relay: Int, relayName: String) {
        if (_uiState.value.buzzingRelay != null) return
        val unitCode = _uiState.value.selectedUnitCode
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(buzzingRelay = relay, error = null, bannerMessage = null)
            runCatching {
                val res = api.portalBuzz(gatePhone, BuzzRequest(relay = relay, code = unitCode))
                if (!res.isSuccessful) error(parseError(res.errorBody()?.string(), res.code(), "Could not open gate"))
                res.body()?.alreadyOpen == true
            }
                .onSuccess { alreadyOpen ->
                    val msg = if (alreadyOpen) "$relayName is already open." else "$relayName opened!"
                    _uiState.value = _uiState.value.copy(buzzingRelay = null, bannerMessage = msg)
                    refreshStatusOnce()
                    loadAll(initial = false)
                }
                .onFailure { err ->
                    _uiState.value = _uiState.value.copy(
                        buzzingRelay = null,
                        error = err.message ?: "Could not open gate",
                    )
                }
        }
    }

    fun rerollCode() {
        if (_uiState.value.rerollBusy) return
        val unitCode = _uiState.value.selectedUnitCode
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(rerollBusy = true, error = null, bannerMessage = null)
            runCatching {
                val res = api.portalRerollCode(gatePhone, PortalCodeRequest(code = unitCode))
                if (!res.isSuccessful) error(parseError(res.errorBody()?.string(), res.code(), "Could not change code"))
                res.body() ?: error("Empty response from server")
            }
                .onSuccess { reply ->
                    val eta = reply.nextScheduledSend?.let { " Next scheduled sync: $it." } ?: ""
                    val msg = "New entry code saved (${reply.remainingToday} change(s) left today).$eta"
                    val currentMe = _uiState.value.me
                    val updatedMe = currentMe?.copy(
                        unit = currentMe.unit.copy(entryCode = reply.entryCode),
                        reroll = currentMe.reroll.copy(
                            usedToday = currentMe.reroll.usedToday + 1,
                            remainingToday = reply.remainingToday,
                            pendingSend = true,
                            nextScheduledSend = reply.nextScheduledSend ?: currentMe.reroll.nextScheduledSend,
                        ),
                    )
                    _uiState.value = _uiState.value.copy(
                        rerollBusy = false,
                        me = updatedMe,
                        bannerMessage = msg,
                    )
                }
                .onFailure { err ->
                    _uiState.value = _uiState.value.copy(
                        rerollBusy = false,
                        error = err.message ?: "Could not change entry code",
                    )
                }
        }
    }

    fun reportSuspicious(row: TransactionRowDto, note: String) {
        val txKey = row.txKey ?: return
        val unitCode = _uiState.value.selectedUnitCode
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(error = null, bannerMessage = null)
            runCatching {
                val res = api.reportSuspiciousActivity(
                    gatePhone,
                    PortalReportRequest(
                        txSource = row.source ?: "gate",
                        txKey = txKey,
                        note = note.trim(),
                        code = unitCode,
                    ),
                )
                if (!res.isSuccessful) error(parseError(res.errorBody()?.string(), res.code(), "Could not report activity"))
            }
                .onSuccess {
                    val updated = _uiState.value.activity.map { item ->
                        if (item.txKey == txKey && (item.source ?: "gate") == (row.source ?: "gate")) {
                            item.copy(reported = true)
                        } else {
                            item
                        }
                    }
                    _uiState.value = _uiState.value.copy(
                        activity = updated,
                        bannerMessage = "Reported to management.",
                    )
                }
                .onFailure { err ->
                    _uiState.value = _uiState.value.copy(error = err.message ?: "Could not submit report")
                }
        }
    }

    fun submitRequest(kind: String, phone: String, note: String, onDone: () -> Unit) {
        val unitCode = _uiState.value.selectedUnitCode
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(requestBusy = true, error = null, bannerMessage = null)
            val payload = if (kind == "phone" && phone.isNotBlank()) {
                mapOf("phone" to JsonPrimitive(phone.trim()))
            } else {
                emptyMap()
            }
            runCatching {
                val res = api.createPortalRequest(
                    gatePhone,
                    CreateResidentRequestBody(kind = kind, payload = payload, note = note.trim(), code = unitCode),
                )
                if (!res.isSuccessful) error(parseError(res.errorBody()?.string(), res.code(), "Could not submit request"))
            }
                .onSuccess {
                    _uiState.value = _uiState.value.copy(requestBusy = false, bannerMessage = "Request sent to management.")
                    onDone()
                    loadAll(initial = false)
                }
                .onFailure { err ->
                    _uiState.value = _uiState.value.copy(
                        requestBusy = false,
                        error = err.message ?: "Could not submit request",
                    )
                }
        }
    }

    suspend fun downloadInstructionsPdfBytes(): Result<Pair<String, ByteArray>> {
        _uiState.value = _uiState.value.copy(pdfBusy = true, error = null)
        return runCatching {
            val code = _uiState.value.selectedUnitCode
            val res = api.downloadPortalInstructionsPdf(gatePhone, PortalCodeRequest(code = code))
            if (!res.isSuccessful) error(parseError(res.errorBody()?.string(), res.code(), "Could not download PDF"))
            val bytes = withContext(Dispatchers.IO) { res.body()?.bytes() } ?: error("Empty PDF response")
            val filename = "ResidentInstructions-${code ?: gatePhone}.pdf"
            filename to bytes
        }.also {
            _uiState.value = _uiState.value.copy(pdfBusy = false)
        }
    }

    fun clearBanner() {
        _uiState.value = _uiState.value.copy(bannerMessage = null, error = null)
    }

    private fun needsStatusPolling(status: PortalStatusReply): Boolean =
        !status.available || status.cooldownSeconds > 0

    private fun syncCooldownTicker(status: PortalStatusReply) {
        if (!statusPollingEnabled || !status.available || status.cooldownSeconds <= 0) {
            cooldownTickJob?.cancel()
            cooldownTickJob = null
            return
        }
        if (cooldownTickJob?.isActive == true) return
        cooldownTickJob = viewModelScope.launch {
            while (isActive && statusPollingEnabled) {
                delay(cooldownTickMs)
                val current = _uiState.value.status
                if (!current.available || current.cooldownSeconds <= 0) break
                val next = (current.cooldownSeconds - 1).coerceAtLeast(0)
                _uiState.value = _uiState.value.copy(status = current.copy(cooldownSeconds = next))
                if (next == 0) break
            }
        }
    }

    private fun startStatusPolling(immediateRefresh: Boolean = false) {
        if (!statusPollingEnabled) return
        statusPollJob?.cancel()
        statusPollJob = viewModelScope.launch {
            var consecutiveErrors = 0
            var delayBeforeNext = if (immediateRefresh) 0L else pollIntervalMs
            while (isActive && statusPollingEnabled) {
                if (delayBeforeNext > 0L) {
                    delay(delayBeforeNext)
                    if (consecutiveErrors == 0 && !needsStatusPolling(_uiState.value.status)) break
                }
                if (!statusPollingEnabled) break
                val fetched = try {
                    api.getPortalStatus(gatePhone)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (_: Throwable) {
                    null
                }
                if (fetched != null) {
                    consecutiveErrors = 0
                    _uiState.value = _uiState.value.copy(status = fetched)
                    syncCooldownTicker(fetched)
                    if (!needsStatusPolling(fetched)) break
                    delayBeforeNext = pollIntervalMs
                } else {
                    consecutiveErrors += 1
                    if (consecutiveErrors >= MAX_POLL_ERRORS) break
                    delayBeforeNext = (pollIntervalMs * (1L shl (consecutiveErrors - 1)))
                        .coerceAtMost(MAX_POLL_BACKOFF_MS)
                }
            }
        }
    }

    private suspend fun refreshStatusOnce() {
        runCatchingNonFatal { api.getPortalStatus(gatePhone) }
            .onSuccess { status ->
                _uiState.value = _uiState.value.copy(status = status)
                syncCooldownTicker(status)
                if (statusPollingEnabled && needsStatusPolling(status) && statusPollJob?.isActive != true) {
                    startStatusPolling(immediateRefresh = false)
                }
            }
    }

    private inline fun <T> runCatchingNonFatal(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (ce: CancellationException) {
        throw ce
    } catch (t: Throwable) {
        Result.failure(t)
    }

    private fun parseError(raw: String?, code: Int, fallback: String): String {
        if (!raw.isNullOrBlank()) {
            val parsed = runCatching { ApiClientFactory.json.decodeFromString<ApiErrorResponse>(raw) }.getOrNull()
            if (parsed != null && parsed.error.isNotBlank()) return parsed.error
        }
        return "$fallback (HTTP $code)"
    }

    companion object {
        private const val MAX_POLL_ERRORS = 5
        private const val MAX_POLL_BACKOFF_MS = 30_000L

        fun factory(container: AppContainer, gatePhone: String): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    ResidentPortalViewModel(gatePhone = gatePhone, api = container.api) as T
            }
    }
}
