package com.example.analytics

import com.example.data.local.dao.AnalysisDao
import com.example.data.local.dao.PredictionDao
import com.example.data.local.entity.AnalysisEntity
import com.example.data.local.entity.PredictionEntity
import com.example.domain.model.PredictionStatus
import com.example.domain.model.VerificationStatus
import com.example.validation.AccuracyTracker
import com.example.validation.ModuleAccuracySummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class AnalyticsWindow(val label: String, val limit: Int) {
    LAST_50("Last 50", 50),
    LAST_100("Last 100", 100),
    LAST_500("Last 500", 500)
}

data class AccuracyTrendPoint(
    val issueNumber: String,
    val cumulativeAccuracyPercent: Float
)

data class AnalyticsSnapshot(
    val window: AnalyticsWindow = AnalyticsWindow.LAST_50,
    val totalRecords: Int = 0,
    val verifiedCount: Int = 0,
    val correctCount: Int = 0,
    val wrongCount: Int = 0,
    val skippedCount: Int = 0,
    val overallAccuracyPercent: Double? = null,
    val numberAccuracyPercent: Double? = null,
    val colorAccuracyPercent: Double? = null,
    val sizeAccuracyPercent: Double? = null,
    val modulePerformance: List<ModuleAccuracySummary> = emptyList(),
    val trendPoints: List<AccuracyTrendPoint> = emptyList(),
    val records: List<PredictionEntity> = emptyList()
)

class AnalyticsRepository(
    private val predictionDao: PredictionDao,
    private val analysisDao: AnalysisDao,
    private val accuracyTracker: AccuracyTracker
) {

    fun observeAnalytics(window: AnalyticsWindow): Flow<AnalyticsSnapshot> {
        return predictionDao.observeLatestPredictions(window.limit).map { predictions ->
            buildSnapshot(window, predictions)
        }
    }

    fun observeAnalysisRuns(): Flow<List<AnalysisEntity>> = analysisDao.observeAll()

    fun buildSnapshot(
        window: AnalyticsWindow,
        predictions: List<PredictionEntity>
    ): AnalyticsSnapshot {
        val report = accuracyTracker.computeReport(predictions)

        val verified = predictions.filter {
            it.verificationStatus == VerificationStatus.VERIFIED &&
                it.lockedAt != null &&
                it.predictionStatus != PredictionStatus.SKIPPED
        }

        val chronological = verified.sortedBy { it.createdAt }
        var runningHits = 0
        val trendPoints = chronological.mapIndexed { index, item ->
            val hit = if (AccuracyTracker.isStrictOverallHit(item)) 1 else 0
            runningHits += hit
            val pct = (runningHits.toFloat() / (index + 1).toFloat()) * 100f
            AccuracyTrendPoint(
                issueNumber = item.issueNumber,
                cumulativeAccuracyPercent = pct
            )
        }

        return AnalyticsSnapshot(
            window = window,
            totalRecords = report.totalCount,
            verifiedCount = report.verifiedCount,
            correctCount = report.correctCount,
            wrongCount = report.wrongCount,
            skippedCount = report.skippedCount,
            overallAccuracyPercent = report.overallAccuracy?.let { it * 100.0 },
            numberAccuracyPercent = report.numberAccuracy?.let { it * 100.0 },
            colorAccuracyPercent = report.colorAccuracy?.let { it * 100.0 },
            sizeAccuracyPercent = report.sizeAccuracy?.let { it * 100.0 },
            modulePerformance = report.moduleSummaries,
            trendPoints = trendPoints,
            records = predictions
        )
    }
}
