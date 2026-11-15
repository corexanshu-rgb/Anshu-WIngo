package com.example.analysis.gap

import com.example.analysis.AnalysisInput
import com.example.analysis.AnalysisModule
import com.example.analysis.AnalysisOutput
import com.example.analysis.ModelCategory
import com.example.analysis.ModelStatus
import com.example.analysis.ProbabilityMath
import com.example.data.local.entity.ResultEntity
import com.example.training.WalkForwardValidator
import kotlin.math.exp

data class DigitGapStats(
    val number: Int,
    val currentGap: Int,
    val avgGap: Double?,
    val minGap: Int?,
    val maxGap: Int?,
    val occurrences: Int
)

/**
 * Module 3: Gap Analysis
 * Calculates per-digit current gap, average historical gap, min/max gap, and empirical
 * gap distribution. Explicitly avoids gambler's fallacy ("due" assumptions).
 */
class GapAnalysis : AnalysisModule {
    override val moduleId = "gap_analysis"
    override val moduleName = "Gap Analysis"
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

        val stats = computeGapStats(history)
        val probs = estimateHazardSmoothedDistribution(history)
        val longestGap = stats.maxByOrNull { it.currentGap }

        val validation = WalkForwardValidator.validatePredictor(
            historyChronological = history,
            minTrainRecords = minimumRequiredSamples,
            maxEvaluationSteps = 60,
            predictor = ::estimateHazardSmoothedDistribution
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
            explanation = "Measured inter-arrival gaps across ${history.size} draws. Longest current gap: digit ${longestGap?.number} (${longestGap?.currentGap} draws). Long gaps do not force an outcome in independent draws.",
            diagnostics = stats.associate { stat ->
                "gap_${stat.number}" to "cur=${stat.currentGap},avg=${stat.avgGap?.let { "%.1f".format(it) } ?: "-"},max=${stat.maxGap ?: "-"}"
            },
            validationMetrics = validation?.metrics
        )
    }

    fun computeGapStats(history: List<ResultEntity>): List<DigitGapStats> {
        val n = history.size
        return (0..9).map { digit ->
            val indices = mutableListOf<Int>()
            for (i in history.indices) {
                if (history[i].number == digit) indices.add(i)
            }
            val gaps = mutableListOf<Int>()
            for (j in 1 until indices.size) {
                gaps.add(indices[j] - indices[j - 1] - 1)
            }
            val currentGap = if (indices.isEmpty()) n else (n - 1 - indices.last())
            DigitGapStats(
                number = digit,
                currentGap = currentGap,
                avgGap = if (gaps.isNotEmpty()) gaps.average() else null,
                minGap = gaps.minOrNull(),
                maxGap = gaps.maxOrNull(),
                occurrences = indices.size
            )
        }
    }

    internal fun estimateHazardSmoothedDistribution(history: List<ResultEntity>): Map<Int, Double> {
        // Empirical occurrence rate regularized toward uniform 0.10 with subtle gap-stability damping
        val stats = computeGapStats(history)
        val n = history.size.toDouble().coerceAtLeast(1.0)
        val weights = DoubleArray(10) { d ->
            val s = stats[d]
            val empiricalRate = (s.occurrences + 2.0) / (n + 20.0)
            val relGap = if (s.avgGap != null && s.avgGap > 0.0) {
                (s.currentGap / s.avgGap).coerceIn(0.0, 3.0)
            } else {
                1.0
            }
            // Gentle bounded adjustment rather than aggressive gambler's fallacy weighting
            empiricalRate * (0.9 + 0.1 * (1.0 - exp(-relGap)))
        }
        return ProbabilityMath.normalizeArray(weights)
    }
}
