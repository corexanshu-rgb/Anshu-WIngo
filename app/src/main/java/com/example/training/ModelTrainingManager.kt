package com.example.training

import com.example.analysis.AnalysisCoordinator
import com.example.analysis.AnalysisOutput
import com.example.analysis.ModelStatus
import com.example.classification.ResultClassificationService
import com.example.data.local.dao.AnalysisDao
import com.example.data.local.entity.AnalysisEntity
import com.example.data.local.entity.PredictionEntity
import com.example.data.local.entity.ResultEntity
import com.example.domain.model.DataFreshnessStatus
import com.example.validation.BaselineBenchmark
import com.example.validation.BaselineEvaluator
import com.example.validation.CalibrationReport
import com.example.validation.DriftDetector
import com.example.validation.DriftReport
import com.example.validation.ProbabilityCalibrator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

data class ModelTrainingSnapshot(
    val historySampleSize: Int = 0,
    val backtestingSampleSize: Int = 0,
    val lastTrainedAtMillis: Long? = null,
    val baselineBenchmark: BaselineBenchmark? = null,
    val calibrationReport: CalibrationReport? = null,
    val driftReport: DriftReport? = null,
    val moduleOutputs: List<AnalysisOutput> = emptyList(),
    val isTraining: Boolean = false
)

/**
 * Manages chronological local training, walk-forward validation, drift checks,
 * and safe persistence of module metadata into Room (analysis_runs).
 */
class ModelTrainingManager(
    private val analysisCoordinator: AnalysisCoordinator,
    private val analysisDao: AnalysisDao,
    private val classificationService: ResultClassificationService,
    private val driftDetector: DriftDetector,
    private val calibrator: ProbabilityCalibrator
) {
    private val _trainingSnapshot = MutableStateFlow(ModelTrainingSnapshot())
    val trainingSnapshot: StateFlow<ModelTrainingSnapshot> = _trainingSnapshot.asStateFlow()

    @Volatile
    private var lastTrainedHistoryKey: String? = null

    fun observePersistedRuns(): Flow<List<AnalysisEntity>> = analysisDao.observeAll()

    suspend fun trainAndValidateIfNeeded(
        historyNewestFirst: List<ResultEntity>,
        recentPredictions: List<PredictionEntity>,
        targetIssueNumber: String?,
        dataFreshness: DataFreshnessStatus
    ): ModelTrainingSnapshot = withContext(Dispatchers.Default) {
        val chronological = historyNewestFirst
            .filter { it.number in 0..9 && it.issueNumber.isNotBlank() }
            .sortedBy { it.issueNumber }

        val historyKey = "${chronological.size}:${chronological.lastOrNull()?.issueNumber ?: "none"}"
        if (historyKey == lastTrainedHistoryKey && _trainingSnapshot.value.moduleOutputs.isNotEmpty()) {
            return@withContext _trainingSnapshot.value
        }

        _trainingSnapshot.value = _trainingSnapshot.value.copy(isTraining = true)

        val baseline = BaselineEvaluator.evaluateBaselinesChronologically(
            historyChronological = chronological,
            startEvaluationIndex = 20
        )
        val drift = driftDetector.evaluateDrift(chronological, recentPredictions)

        val outputs = analysisCoordinator.runAllModules(
            historyNewestFirst = chronological,
            targetIssueNumber = targetIssueNumber,
            classificationService = classificationService,
            dataFreshness = dataFreshness
        )

        val verifiedOutOfSample = recentPredictions
            .filter { it.actualNumber in 0..9 && it.numberProbabilities.size == 10 }
            .map { it.numberProbabilities to it.actualNumber!! }
        val calibrationReport = calibrator.fitTemperatureAndEvaluate(verifiedOutOfSample)

        val now = System.currentTimeMillis()
        val entities = outputs.map { out ->
            val m = out.validationMetrics
            val finalStatus = if (drift.driftDetected && out.status == ModelStatus.READY &&
                m != null && !m.outperformsBaseline
            ) {
                ModelStatus.UNRELIABLE
            } else {
                out.status
            }
            AnalysisEntity(
                moduleId = out.moduleId,
                moduleName = out.moduleName,
                moduleVersion = out.moduleVersion,
                status = finalStatus.label,
                sampleSize = out.sampleSize,
                validationSampleSize = m?.evaluatedSamples ?: 0,
                lastExecutedAt = out.calculatedAtMillis,
                logLoss = m?.logLoss?.takeIf { !it.isNaN() },
                brierScore = m?.brierScore?.takeIf { !it.isNaN() },
                accuracy = m?.numberAccuracy?.takeIf { !it.isNaN() },
                baselineBrierScore = m?.baselineBrierScore?.takeIf { !it.isNaN() },
                assignedWeight = 0.0,
                summaryNote = out.explanation
            )
        }
        if (entities.isNotEmpty()) {
            analysisDao.upsertAll(entities)
        }

        val maxBacktestSamples = outputs.mapNotNull { it.validationMetrics?.evaluatedSamples }.maxOrNull() ?: 0
        val snapshot = ModelTrainingSnapshot(
            historySampleSize = chronological.size,
            backtestingSampleSize = maxBacktestSamples,
            lastTrainedAtMillis = now,
            baselineBenchmark = baseline,
            calibrationReport = calibrationReport,
            driftReport = drift,
            moduleOutputs = outputs,
            isTraining = false
        )
        lastTrainedHistoryKey = historyKey
        _trainingSnapshot.value = snapshot
        snapshot
    }

    suspend fun updateAssignedWeights(weightsByModule: Map<String, Double>) {
        val current = analysisDao.getAllSnapshot()
        if (current.isEmpty()) return
        val updated = current.map { entity ->
            entity.copy(assignedWeight = weightsByModule[entity.moduleId] ?: 0.0)
        }
        analysisDao.upsertAll(updated)
    }
}
