package com.example.prediction

import com.example.analysis.AnalysisOutput
import com.example.analysis.ModelStatus
import com.example.analysis.ProbabilityMath
import com.example.classification.ResultClassificationService
import com.example.domain.model.DataFreshnessStatus
import com.example.domain.model.PredictionStatus
import com.example.domain.model.WinGoColor
import com.example.domain.model.WinGoSize
import com.example.training.ModelTrainingSnapshot
import com.example.validation.ProbabilityCalibrator
import kotlin.math.max

/**
 * Central Ensemble Prediction Engine.
 *
 * Enforces strict statistical integrity:
 * 1. Validates every module distribution on the 10-class simplex.
 * 2. Excludes EntropyAnalysis as a standalone directional voter (informativeness metric only).
 * 3. Compares each module against BaselineEvaluator (never assigns equal or complexity-biased weights).
 * 4. Weights modules solely by verified out-of-sample Brier lift over the best baseline.
 * 5. Applies DriftDetector penalty and out-of-sample Temperature calibration.
 * 6. Computes inter-model disagreement (mean pairwise Total Variation distance).
 * 7. Reports "No reliable statistical signal." if no model meaningfully outperforms baselines.
 */
class EnsembleEngine(
    private val classificationService: ResultClassificationService,
    private val calibrator: ProbabilityCalibrator
) {

    fun combine(
        issueNumber: String?,
        freshness: DataFreshnessStatus,
        dataCutoffIssue: String?,
        trainingSnapshot: ModelTrainingSnapshot
    ): PredictionResult {
        val sampleSize = trainingSnapshot.historySampleSize
        val outputs = trainingSnapshot.moduleOutputs
        val baseline = trainingSnapshot.baselineBenchmark
        val drift = trainingSnapshot.driftReport
        val calibration = trainingSnapshot.calibrationReport

        if (sampleSize == 0) {
            return PredictionResult.Unavailable(
                headline = "No historical data available.",
                details = "Actual WinGo_1M results are required before any statistical or ML analysis can execute.",
                issueNumber = issueNumber,
                freshness = freshness,
                sampleSize = 0,
                moduleOutputs = outputs,
                baselineBenchmark = baseline,
                calibrationReport = calibration,
                driftReport = drift
            )
        }

        if (issueNumber.isNullOrBlank()) {
            return PredictionResult.Unavailable(
                headline = "Authorized API session unavailable.",
                details = "Synchronized GetPanel issue number is required to target a live WinGo_1M draw. Offline analysis of $sampleSize cached draws remains available in the Analysis tab.",
                issueNumber = null,
                freshness = freshness,
                sampleSize = sampleSize,
                moduleOutputs = outputs,
                baselineBenchmark = baseline,
                calibrationReport = calibration,
                driftReport = drift
            )
        }

        if (sampleSize < MIN_ENSEMBLE_SAMPLES) {
            return PredictionResult.Unavailable(
                headline = "Insufficient training data.",
                details = "Collected $sampleSize verified WinGo_1M draws; at least $MIN_ENSEMBLE_SAMPLES chronological draws are required for out-of-sample baseline validation.",
                issueNumber = issueNumber,
                freshness = freshness,
                sampleSize = sampleSize,
                moduleOutputs = outputs,
                baselineBenchmark = baseline,
                calibrationReport = calibration,
                driftReport = drift
            )
        }

        // Filter to valid probability distributions (excluding pure uncertainty metric EntropyAnalysis)
        val validCandidates = outputs.filter { out ->
            out.moduleId != "entropy_analysis" &&
                out.status == ModelStatus.READY &&
                ProbabilityMath.isValidSimplex(out.numberProbabilities)
        }

        if (validCandidates.isEmpty()) {
            return PredictionResult.Unavailable(
                headline = "No reliable statistical signal.",
                details = "No analysis module produced a validated out-of-sample distribution outperforming the baseline.",
                issueNumber = issueNumber,
                freshness = freshness,
                sampleSize = sampleSize,
                moduleOutputs = outputs,
                baselineBenchmark = baseline,
                calibrationReport = calibration,
                driftReport = drift
            )
        }

        // Evidence-based weights derived strictly from out-of-sample Brier improvement over baseline
        val weighted = mutableListOf<Pair<AnalysisOutput, Double>>()
        for (out in validCandidates) {
            val m = out.validationMetrics
            if (m != null && m.outperformsBaseline && !m.brierScore.isNaN()) {
                val brierLift = max(0.0, m.baselineBrierScore - m.brierScore)
                val logLossLift = max(0.0, m.baselineLogLoss - m.logLoss)
                val rawEvidenceWeight = brierLift + 0.5 * logLossLift
                if (rawEvidenceWeight > 0.0) {
                    weighted.add(out to rawEvidenceWeight)
                }
            }
        }

        if (weighted.isEmpty()) {
            val bestBaseBrier = baseline?.bestBaselineBrier?.let { "%.4f".format(it) } ?: "--"
            return PredictionResult.Unavailable(
                headline = "No reliable statistical signal.",
                details = "Evaluated ${outputs.size} modules across $sampleSize draws: none outperformed the simple statistical baseline (best baseline Brier=$bestBaseBrier). Equal/arbitrary weighting is disabled.",
                issueNumber = issueNumber,
                freshness = freshness,
                sampleSize = sampleSize,
                moduleOutputs = outputs,
                baselineBenchmark = baseline,
                calibrationReport = calibration,
                driftReport = drift
            )
        }

        val totalEvidence = weighted.sumOf { it.second }
        val normalizedWeights = weighted.map { (out, w) -> out to (w / totalEvidence) }

        val rawCombined = DoubleArray(10)
        for ((out, weight) in normalizedWeights) {
            val dist = out.numberProbabilities!!
            for (d in 0..9) {
                rawCombined[d] += (dist[d] ?: 0.1) * weight
            }
        }
        val combinedNumberProbs = ProbabilityMath.normalizeArray(rawCombined)

        val calibratedNumbers = if (calibration != null) {
            calibrator.applyTemperature(combinedNumberProbs, calibration.optimalTemperature)
        } else {
            combinedNumberProbs
        }

        // Inter-model disagreement: mean pairwise Total Variation distance
        val disagreement = computePairwiseDisagreement(normalizedWeights.map { it.first.numberProbabilities!! })
        if (disagreement > MAX_DISAGREEMENT_THRESHOLD) {
            return PredictionResult.Unavailable(
                headline = "No reliable statistical signal.",
                details = "Active models exhibit high disagreement (mean TV=${"%.3f".format(disagreement)} > $MAX_DISAGREEMENT_THRESHOLD).",
                issueNumber = issueNumber,
                freshness = freshness,
                sampleSize = sampleSize,
                moduleOutputs = outputs,
                baselineBenchmark = baseline,
                calibrationReport = calibration,
                driftReport = drift
            )
        }

        val topNumber = calibratedNumbers.maxByOrNull { it.value }?.key ?: 0
        val topProb = calibratedNumbers[topNumber] ?: 0.1
        val driftMultiplier = drift?.recommendedWeightMultiplier ?: 1.0
        val confidence = ((topProb * 2.5) * (1.0 - disagreement) * driftMultiplier).coerceIn(0.05, 0.95)

        val classReport = classificationService.getVerificationReport()
        val colorProbs = classificationService.deriveColorDistribution(calibratedNumbers)
        val sizeProbs = classificationService.deriveSizeDistribution(calibratedNumbers)

        val topColor = colorProbs?.maxByOrNull { it.value }?.key?.let { label ->
            WinGoColor.entries.firstOrNull { it.label.equals(label, ignoreCase = true) }
        }
        val topSize = sizeProbs?.maxByOrNull { it.value }?.key?.let { label ->
            WinGoSize.entries.firstOrNull { it.label.equals(label, ignoreCase = true) }
        }

        val contributions = normalizedWeights.map { (out, weight) ->
            val m = out.validationMetrics
            ModuleContribution(
                moduleId = out.moduleId,
                moduleName = out.moduleName,
                moduleVersion = out.moduleVersion,
                assignedWeight = weight,
                brierScore = m?.brierScore,
                logLoss = m?.logLoss,
                numberAccuracy = m?.numberAccuracy,
                outperformsBaseline = m?.outperformsBaseline == true,
                statusNote = "Evidence weight ${"%.1f".format(weight * 100)}% (Brier=${m?.brierScore?.let { "%.4f".format(it) } ?: "-"})"
            )
        }

        return PredictionResult.Ready(
            issueNumber = issueNumber,
            createdAtMillis = System.currentTimeMillis(),
            lockedAtMillis = null,
            predictionStatus = PredictionStatus.DRAFT,
            freshness = freshness,
            predictedNumber = topNumber,
            predictedColor = topColor,
            predictedSize = topSize,
            colorUnavailableReason = if (colorProbs == null) classReport.colorReason else null,
            sizeUnavailableReason = if (sizeProbs == null) classReport.bigSmallReason else null,
            numberProbabilities = calibratedNumbers,
            colorProbabilities = colorProbs,
            sizeProbabilities = sizeProbs,
            confidence = confidence,
            disagreementScore = disagreement,
            validContributingCount = contributions.size,
            modelVersions = contributions.joinToString(",") { "${it.moduleId}@${it.moduleVersion}" },
            dataCutoffIssue = dataCutoffIssue,
            contributions = contributions,
            moduleOutputs = outputs,
            baselineBenchmark = baseline,
            calibrationReport = calibration,
            driftReport = drift
        )
    }

    private fun computePairwiseDisagreement(distributions: List<Map<Int, Double>>): Double {
        if (distributions.size < 2) return 0.0
        var totalTv = 0.0
        var pairs = 0
        for (i in distributions.indices) {
            for (j in i + 1 until distributions.size) {
                totalTv += ProbabilityMath.totalVariationDistance(distributions[i], distributions[j])
                pairs++
            }
        }
        return if (pairs == 0) 0.0 else totalTv / pairs.toDouble()
    }

    private companion object {
        const val MIN_ENSEMBLE_SAMPLES = 40
        const val MAX_DISAGREEMENT_THRESHOLD = 0.45
    }
}
