package com.example.analysis.markov

import com.example.analysis.AnalysisInput
import com.example.analysis.AnalysisModule
import com.example.analysis.AnalysisOutput
import com.example.analysis.ModelCategory
import com.example.analysis.ModelStatus
import com.example.analysis.ProbabilityMath
import com.example.data.local.entity.ResultEntity
import com.example.training.WalkForwardValidator

/**
 * Module 6: First-Order Markov Chain Model
 * Estimates conditional transition probabilities P(X_t = j | X_{t-1} = i) using smoothed
 * empirical transitions built strictly prior to target evaluation.
 */
class MarkovChainModel(
    private val dirichletSmoothing: Double = 1.0
) : AnalysisModule {
    override val moduleId = "markov_chain"
    override val moduleName = "Markov Chain"
    override val moduleVersion = "1.0.0"
    override val category = ModelCategory.STATISTICAL
    override val minimumRequiredSamples = 40

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

        val lastState = history.last().number
        val (probs, rowSupport) = estimateNextStateWithSupport(history)

        val validation = WalkForwardValidator.validatePredictor(
            historyChronological = history,
            minTrainRecords = minimumRequiredSamples,
            maxEvaluationSteps = 60,
            predictor = { prior -> estimateNextStateWithSupport(prior).first }
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
            explanation = "First-order Markov transitions conditioned on state $lastState ($rowSupport observed transitions, Laplace alpha=$dirichletSmoothing). Historical transitions are not guaranteed to persist in independent draws.",
            diagnostics = mapOf(
                "conditioning_state" to lastState.toString(),
                "row_transition_support" to rowSupport.toString()
            ),
            validationMetrics = validation?.metrics
        )
    }

    internal fun estimateNextStateWithSupport(history: List<ResultEntity>): Pair<Map<Int, Double>, Int> {
        if (history.size < 2) return ProbabilityMath.uniformDistribution() to 0
        val currentState = history.last().number
        if (currentState !in 0..9) return ProbabilityMath.uniformDistribution() to 0

        val nextCounts = IntArray(10)
        var rowTotal = 0
        for (i in 1 until history.size) {
            val prev = history[i - 1].number
            val next = history[i].number
            if (prev == currentState && next in 0..9) {
                nextCounts[next]++
                rowTotal++
            }
        }

        // Fallback smoothly toward marginal frequencies when row support is sparse
        val marginal = IntArray(10)
        history.forEach { if (it.number in 0..9) marginal[it.number]++ }
        val nTotal = history.size.toDouble().coerceAtLeast(1.0)

        val probs = DoubleArray(10) { j ->
            val markovEst = (nextCounts[j] + dirichletSmoothing) / (rowTotal + 10.0 * dirichletSmoothing)
            val marginalEst = (marginal[j] + 1.0) / (nTotal + 10.0)
            val blend = (rowTotal / (rowTotal + 15.0)).coerceIn(0.0, 1.0)
            blend * markovEst + (1.0 - blend) * marginalEst
        }
        return ProbabilityMath.normalizeArray(probs) to rowTotal
    }
}
