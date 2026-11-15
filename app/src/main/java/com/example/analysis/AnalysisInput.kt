package com.example.analysis

import com.example.classification.ResultClassificationService
import com.example.data.local.entity.ResultEntity
import com.example.domain.model.DataFreshnessStatus

data class AnalysisInput(
    /**
     * Valid historical results sorted strictly in chronological order (oldest -> newest).
     * Never contains target issue outcomes or fabricated draws.
     */
    val historyChronological: List<ResultEntity>,
    val targetIssueNumber: String?,
    val configuredWindows: List<Int> = listOf(20, 50, 100, 200, 500),
    val classificationService: ResultClassificationService,
    val dataFreshness: DataFreshnessStatus,
    val cutoffTimestampMillis: Long = System.currentTimeMillis()
)
