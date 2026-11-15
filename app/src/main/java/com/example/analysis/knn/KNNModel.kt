package com.example.analysis.knn

import com.example.analysis.AnalysisInput
import com.example.analysis.AnalysisModule
import com.example.analysis.AnalysisOutput
import com.example.analysis.ModelCategory
import com.example.analysis.ModelStatus
import com.example.analysis.ProbabilityMath
import com.example.training.FeatureEngineering
import com.example.training.FeatureScaler
import com.example.training.TrainingDatasetBuilder
import com.example.training.TrainingSample
import com.example.validation.BaselineEvaluator
import kotlin.math.sqrt

/**
 * Module 11: K-Nearest Neighbors (Distance-Weighted Classifier)
 * Uses strictly causal standardized feature vectors, configurable K, inverse-distance
 * voting with Laplace smoothing, and chronological validation.
 */
class KNNModel(
    private val kNeighbors: Int = 15
) : AnalysisModule {
    override val moduleId = "k_nearest_neighbors"
    override val moduleName = "K-Nearest Neighbors"
    override val moduleVersion = "1.0.0"
    override val category = ModelCategory.MACHINE_LEARNING
    override val minimumRequiredSamples = 50

    override suspend fun analyze(input: AnalysisInput): AnalysisOutput {
        val history = input.historyChronological
        val now = System.currentTimeMillis()
        if (history.size < minimumRequiredSamples) {
            return AnalysisOutput(
                moduleId = moduleId,
                moduleName = moduleName,
                moduleVersion = moduleVersion,
                category = category,
                status = ModelStatus.INSUFFICIENT_DATA,
                numberProbabilities = null,
                colorProbabilities = null,
                sizeProbabilities = null,
                reliabilityAssessment = null,
                sampleSize = history.size,
                calculatedAtMillis = now,
                explanation = "Insufficient training data ($minimumRequiredSamples draws required, ${history.size} available)."
            )
        }

        val samples = TrainingDatasetBuilder.buildChronologicalDataset(history)
        val split = TrainingDatasetBuilder.splitChronologically(samples, trainRatio = 0.75)
        if (split.train.size < kNeighbors || split.validation.size < 10) {
            return AnalysisOutput(
                moduleId = moduleId,
                moduleName = moduleName,
                moduleVersion = moduleVersion,
                category = category,
                status = ModelStatus.INSUFFICIENT_DATA,
                numberProbabilities = null,
                colorProbabilities = null,
                sizeProbabilities = null,
                reliabilityAssessment = null,
                sampleSize = history.size,
                calculatedAtMillis = now,
                explanation = "Insufficient neighbor pool (${split.train.size} available, need >= $kNeighbors)."
            )
        }

        val valScaler = FeatureScaler.fit(split.train.map { it.features })
        val scaledTrain = split.train.map { valScaler.transform(it.features) to it.targetNumber }
        val valPredictions = split.validation.map { sample ->
            predictFromScaled(scaledTrain, valScaler.transform(sample.features), kNeighbors) to sample.targetNumber
        }

        val baseline = BaselineEvaluator.evaluateBaselinesChronologically(
            historyChronological = history,
            startEvaluationIndex = history.size - split.validation.size
        )
        val valMetrics = baseline?.let { BaselineEvaluator.buildMetrics(valPredictions, it) }

        val fullScaler = FeatureScaler.fit(samples.map { it.features })
        val scaledFull = samples.map { fullScaler.transform(it.features) to it.targetNumber }
        val nextRaw = FeatureEngineering.extractFeaturesAt(history, history.size)
        val nextProbs = predictFromScaled(scaledFull, fullScaler.transform(nextRaw), kNeighbors)

        val status = when {
            valMetrics == null -> ModelStatus.READY
            valMetrics.outperformsBaseline -> ModelStatus.READY
            else -> ModelStatus.UNRELIABLE
        }

        return AnalysisOutput(
            moduleId = moduleId,
            moduleName = moduleName,
            moduleVersion = moduleVersion,
            category = category,
            status = status,
            numberProbabilities = nextProbs,
            colorProbabilities = input.classificationService.deriveColorDistribution(nextProbs),
            sizeProbabilities = input.classificationService.deriveSizeDistribution(nextProbs),
            reliabilityAssessment = valMetrics?.let { (1.0 - it.brierScore).coerceIn(0.0, 1.0) },
            sampleSize = history.size,
            calculatedAtMillis = now,
            explanation = "Distance-weighted KNN (K=$kNeighbors, standardized ${FeatureEngineering.FEATURE_COUNT}-dim causal features, train=${split.train.size}, val=${split.validation.size}).",
            diagnostics = mapOf(
                "k_neighbors" to kNeighbors.toString(),
                "neighbor_pool" to samples.size.toString()
            ),
            validationMetrics = valMetrics
        )
    }

    internal fun predictFromScaled(
        trainScaled: List<Pair<DoubleArray, Int>>,
        queryScaled: DoubleArray,
        k: Int
    ): Map<Int, Double> {
        if (trainScaled.size < k) return ProbabilityMath.uniformDistribution()

        val nearest = trainScaled
            .map { (vec, label) -> euclideanDistance(vec, queryScaled) to label }
            .sortedBy { it.first }
            .take(k)

        // Weighted votes + mild uniform Dirichlet prior (0.5 per class) to prevent zero probabilities
        val votes = DoubleArray(10) { 0.5 }
        for ((dist, label) in nearest) {
            if (label in 0..9) {
                votes[label] += 1.0 / (dist + 0.1)
            }
        }
        return ProbabilityMath.normalizeArray(votes)
    }

    private fun euclideanDistance(a: DoubleArray, b: DoubleArray): Double {
        var sum = 0.0
        for (i in a.indices) {
            val d = a[i] - b[i]
            sum += d * d
        }
        return sqrt(sum)
    }
}
