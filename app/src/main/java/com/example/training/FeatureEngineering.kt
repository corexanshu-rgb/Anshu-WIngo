package com.example.training

import com.example.analysis.ProbabilityMath
import com.example.data.local.entity.ResultEntity
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

data class FeatureScaler(
    val means: DoubleArray,
    val stds: DoubleArray
) {
    fun transform(raw: DoubleArray): DoubleArray {
        require(raw.size == means.size) { "Expected ${means.size} features, got ${raw.size}" }
        return DoubleArray(raw.size) { i ->
            val s = stds[i]
            if (s < 1e-8) 0.0 else ((raw[i] - means[i]) / s).coerceIn(-6.0, 6.0)
        }
    }

    fun transformBatch(batch: List<DoubleArray>): List<DoubleArray> = batch.map(::transform)

    companion object {
        fun fit(trainVectors: List<DoubleArray>): FeatureScaler {
            if (trainVectors.isEmpty()) {
                return FeatureScaler(DoubleArray(FeatureEngineering.FEATURE_COUNT), DoubleArray(FeatureEngineering.FEATURE_COUNT) { 1.0 })
            }
            val dim = trainVectors.first().size
            val means = DoubleArray(dim)
            for (vec in trainVectors) {
                for (j in 0 until dim) means[j] += vec[j]
            }
            for (j in 0 until dim) means[j] /= trainVectors.size.toDouble()

            val variances = DoubleArray(dim)
            for (vec in trainVectors) {
                for (j in 0 until dim) {
                    val d = vec[j] - means[j]
                    variances[j] += d * d
                }
            }
            val stds = DoubleArray(dim) { j ->
                val v = variances[j] / trainVectors.size.toDouble()
                sqrt(v).coerceAtLeast(1e-6)
            }
            return FeatureScaler(means, stds)
        }
    }
}

/**
 * Causal feature engineering pipeline.
 * Strictly extracts features ONLY from indices [0, targetIndex), guaranteeing zero future
 * outcome or target-derived data leakage.
 */
object FeatureEngineering {

    const val MIN_LOOKBACK = 10
    const val FEATURE_COUNT = 30

    val FEATURE_NAMES: List<String> = listOf(
        "prev1_norm",
        "prev2_norm",
        "prev3_norm",
        "mean_last5_norm",
        "std_last5_norm",
        "mean_last10_norm",
        "std_last10_norm",
        "entropy_last20_norm",
        "window_tv_shift_20_50",
        "prev1_repeat_rate_prior"
    ) + (0..9).map { "rolling_freq_20_digit_$it" } + (0..9).map { "rolling_gap_norm_digit_$it" }

    /**
     * Extracts causal feature vector for predicting the draw at [targetIndex] (or next unseen draw
     * when targetIndex == historyChronological.size).
     */
    fun extractFeaturesAt(
        historyChronological: List<ResultEntity>,
        targetIndex: Int
    ): DoubleArray {
        require(targetIndex in MIN_LOOKBACK..historyChronological.size) {
            "Need at least $MIN_LOOKBACK prior draws before index $targetIndex"
        }

        val vec = DoubleArray(FEATURE_COUNT)
        val prev1 = historyChronological[targetIndex - 1].number
        val prev2 = historyChronological[targetIndex - 2].number
        val prev3 = historyChronological[targetIndex - 3].number

        vec[0] = prev1 / 9.0
        vec[1] = prev2 / 9.0
        vec[2] = prev3 / 9.0

        val last5 = (targetIndex - 5 until targetIndex).map { historyChronological[it].number / 9.0 }
        val last10 = (targetIndex - 10 until targetIndex).map { historyChronological[it].number / 9.0 }

        val mean5 = last5.average()
        val std5 = sqrt(last5.sumOf { (it - mean5) * (it - mean5) } / last5.size)
        val mean10 = last10.average()
        val std10 = sqrt(last10.sumOf { (it - mean10) * (it - mean10) } / last10.size)

        vec[3] = mean5
        vec[4] = std5
        vec[5] = mean10
        vec[6] = std10

        val start20 = (targetIndex - 20).coerceAtLeast(0)
        val count20 = targetIndex - start20
        val counts20 = IntArray(10)
        for (i in start20 until targetIndex) {
            val n = historyChronological[i].number
            if (n in 0..9) counts20[n]++
        }
        val probs20 = (0..9).associateWith { d ->
            (counts20[d] + 0.5) / (count20 + 5.0)
        }
        vec[7] = ProbabilityMath.normalizedEntropy(probs20)

        val start50 = (targetIndex - 50).coerceAtLeast(0)
        val count50 = targetIndex - start50
        val counts50 = IntArray(10)
        for (i in start50 until targetIndex) {
            val n = historyChronological[i].number
            if (n in 0..9) counts50[n]++
        }
        val probs50 = (0..9).associateWith { d ->
            (counts50[d] + 0.5) / (count50 + 5.0)
        }
        vec[8] = ProbabilityMath.totalVariationDistance(probs20, probs50)

        // Prior empirical rate of immediate repeat following prev1
        var prev1Total = 0
        var prev1Repeats = 0
        for (i in 1 until targetIndex) {
            if (historyChronological[i - 1].number == prev1) {
                prev1Total++
                if (historyChronological[i].number == prev1) prev1Repeats++
            }
        }
        vec[9] = if (prev1Total == 0) 0.1 else (prev1Repeats + 0.1) / (prev1Total + 1.0)

        // Rolling frequencies (last 20)
        for (d in 0..9) {
            vec[10 + d] = counts20[d].toDouble() / count20.coerceAtLeast(1).toDouble()
        }

        // Rolling gaps (draws since each digit 0..9 appeared prior to targetIndex)
        for (d in 0..9) {
            var gap = 0
            var found = false
            for (i in (targetIndex - 1) downTo 0) {
                if (historyChronological[i].number == d) {
                    found = true
                    break
                }
                gap++
            }
            val effectiveGap = if (found) gap else targetIndex
            vec[20 + d] = min(effectiveGap, 50) / 50.0
        }

        return vec
    }
}
