package com.example.validation

import com.example.data.local.entity.PredictionEntity
import com.example.domain.model.PredictionStatus
import com.example.domain.model.VerificationStatus

data class ModuleAccuracySummary(
    val moduleId: String,
    val evaluatedCount: Int,
    val numberAccuracy: Double?,
    val colorAccuracy: Double?,
    val sizeAccuracy: Double?,
    val overallAccuracy: Double?
)

data class AccuracyReport(
    val totalCount: Int,
    val verifiedCount: Int,
    val correctCount: Int,
    val wrongCount: Int,
    val skippedCount: Int,
    val numberAccuracy: Double?,
    val colorAccuracy: Double?,
    val sizeAccuracy: Double?,
    val overallAccuracy: Double?,
    val moduleSummaries: List<ModuleAccuracySummary>
)

/**
 * Tracks independent prediction accuracy strictly from locked, pre-outcome verified predictions.
 * Overall accuracy measures full-target matches (exact number hit and no mismatch on evaluated
 * color/size attributes) rather than inflating via OR-matching ~50% binary attributes.
 */
class AccuracyTracker {

    fun computeReport(predictions: List<PredictionEntity>): AccuracyReport {
        val skippedCount = predictions.count {
            it.predictionStatus == PredictionStatus.SKIPPED ||
                it.verificationStatus == VerificationStatus.SKIPPED
        }

        val verified = predictions.filter {
            it.verificationStatus == VerificationStatus.VERIFIED &&
                it.lockedAt != null &&
                it.predictionStatus != PredictionStatus.SKIPPED
        }

        if (verified.isEmpty()) {
            return AccuracyReport(
                totalCount = predictions.size,
                verifiedCount = 0,
                correctCount = 0,
                wrongCount = 0,
                skippedCount = skippedCount,
                numberAccuracy = null,
                colorAccuracy = null,
                sizeAccuracy = null,
                overallAccuracy = null,
                moduleSummaries = emptyList()
            )
        }

        val n = verified.size.toDouble()
        val numHits = verified.count { it.numberCorrect == true }
        val colorEval = verified.filter { it.colorCorrect != null }
        val sizeEval = verified.filter { it.sizeCorrect != null }
        val strictOverallHits = verified.count { isStrictOverallHit(it) }

        return AccuracyReport(
            totalCount = predictions.size,
            verifiedCount = verified.size,
            correctCount = strictOverallHits,
            wrongCount = verified.size - strictOverallHits,
            skippedCount = skippedCount,
            numberAccuracy = numHits / n,
            colorAccuracy = if (colorEval.isNotEmpty()) colorEval.count { it.colorCorrect == true }.toDouble() / colorEval.size else null,
            sizeAccuracy = if (sizeEval.isNotEmpty()) sizeEval.count { it.sizeCorrect == true }.toDouble() / sizeEval.size else null,
            overallAccuracy = strictOverallHits / n,
            moduleSummaries = summarizeFromPredictions(verified)
        )
    }

    fun summarizeFromPredictions(verified: List<PredictionEntity>): List<ModuleAccuracySummary> {
        if (verified.isEmpty()) return emptyList()
        val grouped = mutableMapOf<String, MutableList<PredictionEntity>>()
        for (p in verified) {
            val parts = (p.contributingModels.takeIf { it.isNotBlank() } ?: p.modelVersions)
                .split(",")
                .map { it.trim().substringBefore("@") }
                .filter { it.isNotEmpty() }
            for (moduleId in parts) {
                grouped.getOrPut(moduleId) { mutableListOf() }.add(p)
            }
        }
        return grouped.map { (moduleId, items) ->
            val denom = items.size.toDouble()
            val numAcc = items.count { it.numberCorrect == true } / denom
            val colorItems = items.filter { it.colorCorrect != null }
            val sizeItems = items.filter { it.sizeCorrect != null }
            val strictOverallAcc = items.count { isStrictOverallHit(it) } / denom

            ModuleAccuracySummary(
                moduleId = moduleId,
                evaluatedCount = items.size,
                numberAccuracy = numAcc,
                colorAccuracy = if (colorItems.isNotEmpty()) colorItems.count { it.colorCorrect == true } / colorItems.size.toDouble() else null,
                sizeAccuracy = if (sizeItems.isNotEmpty()) sizeItems.count { it.sizeCorrect == true } / sizeItems.size.toDouble() else null,
                overallAccuracy = strictOverallAcc
            )
        }
    }

    companion object {
        fun isStrictOverallHit(prediction: PredictionEntity): Boolean =
            prediction.numberCorrect == true &&
                prediction.colorCorrect != false &&
                prediction.sizeCorrect != false
    }
}
