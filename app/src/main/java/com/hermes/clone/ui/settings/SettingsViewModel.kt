package com.hermes.clone.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.clone.AppGraph
import com.hermes.clone.data.AppSettings
import com.hermes.clone.data.Provider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface TestState {
    data object Idle : TestState
    data object Running : TestState
    data class Success(val info: String) : TestState
    data class Failure(val message: String) : TestState
}

sealed interface FetchModelsState {
    data object Idle : FetchModelsState
    data object Running : FetchModelsState
    data class Done(val models: List<String>) : FetchModelsState
    data class Error(val message: String) : FetchModelsState
}

class SettingsViewModel(private val graph: AppGraph) : ViewModel() {
    private val store = graph.settings
    private val client = graph.provider

    val settings: StateFlow<AppSettings> = store.settings

    private val _testState = MutableStateFlow<TestState>(TestState.Idle)
    val testState: StateFlow<TestState> = _testState.asStateFlow()

    private val _fetchState = MutableStateFlow<FetchModelsState>(FetchModelsState.Idle)
    val fetchState: StateFlow<FetchModelsState> = _fetchState.asStateFlow()

    private val _clearConfirmVisible = MutableStateFlow(false)
    val clearConfirmVisible: StateFlow<Boolean> = _clearConfirmVisible.asStateFlow()

    fun selectProvider(p: Provider) = store.selectProvider(p)
    fun setBaseUrl(url: String) = store.update { it.copy(baseUrl = url) }
    fun setModel(model: String) = store.update { it.copy(model = model) }
    fun setApiKey(key: String) = store.update { it.copy(apiKey = key) }
    fun clearApiKey() = store.clearApiKey()
    fun setMaxIterations(n: Int) = store.update { it.copy(maxIterations = n) }
    fun setStreaming(on: Boolean) = store.update { it.copy(streaming = on) }
    fun setContextLimit(tokens: Int) = store.update { it.copy(contextLimitTokens = tokens) }

    /** Runs a small test call (GET /models or a minimal chat) and updates [testState]. */
    fun testConnection() {
        val current = store.current()
        _testState.value = TestState.Running
        viewModelScope.launch {
            runCatching { client.testConnection(current) }
                .onSuccess { _testState.value = TestState.Success(it) }
                .onFailure { _testState.value = TestState.Failure(it.message ?: "Unknown error") }
        }
    }

    /** Fetches available model ids from the provider's /models endpoint. */
    fun fetchModels() {
        val current = store.current()
        _fetchState.value = FetchModelsState.Running
        viewModelScope.launch {
            runCatching { client.listModels(current) }
                .onSuccess { _fetchState.value = FetchModelsState.Done(it) }
                .onFailure { _fetchState.value = FetchModelsState.Error(it.message ?: "Failed") }
        }
    }

    fun pickModel(model: String) {
        store.update { it.copy(model = model) }
        _fetchState.value = FetchModelsState.Idle
    }

    fun dismissFetchModels() { _fetchState.value = FetchModelsState.Idle }
    fun resetTestState() { _testState.value = TestState.Idle }
    fun showClearConfirm() { _clearConfirmVisible.value = true }
    fun hideClearConfirm() { _clearConfirmVisible.value = false }

    fun clearAllData() {
        viewModelScope.launch {
            graph.clearAllData()
            _clearConfirmVisible.value = false
        }
    }
}
