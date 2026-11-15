package com.example.prediction

import com.example.classification.ColorClassification
import com.example.classification.ResultClassificationService
import com.example.classification.SizeClassification
import com.example.data.local.dao.PredictionDao
import com.example.data.local.entity.PredictionEntity
import com.example.data.local.entity.ResultEntity
import com.example.domain.model.PredictionStatus
import com.example.domain.model.VerificationStatus
import com.example.domain.model.WinGoGameConfig
import kotlinx.coroutines.flow.Flow

class PredictionRepository(
    private val predictionDao: PredictionDao,
    private val classificationService: ResultClassificationService
) {
    fun observeLatestPredictions(): Flow<List<PredictionEntity>> =
        predictionDao.observeLatestPredictions(WinGoGameConfig.MAX_PREDICTION_RECORDS)

    fun observePredictionForIssue(issueNumber: String): Flow<PredictionEntity?> =
        predictionDao.observeByIssueNumber(issueNumber)

    suspend fun getLatestPredictionsSnapshot(): List<PredictionEntity> =
        predictionDao.getLatestPredictionsSnapshot(WinGoGameConfig.MAX_PREDICTION_RECORDS)

    suspend fun verifyAgainstNewResults(results: List<ResultEntity>) {
        if (results.isEmpty()) return
        classificationService.auditAndVerifyFromResults(results)

        val byIssue = results.associateBy { it.issueNumber }
        val pending = predictionDao.getByVerificationStatus(VerificationStatus.PENDING)

        for (prediction in pending) {
            val actual = byIssue[prediction.issueNumber] ?: continue
            val lockedAt = prediction.lockedAt

            if (prediction.predictionStatus == PredictionStatus.SKIPPED) {
                continue
            }
            if (lockedAt == null || lockedAt > actual.receivedAt) {
                predictionDao.markUnverifiableIfNotLockedInTime(
                    issueNumber = prediction.issueNumber,
                    resultReceivedAt = actual.receivedAt
                )
                continue
            }

            val colorClass = classificationService.classifyRecordColor(actual)
            val sizeClass = classificationService.classifySize(actual.number)

            val actualColorLabel = (colorClass as? ColorClassification.Verified)?.primaryColor?.label
                ?: actual.color
            val actualSizeLabel = (sizeClass as? SizeClassification.Verified)?.size?.label

            val numCorrect = prediction.predictedNumber == actual.number
            val colorCorrect = if (prediction.predictedColor != null && colorClass is ColorClassification.Verified) {
                colorClass.allColors.any { it.label.equals(prediction.predictedColor, ignoreCase = true) }
            } else {
                false
            }
            val sizeCorrect = if (prediction.predictedSize != null && actualSizeLabel != null) {
                prediction.predictedSize.equals(actualSizeLabel, ignoreCase = true)
            } else {
                false
            }

            predictionDao.verifyLockedPrediction(
                issueNumber = prediction.issueNumber,
                actualNumber = actual.number,
                actualColor = actualColorLabel,
                actualSize = actualSizeLabel ?: "Unverified",
                numberCorrect = numCorrect,
                colorCorrect = colorCorrect,
                sizeCorrect = sizeCorrect,
                verificationStatus = VerificationStatus.VERIFIED,
                resultReceivedAt = actual.receivedAt
            )
        }
    }
}
