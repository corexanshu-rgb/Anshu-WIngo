package com.example.analysis

enum class ModelCategory(val label: String) {
    STATISTICAL("Statistical"),
    MACHINE_LEARNING("Machine Learning")
}

enum class ModelStatus(val label: String) {
    NOT_INITIALIZED("Not initialized"),
    INSUFFICIENT_DATA("Insufficient data"),
    TRAINING("Training"),
    READY("Ready"),
    VALIDATING("Validating"),
    UNRELIABLE("Unreliable"),
    DISABLED("Disabled"),
    ERROR("Error")
}

data class ModelValidationMetrics(
    val evaluatedSamples: Int,
    val numberAccuracy: Double,
    val logLoss: Double,
    val brierScore: Double,
    val baselineBrierScore: Double,
    val baselineLogLoss: Double,
    val outperformsBaseline: Boolean
)

data class AnalysisOutput(
    val moduleId: String,
    val moduleName: String,
    val moduleVersion: String,
    val category: ModelCategory,
    val status: ModelStatus,
    val numberProbabilities: Map<Int, Double>?,
    val colorProbabilities: Map<String, Double>?,
    val sizeProbabilities: Map<String, Double>?,
    val reliabilityAssessment: Double?,
    val sampleSize: Int,
    val calculatedAtMillis: Long,
    val explanation: String,
    val diagnostics: Map<String, String> = emptyMap(),
    val validationMetrics: ModelValidationMetrics? = null
)
