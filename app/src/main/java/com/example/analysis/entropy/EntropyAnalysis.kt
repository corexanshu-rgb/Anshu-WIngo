package com.example.analysis.entropy

import com.example.analysis.AnalysisInput
import com.example.analysis.AnalysisModule
import com.example.analysis.AnalysisOutput
import com.example.analysis.ModelCategory
import com.example.analysis.ModelStatus
import com.example.analysis.ProbabilityMath
import com.example.data.local.entity.ResultEntity

/**
 * Module 5: Entropy Analysis
 * Computes Shannon entropy, normalized entropy, Herfindahl concentration index,
 * and window-wise entropy deltas to assess pattern informativeness and uncertainty.
 * High/low entropy never becomes a fabricated directional prediction.
 */
class EntropyAnalysis : AnalysisModule {
    override val moduleId = "entropy_analysis"
    override val moduleName = "Entropy Analysis"
    override val moduleVersion = "1.0.0"
    override val category = ModelCategory.STATISTICAL
    override val minimumRequiredSamples = 20

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
                explanation = "Requires at least $minimumRequiredSamples verified draws (${history.size} available)."
            )
        }

        val fullDist = smoothedEmpirical(history)
        val hBits = ProbabilityMath.shannonEntropyBits(fullDist)
        val hNorm = ProbabilityMath.normalizedEntropy(fullDist)
        val concentrationHhi = fullDist.values.sumOf { it * it }

        val dist20 = smoothedEmpirical(history.takeLast(20))
        val dist100 = smoothedEmpirical(history.takeLast(100))
        val h20Norm = ProbabilityMath.normalizedEntropy(dist20)
        val h100Norm = ProbabilityMath.normalizedEntropy(dist100)
        val entropyDelta = h20Norm - h100Norm

        // Uncertainty-tempered distribution: near-uniform when entropy is high (near random)
        val informativeness = (1.0 - hNorm).coerceIn(0.0, 1.0)

        return AnalysisOutput(
            moduleId = moduleId,
            moduleName = moduleName,
            moduleVersion = moduleVersion,
            category = category,
            status = ModelStatus.READY,
            numberProbabilities = fullDist,
            colorProbabilities = input.classificationService.deriveColorDistribution(fullDist),
            sizeProbabilities = input.classificationService.deriveSizeDistribution(fullDist),
            reliabilityAssessment = informativeness,
            sampleSize = history.size,
            calculatedAtMillis = now,
            explanation = "Shannon entropy ${"%.3f".format(hBits)} bits (normalized ${"%.3f".format(hNorm)}; HHI=${"%.4f".format(concentrationHhi)}). Entropy measures randomness/concentration rather than predicting a specific digit.",
            diagnostics = mapOf(
                "shannon_entropy_bits" to "%.4f".format(hBits),
                "normalized_entropy" to "%.4f".format(hNorm),
                "hhi_concentration" to "%.4f".format(concentrationHhi),
                "entropy_delta_20_100" to "%.4f".format(entropyDelta)
            ),
            validationMetrics = null
        )
    }

    internal fun smoothedEmpirical(slice: List<ResultEntity>): Map<Int, Double> {
        val counts = IntArray(10)
        slice.forEach { if (it.number in 0..9) counts[it.number]++ }
        val alpha = 1.0
        val denom = slice.size.toDouble() + 10.0 * alpha
        return ProbabilityMath.normalizeArray(DoubleArray(10) { d -> (counts[d] + alpha) / denom })
    }
}
