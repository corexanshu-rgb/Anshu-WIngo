package com.example.analysis.randomforest

import com.example.analysis.AnalysisInput
import com.example.analysis.AnalysisModule
import com.example.analysis.AnalysisOutput
import com.example.analysis.ModelCategory
import com.example.analysis.ModelStatus
import com.example.analysis.ProbabilityMath
import com.example.training.FeatureEngineering
import com.example.training.TrainingDatasetBuilder
import com.example.training.TrainingSample
import com.example.validation.BaselineEvaluator
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Module 10: Random Forest Classifier
 * Lightweight CART decision-tree ensemble with bootstrap sampling, sqrt(d) feature subsampling,
 * controlled maxDepth and minSamplesLeaf to prevent overfitting on <= 500 draws.
 */
class RandomForestModel(
    private val numTrees: Int = 25,
    private val maxDepth: Int = 4,
    private val minSamplesLeaf: Int = 6,
    private val seed: Long = 42L
) : AnalysisModule {
    override val moduleId = "random_forest"
    override val moduleName = "Random Forest"
    override val moduleVersion = "1.0.0"
    override val category = ModelCategory.MACHINE_LEARNING
    override val minimumRequiredSamples = 70

    sealed interface TreeNode {
        data class Leaf(val probabilities: DoubleArray) : TreeNode
        data class Split(
            val featureIndex: Int,
            val threshold: Double,
            val left: TreeNode,
            val right: TreeNode
        ) : TreeNode
    }

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
        if (split.train.size < 30 || split.validation.size < 10) {
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
                explanation = "Insufficient chronological split for Random Forest validation."
            )
        }

        val valForest = trainForest(split.train)
        val valPredictions = split.validation.map { sample ->
            predictForest(valForest, sample.features) to sample.targetNumber
        }
        val baseline = BaselineEvaluator.evaluateBaselinesChronologically(
            historyChronological = history,
            startEvaluationIndex = history.size - split.validation.size
        )
        val valMetrics = baseline?.let { BaselineEvaluator.buildMetrics(valPredictions, it) }

        val fullForest = trainForest(samples)
        val nextFeatures = FeatureEngineering.extractFeaturesAt(history, history.size)
        val nextProbs = predictForest(fullForest, nextFeatures)

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
            explanation = "Random Forest ($numTrees CART trees, maxDepth=$maxDepth, minLeaf=$minSamplesLeaf, train=${split.train.size}, val=${split.validation.size}).",
            diagnostics = mapOf(
                "trees" to numTrees.toString(),
                "max_depth" to maxDepth.toString(),
                "min_samples_leaf" to minSamplesLeaf.toString(),
                "val_samples" to split.validation.size.toString()
            ),
            validationMetrics = valMetrics
        )
    }

    internal fun trainForest(samples: List<TrainingSample>): List<TreeNode> {
        val rng = Random(seed)
        val featureSubsampleSize = sqrt(FeatureEngineering.FEATURE_COUNT.toDouble()).toInt().coerceAtLeast(3)
        return List(numTrees) {
            val bootstrap = List(samples.size) { samples[rng.nextInt(samples.size)] }
            buildTree(bootstrap, depth = 0, featureSubsampleSize = featureSubsampleSize, rng = rng)
        }
    }

    internal fun predictForest(forest: List<TreeNode>, features: DoubleArray): Map<Int, Double> {
        val avg = DoubleArray(10)
        for (tree in forest) {
            val leaf = predictTree(tree, features)
            for (c in 0..9) avg[c] += leaf[c]
        }
        val denom = forest.size.toDouble().coerceAtLeast(1.0)
        for (c in 0..9) avg[c] /= denom
        return ProbabilityMath.normalizeArray(avg)
    }

    private fun buildTree(
        samples: List<TrainingSample>,
        depth: Int,
        featureSubsampleSize: Int,
        rng: Random
    ): TreeNode {
        if (depth >= maxDepth || samples.size < minSamplesLeaf * 2 || isPure(samples)) {
            return makeLeaf(samples)
        }

        val featureIndices = (0 until FeatureEngineering.FEATURE_COUNT).shuffled(rng).take(featureSubsampleSize)
        var bestFeature = -1
        var bestThreshold = 0.0
        var bestGini = Double.POSITIVE_INFINITY
        var bestLeft: List<TrainingSample> = emptyList()
        var bestRight: List<TrainingSample> = emptyList()

        for (f in featureIndices) {
            val values = samples.map { it.features[f] }.distinct().sorted()
            if (values.size < 2) continue

            // Evaluate candidate quantiles to keep runtime fast on device
            val step = (values.size / 6).coerceAtLeast(1)
            for (idx in 0 until values.size - 1 step step) {
                val threshold = 0.5 * (values[idx] + values[idx + 1])
                val left = ArrayList<TrainingSample>()
                val right = ArrayList<TrainingSample>()
                for (s in samples) {
                    if (s.features[f] <= threshold) left.add(s) else right.add(s)
                }
                if (left.size < minSamplesLeaf || right.size < minSamplesLeaf) continue

                val weightedGini =
                    (left.size.toDouble() / samples.size) * gini(left) +
                        (right.size.toDouble() / samples.size) * gini(right)

                if (weightedGini < bestGini) {
                    bestGini = weightedGini
                    bestFeature = f
                    bestThreshold = threshold
                    bestLeft = left
                    bestRight = right
                }
            }
        }

        if (bestFeature == -1) return makeLeaf(samples)

        return TreeNode.Split(
            featureIndex = bestFeature,
            threshold = bestThreshold,
            left = buildTree(bestLeft, depth + 1, featureSubsampleSize, rng),
            right = buildTree(bestRight, depth + 1, featureSubsampleSize, rng)
        )
    }

    private fun predictTree(node: TreeNode, features: DoubleArray): DoubleArray = when (node) {
        is TreeNode.Leaf -> node.probabilities
        is TreeNode.Split -> if (features[node.featureIndex] <= node.threshold) {
            predictTree(node.left, features)
        } else {
            predictTree(node.right, features)
        }
    }

    private fun makeLeaf(samples: List<TrainingSample>): TreeNode.Leaf {
        val counts = IntArray(10)
        samples.forEach { if (it.targetNumber in 0..9) counts[it.targetNumber]++ }
        val alpha = 1.0
        val denom = samples.size.toDouble() + 10.0 * alpha
        return TreeNode.Leaf(DoubleArray(10) { c -> (counts[c] + alpha) / denom })
    }

    private fun gini(samples: List<TrainingSample>): Double {
        if (samples.isEmpty()) return 0.0
        val counts = IntArray(10)
        samples.forEach { if (it.targetNumber in 0..9) counts[it.targetNumber]++ }
        val n = samples.size.toDouble()
        var sumSq = 0.0
        for (c in 0..9) {
            val p = counts[c] / n
            sumSq += p * p
        }
        return 1.0 - sumSq
    }

    private fun isPure(samples: List<TrainingSample>): Boolean {
        val first = samples.firstOrNull()?.targetNumber ?: return true
        return samples.all { it.targetNumber == first }
    }
}
