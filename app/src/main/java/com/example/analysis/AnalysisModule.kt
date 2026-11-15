package com.example.analysis

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max

interface AnalysisModule {
    val moduleId: String
    val moduleName: String
    val moduleVersion: String
    val category: ModelCategory
    val minimumRequiredSamples: Int

    suspend fun analyze(input: AnalysisInput): AnalysisOutput
}

object ProbabilityMath {

    fun uniformDistribution(): Map<Int, Double> = (0..9).associateWith { 0.1 }

    fun normalizeArray(raw: DoubleArray): Map<Int, Double> {
        require(raw.size == 10) { "WinGo number distribution must have 10 classes (0..9)" }
        val sanitized = DoubleArray(10) { i ->
            val v = raw[i]
            if (v.isNaN() || v.isInfinite() || v < 0.0) 0.0 else v
        }
        val sum = sanitized.sum()
        if (sum <= 0.0) return uniformDistribution()
        return (0..9).associateWith { i -> sanitized[i] / sum }
    }

    fun normalizeMap(raw: Map<Int, Double>): Map<Int, Double> {
        val arr = DoubleArray(10) { i -> raw[i] ?: 0.0 }
        return normalizeArray(arr)
    }

    fun softmax(logits: DoubleArray): DoubleArray {
        val maxLogit = logits.maxOrNull() ?: 0.0
        val exps = DoubleArray(logits.size) { i -> exp((logits[i] - maxLogit).coerceIn(-50.0, 50.0)) }
        val sum = exps.sum().coerceAtLeast(1e-12)
        return DoubleArray(logits.size) { i -> exps[i] / sum }
    }

    fun shannonEntropyBits(probabilities: Map<Int, Double>): Double {
        var h = 0.0
        for (p in probabilities.values) {
            if (p > 1e-12) {
                h -= p * (ln(p) / ln(2.0))
            }
        }
        return h.coerceAtLeast(0.0)
    }

    fun normalizedEntropy(probabilities: Map<Int, Double>): Double {
        val maxBits = ln(10.0) / ln(2.0)
        return (shannonEntropyBits(probabilities) / maxBits).coerceIn(0.0, 1.0)
    }

    fun multiClassLogLoss(probs: Map<Int, Double>, actualNumber: Int): Double {
        val p = (probs[actualNumber] ?: 0.0).coerceIn(1e-6, 1.0)
        return -ln(p)
    }

    fun multiClassBrierScore(probs: Map<Int, Double>, actualNumber: Int): Double {
        var sumSq = 0.0
        for (k in 0..9) {
            val y = if (k == actualNumber) 1.0 else 0.0
            val p = (probs[k] ?: 0.0).coerceIn(0.0, 1.0)
            val diff = p - y
            sumSq += diff * diff
        }
        return sumSq
    }

    fun isValidSimplex(probs: Map<Int, Double>?): Boolean {
        if (probs == null || probs.size != 10) return false
        for (k in 0..9) {
            val v = probs[k] ?: return false
            if (v.isNaN() || v.isInfinite() || v < 0.0 || v > 1.0) return false
        }
        val sum = probs.values.sum()
        return kotlin.math.abs(sum - 1.0) < 1e-4
    }

    fun totalVariationDistance(p: Map<Int, Double>, q: Map<Int, Double>): Double {
        var sum = 0.0
        for (k in 0..9) {
            sum += kotlin.math.abs((p[k] ?: 0.0) - (q[k] ?: 0.0))
        }
        return 0.5 * sum
    }

    fun clampProbability(v: Double): Double = max(0.0, kotlin.math.min(1.0, v))
}
