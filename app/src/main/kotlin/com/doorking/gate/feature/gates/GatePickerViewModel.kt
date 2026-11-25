package com.doorking.gate.feature.gates

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.doorking.gate.AppContainer
import com.doorking.gate.core.auth.SessionStorage
import com.doorking.gate.core.network.AddGateRequest
import com.doorking.gate.core.network.ApiClientFactory
import com.doorking.gate.core.network.ApiErrorResponse
import com.doorking.gate.core.network.DoorkingApi
import com.doorking.gate.core.network.GateConfigDto
import com.doorking.gate.core.network.GateInfoDto
import com.doorking.gate.core.network.JobDto
import com.doorking.gate.core.network.OpenGateRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class GatePickerUiState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val gates: List<GateInfoDto> = emptyList(),
    val openingPhone: String? = null,
    val error: String? = null,
    val addBusy: Boolean = false,
    val addError: String? = null,
    val addJob: JobDto? = null,
)

class GatePickerViewModel(
    private val api: DoorkingApi,
    private val sessionStorage: SessionStorage,
    private val pollIntervalMs: Long = 1500L,
) : ViewModel() {

    private val _uiState = MutableStateFlow(GatePickerUiState())
    val uiState: StateFlow<GatePickerUiState> = _uiState.asStateFlow()

    private var pollJob: Job? = null
    private var active: Boolean = true

    init {
        loadGates(initial = true)
        checkActiveAddJob()
    }

    fun setActive(isActive: Boolean) {
        val wasActive = active
        active = isActive
        if (!isActive) {
            pollJob?.cancel()
            pollJob = null
        } else if (!wasActive) {
            if (_uiState.value.addJob?.state == "running") {
                startPollingAddJob()
            } else {
                checkActiveAddJob()
            }
        }
    }

    fun loadGates(initial: Boolean = false) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                loading = initial,
                refreshing = !initial,
                error = null,
            )
            runCatching { api.listGates().gates }
                .onSuccess { gates ->
                    _uiState.value = _uiState.value.copy(
                        loading = false,
                        refreshing = false,
                        gates = gates,
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

    fun openGate(gate: GateInfoDto, onOpened: (GateInfoDto) -> Unit) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(openingPhone = gate.phoneNumber, error = null)
            runCatching {
                api.openGate(OpenGateRequest(phoneNumber = gate.phoneNumber))
                sessionStorage.setLastGatePhone(gate.phoneNumber)
            }
                .onSuccess {
                    _uiState.value = _uiState.value.copy(openingPhone = null)
                    onOpened(gate)
                }
                .onFailure { err ->
                    _uiState.value = _uiState.value.copy(
                        openingPhone = null,
                        error = ApiClientFactory.parseErrorMessage(err),
                    )
                }
        }
    }

    fun startAddGate(
        phoneNumber: String,
        masterCode: String,
        dksUser: String,
        dksPassword: String,
        gateConfig: GateConfigDto,
        onStarted: () -> Unit = {},
    ) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(addBusy = true, addError = null)
            runCatching {
                val res = api.addGate(
                    AddGateRequest(
                        phoneNumber = phoneNumber.trim(),
                        masterCode = masterCode.trim(),
                        dksUser = dksUser.trim(),
                        dksPassword = dksPassword,
                        gateConfig = gateConfig,
                    ),
                )
                if (!res.isSuccessful) {
                    val raw = res.errorBody()?.string().orEmpty()
                    val msg = runCatching {
                        ApiClientFactory.json.decodeFromString<ApiErrorResponse>(raw).error
                    }.getOrNull() ?: "Add gate failed (HTTP ${res.code()})"
                    error(msg)
                }
            }
                .onSuccess {
                    _uiState.value = _uiState.value.copy(addBusy = false, addError = null)
                    onStarted()
                    startPollingAddJob()
                }
                .onFailure { err ->
                    _uiState.value = _uiState.value.copy(
                        addBusy = false,
                        addError = err.message ?: "Could not start gate pull",
                    )
                }
        }
    }

    fun dismissAddJob() {
        pollJob?.cancel()
        pollJob = null
        _uiState.value = _uiState.value.copy(addJob = null, addError = null)
    }

    private fun checkActiveAddJob() {
        viewModelScope.launch {
            runCatching { api.getGateAddJob().job }
                .onSuccess { job ->
                    if (job != null && job.state == "running") {
                        _uiState.value = _uiState.value.copy(addJob = job)
                        startPollingAddJob()
                    }
                }
        }
    }

    private fun startPollingAddJob() {
        if (!active) return
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            var consecutiveErrors = 0
            while (isActive && active) {
                val reply = try {
                    Result.success(api.getGateAddJob())
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    Result.failure(t)
                }
                if (reply.isSuccess) {
                    consecutiveErrors = 0
                    val job = reply.getOrNull()?.job
                    if (job != null) {
                        _uiState.value = _uiState.value.copy(addJob = job)
                        if (job.state != "running") {
                            if (job.state == "done") loadGates(initial = false)
                            break
                        }
                    }
                    delay(pollIntervalMs)
                } else {
                    consecutiveErrors += 1
                    if (consecutiveErrors >= MAX_POLL_ERRORS) {
                        _uiState.value = _uiState.value.copy(
                            addError = "Lost connection while checking add-gate progress. Pull to refresh.",
                        )
                        break
                    }
                    val backoffMs = (pollIntervalMs * (1L shl (consecutiveErrors - 1)))
                        .coerceAtMost(MAX_POLL_BACKOFF_MS)
                    delay(backoffMs)
                }
            }
        }
    }

    companion object {
        private const val MAX_POLL_ERRORS = 5
        private const val MAX_POLL_BACKOFF_MS = 30_000L

        fun factory(container: AppContainer): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                GatePickerViewModel(
                    api = container.api,
                    sessionStorage = container.sessionStorage,
                ) as T
        }
    }
}
