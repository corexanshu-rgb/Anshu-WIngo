package com.example.analysis.svm

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
import kotlin.math.exp
import kotlin.math.max

/**
 * Module 12: Multiclass One-vs-Rest Linear SVM with Platt Scaling Calibration
 *
 * Trains 10 regularized linear hinge-loss classifiers (One-vs-Rest) on standardized causal features.
 * Raw SVM decision scores are NEVER reported as probabilities; probabilities are computed via
 * 1D Platt logistic calibration fitted strictly on held-out chronological validation margins.
 * If insufficient validation samples exist for Platt calibration, probability output is marked unavailable.
 */
class SVMModel(
    private val cRegularization: Double = 1.0,
    private val epochs: Int = 80
) : AnalysisModule {
    override val moduleId = "support_vector_machine"
    override val moduleName = "Support Vector Machine"
    override val moduleVersion = "1.0.0"
    override val category = ModelCategory.MACHINE_LEARNING
    override val minimumRequiredSamples = 80

    data class LinearBinarySvm(
        val weights: DoubleArray,
        val bias: Double
    ) {
        fun margin(scaledX: DoubleArray): Double {
            var s = bias
            for (i in weights.indices) s += weights[i] * scaledX[i]
            return s
        }
    }

    data class PlattCalibrator(
        val slopeA: Double,
        val interceptB: Double
    ) {
        fun probability(margin: Double): Double {
            val z = (slopeA * margin + interceptB).coerceIn(-30.0, 30.0)
            return 1.0 / (1.0 + exp(z))
        }
    }

    data class TrainedMulticlassSvm(
        val classifiers: List<LinearBinarySvm>,
        val plattCalibrators: List<PlattCalibrator>?,
        val scaler: FeatureScaler
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
        // Use 60% train, 20% Platt calibration, 20% out-of-sample evaluation (strictly chronological)
        val trainEnd = (samples.size * 0.60).toInt()
        val calEnd = (samples.size * 0.80).toInt()

        val trainSlice = samples.subList(0, trainEnd)
        val calSlice = samples.subList(trainEnd, calEnd)
        val valSlice = samples.subList(calEnd, samples.size)

        if (trainSlice.size < 30 || calSlice.size < MIN_PLATT_SAMPLES || valSlice.size < 10) {
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
                explanation = "Platt probability calibration unavailable: need >= $MIN_PLATT_SAMPLES calibration samples (raw SVM margins are not reported as probabilities)."
            )
        }

        val evalModel = trainWithCalibration(trainSlice, calSlice)
        val calValid = evalModel.plattCalibrators != null
        if (!calValid) {
            return AnalysisOutput(
                moduleId = moduleId,
                moduleName = moduleName,
                moduleVersion = moduleVersion,
                category = category,
                status = ModelStatus.UNRELIABLE,
                numberProbabilities = null,
                colorProbabilities = null,
                sizeProbabilities = null,
                reliabilityAssessment = null,
                sampleSize = history.size,
                calculatedAtMillis = now,
                explanation = "Platt calibration did not converge on validation margins; probability output marked unavailable."
            )
        }

        val valPredictions = valSlice.mapNotNull { s ->
            predictCalibratedProbs(evalModel, s.features)?.let { it to s.targetNumber }
        }
        val baseline = BaselineEvaluator.evaluateBaselinesChronologically(
            historyChronological = history,
            startEvaluationIndex = history.size - valSlice.size
        )
        val valMetrics = baseline?.let { BaselineEvaluator.buildMetrics(valPredictions, it) }

        // Final inference model trained on [0..80%], calibrated on latest [80%..100%]
        val finalTrain = samples.subList(0, calEnd)
        val finalCal = samples.subList(calEnd, samples.size)
        val finalModel = trainWithCalibration(finalTrain, finalCal)
        val nextFeatures = FeatureEngineering.extractFeaturesAt(history, history.size)
        val nextProbs = predictCalibratedProbs(finalModel, nextFeatures)

        if (nextProbs == null) {
            return AnalysisOutput(
                moduleId = moduleId,
                moduleName = moduleName,
                moduleVersion = moduleVersion,
                category = category,
                status = ModelStatus.UNRELIABLE,
                numberProbabilities = null,
                colorProbabilities = null,
                sizeProbabilities = null,
                reliabilityAssessment = null,
                sampleSize = history.size,
                calculatedAtMillis = now,
                explanation = "Probability calibration unavailable for final inference model."
            )
        }

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
            explanation = "One-vs-Rest Linear SVM + Platt scaling calibration (train=${trainSlice.size}, cal=${calSlice.size}, val=${valSlice.size}).",
            diagnostics = mapOf(
                "strategy" to "One-vs-Rest + Platt Scaling",
                "c_reg" to cRegularization.toString(),
                "calibration_samples" to finalCal.size.toString()
            ),
            validationMetrics = valMetrics
        )
    }

    internal fun trainWithCalibration(
        trainSamples: List<TrainingSample>,
        calibrationSamples: List<TrainingSample>
    ): TrainedMulticlassSvm {
        val scaler = FeatureScaler.fit(trainSamples.map { it.features })
        val scaledTrain = trainSamples.map { scaler.transform(it.features) to it.targetNumber }
        val scaledCal = calibrationSamples.map { scaler.transform(it.features) to it.targetNumber }

        val classifiers = (0..9).map { targetClass ->
            trainBinaryLinearSvm(scaledTrain, targetClass)
        }

        val calibrators = if (scaledCal.size >= MIN_PLATT_SAMPLES) {
            (0..9).map { targetClass ->
                val clf = classifiers[targetClass]
                val pairs = scaledCal.map { (x, y) ->
                    clf.margin(x) to (if (y == targetClass) 1.0 else 0.0)
                }
                fitPlattCalibrator(pairs)
            }
        } else {
            null
        }

        return TrainedMulticlassSvm(classifiers, calibrators, scaler)
    }

    internal fun predictCalibratedProbs(
        model: TrainedMulticlassSvm,
        rawFeatures: DoubleArray
    ): Map<Int, Double>? {
        val calibrators = model.plattCalibrators ?: return null
        val x = model.scaler.transform(rawFeatures)
        val rawProbs = DoubleArray(10) { c ->
            val margin = model.classifiers[c].margin(x)
            calibrators[c].probability(margin)
        }
        return ProbabilityMath.normalizeArray(rawProbs)
    }

    private fun trainBinaryLinearSvm(
        scaledSamples: List<Pair<DoubleArray, Int>>,
        positiveClass: Int
    ): LinearBinarySvm {
        val dim = FeatureEngineering.FEATURE_COUNT
        val w = DoubleArray(dim)
        var b = 0.0
        val lambda = 1.0 / (cRegularization * scaledSamples.size.toDouble().coerceAtLeast(1.0))
        val baseLr = 0.05

        for (epoch in 1..epochs) {
            val lr = baseLr / (1.0 + 0.05 * epoch)
            for ((x, label) in scaledSamples) {
                val y = if (label == positiveClass) 1.0 else -1.0
                var dot = b
                for (j in 0 until dim) dot += w[j] * x[j]

                if (y * dot < 1.0) {
                    for (j in 0 until dim) {
                        w[j] = (1.0 - lr * lambda) * w[j] + lr * y * x[j]
                    }
                    b += lr * y
                } else {
                    for (j in 0 until dim) {
                        w[j] = (1.0 - lr * lambda) * w[j]
                    }
                }
            }
        }
        return LinearBinarySvm(w, b)
    }

    private fun fitPlattCalibrator(marginsAndLabels: List<Pair<Double, Double>>): PlattCalibrator {
        // Minimize regularized binary cross-entropy over P(y=1|f) = 1 / (1 + exp(A*f + B))
        val posCount = marginsAndLabels.count { it.second > 0.5 }.toDouble()
        val negCount = marginsAndLabels.size - posCount
        val tPlus = (posCount + 1.0) / (posCount + 2.0)
        val tMinus = 1.0 / (negCount + 2.0)

        var a = -1.0
        var b = 0.0
        val lr = 0.05

        for (iter in 0 until 100) {
            var gradA = 0.0
            var gradB = 0.0
            for ((f, y) in marginsAndLabels) {
                val target = if (y > 0.5) tPlus else tMinus
                val z = (a * f + b).coerceIn(-25.0, 25.0)
                val p = 1.0 / (1.0 + exp(z))
                // d(-[t*ln(p) + (1-t)*ln(1-p)])/dz = p - t
                val diff = p - target
                gradA += diff * f
                gradB += diff
            }
            val n = marginsAndLabels.size.toDouble().coerceAtLeast(1.0)
            a -= lr * ((gradA / n) + 0.01 * a)
            b -= lr * (gradB / n)
        }
        return PlattCalibrator(a, b)
    }

    private companion object {
        const val MIN_PLATT_SAMPLES = 15
    }
}
