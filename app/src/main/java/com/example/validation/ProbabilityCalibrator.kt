package com.example.validation

import com.example.analysis.ProbabilityMath
import kotlin.math.abs
import kotlin.math.ln

data class ReliabilityBucket(
    val bucketLabel: String,
    val sampleCount: Int,
    val avgPredictedConfidence: Double,
    val empiricalAccuracy: Double
)

data class CalibrationReport(
    val sampleCount: Int,
    val optimalTemperature: Double,
    val logLoss: Double,
    val brierScore: Double,
    val expectedCalibrationError: Double,
    val reliabilityBuckets: List<ReliabilityBucket>
)

/**
 * Out-of-sample probability calibration using Temperature Scaling on log-probabilities
 * plus reliability bucket evaluation (ECE, Log Loss, Brier Score).
 */
class ProbabilityCalibrator {

    fun fitTemperatureAndEvaluate(
        outOfSamplePredictions: List<Pair<Map<Int, Double>, Int>>
    ): CalibrationReport? {
        if (outOfSamplePredictions.size < 10) return null

        val candidateTemps = listOf(0.75, 0.9, 1.0, 1.15, 1.35, 1.6, 2.0, 2.5)
        var bestTemp = 1.0
        var bestLoss = Double.POSITIVE_INFINITY

        for (temp in candidateTemps) {
            val loss = outOfSamplePredictions.sumOf { (probs, actual) ->
                val calibrated = applyTemperature(probs, temp)
                ProbabilityMath.multiClassLogLoss(calibrated, actual)
            } / outOfSamplePredictions.size.toDouble()

            if (loss < bestLoss) {
                bestLoss = loss
                bestTemp = temp
            }
        }

        val calibratedPairs = outOfSamplePredictions.map { (probs, actual) ->
            applyTemperature(probs, bestTemp) to actual
        }

        val n = calibratedPairs.size.toDouble()
        val avgLogLoss = calibratedPairs.sumOf { (p, y) -> ProbabilityMath.multiClassLogLoss(p, y) } / n
        val avgBrier = calibratedPairs.sumOf { (p, y) -> ProbabilityMath.multiClassBrierScore(p, y) } / n

        // Reliability buckets across top predicted class probabilities (5 equal-width bins 0.0..1.0)
        val bins = Array(5) { mutableListOf<Pair<Double, Boolean>>() }
        for ((probs, actual) in calibratedPairs) {
            val top = probs.maxByOrNull { it.value } ?: continue
            val binIdx = (top.value * 5.0).toInt().coerceIn(0, 4)
            bins[binIdx].add(top.value to (top.key == actual))
        }

        val buckets = mutableListOf<ReliabilityBucket>()
        var ece = 0.0
        for (b in 0 until 5) {
            val items = bins[b]
            if (items.isEmpty()) continue
            val avgConf = items.map { it.first }.average()
            val empAcc = items.count { it.second }.toDouble() / items.size.toDouble()
            ece += (items.size.toDouble() / n) * abs(avgConf - empAcc)
            buckets.add(
                ReliabilityBucket(
                    bucketLabel = "${b * 20}-${(b + 1) * 20}%",
                    sampleCount = items.size,
                    avgPredictedConfidence = avgConf,
                    empiricalAccuracy = empAcc
                )
            )
        }

        return CalibrationReport(
            sampleCount = calibratedPairs.size,
            optimalTemperature = bestTemp,
            logLoss = avgLogLoss,
            brierScore = avgBrier,
            expectedCalibrationError = ece,
            reliabilityBuckets = buckets
        )
    }

    fun applyTemperature(probs: Map<Int, Double>, temperature: Double): Map<Int, Double> {
        if (probs.size != 10 || temperature <= 0.0) return ProbabilityMath.uniformDistribution()
        val invT = 1.0 / temperature
        val logits = DoubleArray(10) { k ->
            val p = (probs[k] ?: 1e-6).coerceIn(1e-6, 1.0)
            ln(p) * invT
        }
        val softmaxed = ProbabilityMath.softmax(logits)
        return (0..9).associateWith { k -> softmaxed[k] }
    }
}
