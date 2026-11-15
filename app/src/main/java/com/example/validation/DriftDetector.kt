package com.example.validation

import com.example.analysis.ProbabilityMath
import com.example.data.local.entity.PredictionEntity
import com.example.data.local.entity.ResultEntity

data class DriftReport(
    val driftDetected: Boolean,
    val distributionShiftTv: Double,
    val recentAccuracyDrop: Double?,
    val recommendedWeightMultiplier: Double,
    val reason: String?
)

/**
 * Monitors distribution shift in historical WinGo_1M draws and recent vs older
 * out-of-sample model validation degradation.
 */
class DriftDetector {

    fun evaluateDrift(
        historyChronological: List<ResultEntity>,
        recentVerifiedPredictions: List<PredictionEntity>
    ): DriftReport {
        var tvShift = 0.0
        var distDrift = false

        if (historyChronological.size >= 100) {
            val recentWindow = historyChronological.takeLast(40)
            val referenceWindow = historyChronological.dropLast(40)

            val recentCounts = IntArray(10)
            recentWindow.forEach { if (it.number in 0..9) recentCounts[it.number]++ }
            val pRecent = (0..9).associateWith { (recentCounts[it] + 0.5) / (recentWindow.size + 5.0) }

            val refCounts = IntArray(10)
            referenceWindow.forEach { if (it.number in 0..9) refCounts[it.number]++ }
            val pRef = (0..9).associateWith { (refCounts[it] + 0.5) / (referenceWindow.size + 5.0) }

            tvShift = ProbabilityMath.totalVariationDistance(pRecent, pRef)
            distDrift = tvShift > MAX_TV_SHIFT_THRESHOLD
        }

        var accDrop: Double? = null
        var perfDrift = false
        val verified = recentVerifiedPredictions
            .filter { it.numberCorrect != null }
            .sortedBy { it.createdAt }

        if (verified.size >= 30) {
            val split = verified.size / 2
            val older = verified.subList(0, split)
            val newer = verified.subList(split, verified.size)
            val olderAcc = older.count { it.numberCorrect == true }.toDouble() / older.size
            val newerAcc = newer.count { it.numberCorrect == true }.toDouble() / newer.size
            accDrop = olderAcc - newerAcc
            perfDrift = accDrop > MAX_ACCURACY_DROP_THRESHOLD
        }

        val detected = distDrift || perfDrift
        val multiplier = when {
            perfDrift -> 0.25
            distDrift -> 0.5
            else -> 1.0
        }

        val reason = when {
            perfDrift && distDrift -> "Both distribution shift (TV=${"%.3f".format(tvShift)}) and validation accuracy drop detected."
            perfDrift -> "Recent prediction accuracy dropped by ${"%.1f".format((accDrop ?: 0.0) * 100)}% vs earlier window."
            distDrift -> "Recent 40-draw distribution shifted from baseline (TV=${"%.3f".format(tvShift)})."
            else -> null
        }

        return DriftReport(
            driftDetected = detected,
            distributionShiftTv = tvShift,
            recentAccuracyDrop = accDrop,
            recommendedWeightMultiplier = multiplier,
            reason = reason
        )
    }

    private companion object {
        const val MAX_TV_SHIFT_THRESHOLD = 0.24
        const val MAX_ACCURACY_DROP_THRESHOLD = 0.12
    }
}
