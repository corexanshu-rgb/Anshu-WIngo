package com.example.validation

import com.example.analysis.ModelValidationMetrics
import com.example.analysis.ProbabilityMath
import com.example.data.local.entity.ResultEntity

data class BaselineBenchmark(
    val sampleCount: Int,
    val uniformBrierScore: Double,
    val uniformLogLoss: Double,
    val uniformAccuracy: Double,
    val historicalFreqBrierScore: Double,
    val historicalFreqLogLoss: Double,
    val historicalFreqAccuracy: Double,
    val smoothedFreqBrierScore: Double,
    val smoothedFreqLogLoss: Double,
    val smoothedFreqAccuracy: Double
) {
    val bestBaselineBrier: Double
        get() = minOf(uniformBrierScore, smoothedFreqBrierScore)

    val bestBaselineLogLoss: Double
        get() = minOf(uniformLogLoss, smoothedFreqLogLoss)
}

/**
 * Evaluates simple statistical baselines chronologically (walk-forward) and determines
 * whether any candidate model demonstrates meaningful, persistent out-of-sample lift.
 */
object BaselineEvaluator {

    fun evaluateBaselinesChronologically(
        historyChronological: List<ResultEntity>,
        startEvaluationIndex: Int = 20
    ): BaselineBenchmark? {
        if (historyChronological.size <= startEvaluationIndex) return null

        val counts = IntArray(10)
        for (i in 0 until startEvaluationIndex) {
            val n = historyChronological[i].number
            if (n in 0..9) counts[n]++
        }

        val uniform = ProbabilityMath.uniformDistribution()
        var uniformBrier = 0.0
        var uniformLoss = 0.0
        var uniformHits = 0

        var histBrier = 0.0
        var histLoss = 0.0
        var histHits = 0

        var smoothBrier = 0.0
        var smoothLoss = 0.0
        var smoothHits = 0

        var totalEvaluated = 0

        for (t in startEvaluationIndex until historyChronological.size) {
            val target = historyChronological[t].number
            if (target !in 0..9) continue

            val totalPrior = t.toDouble()
            val histProbs = (0..9).associateWith { d -> counts[d].toDouble() / totalPrior }
            val smoothProbs = (0..9).associateWith { d -> (counts[d] + 1.0) / (totalPrior + 10.0) }

            uniformBrier += ProbabilityMath.multiClassBrierScore(uniform, target)
            uniformLoss += ProbabilityMath.multiClassLogLoss(uniform, target)
            if (target == 0) uniformHits++ // Any fixed deterministic class achieves ~10% expected hit rate

            histBrier += ProbabilityMath.multiClassBrierScore(histProbs, target)
            histLoss += ProbabilityMath.multiClassLogLoss(histProbs, target)
            if (histProbs.maxByOrNull { it.value }?.key == target) histHits++

            smoothBrier += ProbabilityMath.multiClassBrierScore(smoothProbs, target)
            smoothLoss += ProbabilityMath.multiClassLogLoss(smoothProbs, target)
            if (smoothProbs.maxByOrNull { it.value }?.key == target) smoothHits++

            counts[target]++
            totalEvaluated++
        }

        if (totalEvaluated == 0) return null
        val denom = totalEvaluated.toDouble()

        return BaselineBenchmark(
            sampleCount = totalEvaluated,
            uniformBrierScore = uniformBrier / denom,
            uniformLogLoss = uniformLoss / denom,
            uniformAccuracy = uniformHits / denom,
            historicalFreqBrierScore = histBrier / denom,
            historicalFreqLogLoss = histLoss / denom,
            historicalFreqAccuracy = histHits / denom,
            smoothedFreqBrierScore = smoothBrier / denom,
            smoothedFreqLogLoss = smoothLoss / denom,
            smoothedFreqAccuracy = smoothHits / denom
        )
    }

    /**
     * Requires both lower Brier score and lower Log Loss than the best baseline across
     * multiple chronological validation halves (persistence check), preventing overfitting noise
     * from receiving ensemble weight.
     */
    fun checkPersistentImprovement(
        perStepPredictions: List<Pair<Map<Int, Double>, Int>>,
        baselineBrierPerStep: List<Double>,
        baselineLossPerStep: List<Double>
    ): Boolean {
        if (perStepPredictions.size < 20 ||
            perStepPredictions.size != baselineBrierPerStep.size ||
            perStepPredictions.size != baselineLossPerStep.size
        ) {
            return false
        }

        val mid = perStepPredictions.size / 2
        val firstHalf = perStepPredictions.subList(0, mid)
        val secondHalf = perStepPredictions.subList(mid, perStepPredictions.size)

        val firstModelBrier = firstHalf.map { (p, y) -> ProbabilityMath.multiClassBrierScore(p, y) }.average()
        val firstBaseBrier = baselineBrierPerStep.subList(0, mid).average()

        val secondModelBrier = secondHalf.map { (p, y) -> ProbabilityMath.multiClassBrierScore(p, y) }.average()
        val secondBaseBrier = baselineBrierPerStep.subList(mid, baselineBrierPerStep.size).average()

        val fullModelLoss = perStepPredictions.map { (p, y) -> ProbabilityMath.multiClassLogLoss(p, y) }.average()
        val fullBaseLoss = baselineLossPerStep.average()

        return firstModelBrier < firstBaseBrier - 1e-4 &&
            secondModelBrier < secondBaseBrier - 1e-4 &&
            fullModelLoss < fullBaseLoss - 1e-4
    }

    fun buildMetrics(
        perStepPredictions: List<Pair<Map<Int, Double>, Int>>,
        baseline: BaselineBenchmark
    ): ModelValidationMetrics {
        if (perStepPredictions.isEmpty()) {
            return ModelValidationMetrics(
                evaluatedSamples = 0,
                numberAccuracy = 0.0,
                logLoss = Double.NaN,
                brierScore = Double.NaN,
                baselineBrierScore = baseline.bestBaselineBrier,
                baselineLogLoss = baseline.bestBaselineLogLoss,
                outperformsBaseline = false
            )
        }
        val n = perStepPredictions.size.toDouble()
        val hits = perStepPredictions.count { (p, y) -> p.maxByOrNull { it.value }?.key == y }
        val logLoss = perStepPredictions.sumOf { (p, y) -> ProbabilityMath.multiClassLogLoss(p, y) } / n
        val brier = perStepPredictions.sumOf { (p, y) -> ProbabilityMath.multiClassBrierScore(p, y) } / n

        val outperforms = perStepPredictions.size >= 20 &&
            brier < baseline.bestBaselineBrier - 1e-4 &&
            logLoss < baseline.bestBaselineLogLoss - 1e-4

        return ModelValidationMetrics(
            evaluatedSamples = perStepPredictions.size,
            numberAccuracy = hits / n,
            logLoss = logLoss,
            brierScore = brier,
            baselineBrierScore = baseline.bestBaselineBrier,
            baselineLogLoss = baseline.bestBaselineLogLoss,
            outperformsBaseline = outperforms
        )
    }
}
