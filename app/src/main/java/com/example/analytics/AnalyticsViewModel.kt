package com.example.analytics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.local.entity.AnalysisEntity
import com.example.di.AppContainer
import com.example.training.ModelTrainingSnapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn

@OptIn(ExperimentalCoroutinesApi::class)
class AnalyticsViewModel(
    private val analyticsRepository: AnalyticsRepository,
    trainingSnapshotFlow: StateFlow<ModelTrainingSnapshot>
) : ViewModel() {

    private val _selectedWindow = MutableStateFlow(AnalyticsWindow.LAST_50)
    val selectedWindow: StateFlow<AnalyticsWindow> = _selectedWindow.asStateFlow()

    val analyticsSnapshot: StateFlow<AnalyticsSnapshot> = _selectedWindow
        .flatMapLatest { window -> analyticsRepository.observeAnalytics(window) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = AnalyticsSnapshot()
        )

    val persistedAnalysisRuns: StateFlow<List<AnalysisEntity>> = analyticsRepository
        .observeAnalysisRuns()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    val trainingSnapshot: StateFlow<ModelTrainingSnapshot> = trainingSnapshotFlow

    fun selectWindow(window: AnalyticsWindow) {
        _selectedWindow.value = window
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(AnalyticsViewModel::class.java)) {
                return AnalyticsViewModel(
                    analyticsRepository = container.analyticsRepository,
                    trainingSnapshotFlow = container.modelTrainingManager.trainingSnapshot
                ) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}
