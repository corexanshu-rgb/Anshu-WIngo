package com.example.training

import com.example.analysis.ModelValidationMetrics
import com.example.analysis.ProbabilityMath
import com.example.data.local.entity.ResultEntity
import com.example.validation.BaselineBenchmark
import com.example.validation.BaselineEvaluator

data class WalkForwardResult(
    val metrics: ModelValidationMetrics,
    val outOfSamplePredictions: List<Pair<Map<Int, Double>, Int>>,
    val baselineBenchmark: BaselineBenchmark
)

/**
 * Chronological walk-forward backtester.
 * For every target step t, trains/estimates using ONLY historical records [0, t) and
 * evaluates on t, then advances chronologically without ever shuffling time series.
 */
object WalkForwardValidator {

    fun validatePredictor(
        historyChronological: List<ResultEntity>,
        minTrainRecords: Int,
        maxEvaluationSteps: Int = 60,
        predictor: (priorHistory: List<ResultEntity>) -> Map<Int, Double>?
    ): WalkForwardResult? {
        if (historyChronological.size <= minTrainRecords) return null

        val startIdx = maxOf(minTrainRecords, historyChronological.size - maxEvaluationSteps)
        val baseline = BaselineEvaluator.evaluateBaselinesChronologically(
            historyChronological = historyChronological,
            startEvaluationIndex = startIdx
        ) ?: return null

        val predictions = mutableListOf<Pair<Map<Int, Double>, Int>>()
        val baseBrierList = mutableListOf<Double>()
        val baseLossList = mutableListOf<Double>()

        val priorCounts = IntArray(10)
        for (i in 0 until startIdx) {
            val n = historyChronological[i].number
            if (n in 0..9) priorCounts[n]++
        }

        for (t in startIdx until historyChronological.size) {
            val target = historyChronological[t].number
            if (target !in 0..9) continue

            val priorSlice = historyChronological.subList(0, t)
            val predicted = predictor(priorSlice)
            if (predicted != null && ProbabilityMath.isValidSimplex(predicted)) {
                predictions.add(predicted to target)

                val smoothedBase = (0..9).associateWith { d -> (priorCounts[d] + 1.0) / (t + 10.0) }
                baseBrierList.add(ProbabilityMath.multiClassBrierScore(smoothedBase, target))
                baseLossList.add(ProbabilityMath.multiClassLogLoss(smoothedBase, target))
            }
            priorCounts[target]++
        }

        if (predictions.isEmpty()) return null

        val baseMetrics = BaselineEvaluator.buildMetrics(predictions, baseline)
        val persistentLift = BaselineEvaluator.checkPersistentImprovement(
            perStepPredictions = predictions,
            baselineBrierPerStep = baseBrierList,
            baselineLossPerStep = baseLossList
        )

        return WalkForwardResult(
            metrics = baseMetrics.copy(
                outperformsBaseline = baseMetrics.outperformsBaseline && persistentLift
            ),
            outOfSamplePredictions = predictions,
            baselineBenchmark = baseline
        )
    }
}
