package com.example.prediction

import com.example.data.repository.HistoryRepository
import com.example.data.repository.HistorySyncResult
import com.example.domain.model.DataFreshnessStatus
import com.example.domain.model.PredictionStatus
import com.example.training.ModelTrainingManager
import com.example.training.ModelTrainingSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class CoordinatorUiState(
    val latestResult: PredictionResult? = null,
    val trainingSnapshot: ModelTrainingSnapshot = ModelTrainingSnapshot(),
    val lastHistorySync: HistorySyncResult? = null,
    val lastLockNote: String? = null,
    val isEvaluating: Boolean = false
)

class PredictionCoordinator(
    private val historyRepository: HistoryRepository,
    private val predictionRepository: PredictionRepository,
    private val trainingManager: ModelTrainingManager,
    private val ensembleEngine: EnsembleEngine,
    private val lockManager: PredictionLockManager
) {
    private val _state = MutableStateFlow(CoordinatorUiState())
    val state: StateFlow<CoordinatorUiState> = _state.asStateFlow()

    suspend fun evaluateRound(
        issueNumber: String?,
        freshness: DataFreshnessStatus,
        remainingMillis: Long?,
        currentPhase: String?
    ): PredictionResult {
        _state.value = _state.value.copy(isEvaluating = true)

        val historyNewestFirst = historyRepository.getLatestResultsSnapshot()
        val recentPredictions = predictionRepository.getLatestPredictionsSnapshot()

        val trainingSnapshot = trainingManager.trainAndValidateIfNeeded(
            historyNewestFirst = historyNewestFirst,
            recentPredictions = recentPredictions,
            targetIssueNumber = issueNumber,
            dataFreshness = freshness
        )

        val rawResult = ensembleEngine.combine(
            issueNumber = issueNumber,
            freshness = freshness,
            dataCutoffIssue = historyNewestFirst.firstOrNull()?.issueNumber,
            trainingSnapshot = trainingSnapshot
        )

        var finalResult = rawResult
        var lockNote: String? = null

        if (rawResult is PredictionResult.Ready) {
            trainingManager.updateAssignedWeights(
                rawResult.contributions.associate { it.moduleId to it.assignedWeight }
            )
            when (val lock = lockManager.lockBeforeOutcome(rawResult, freshness, remainingMillis, currentPhase)) {
                is LockDecision.Locked -> {
                    finalResult = rawResult.copy(
                        lockedAtMillis = lock.entity.lockedAt,
                        predictionStatus = PredictionStatus.LOCKED
                    )
                    lockNote = "Locked before outcome for issue ${rawResult.issueNumber}"
                }
                is LockDecision.AlreadyLocked -> {
                    finalResult = rawResult.copy(
                        lockedAtMillis = lock.existing.lockedAt,
                        predictionStatus = lock.existing.predictionStatus
                    )
                    lockNote = "Prediction already locked for issue ${rawResult.issueNumber}"
                }
                is LockDecision.Rejected -> {
                    lockNote = lock.reason
                }
            }
        }

        _state.value = _state.value.copy(
            latestResult = finalResult,
            trainingSnapshot = trainingSnapshot,
            lastLockNote = lockNote,
            isEvaluating = false
        )
        return finalResult
    }

    suspend fun syncHistoryAndVerify(): HistorySyncResult {
        val sync = historyRepository.syncLatest500()
        _state.value = _state.value.copy(lastHistorySync = sync)
        return sync
    }
}
