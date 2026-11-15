package com.example.ui

import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.analysis.ModelStatus
import com.example.analytics.AnalyticsSnapshot
import com.example.analytics.AnalyticsWindow
import com.example.classification.ClassificationVerificationReport
import com.example.data.local.entity.PredictionEntity
import com.example.data.local.entity.ResultEntity
import com.example.data.repository.HistoricalAnalysisSummary
import com.example.di.AppContainer
import com.example.domain.model.NetworkStatus
import com.example.domain.model.WinGoGameConfig
import com.example.network.HistoryFetchOutcome
import com.example.network.HistorySyncDiagnostics
import com.example.prediction.CoordinatorUiState
import com.example.prediction.PredictionResult
import com.example.security.ApiAuthenticationState
import com.example.security.LoginDiagnosticState
import com.example.security.WebsiteSessionState
import com.example.timer.TimerUiState
import com.example.training.ModelTrainingSnapshot
import com.example.webview.WebViewLoadState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class UserActivationState(val label: String) {
    INACTIVE("INACTIVE"),
    ACTIVE("ACTIVE")
}

enum class PredictorPanelTab {
    PREDICTION,
    ANALYSIS,
    HISTORY
}

data class PredictorPanelUiState(
    val isPanelOpen: Boolean = false,
    val isExpanded: Boolean = false,
    val activeTab: PredictorPanelTab = PredictorPanelTab.PREDICTION,
    val buttonOffset: Offset? = null,
    val panelOffset: Offset = Offset.Zero
)

@OptIn(ExperimentalCoroutinesApi::class)
class PredictorViewModel(
    private val container: AppContainer
) : ViewModel() {

    val websiteSessionState: StateFlow<WebsiteSessionState> = container.websiteSessionManager.websiteSessionState
    val loginDiagnostics: StateFlow<LoginDiagnosticState> = container.websiteSessionManager.diagnostics
    val apiAuthState: StateFlow<ApiAuthenticationState> = container.websiteSessionManager.apiAuthState
    val historyDiagnostics: StateFlow<HistorySyncDiagnostics> = container.historyDataSource.diagnostics
    val networkStatus: StateFlow<NetworkStatus> = container.networkMonitor.networkStatus
    val timerState: StateFlow<TimerUiState> = container.timerSyncManager.timerState

    private val _userActivationState = MutableStateFlow(UserActivationState.INACTIVE)
    val userActivationState: StateFlow<UserActivationState> = _userActivationState.asStateFlow()

    private val _activationPromptDismissed = MutableStateFlow(false)
    val activationPromptDismissed: StateFlow<Boolean> = _activationPromptDismissed.asStateFlow()

    val latestHistory: StateFlow<List<ResultEntity>> = container.historyRepository.observeLatest500Results()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val latestPredictions: StateFlow<List<PredictionEntity>> = container.predictionRepository.observeLatestPredictions()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val historyCount: StateFlow<Int> = container.historyRepository.observeResultCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val historicalAnalysis: StateFlow<HistoricalAnalysisSummary> = latestHistory
        .map { results -> container.historyRepository.computeHistoricalAnalysis(results) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoricalAnalysisSummary.Empty)

    private val _selectedAnalyticsWindow = MutableStateFlow(AnalyticsWindow.LAST_50)
    val selectedAnalyticsWindow: StateFlow<AnalyticsWindow> = _selectedAnalyticsWindow.asStateFlow()

    val analyticsSnapshot: StateFlow<AnalyticsSnapshot> = _selectedAnalyticsWindow
        .flatMapLatest { window -> container.analyticsRepository.observeAnalytics(window) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = AnalyticsSnapshot()
        )

    private val _panelUiState = MutableStateFlow(PredictorPanelUiState())
    val panelUiState: StateFlow<PredictorPanelUiState> = _panelUiState.asStateFlow()

    private val _webViewLoadState = MutableStateFlow<WebViewLoadState>(WebViewLoadState.Idle)
    val webViewLoadState: StateFlow<WebViewLoadState> = _webViewLoadState.asStateFlow()

    private val _canWebViewGoBack = MutableStateFlow(false)
    val canWebViewGoBack: StateFlow<Boolean> = _canWebViewGoBack.asStateFlow()

    private val _currentWebUrl = MutableStateFlow<String?>(null)
    val currentWebUrl: StateFlow<String?> = _currentWebUrl.asStateFlow()

    private val _lastSyncMessage = MutableStateFlow<String?>(null)
    val lastSyncMessage: StateFlow<String?> = _lastSyncMessage.asStateFlow()

    private val _isHistorySyncing = MutableStateFlow(false)
    val isHistorySyncing: StateFlow<Boolean> = _isHistorySyncing.asStateFlow()

    val coordinatorState: StateFlow<CoordinatorUiState> = container.predictionCoordinator.state
    val trainingSnapshot: StateFlow<ModelTrainingSnapshot> = container.modelTrainingManager.trainingSnapshot
    val moduleStatuses: StateFlow<Map<String, ModelStatus>> = container.analysisCoordinator.moduleStatuses
    val moduleSummaries = container.analysisCoordinator.getModuleSummaries()

    init {
        viewModelScope.launch {
            websiteSessionState.collect { session ->
                if (session is WebsiteSessionState.Unauthenticated) {
                    // Explicit reliable website logout/expiration must clear manual activation too
                    _userActivationState.value = UserActivationState.INACTIVE
                    _activationPromptDismissed.value = false
                }
            }
        }

        viewModelScope.launch {
            combine(websiteSessionState, userActivationState) { session, activation ->
                (session is WebsiteSessionState.Authenticated) || (activation == UserActivationState.ACTIVE)
            }.collect { canAccessPredictor ->
                if (canAccessPredictor) {
                    container.timerSyncManager.startSynchronization()
                    if (WinGoGameConfig.PHASE_1_AI_EXECUTION_ENABLED) {
                        evaluateCurrentPrediction()
                    }
                } else {
                    container.timerSyncManager.stopSynchronization()
                    _panelUiState.value = _panelUiState.value.copy(
                        isPanelOpen = false,
                        isExpanded = false
                    )
                }
            }
        }
    }

    fun onWebUrlObserved(url: String?) {
        _currentWebUrl.value = url
    }

    fun onWebLoadStateChanged(state: WebViewLoadState) {
        _webViewLoadState.value = state
    }

    fun onCanGoBackChanged(canGoBack: Boolean) {
        _canWebViewGoBack.value = canGoBack
    }

    fun onAppResumed() {
        container.timerSyncManager.onAppResumed()
        container.liveHistoryPoller.resumePolling()
    }

    fun onAppPaused() {
        container.liveHistoryPoller.pausePolling()
    }

    fun activateManualPredictor() {
        // Explicit user-confirmed UI activation ONLY; does NOT mark WebsiteSessionState as Authenticated
        // and does NOT mark ApiAuthenticationState as Ready.
        _userActivationState.value = UserActivationState.ACTIVE
        _activationPromptDismissed.value = true
    }

    fun dismissActivationPrompt() {
        _activationPromptDismissed.value = true
    }

    fun deactivateManualPredictor() {
        _userActivationState.value = UserActivationState.INACTIVE
        _panelUiState.value = _panelUiState.value.copy(
            isPanelOpen = false,
            isExpanded = false
        )
    }

    fun resetSession() {
        _userActivationState.value = UserActivationState.INACTIVE
        _activationPromptDismissed.value = false
        _panelUiState.value = _panelUiState.value.copy(
            isPanelOpen = false,
            isExpanded = false
        )
        container.websiteSessionManager.resetApplicationSession()
    }

    fun openPredictorPanel() {
        val canOpen = (websiteSessionState.value is WebsiteSessionState.Authenticated) ||
            (_userActivationState.value == UserActivationState.ACTIVE)
        if (!canOpen) return
        _panelUiState.value = _panelUiState.value.copy(isPanelOpen = true)
        container.liveHistoryPoller.resumePolling()
        refreshWinGoData()
    }

    fun minimizePredictorPanel() {
        _panelUiState.value = _panelUiState.value.copy(
            isPanelOpen = false,
            isExpanded = false
        )
    }

    fun togglePanelExpanded() {
        _panelUiState.value = _panelUiState.value.copy(
            isExpanded = !_panelUiState.value.isExpanded
        )
    }

    fun selectPanelTab(tab: PredictorPanelTab) {
        _panelUiState.value = _panelUiState.value.copy(activeTab = tab)
    }

    fun updateFloatingButtonOffset(offset: Offset) {
        _panelUiState.value = _panelUiState.value.copy(buttonOffset = offset)
    }

    fun updatePanelOffset(offset: Offset) {
        _panelUiState.value = _panelUiState.value.copy(panelOffset = offset)
    }

    fun selectAnalyticsWindow(window: AnalyticsWindow) {
        _selectedAnalyticsWindow.value = window
    }

    fun refreshWinGoData() {
        viewModelScope.launch {
            _isHistorySyncing.value = true
            try {
                container.timerSyncManager.synchronizeNow()
                val outcome = container.liveHistoryPoller.syncOnce()
                val storedCount = historyDiagnostics.value.totalStoredCount
                _lastSyncMessage.value = when (outcome) {
                    is HistoryFetchOutcome.Success ->
                        "Live history synced: ${outcome.records.size} fetched ($storedCount/500 cached, RTT ${outcome.roundTripMs}ms)."
                    is HistoryFetchOutcome.Empty ->
                        "History endpoint reachable (0 items returned; $storedCount/500 cached)."
                    is HistoryFetchOutcome.Offline ->
                        "Device offline; displaying cached Room records ($storedCount/500)."
                    is HistoryFetchOutcome.HttpFailure ->
                        "History HTTP ${outcome.httpCode}: ${outcome.reason}"
                    is HistoryFetchOutcome.NetworkFailure ->
                        if (outcome.isTimeout) "History timeout: ${outcome.reason}" else "History network error: ${outcome.reason}"
                    is HistoryFetchOutcome.ParseFailure ->
                        "History parse error: ${outcome.reason}"
                }
                if (WinGoGameConfig.PHASE_1_AI_EXECUTION_ENABLED) {
                    evaluateCurrentPrediction()
                }
            } finally {
                _isHistorySyncing.value = false
            }
        }
    }

    fun evaluateCurrentPrediction() {
        if (!WinGoGameConfig.PHASE_1_AI_EXECUTION_ENABLED) return
        viewModelScope.launch {
            val timer = timerState.value
            container.predictionCoordinator.evaluateRound(
                issueNumber = timer.issueNumber,
                freshness = timer.freshness,
                remainingMillis = timer.remainingMillis,
                currentPhase = timer.phase
            )
        }
    }

    override fun onCleared() {
        container.liveHistoryPoller.stopPolling()
        container.timerSyncManager.stopSynchronization()
        super.onCleared()
    }

    fun getClassificationReport(): ClassificationVerificationReport =
        container.classificationService.getVerificationReport()

    fun getCurrentPredictionOrNull(): PredictionResult? = coordinatorState.value.latestResult

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(PredictorViewModel::class.java)) {
                return PredictorViewModel(container) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}
