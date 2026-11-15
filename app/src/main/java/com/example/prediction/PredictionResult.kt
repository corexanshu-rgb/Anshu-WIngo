package com.example.prediction

import com.example.analysis.AnalysisOutput
import com.example.domain.model.DataFreshnessStatus
import com.example.domain.model.PredictionStatus
import com.example.domain.model.WinGoColor
import com.example.domain.model.WinGoSize
import com.example.validation.BaselineBenchmark
import com.example.validation.CalibrationReport
import com.example.validation.DriftReport

data class ModuleContribution(
    val moduleId: String,
    val moduleName: String,
    val moduleVersion: String,
    val assignedWeight: Double,
    val brierScore: Double?,
    val logLoss: Double?,
    val numberAccuracy: Double?,
    val outperformsBaseline: Boolean,
    val statusNote: String
)

sealed interface PredictionResult {
    data class Ready(
        val issueNumber: String,
        val createdAtMillis: Long,
        val lockedAtMillis: Long?,
        val predictionStatus: PredictionStatus,
        val freshness: DataFreshnessStatus,
        val predictedNumber: Int,
        val predictedColor: WinGoColor?,
        val predictedSize: WinGoSize?,
        val colorUnavailableReason: String?,
        val sizeUnavailableReason: String?,
        val numberProbabilities: Map<Int, Double>,
        val colorProbabilities: Map<String, Double>?,
        val sizeProbabilities: Map<String, Double>?,
        val confidence: Double,
        val disagreementScore: Double,
        val validContributingCount: Int,
        val modelVersions: String,
        val dataCutoffIssue: String?,
        val contributions: List<ModuleContribution>,
        val moduleOutputs: List<AnalysisOutput>,
        val baselineBenchmark: BaselineBenchmark?,
        val calibrationReport: CalibrationReport?,
        val driftReport: DriftReport?
    ) : PredictionResult

    data class Unavailable(
        val headline: String,
        val details: String,
        val issueNumber: String?,
        val freshness: DataFreshnessStatus,
        val sampleSize: Int,
        val moduleOutputs: List<AnalysisOutput>,
        val baselineBenchmark: BaselineBenchmark?,
        val calibrationReport: CalibrationReport?,
        val driftReport: DriftReport?
    ) : PredictionResult
}
