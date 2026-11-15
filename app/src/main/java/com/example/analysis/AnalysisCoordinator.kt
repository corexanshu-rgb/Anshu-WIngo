package com.example.analysis

import com.example.analysis.bayesian.BayesianModel
import com.example.analysis.entropy.EntropyAnalysis
import com.example.analysis.frequency.FrequencyAnalysis
import com.example.analysis.gap.GapAnalysis
import com.example.analysis.knn.KNNModel
import com.example.analysis.logistic.LogisticRegressionModel
import com.example.analysis.markov.MarkovChainModel
import com.example.analysis.randomforest.RandomForestModel
import com.example.analysis.recency.RecencyAnalysis
import com.example.analysis.rolling.RollingWindowAnalysis
import com.example.analysis.svm.SVMModel
import com.example.analysis.transition.TransitionMatrixModel
import com.example.classification.ResultClassificationService
import com.example.data.local.entity.ResultEntity
import com.example.domain.model.DataFreshnessStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

data class ModuleDescriptorSummary(
    val moduleId: String,
    val moduleName: String,
    val moduleVersion: String,
    val category: ModelCategory,
    val minimumRequiredSamples: Int,
    val currentStatus: ModelStatus
)

/**
 * Orchestrates all 12 real statistical & ML analysis modules on Dispatchers.Default.
 * Caches unchanged history snapshots to avoid redundant CPU/battery consumption.
 */
class AnalysisCoordinator(
    val modules: List<AnalysisModule> = createDefaultModules()
) {
    private val _moduleStatuses = MutableStateFlow<Map<String, ModelStatus>>(
        modules.associate { it.moduleId to ModelStatus.NOT_INITIALIZED }
    )
    val moduleStatuses: StateFlow<Map<String, ModelStatus>> = _moduleStatuses.asStateFlow()

    @Volatile
    private var cachedHistorySignature: String? = null

    @Volatile
    private var cachedOutputs: List<AnalysisOutput> = emptyList()

    fun getModuleSummaries(): List<ModuleDescriptorSummary> {
        val statuses = _moduleStatuses.value
        return modules.map { m ->
            ModuleDescriptorSummary(
                moduleId = m.moduleId,
                moduleName = m.moduleName,
                moduleVersion = m.moduleVersion,
                category = m.category,
                minimumRequiredSamples = m.minimumRequiredSamples,
                currentStatus = statuses[m.moduleId] ?: ModelStatus.NOT_INITIALIZED
            )
        }
    }

    suspend fun runAllModules(
        historyNewestFirst: List<ResultEntity>,
        targetIssueNumber: String?,
        classificationService: ResultClassificationService,
        dataFreshness: DataFreshnessStatus
    ): List<AnalysisOutput> = withContext(Dispatchers.Default) {
        val chronological = historyNewestFirst
            .filter { it.number in 0..9 && it.issueNumber.isNotBlank() }
            .sortedBy { it.issueNumber }

        classificationService.auditAndVerifyFromResults(chronological)

        val signature = buildString {
            append(chronological.size)
            append(':')
            append(chronological.lastOrNull()?.issueNumber ?: "empty")
            append(':')
            append(classificationService.getVerificationReport().colorVerified)
            append(':')
            append(classificationService.getVerificationReport().bigSmallVerified)
        }

        if (signature == cachedHistorySignature && cachedOutputs.isNotEmpty()) {
            return@withContext cachedOutputs
        }

        val input = AnalysisInput(
            historyChronological = chronological,
            targetIssueNumber = targetIssueNumber,
            classificationService = classificationService,
            dataFreshness = dataFreshness
        )

        val results = ArrayList<AnalysisOutput>(modules.size)
        val updatedStatuses = _moduleStatuses.value.toMutableMap()

        for (module in modules) {
            updatedStatuses[module.moduleId] = if (module.category == ModelCategory.MACHINE_LEARNING) {
                ModelStatus.TRAINING
            } else {
                ModelStatus.VALIDATING
            }
            _moduleStatuses.value = updatedStatuses.toMap()

            val output = try {
                module.analyze(input)
            } catch (t: Throwable) {
                AnalysisOutput(
                    moduleId = module.moduleId,
                    moduleName = module.moduleName,
                    moduleVersion = module.moduleVersion,
                    category = module.category,
                    status = ModelStatus.ERROR,
                    numberProbabilities = null,
                    colorProbabilities = null,
                    sizeProbabilities = null,
                    reliabilityAssessment = null,
                    sampleSize = chronological.size,
                    calculatedAtMillis = System.currentTimeMillis(),
                    explanation = "Module error: ${t.message ?: t::class.simpleName}"
                )
            }

            updatedStatuses[module.moduleId] = output.status
            _moduleStatuses.value = updatedStatuses.toMap()
            results.add(output)
        }

        cachedHistorySignature = signature
        cachedOutputs = results
        results
    }

    companion object {
        fun createDefaultModules(): List<AnalysisModule> = listOf(
            // 8 Statistical Modules
            FrequencyAnalysis(),
            RecencyAnalysis(),
            GapAnalysis(),
            BayesianModel(),
            EntropyAnalysis(),
            MarkovChainModel(),
            TransitionMatrixModel(),
            RollingWindowAnalysis(),
            // 4 Machine-Learning Modules
            LogisticRegressionModel(),
            RandomForestModel(),
            KNNModel(),
            SVMModel()
        )
    }
}
