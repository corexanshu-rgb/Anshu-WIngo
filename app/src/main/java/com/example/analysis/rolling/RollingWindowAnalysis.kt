package com.example.analysis.rolling

import com.example.analysis.AnalysisInput
import com.example.analysis.AnalysisModule
import com.example.analysis.AnalysisOutput
import com.example.analysis.ModelCategory
import com.example.analysis.ModelStatus
import com.example.analysis.ProbabilityMath
import com.example.data.local.entity.ResultEntity
import com.example.training.WalkForwardValidator

/**
 * Module 8: Rolling Window Analysis
 * Analyzes windows 20, 50, 100, 200, and 500 independently for frequencies, entropies,
 * distribution shifts, and historical consistency.
 */
class RollingWindowAnalysis : AnalysisModule {
    override val moduleId = "rolling_window"
    override val moduleName = "Rolling Window Analysis"
    override val moduleVersion = "1.0.0"
    override val category = ModelCategory.STATISTICAL
    override val minimumRequiredSamples = 30

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
                explanation = "Requires at least $minimumRequiredSamples verified draws (${history.size} available)."
            )
        }

        val activeWindows = input.configuredWindows.filter { it <= history.size }
            .ifEmpty { listOf(history.size) }

        val windowDistributions = activeWindows.associateWith { w ->
            smoothedDistribution(history.takeLast(w))
        }
        val windowEntropies = windowDistributions.mapValues { (_, dist) ->
            ProbabilityMath.normalizedEntropy(dist)
        }

        val smallestWindow = windowDistributions[activeWindows.first()]!!
        val largestWindow = windowDistributions[activeWindows.last()]!!
        val tvShift = ProbabilityMath.totalVariationDistance(smallestWindow, largestWindow)
        val consistencyScore = (1.0 - tvShift).coerceIn(0.0, 1.0)

        val combinedProbs = combineConsistentWindows(history)
        val validation = WalkForwardValidator.validatePredictor(
            historyChronological = history,
            minTrainRecords = minimumRequiredSamples,
            maxEvaluationSteps = 60,
            predictor = ::combineConsistentWindows
        )

        val diagnostics = buildMap {
            put("active_windows", activeWindows.joinToString(","))
            put("consistency_score", "%.4f".format(consistencyScore))
            put("tv_shift_short_vs_long", "%.4f".format(tvShift))
            windowEntropies.forEach { (w, ent) ->
                put("entropy_w$w", "%.4f".format(ent))
            }
        }

        return AnalysisOutput(
            moduleId = moduleId,
            moduleName = moduleName,
            moduleVersion = moduleVersion,
            category = category,
            status = ModelStatus.READY,
            numberProbabilities = combinedProbs,
            colorProbabilities = input.classificationService.deriveColorDistribution(combinedProbs),
            sizeProbabilities = input.classificationService.deriveSizeDistribution(combinedProbs),
            reliabilityAssessment = consistencyScore,
            sampleSize = history.size,
            calculatedAtMillis = now,
            explanation = "Evaluated windows [${activeWindows.joinToString(",")}]. Window consistency=${"%.1f".format(consistencyScore * 100)}% (short-vs-long TV shift=${"%.3f".format(tvShift)}).",
            diagnostics = diagnostics,
            validationMetrics = validation?.metrics
        )
    }

    internal fun combineConsistentWindows(history: List<ResultEntity>): Map<Int, Double> {
        val windows = listOf(20, 50, 100, 200, 500).filter { it <= history.size }
            .ifEmpty { listOf(history.size) }
        val combined = DoubleArray(10)
        var weightSum = 0.0
        for (w in windows) {
            val dist = smoothedDistribution(history.takeLast(w))
            val weight = kotlin.math.sqrt(w.toDouble())
            for (d in 0..9) combined[d] += (dist[d] ?: 0.1) * weight
            weightSum += weight
        }
        if (weightSum <= 0.0) return ProbabilityMath.uniformDistribution()
        for (d in 0..9) combined[d] /= weightSum
        return ProbabilityMath.normalizeArray(combined)
    }

    private fun smoothedDistribution(slice: List<ResultEntity>): Map<Int, Double> {
        val counts = IntArray(10)
        slice.forEach { if (it.number in 0..9) counts[it.number]++ }
        val alpha = 1.0
        val denom = slice.size.toDouble() + 10.0 * alpha
        return ProbabilityMath.normalizeArray(DoubleArray(10) { d -> (counts[d] + alpha) / denom })
    }
}
