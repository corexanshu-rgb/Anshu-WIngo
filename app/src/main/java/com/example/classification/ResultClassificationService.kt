package com.example.classification

import com.example.data.local.entity.ResultEntity
import com.example.domain.model.WinGoColor
import com.example.domain.model.WinGoSize

data class ClassificationRuleConfig(
    val officialColorRuleConfirmed: Boolean = false,
    val officialBigSmallRuleConfirmed: Boolean = false,
    val minRecordsForEmpiricalColorVerification: Int = 30
)

sealed interface ColorClassification {
    data class Verified(
        val primaryColor: WinGoColor,
        val allColors: Set<WinGoColor>
    ) : ColorClassification

    data class Unavailable(val reason: String) : ColorClassification
}

sealed interface SizeClassification {
    data class Verified(val size: WinGoSize) : SizeClassification
    data class Unavailable(val reason: String) : SizeClassification
}

data class ClassificationVerificationReport(
    val colorVerified: Boolean,
    val bigSmallVerified: Boolean,
    val colorReason: String,
    val bigSmallReason: String,
    val inconsistentRecordCount: Int,
    val observedDigitsCount: Int
)

/**
 * Configurable ResultClassificationService.
 * Never silently assumes a number-to-colour or number-to-Big/Small mapping unless verified
 * against official rule configuration or consistent actual API records.
 */
class ResultClassificationService(
    initialConfig: ClassificationRuleConfig = ClassificationRuleConfig()
) {
    @Volatile
    var config: ClassificationRuleConfig = initialConfig
        private set

    @Volatile
    private var empiricalColorMap: Map<Int, Set<WinGoColor>>? = null

    @Volatile
    private var lastReport: ClassificationVerificationReport = ClassificationVerificationReport(
        colorVerified = initialConfig.officialColorRuleConfirmed,
        bigSmallVerified = initialConfig.officialBigSmallRuleConfirmed,
        colorReason = if (initialConfig.officialColorRuleConfirmed) {
            "Confirmed by official game rules."
        } else {
            "Classification rules not verified."
        },
        bigSmallReason = if (initialConfig.officialBigSmallRuleConfirmed) {
            "Confirmed by official game rules."
        } else {
            "Classification rules not verified."
        },
        inconsistentRecordCount = 0,
        observedDigitsCount = 0
    )

    fun updateConfig(newConfig: ClassificationRuleConfig) {
        config = newConfig
    }

    fun getVerificationReport(): ClassificationVerificationReport = lastReport

    /**
     * Audits actual ResultEntity records to verify whether API color values match a consistent
     * deterministic mapping per digit (0..9) and detects any inconsistent API records.
     */
    fun auditAndVerifyFromResults(results: List<ResultEntity>): ClassificationVerificationReport {
        val observedByDigit = mutableMapOf<Int, MutableSet<Set<WinGoColor>>>()
        var inconsistent = 0

        for (record in results) {
            if (record.number !in 0..9) {
                inconsistent++
                continue
            }
            val parsedColors = parseApiColorTokens(record.color)
            if (parsedColors.isEmpty()) {
                inconsistent++
                continue
            }
            observedByDigit.getOrPut(record.number) { mutableSetOf() }.add(parsedColors)
        }

        val consistentDigits = observedByDigit.filterValues { it.size == 1 }
        val contradictions = observedByDigit.count { it.value.size > 1 }
        inconsistent += contradictions

        val empiricallyVerified = !config.officialColorRuleConfirmed &&
            contradictions == 0 &&
            results.size >= config.minRecordsForEmpiricalColorVerification &&
            consistentDigits.size == 10

        if (empiricallyVerified) {
            empiricalColorMap = consistentDigits.mapValues { it.value.first() }
        } else if (!config.officialColorRuleConfirmed) {
            empiricalColorMap = null
        }

        val colorVerified = config.officialColorRuleConfirmed || empiricallyVerified
        val bigSmallVerified = config.officialBigSmallRuleConfirmed

        val colorReason = when {
            config.officialColorRuleConfirmed -> "Verified by official rule configuration."
            empiricallyVerified -> "Empirically verified with zero contradictions across ${results.size} API records (all 10 digits observed)."
            contradictions > 0 -> "Classification rules not verified: $contradictions contradictory digit-to-color mappings in API data."
            results.isEmpty() -> "Classification rules not verified: no historical API records available."
            else -> "Classification rules not verified: need >=${config.minRecordsForEmpiricalColorVerification} consistent records covering digits 0-9 (observed ${observedByDigit.size}/10 across ${results.size} records)."
        }

        val bigSmallReason = if (bigSmallVerified) {
            "Verified by official Big/Small rule configuration (Big: 5-9, Small: 0-4)."
        } else {
            "Classification rules not verified: GetHistoryIssuePage omits explicit Big/Small field and official confirmation is not set."
        }

        val report = ClassificationVerificationReport(
            colorVerified = colorVerified,
            bigSmallVerified = bigSmallVerified,
            colorReason = colorReason,
            bigSmallReason = bigSmallReason,
            inconsistentRecordCount = inconsistent,
            observedDigitsCount = observedByDigit.size
        )
        lastReport = report
        return report
    }

    fun classifyNumberColor(number: Int): ColorClassification {
        if (number !in 0..9) {
            return ColorClassification.Unavailable("Invalid WinGo number: $number")
        }
        if (config.officialColorRuleConfirmed) {
            val all = officialColorSet(number)
            return ColorClassification.Verified(
                primaryColor = primaryFromSet(all),
                allColors = all
            )
        }
        val empirical = empiricalColorMap
        if (empirical != null && lastReport.colorVerified) {
            val all = empirical[number]
            if (!all.isNullOrEmpty()) {
                return ColorClassification.Verified(
                    primaryColor = primaryFromSet(all),
                    allColors = all
                )
            }
        }
        return ColorClassification.Unavailable(lastReport.colorReason)
    }

    fun classifyRecordColor(record: ResultEntity): ColorClassification {
        val parsed = parseApiColorTokens(record.color)
        if (parsed.isNotEmpty() && record.number in 0..9) {
            return ColorClassification.Verified(
                primaryColor = primaryFromSet(parsed),
                allColors = parsed
            )
        }
        return classifyNumberColor(record.number)
    }

    fun classifySize(number: Int): SizeClassification {
        if (number !in 0..9) {
            return SizeClassification.Unavailable("Invalid WinGo number: $number")
        }
        if (!config.officialBigSmallRuleConfirmed) {
            return SizeClassification.Unavailable("Classification rules not verified.")
        }
        return SizeClassification.Verified(
            if (number >= 5) WinGoSize.BIG else WinGoSize.SMALL
        )
    }

    fun deriveColorDistribution(numberProbabilities: Map<Int, Double>): Map<String, Double>? {
        if (numberProbabilities.size != 10) return null
        val totals = mutableMapOf(
            WinGoColor.RED.label to 0.0,
            WinGoColor.GREEN.label to 0.0,
            WinGoColor.VIOLET.label to 0.0
        )

        for ((number, prob) in numberProbabilities) {
            when (val cls = classifyNumberColor(number)) {
                is ColorClassification.Unavailable -> return null
                is ColorClassification.Verified -> {
                    // Split dual-color numbers (e.g. 0 = Red+Violet, 5 = Green+Violet) proportionally
                    // across their verified color classes so total sums to 1.0
                    val share = prob / cls.allColors.size.toDouble()
                    for (c in cls.allColors) {
                        totals[c.label] = (totals[c.label] ?: 0.0) + share
                    }
                }
            }
        }

        val sum = totals.values.sum()
        if (sum <= 0.0) return null
        return totals.mapValues { (_, v) -> v / sum }
    }

    fun deriveSizeDistribution(numberProbabilities: Map<Int, Double>): Map<String, Double>? {
        if (numberProbabilities.size != 10) return null
        var big = 0.0
        var small = 0.0

        for ((number, prob) in numberProbabilities) {
            when (val cls = classifySize(number)) {
                is SizeClassification.Unavailable -> return null
                is SizeClassification.Verified -> {
                    if (cls.size == WinGoSize.BIG) big += prob else small += prob
                }
            }
        }

        val sum = big + small
        if (sum <= 0.0) return null
        return mapOf(
            WinGoSize.BIG.label to (big / sum),
            WinGoSize.SMALL.label to (small / sum)
        )
    }

    private fun parseApiColorTokens(raw: String): Set<WinGoColor> {
        val lower = raw.lowercase()
        val set = mutableSetOf<WinGoColor>()
        if (lower.contains("red")) set.add(WinGoColor.RED)
        if (lower.contains("green")) set.add(WinGoColor.GREEN)
        if (lower.contains("violet") || lower.contains("purple")) set.add(WinGoColor.VIOLET)
        return set
    }

    private fun officialColorSet(number: Int): Set<WinGoColor> = when (number) {
        0 -> setOf(WinGoColor.RED, WinGoColor.VIOLET)
        5 -> setOf(WinGoColor.GREEN, WinGoColor.VIOLET)
        1, 3, 7, 9 -> setOf(WinGoColor.GREEN)
        2, 4, 6, 8 -> setOf(WinGoColor.RED)
        else -> emptySet()
    }

    private fun primaryFromSet(colors: Set<WinGoColor>): WinGoColor {
        return when {
            colors.contains(WinGoColor.GREEN) -> WinGoColor.GREEN
            colors.contains(WinGoColor.RED) -> WinGoColor.RED
            colors.contains(WinGoColor.VIOLET) -> WinGoColor.VIOLET
            else -> WinGoColor.RED
        }
    }
}
