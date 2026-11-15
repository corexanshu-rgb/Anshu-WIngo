package com.example.prediction

import com.example.data.local.dao.PredictionDao
import com.example.data.local.dao.ResultDao
import com.example.data.local.entity.PredictionEntity
import com.example.domain.model.DataFreshnessStatus
import com.example.domain.model.PredictionStatus
import com.example.domain.model.VerificationStatus

sealed interface LockDecision {
    data class Locked(val entity: PredictionEntity) : LockDecision
    data class AlreadyLocked(val existing: PredictionEntity) : LockDecision
    data class Rejected(val reason: String) : LockDecision
}

/**
 * Enforces strict pre-outcome prediction locking:
 * - Never creates a prediction if the target issue already exists in actual_results.
 * - Never overwrites an already-locked prediction for the same issueNumber.
 * - Requires live synchronized GetPanel deadline with safe buffer (> 2000ms) before locking.
 */
class PredictionLockManager(
    private val predictionDao: PredictionDao,
    private val resultDao: ResultDao
) {

    suspend fun lockBeforeOutcome(
        result: PredictionResult.Ready,
        freshness: DataFreshnessStatus,
        remainingMillis: Long?,
        currentPhase: String?
    ): LockDecision {
        val existingPrediction = predictionDao.getByIssueNumber(result.issueNumber)
        if (existingPrediction != null) {
            return LockDecision.AlreadyLocked(existingPrediction)
        }

        // Prevent post-outcome prediction creation
        val alreadySettled = resultDao.getByIssueNumber(result.issueNumber)
        if (alreadySettled != null) {
            return LockDecision.Rejected(
                "Actual result for issue ${result.issueNumber} is already recorded; post-outcome predictions are prohibited."
            )
        }

        if (freshness != DataFreshnessStatus.LIVE) {
            return LockDecision.Rejected(
                "Cannot lock prediction without live synchronized GetPanel timing (${freshness.label})."
            )
        }

        if (remainingMillis == null || remainingMillis <= MIN_LOCK_BUFFER_MS) {
            return LockDecision.Rejected(
                "Insufficient remaining time before server deadline (${remainingMillis ?: 0}ms <= ${MIN_LOCK_BUFFER_MS}ms)."
            )
        }

        if (!currentPhase.isNullOrBlank() && !currentPhase.equals("Betting", ignoreCase = true)) {
            return LockDecision.Rejected(
                "Round phase is '$currentPhase'; predictions lock only during active open round."
            )
        }

        val now = System.currentTimeMillis()
        val entity = PredictionEntity(
            issueNumber = result.issueNumber,
            createdAt = result.createdAtMillis,
            lockedAt = now,
            numberProbabilities = result.numberProbabilities,
            colorProbabilities = result.colorProbabilities.orEmpty(),
            sizeProbabilities = result.sizeProbabilities.orEmpty(),
            predictedNumber = result.predictedNumber,
            predictedColor = result.predictedColor?.label,
            predictedSize = result.predictedSize?.label,
            confidence = result.confidence,
            contributingModels = result.contributions.joinToString(",") { it.moduleId },
            modelVersions = result.modelVersions,
            verificationStatus = VerificationStatus.PENDING,
            predictionStatus = PredictionStatus.LOCKED
        )

        val insertedId = predictionDao.insertPredictionAndTrim(entity)
        return if (insertedId > 0L) {
            LockDecision.Locked(entity.copy(id = insertedId))
        } else {
            val loaded = predictionDao.getByIssueNumber(result.issueNumber)
            if (loaded != null) LockDecision.AlreadyLocked(loaded)
            else LockDecision.Rejected("Failed to persist locked prediction.")
        }
    }

    private companion object {
        const val MIN_LOCK_BUFFER_MS = 2_000L
    }
}
