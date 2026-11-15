package com.example.analysis.transition

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
 * Module 7: 10x10 Transition Matrix Model
 * Builds and maintains an independent 10x10 transition matrix (rows = previous number,
 * columns = next number), tracking row-normalized probabilities, transition support,
 * sparse-data shrinkage, and matrix Frobenius drift across windows.
 */
class TransitionMatrixModel : AnalysisModule {
    override val moduleId = "transition_matrix"
    override val moduleName = "Transition Matrix"
    override val moduleVersion = "1.0.0"
    override val category = ModelCategory.STATISTICAL
    override val minimumRequiredSamples = 50

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

        val fullMatrix = buildTransitionMatrix(history)
        val recentMatrix = buildTransitionMatrix(history.takeLast(minOf(history.size, 60)))
        val matrixFrobeniusShift = frobeniusDistance(fullMatrix.probabilities, recentMatrix.probabilities)

        val currentDigit = history.last().number.coerceIn(0, 9)
        val rowProbs = ProbabilityMath.normalizeArray(fullMatrix.probabilities[currentDigit])
        val rowSupport = fullMatrix.rowTotals[currentDigit]

        val validation = WalkForwardValidator.validatePredictor(
            historyChronological = history,
            minTrainRecords = minimumRequiredSamples,
            maxEvaluationSteps = 60,
            predictor = { prior ->
                val mat = buildTransitionMatrix(prior)
                val prev = prior.last().number
                if (prev in 0..9) ProbabilityMath.normalizeArray(mat.probabilities[prev]) else null
            }
        )

        return AnalysisOutput(
            moduleId = moduleId,
            moduleName = moduleName,
            moduleVersion = moduleVersion,
            category = category,
            status = ModelStatus.READY,
            numberProbabilities = rowProbs,
            colorProbabilities = input.classificationService.deriveColorDistribution(rowProbs),
            sizeProbabilities = input.classificationService.deriveSizeDistribution(rowProbs),
            reliabilityAssessment = validation?.metrics?.let { (1.0 - it.brierScore).coerceIn(0.0, 1.0) },
            sampleSize = history.size,
            calculatedAtMillis = now,
            explanation = "10x10 row-normalized transition matrix (active row=$currentDigit, support=$rowSupport transitions, recent Frobenius drift=${"%.3f".format(matrixFrobeniusShift)}).",
            diagnostics = mapOf(
                "active_row" to currentDigit.toString(),
                "active_row_support" to rowSupport.toString(),
                "matrix_frobenius_drift" to "%.4f".format(matrixFrobeniusShift),
                "total_transitions" to fullMatrix.rowTotals.sum().toString()
            ),
            validationMetrics = validation?.metrics
        )
    }

    data class TransitionMatrixData(
        val counts: Array<IntArray>,
        val probabilities: Array<DoubleArray>,
        val rowTotals: IntArray
    )

    fun buildTransitionMatrix(history: List<ResultEntity>): TransitionMatrixData {
        val counts = Array(10) { IntArray(10) }
        val rowTotals = IntArray(10)
        for (i in 1 until history.size) {
            val r = history[i - 1].number
            val c = history[i].number
            if (r in 0..9 && c in 0..9) {
                counts[r][c]++
                rowTotals[r]++
            }
        }
        // Shrink sparse rows toward 0.10 uniform prior with alpha = 1.5
        val alpha = 1.5
        val probs = Array(10) { r ->
            val denom = rowTotals[r].toDouble() + 10.0 * alpha
            DoubleArray(10) { c -> (counts[r][c] + alpha) / denom }
        }
        return TransitionMatrixData(counts, probs, rowTotals)
    }

    private fun frobeniusDistance(a: Array<DoubleArray>, b: Array<DoubleArray>): Double {
        var sq = 0.0
        for (r in 0..9) {
            for (c in 0..9) {
                val d = a[r][c] - b[r][c]
                sq += d * d
            }
        }
        return sqrt(sq)
    }
}
