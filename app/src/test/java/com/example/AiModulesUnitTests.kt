package com.example

import com.example.analysis.AnalysisInput
import com.example.analysis.AnalysisOutput
import com.example.analysis.ModelCategory
import com.example.analysis.ModelStatus
import com.example.analysis.ModelValidationMetrics
import com.example.analysis.ProbabilityMath
import com.example.analysis.bayesian.BayesianModel
import com.example.analysis.entropy.EntropyAnalysis
import com.example.analysis.frequency.FrequencyAnalysis
import com.example.analysis.gap.GapAnalysis
import com.example.analysis.knn.KNNModel
import com.example.analysis.logistic.LogisticRegressionModel
import com.example.analysis.markov.MarkovChainModel
import com.example.analysis.randomforest.RandomForestModel
import com.example.analysis.recency.RecencyAnalysis
import com.example.analysis.rolling.RollingWindowAnalysis
import com.example.analysis.svm.SVMModel
import com.example.analysis.transition.TransitionMatrixModel
import com.example.classification.ClassificationRuleConfig
import com.example.classification.ColorClassification
import com.example.classification.ResultClassificationService
import com.example.classification.SizeClassification
import com.example.data.local.entity.PredictionEntity
import com.example.data.local.entity.ResultEntity
import com.example.domain.model.DataFreshnessStatus
import com.example.domain.model.WinGoColor
import com.example.prediction.EnsembleEngine
import com.example.prediction.PredictionResult
import com.example.training.FeatureEngineering
import com.example.training.ModelTrainingSnapshot
import com.example.training.TrainingDatasetBuilder
import com.example.validation.BaselineBenchmark
import com.example.validation.DriftDetector
import com.example.validation.ProbabilityCalibrator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Isolated mathematical and algorithmic verification for all 12 statistical/ML modules,
 * feature leakage guards, calibration, drift detection, classification audit, and ensemble gating.
 * Synthetic sequences below are strictly confined to unit testing.
 */
class AiModulesUnitTests {

    private fun makeResult(index: Int, number: Int, rawColor: String? = null): ResultEntity {
        val color = rawColor ?: when (number) {
            0 -> "red,violet"
            5 -> "green,violet"
            1, 3, 7, 9 -> "green"
            else -> "red"
        }
        return ResultEntity(
            issueNumber = "202611131000%05d".format(index),
            number = number,
            color = color,
            premium = "0",
            sum = number,
            blockId = "b$index",
            blockNumber = "$index",
            blockTimestamp = "${1700000000L + index * 60L}",
            receivedAt = 1_700_000_000_000L + index * 60_000L
        )
    }

    private fun makeInput(
        numbers: List<Int>,
        classificationService: ResultClassificationService = ResultClassificationService(
            ClassificationRuleConfig(
                officialColorRuleConfirmed = true,
                officialBigSmallRuleConfirmed = true
            )
        )
    ): AnalysisInput {
        val records = numbers.mapIndexed { idx, num -> makeResult(idx + 1, num) }
        return AnalysisInput(
            historyChronological = records,
            targetIssueNumber = "20261113100099999",
            classificationService = classificationService,
            dataFreshness = DataFreshnessStatus.LIVE
        )
    }

    @Test
    fun `probability normalization sanitizes negatives NaN Infinity and sums to 1`() {
        val normalized = ProbabilityMath.normalizeArray(
            doubleArrayOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY, 2.0, 2.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        )
        assertTrue(ProbabilityMath.isValidSimplex(normalized))
        assertEquals(0.5, normalized[3]!!, 1e-6)
        assertEquals(0.5, normalized[4]!!, 1e-6)
        assertEquals(0.0, normalized[0]!!, 1e-6)
    }

    @Test
    fun `feature engineering strictly prevents future outcome leakage`() {
        val seqA = List(40) { i -> makeResult(i + 1, (i * 3 + 1) % 10) }
        val seqB = seqA.toMutableList()
        // Mutate ALL future records from index 25 onward
        for (i in 25 until 40) {
            seqB[i] = makeResult(i + 1, 9 - (i % 3))
        }

        val featA = FeatureEngineering.extractFeaturesAt(seqA, targetIndex = 25)
        val featB = FeatureEngineering.extractFeaturesAt(seqB, targetIndex = 25)
        assertArrayEquals(featA, featB, 1e-12)
        assertEquals(FeatureEngineering.FEATURE_COUNT, featA.size)
    }

    @Test
    fun `dataset builder maintains chronological ordering without shuffling`() {
        val records = List(50) { i -> makeResult(i + 1, i % 10) }
        val samples = TrainingDatasetBuilder.buildChronologicalDataset(records)
        assertEquals(50 - FeatureEngineering.MIN_LOOKBACK, samples.size)
        for (i in 1 until samples.size) {
            assertTrue(samples[i].issueNumber > samples[i - 1].issueNumber)
        }
        val split = TrainingDatasetBuilder.splitChronologically(samples, trainRatio = 0.75)
        assertTrue(split.train.last().issueNumber < split.validation.first().issueNumber)
    }

    @Test
    fun `frequency recency gap and bayesian modules compute valid distributions and respect insufficient data`() = runBlocking {
        val modules = listOf(
            FrequencyAnalysis(),
            RecencyAnalysis(),
            GapAnalysis(),
            BayesianModel()
        )
        val shortInput = makeInput(listOf(1, 2, 3))
        for (mod in modules) {
            val out = mod.analyze(shortInput)
            assertEquals(ModelStatus.INSUFFICIENT_DATA, out.status)
            assertNull(out.numberProbabilities)
        }

        // Skewed repeating sequence where digit 7 dominates
        val skewed = List(90) { i -> if (i % 3 != 0) 7 else (i % 10) }
        val fullInput = makeInput(skewed)
        for (mod in modules) {
            val out = mod.analyze(fullInput)
            assertNotNull("${mod.moduleId} should produce probabilities", out.numberProbabilities)
            assertTrue(ProbabilityMath.isValidSimplex(out.numberProbabilities))
            assertNotNull(out.validationMetrics)
        }
    }

    @Test
    fun `entropy analysis flags low entropy structured series vs near uniform series`() = runBlocking {
        val entropyModule = EntropyAnalysis()

        val uniformLike = (0 until 100).map { it % 10 }
        val uniformOut = entropyModule.analyze(makeInput(uniformLike))
        assertEquals(ModelStatus.READY, uniformOut.status)
        val uniformRatio = uniformOut.diagnostics["normalized_entropy"]!!.toDouble()
        assertTrue("Expected near-1.0 entropy ratio, got $uniformRatio", uniformRatio > 0.95)

        val concentrated = List(100) { 4 }
        val concOut = entropyModule.analyze(makeInput(concentrated))
        val concRatio = concOut.diagnostics["normalized_entropy"]!!.toDouble()
        assertTrue("Expected low entropy ratio, got $concRatio", concRatio < 0.35)
    }

    @Test
    fun `markov chain transition matrix and rolling window learn predictable cyclical structure`() = runBlocking {
        // Deterministic 10-step cycle 0->1->2->...->9->0
        val cycle = List(140) { i -> i % 10 }
        val input = makeInput(cycle)

        val markov = MarkovChainModel().analyze(input)
        val trans = TransitionMatrixModel().analyze(input)
        val rolling = RollingWindowAnalysis().analyze(input)

        // Last element of cycle(140) is 139 % 10 = 9 -> next deterministic digit in cycle is 0
        assertEquals(0, markov.numberProbabilities!!.maxByOrNull { it.value }!!.key)
        assertEquals(0, trans.numberProbabilities!!.maxByOrNull { it.value }!!.key)
        assertTrue(ProbabilityMath.isValidSimplex(rolling.numberProbabilities))
        assertTrue(markov.validationMetrics!!.outperformsBaseline)
        assertTrue(trans.validationMetrics!!.outperformsBaseline)
    }

    @Test
    fun `all 4 ML modules train chronologically and output valid calibrated simplices`() = runBlocking {
        // Deterministic cycle where causal prev1_norm predicts next digit cleanly
        val cycle = List(160) { i -> i % 10 }
        val input = makeInput(cycle)

        val mlModules = listOf(
            LogisticRegressionModel(epochs = 60),
            RandomForestModel(numTrees = 15),
            KNNModel(kNeighbors = 9),
            SVMModel(epochs = 50)
        )

        for (mod in mlModules) {
            val out = mod.analyze(input)
            assertNotNull("${mod.moduleId} missing probabilities (${out.explanation})", out.numberProbabilities)
            assertTrue("${mod.moduleId} invalid simplex", ProbabilityMath.isValidSimplex(out.numberProbabilities))
            assertNotNull("${mod.moduleId} missing validationMetrics", out.validationMetrics)
        }
    }

    @Test
    fun `svm never reports raw margins when platt calibration data is insufficient`() = runBlocking {
        val svm = SVMModel()
        // Enough for training sample builder (< 80 or just at edge where cal slice < 15)
        val shortHistory = List(45) { i -> makeResult(i + 1, i % 10) }
        val input = AnalysisInput(
            historyChronological = shortHistory,
            targetIssueNumber = "NEXT",
            classificationService = ResultClassificationService(),
            dataFreshness = DataFreshnessStatus.LIVE
        )
        val out = svm.analyze(input)
        assertEquals(ModelStatus.INSUFFICIENT_DATA, out.status)
        assertNull(out.numberProbabilities)

        // Even calling trainWithCalibration directly with < 15 calibration samples must return null calibrators & null probs
        val samples = TrainingDatasetBuilder.buildChronologicalDataset(shortHistory)
        val trained = svm.trainWithCalibration(samples.take(25), samples.drop(25).take(5))
        assertNull(trained.plattCalibrators)
        assertNull(svm.predictCalibratedProbs(trained, samples.first().features))
    }

    @Test
    fun `classification service refuses unverified assumptions and audits real API colors`() {
        val unconfirmed = ResultClassificationService(
            ClassificationRuleConfig(
                officialColorRuleConfirmed = false,
                officialBigSmallRuleConfirmed = false,
                minRecordsForEmpiricalColorVerification = 20
            )
        )
        val probs = ProbabilityMath.uniformDistribution()
        assertNull(unconfirmed.deriveColorDistribution(probs))
        assertNull(unconfirmed.deriveSizeDistribution(probs))
        assertTrue(unconfirmed.classifySize(7) is SizeClassification.Unavailable)

        // Supply 30 consistent records covering 0..9
        val consistentRecords = (0 until 30).map { i -> makeResult(i + 1, i % 10) }
        val report = unconfirmed.auditAndVerifyFromResults(consistentRecords)
        assertTrue(report.colorVerified)
        assertFalse(report.bigSmallVerified)
        assertTrue(unconfirmed.classifyNumberColor(0) is ColorClassification.Verified)
        assertNotNull(unconfirmed.deriveColorDistribution(probs))
        assertNull(unconfirmed.deriveSizeDistribution(probs))

        // Contradiction in API color must immediately revoke empirical color verification
        val contradicted = consistentRecords + makeResult(31, 7, rawColor = "red")
        val badReport = unconfirmed.auditAndVerifyFromResults(contradicted)
        assertFalse(badReport.colorVerified)
        assertTrue(badReport.inconsistentRecordCount > 0)
    }

    @Test
    fun `probability calibrator and drift detector compute ECE log loss and detect shifts`() {
        val calibrator = ProbabilityCalibrator()
        val pairs = (0 until 20).map { i ->
            val y = i % 10
            val p = (0..9).associateWith { d -> if (d == y) 0.55 else 0.05 }
            p to y
        }
        val calReport = calibrator.fitTemperatureAndEvaluate(pairs)
        assertNotNull(calReport)
        assertEquals(20, calReport!!.sampleCount)
        assertTrue(calReport.expectedCalibrationError >= 0.0)
        assertTrue(calReport.brierScore >= 0.0)

        val detector = DriftDetector()
        // Older 80 draws all even digits; recent 40 draws all odd digits -> large TV shift
        val shiftedHistory = List(80) { i -> makeResult(i + 1, (i * 2) % 10) } +
            List(40) { i -> makeResult(81 + i, ((i * 2) + 1) % 10) }
        val driftReport = detector.evaluateDrift(shiftedHistory, emptyList<PredictionEntity>())
        assertTrue(driftReport.driftDetected)
        assertTrue(driftReport.recommendedWeightMultiplier < 1.0)
    }

    @Test
    fun `ensemble rejects modules without baseline lift and weights evidence-bearing modules`() {
        val service = ResultClassificationService(
            ClassificationRuleConfig(officialColorRuleConfirmed = true, officialBigSmallRuleConfirmed = true)
        )
        val ensemble = EnsembleEngine(service, ProbabilityCalibrator())

        val baseline = BaselineBenchmark(
            sampleCount = 40,
            uniformBrierScore = 0.90,
            uniformLogLoss = 2.30,
            uniformAccuracy = 0.10,
            historicalFreqBrierScore = 0.89,
            historicalFreqLogLoss = 2.28,
            historicalFreqAccuracy = 0.10,
            smoothedFreqBrierScore = 0.89,
            smoothedFreqLogLoss = 2.28,
            smoothedFreqAccuracy = 0.10
        )

        val weakOut = AnalysisOutput(
            moduleId = "frequency_analysis",
            moduleName = "Frequency Analysis",
            moduleVersion = "1.0.0",
            category = ModelCategory.STATISTICAL,
            status = ModelStatus.READY,
            numberProbabilities = ProbabilityMath.uniformDistribution(),
            colorProbabilities = null,
            sizeProbabilities = null,
            reliabilityAssessment = 0.1,
            sampleSize = 80,
            calculatedAtMillis = 1000L,
            explanation = "No lift",
            validationMetrics = ModelValidationMetrics(
                evaluatedSamples = 40,
                numberAccuracy = 0.10,
                logLoss = 2.31,
                brierScore = 0.90,
                baselineBrierScore = 0.89,
                baselineLogLoss = 2.28,
                outperformsBaseline = false
            )
        )

        val noLiftResult = ensemble.combine(
            issueNumber = "ISSUE_NEXT",
            freshness = DataFreshnessStatus.LIVE,
            dataCutoffIssue = "ISSUE_LAST",
            trainingSnapshot = ModelTrainingSnapshot(
                historySampleSize = 80,
                baselineBenchmark = baseline,
                moduleOutputs = listOf(weakOut)
            )
        )
        assertTrue(noLiftResult is PredictionResult.Unavailable)
        assertEquals("No reliable statistical signal.", (noLiftResult as PredictionResult.Unavailable).headline)

        val strongDistA = (0..9).associateWith { d -> if (d == 3) 0.46 else 0.06 }
        val strongDistB = (0..9).associateWith { d -> if (d == 3) 0.37 else 0.07 }
        val strongOutA = weakOut.copy(
            moduleId = "markov_chain",
            moduleName = "Markov Chain Model",
            numberProbabilities = strongDistA,
            validationMetrics = ModelValidationMetrics(
                evaluatedSamples = 40,
                numberAccuracy = 0.42,
                logLoss = 1.65,
                brierScore = 0.65,
                baselineBrierScore = 0.89,
                baselineLogLoss = 2.28,
                outperformsBaseline = true
            )
        )
        val strongOutB = weakOut.copy(
            moduleId = "transition_matrix",
            moduleName = "Transition Matrix Model",
            numberProbabilities = strongDistB,
            validationMetrics = ModelValidationMetrics(
                evaluatedSamples = 40,
                numberAccuracy = 0.30,
                logLoss = 1.95,
                brierScore = 0.79,
                baselineBrierScore = 0.89,
                baselineLogLoss = 2.28,
                outperformsBaseline = true
            )
        )

        val readyRes = ensemble.combine(
            issueNumber = "ISSUE_NEXT",
            freshness = DataFreshnessStatus.LIVE,
            dataCutoffIssue = "ISSUE_LAST",
            trainingSnapshot = ModelTrainingSnapshot(
                historySampleSize = 80,
                baselineBenchmark = baseline,
                moduleOutputs = listOf(weakOut, strongOutA, strongOutB)
            )
        )
        assertTrue(readyRes is PredictionResult.Ready)
        val ready = readyRes as PredictionResult.Ready
        assertEquals(3, ready.predictedNumber)
        assertEquals(WinGoColor.GREEN, ready.predictedColor)
        assertEquals(2, ready.validContributingCount)
        // Stronger Brier-lift module must receive strictly larger weight than weaker lifting module
        val wA = ready.contributions.first { it.moduleId == "markov_chain" }.assignedWeight
        val wB = ready.contributions.first { it.moduleId == "transition_matrix" }.assignedWeight
        assertTrue("Expected higher-lift module weight $wA > $wB", wA > wB)

        // High inter-model disagreement (> 0.45 TV) must reject signal instead of guessing
        val conflictA = strongOutA.copy(
            numberProbabilities = (0..9).associateWith { d -> if (d == 1) 0.91 else 0.01 }
        )
        val conflictB = strongOutB.copy(
            numberProbabilities = (0..9).associateWith { d -> if (d == 8) 0.91 else 0.01 }
        )
        val conflictRes = ensemble.combine(
            issueNumber = "ISSUE_CONFLICT",
            freshness = DataFreshnessStatus.LIVE,
            dataCutoffIssue = "ISSUE_LAST",
            trainingSnapshot = ModelTrainingSnapshot(
                historySampleSize = 80,
                baselineBenchmark = baseline,
                moduleOutputs = listOf(conflictA, conflictB)
            )
        )
        assertTrue(conflictRes is PredictionResult.Unavailable)
        assertEquals("No reliable statistical signal.", (conflictRes as PredictionResult.Unavailable).headline)
    }
}
