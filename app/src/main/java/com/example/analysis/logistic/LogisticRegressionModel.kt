package com.example.analysis.logistic

import com.example.analysis.AnalysisInput
import com.example.analysis.AnalysisModule
import com.example.analysis.AnalysisOutput
import com.example.analysis.ModelCategory
import com.example.analysis.ModelStatus
import com.example.analysis.ProbabilityMath
import com.example.data.local.entity.ResultEntity
import com.example.training.FeatureEngineering
import com.example.training.FeatureScaler
import com.example.training.TrainingDatasetBuilder
import com.example.training.TrainingSample
import com.example.validation.BaselineEvaluator

/**
 * Module 9: Multinomial Logistic Regression (Softmax + L2 Regularization)
 * Pure Kotlin lightweight implementation trained chronologically on causal features.
 */
class LogisticRegressionModel(
    private val learningRate: Double = 0.08,
    private val l2Lambda: Double = 0.05,
    private val epochs: Int = 90
) : AnalysisModule {
    override val moduleId = "logistic_regression"
    override val moduleName = "Logistic Regression"
    override val moduleVersion = "1.0.0"
    override val category = ModelCategory.MACHINE_LEARNING
    override val minimumRequiredSamples = 60

    data class TrainedWeights(
        val weights: Array<DoubleArray>, // [10][featureDim]
        val biases: DoubleArray,         // [10]
        val scaler: FeatureScaler,
        val trainLoss: Double
    )

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
        if (split.train.size < 25 || split.validation.size < 10) {
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
                explanation = "Insufficient training/validation split size (${samples.size} supervised samples)."
            )
        }

        // Chronological validation on held-out tail
        val valModel = train(split.train)
        val valPredictions = split.validation.map { sample ->
            predictProbs(valModel, sample.features) to sample.targetNumber
        }
        val baseline = BaselineEvaluator.evaluateBaselinesChronologically(
            historyChronological = history,
            startEvaluationIndex = history.size - split.validation.size
        )
        val valMetrics = baseline?.let { BaselineEvaluator.buildMetrics(valPredictions, it) }

        // Retrain on full causal history to predict next unseen issue
        val fullModel = train(samples)
        val nextFeatures = FeatureEngineering.extractFeaturesAt(history, history.size)
        val nextProbs = predictProbs(fullModel, nextFeatures)

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
            explanation = "Multinomial L2 Logistic Regression (train=${split.train.size}, val=${split.validation.size}, trainLoss=${"%.3f".format(valModel.trainLoss)}, valBrier=${valMetrics?.brierScore?.let { "%.4f".format(it) } ?: "-"}).",
            diagnostics = mapOf(
                "train_samples" to split.train.size.toString(),
                "val_samples" to split.validation.size.toString(),
                "train_log_loss" to "%.4f".format(fullModel.trainLoss),
                "l2_lambda" to l2Lambda.toString()
            ),
            validationMetrics = valMetrics
        )
    }

    internal fun train(samples: List<TrainingSample>): TrainedWeights {
        val dim = FeatureEngineering.FEATURE_COUNT
        val scaler = FeatureScaler.fit(samples.map { it.features })
        val scaledX = scaler.transformBatch(samples.map { it.features })
        val n = samples.size.toDouble().coerceAtLeast(1.0)

        val weights = Array(10) { DoubleArray(dim) }
        val biases = DoubleArray(10)
        var lastLoss = 0.0

        for (epoch in 0 until epochs) {
            val gradW = Array(10) { DoubleArray(dim) }
            val gradB = DoubleArray(10)
            var totalLoss = 0.0

            for (i in samples.indices) {
                val x = scaledX[i]
                val y = samples[i].targetNumber.coerceIn(0, 9)
                val logits = DoubleArray(10) { c ->
                    var z = biases[c]
                    val wc = weights[c]
                    for (j in 0 until dim) z += wc[j] * x[j]
                    z
                }
                val probs = ProbabilityMath.softmax(logits)
                totalLoss -= kotlin.math.ln(probs[y].coerceIn(1e-9, 1.0))

                for (c in 0..9) {
                    val err = probs[c] - (if (c == y) 1.0 else 0.0)
                    gradB[c] += err
                    val gwc = gradW[c]
                    for (j in 0 until dim) {
                        gwc[j] += err * x[j]
                    }
                }
            }

            for (c in 0..9) {
                biases[c] -= learningRate * (gradB[c] / n)
                val wc = weights[c]
                val gwc = gradW[c]
                for (j in 0 until dim) {
                    val regGrad = (gwc[j] / n) + l2Lambda * wc[j]
                    wc[j] -= learningRate * regGrad
                }
            }
            lastLoss = totalLoss / n
        }

        return TrainedWeights(weights, biases, scaler, lastLoss)
    }

    internal fun predictProbs(model: TrainedWeights, rawFeatures: DoubleArray): Map<Int, Double> {
        val x = model.scaler.transform(rawFeatures)
        val logits = DoubleArray(10) { c ->
            var z = model.biases[c]
            val wc = model.weights[c]
            for (j in x.indices) z += wc[j] * x[j]
            z
        }
        val probs = ProbabilityMath.softmax(logits)
        return ProbabilityMath.normalizeArray(probs)
    }
}
