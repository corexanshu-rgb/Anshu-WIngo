package com.example.analysis.frequency

import com.example.analysis.AnalysisInput
import com.example.analysis.AnalysisModule
import com.example.analysis.AnalysisOutput
import com.example.analysis.ModelCategory
import com.example.analysis.ModelStatus
import com.example.analysis.ProbabilityMath
import com.example.data.local.entity.ResultEntity
import com.example.training.WalkForwardValidator
import kotlin.math.abs

/**
 * Module 1: Frequency Analysis
 * Computes digit frequencies (0-9) over windows 20, 50, 100, and 500, relative frequencies,
 * window-over-window shifts, and Laplace-smoothed probability estimates.
 */
class FrequencyAnalysis : AnalysisModule {
    override val moduleId = "frequency_analysis"
    override val moduleName = "Frequency Analysis"
    override val moduleVersion = "1.0.0"
    override val category = ModelCategory.STATISTICAL
    override val minimumRequiredSamples = 20

    override suspend fun analyze(input: AnalysisInput): AnalysisOutput {
        val history = input.historyChronological
        val now = System.currentTimeMillis()
        if (history.size < minimumRequiredSamples) {
            return AnalysisOutput(
                moduleId = moduleId,
                moduleName = moduleName,
                moduleVersion = moduleVersion,
                category = category,
                status = ModelStatus.INSUFFICIENT_DATA,
                numberProbabilities = null,
                colorProbabilities = null,
                sizeProbabilities = null,
                reliabilityAssessment = null,
                sampleSize = history.size,
                calculatedAtMillis = now,
                explanation = "Requires at least $minimumRequiredSamples verified WinGo_1M draws (${history.size} available)."
            )
        }

        val freq20 = windowRelativeFrequencies(history, 20)
        val freq50 = windowRelativeFrequencies(history, 50)
        val freq100 = windowRelativeFrequencies(history, 100)
        val freq500 = windowRelativeFrequencies(history, 500)

        val probs = estimateDistribution(history)
        val validation = WalkForwardValidator.validatePredictor(
            historyChronological = history,
            minTrainRecords = minimumRequiredSamples,
            maxEvaluationSteps = 60,
            predictor = ::estimateDistribution
        )

        val delta20vs100 = (0..9).maxOf { d -> abs((freq20[d] ?: 0.0) - (freq100[d] ?: 0.0)) }
        val colorProbs = input.classificationService.deriveColorDistribution(probs)
        val sizeProbs = input.classificationService.deriveSizeDistribution(probs)

        val metrics = validation?.metrics
        val status = if (metrics != null && metrics.evaluatedSamples >= 20 && !metrics.outperformsBaseline) {
            ModelStatus.READY // Descriptive module stays READY with honest baseline comparison metrics
        } else {
            ModelStatus.READY
        }

        return AnalysisOutput(
            moduleId = moduleId,
            moduleName = moduleName,
            moduleVersion = moduleVersion,
            category = category,
            status = status,
            numberProbabilities = probs,
            colorProbabilities = colorProbs,
            sizeProbabilities = sizeProbs,
            reliabilityAssessment = metrics?.let { (1.0 - it.brierScore).coerceIn(0.0, 1.0) },
            sampleSize = history.size,
            calculatedAtMillis = now,
            explanation = "Computed Laplace-smoothed frequencies across last 20/50/100/500 draws (max 20-vs-100 shift: ${"%.3f".format(delta20vs100)}). Past frequency alone does not cause future independent draws.",
            diagnostics = mapOf(
                "w20_top" to (freq20.maxByOrNull { it.value }?.key?.toString() ?: "-"),
                "w50_top" to (freq50.maxByOrNull { it.value }?.key?.toString() ?: "-"),
                "w100_top" to (freq100.maxByOrNull { it.value }?.key?.toString() ?: "-"),
                "w500_top" to (freq500.maxByOrNull { it.value }?.key?.toString() ?: "-"),
                "max_window_shift" to "%.4f".format(delta20vs100)
            ),
            validationMetrics = metrics
        )
    }

    internal fun estimateDistribution(history: List<ResultEntity>): Map<Int, Double> {
        val counts = IntArray(10)
        val sample = history.takeLast(500)
        for (row in sample) {
            if (row.number in 0..9) counts[row.number]++
        }
        val alpha = 1.0
        val denom = sample.size.toDouble() + 10.0 * alpha
        return ProbabilityMath.normalizeArray(DoubleArray(10) { d -> (counts[d] + alpha) / denom })
    }

    internal fun windowRelativeFrequencies(history: List<ResultEntity>, windowSize: Int): Map<Int, Double> {
        val slice = history.takeLast(windowSize)
        if (slice.isEmpty()) return ProbabilityMath.uniformDistribution()
        val counts = IntArray(10)
        slice.forEach { if (it.number in 0..9) counts[it.number]++ }
        return (0..9).associateWith { d -> counts[d].toDouble() / slice.size.toDouble() }
    }
}
