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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
) : ViewModel() {

    private val _uiState = MutableStateFlow(ResidentPortalUiState())
    val uiState: StateFlow<ResidentPortalUiState> = _uiState.asStateFlow()

    private var statusPollJob: Job? = null

    init {
        loadAll(initial = true)
        startStatusPolling()
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
            runCatching {
                val me = api.getPortalMe(gatePhone, codeOverride)
                val activeCode = codeOverride ?: me.unit.directoryCode
                val status = runCatching { api.getPortalStatus(gatePhone) }.getOrDefault(PortalStatusReply())
                val act = runCatching { api.getPortalActivity(gatePhone, activeCode) }.getOrNull()
                val reqs = runCatching { api.getPortalRequests(gatePhone).requests }.getOrDefault(emptyList())
                Triple(me, status, Pair(act, reqs))
            }
                .onSuccess { (me, status, extra) ->
                    val (act, reqs) = extra
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

    private fun startStatusPolling() {
        statusPollJob?.cancel()
        statusPollJob = viewModelScope.launch {
            while (isActive) {
                delay(pollIntervalMs)
                refreshStatusOnce()
            }
        }
    }

    private suspend fun refreshStatusOnce() {
        runCatching { api.getPortalStatus(gatePhone) }
            .onSuccess { status -> _uiState.value = _uiState.value.copy(status = status) }
    }

    private fun parseError(raw: String?, code: Int, fallback: String): String {
        if (!raw.isNullOrBlank()) {
            val parsed = runCatching { ApiClientFactory.json.decodeFromString<ApiErrorResponse>(raw) }.getOrNull()
            if (parsed != null && parsed.error.isNotBlank()) return parsed.error
        }
        return "$fallback (HTTP $code)"
    }

    companion object {
        fun factory(container: AppContainer, gatePhone: String): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    ResidentPortalViewModel(gatePhone = gatePhone, api = container.api) as T
            }
    }
}
