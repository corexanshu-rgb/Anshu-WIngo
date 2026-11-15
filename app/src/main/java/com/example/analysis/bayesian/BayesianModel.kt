package com.example.analysis.bayesian

import com.example.analysis.AnalysisInput
import com.example.analysis.AnalysisModule
import com.example.analysis.AnalysisOutput
import com.example.analysis.ModelCategory
import com.example.analysis.ModelStatus
import com.example.analysis.ProbabilityMath
import com.example.data.local.entity.ResultEntity
import com.example.training.WalkForwardValidator
import kotlin.math.sqrt

/**
 * Module 4: Bayesian Multinomial-Dirichlet Model
 * Explicitly separates symmetric Dirichlet prior (alpha_0) from historical observations,
 * calculating posterior predictive probabilities P(X=k | D) = (alpha_0 + c_k) / (10*alpha_0 + N).
 */
class BayesianModel(
    private val priorAlphaPerClass: Double = 5.0
) : AnalysisModule {
    override val moduleId = "bayesian_model"
    override val moduleName = "Bayesian Model"
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

        val posterior = estimatePosteriorPredictive(history)
        val alphaTotal = 10.0 * priorAlphaPerClass + history.size
        // Posterior predictive standard deviation for the top class under Dirichlet posterior
        val topEntry = posterior.maxByOrNull { it.value }!!
        val pTop = topEntry.value
        val posteriorStd = sqrt((pTop * (1.0 - pTop)) / (alphaTotal + 1.0))

        val validation = WalkForwardValidator.validatePredictor(
            historyChronological = history,
            minTrainRecords = minimumRequiredSamples,
            maxEvaluationSteps = 60,
            predictor = ::estimatePosteriorPredictive
        )

        val priorWeightRatio = (10.0 * priorAlphaPerClass) / alphaTotal

        return AnalysisOutput(
            moduleId = moduleId,
            moduleName = moduleName,
            moduleVersion = moduleVersion,
            category = category,
            status = ModelStatus.READY,
            numberProbabilities = posterior,
            colorProbabilities = input.classificationService.deriveColorDistribution(posterior),
            sizeProbabilities = input.classificationService.deriveSizeDistribution(posterior),
            reliabilityAssessment = validation?.metrics?.let { (1.0 - it.brierScore).coerceIn(0.0, 1.0) },
            sampleSize = history.size,
            calculatedAtMillis = now,
            explanation = "Dirichlet-Multinomial posterior with symmetric prior alpha=$priorAlphaPerClass/class (prior weight ${"%.1f".format(priorWeightRatio * 100)}%, observed evidence ${"%.1f".format((1 - priorWeightRatio) * 100)}%). Top posterior: ${topEntry.key} (${"%.1f".format(pTop * 100)}% +/- ${"%.1f".format(posteriorStd * 100)}%).",
            diagnostics = mapOf(
                "prior_alpha_per_class" to priorAlphaPerClass.toString(),
                "prior_weight_pct" to "%.1f%%".format(priorWeightRatio * 100.0),
                "posterior_top_std" to "%.4f".format(posteriorStd)
            ),
            validationMetrics = validation?.metrics
        )
    }

    internal fun estimatePosteriorPredictive(history: List<ResultEntity>): Map<Int, Double> {
        val counts = IntArray(10)
        for (row in history) {
            if (row.number in 0..9) counts[row.number]++
        }
        val denom = 10.0 * priorAlphaPerClass + history.size.toDouble()
        return ProbabilityMath.normalizeArray(
            DoubleArray(10) { d -> (priorAlphaPerClass + counts[d]) / denom }
        )
    }
}
