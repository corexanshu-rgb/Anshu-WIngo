package com.example.training

import com.example.data.local.entity.ResultEntity

data class TrainingSample(
    val issueNumber: String,
    val features: DoubleArray,
    val targetNumber: Int,
    val timestampMillis: Long
)

data class ChronologicalSplit(
    val train: List<TrainingSample>,
    val validation: List<TrainingSample>
)

/**
 * Builds chronologically ordered supervised datasets from validated actual_results records.
 * Never shuffles time-series records and never uses incomplete/invalid draws.
 */
object TrainingDatasetBuilder {

    fun buildChronologicalDataset(
        historyChronological: List<ResultEntity>
    ): List<TrainingSample> {
        val valid = historyChronological
            .filter { it.number in 0..9 && it.issueNumber.isNotBlank() }
            .sortedBy { it.issueNumber }

        if (valid.size <= FeatureEngineering.MIN_LOOKBACK) return emptyList()

        val samples = ArrayList<TrainingSample>(valid.size - FeatureEngineering.MIN_LOOKBACK)
        for (idx in FeatureEngineering.MIN_LOOKBACK until valid.size) {
            val target = valid[idx]
            val features = FeatureEngineering.extractFeaturesAt(valid, idx)
            samples.add(
                TrainingSample(
                    issueNumber = target.issueNumber,
                    features = features,
                    targetNumber = target.number,
                    timestampMillis = target.receivedAt
                )
            )
        }
        return samples
    }

    fun splitChronologically(
        samples: List<TrainingSample>,
        trainRatio: Double = 0.75
    ): ChronologicalSplit {
        if (samples.isEmpty()) return ChronologicalSplit(emptyList(), emptyList())
        val splitIndex = (samples.size * trainRatio).toInt().coerceIn(1, (samples.size - 1).coerceAtLeast(1))
        if (samples.size < 2) {
            return ChronologicalSplit(samples, emptyList())
        }
        return ChronologicalSplit(
            train = samples.subList(0, splitIndex),
            validation = samples.subList(splitIndex, samples.size)
        )
    }
}
