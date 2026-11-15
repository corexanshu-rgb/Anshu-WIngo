package com.example.analysis.recency

import com.example.analysis.AnalysisInput
import com.example.analysis.AnalysisModule
import com.example.analysis.AnalysisOutput
import com.example.analysis.ModelCategory
import com.example.analysis.ModelStatus
import com.example.analysis.ProbabilityMath
import com.example.data.local.entity.ResultEntity
import com.example.training.WalkForwardValidator
import kotlin.math.exp

/**
 * Module 2: Recency Analysis
 * Analyzes recently occurring numbers, last occurrence index, exponential recency weighting,
 * and short-term vs long-term frequency differences without claiming recent draws force next draws.
 */
class RecencyAnalysis : AnalysisModule {
    override val moduleId = "recency_analysis"
    override val moduleName = "Recency Analysis"
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
                explanation = "Requires at least $minimumRequiredSamples verified draws (${history.size} available)."
            )
        }

        val probs = estimateExponentialRecency(history)
        val lastPositions = computeLastOccurrenceDistance(history)
        val shortTerm = history.takeLast(15)
        val shortFreq = IntArray(10).apply { shortTerm.forEach { if (it.number in 0..9) this[it.number]++ } }
        val longFreq = IntArray(10).apply { history.forEach { if (it.number in 0..9) this[it.number]++ } }

        val maxDeltaDigit = (0..9).maxByOrNull { d ->
            (shortFreq[d].toDouble() / shortTerm.size) - (longFreq[d].toDouble() / history.size)
        } ?: 0

        val validation = WalkForwardValidator.validatePredictor(
            historyChronological = history,
            minTrainRecords = minimumRequiredSamples,
            maxEvaluationSteps = 60,
            predictor = ::estimateExponentialRecency
        )

        return AnalysisOutput(
            moduleId = moduleId,
            moduleName = moduleName,
            moduleVersion = moduleVersion,
            category = category,
            status = ModelStatus.READY,
            numberProbabilities = probs,
            colorProbabilities = input.classificationService.deriveColorDistribution(probs),
            sizeProbabilities = input.classificationService.deriveSizeDistribution(probs),
            reliabilityAssessment = validation?.metrics?.let { (1.0 - it.brierScore).coerceIn(0.0, 1.0) },
            sampleSize = history.size,
            calculatedAtMillis = now,
            explanation = "Exponentially decayed recency distribution (half-life ~14 draws); short-term surge digit: $maxDeltaDigit. Recency describes recent history and does not guarantee repetition.",
            diagnostics = mapOf(
                "latest_digit" to history.last().number.toString(),
                "short_vs_long_surge_digit" to maxDeltaDigit.toString(),
                "unseen_recently_max_pos" to (lastPositions.maxByOrNull { it.value }?.key?.toString() ?: "-")
            ),
            validationMetrics = validation?.metrics
        )
    }

    internal fun estimateExponentialRecency(history: List<ResultEntity>): Map<Int, Double> {
        val lambda = 0.05
        val scores = DoubleArray(10) { 0.5 } // Prior smoothing baseline
        val n = history.size
        for (i in history.indices) {
            val age = (n - 1 - i).toDouble()
            val num = history[i].number
            if (num in 0..9) {
                scores[num] += exp(-lambda * age)
            }
        }
        return ProbabilityMath.normalizeArray(scores)
    }

    internal fun computeLastOccurrenceDistance(history: List<ResultEntity>): Map<Int, Int> {
        val result = mutableMapOf<Int, Int>()
        val n = history.size
        for (d in 0..9) {
            var dist = n
            for (i in (n - 1) downTo 0) {
                if (history[i].number == d) {
                    dist = n - 1 - i
                    break
                }
            }
            result[d] = dist
        }
        return result
    }
}
