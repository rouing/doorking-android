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

    init {
        loadGates(initial = true)
        checkActiveAddJob()
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
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (isActive) {
                val job = runCatching { api.getGateAddJob().job }.getOrNull()
                if (job != null) {
                    _uiState.value = _uiState.value.copy(addJob = job)
                    if (job.state != "running") {
                        if (job.state == "done") loadGates(initial = false)
                        break
                    }
                }
                delay(pollIntervalMs)
            }
        }
    }

    companion object {
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
