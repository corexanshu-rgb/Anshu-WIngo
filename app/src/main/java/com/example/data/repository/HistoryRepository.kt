package com.example.data.repository

import com.example.data.local.dao.ResultDao
import com.example.data.local.entity.ResultEntity
import com.example.domain.model.WinGoColor
import com.example.domain.model.WinGoGameConfig
import com.example.domain.model.WinGoSize
import com.example.network.HistoryDataSource
import com.example.network.HistoryFetchOutcome
import com.example.network.HistorySyncDiagnostics
import com.example.network.LiveHistoryPoller
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

data class NumberGapStat(
    val number: Int,
    val count: Int,
    val currentGapDraws: Int?
)

data class RecentDrawPattern(
    val latestIssue: String?,
    val streakLabel: String,
    val streakLength: Int,
    val recentNumbers: List<Int>
)

data class HistoricalAnalysisSummary(
    val sampleCount: Int,
    val numberStats: List<NumberGapStat>,
    val colorCounts: Map<WinGoColor, Int>,
    val sizeCounts: Map<WinGoSize, Int>,
    val recentPattern: RecentDrawPattern?
) {
    companion object {
        val Empty = HistoricalAnalysisSummary(
            sampleCount = 0,
            numberStats = emptyList(),
            colorCounts = emptyMap(),
            sizeCounts = emptyMap(),
            recentPattern = null
        )
    }
}

class HistoryRepository(
    private val resultDao: ResultDao,
    private val historyCollector: HistoryCollector,
    private val historyDataSource: HistoryDataSource? = null,
    private val liveHistoryPoller: LiveHistoryPoller? = null
) {
    val historyDiagnostics: StateFlow<HistorySyncDiagnostics>?
        get() = historyDataSource?.diagnostics

    fun observeLatest500Results(): Flow<List<ResultEntity>> =
        resultDao.observeLatestResults(WinGoGameConfig.MAX_HISTORY_RESULTS)

    fun observeResultCount(): Flow<Int> = resultDao.observeResultCount()

    suspend fun getLatestResultsSnapshot(): List<ResultEntity> =
        resultDao.getLatestResultsSnapshot(WinGoGameConfig.MAX_HISTORY_RESULTS)

    suspend fun syncLatest500(): HistorySyncResult = historyCollector.collectLatest500()

    suspend fun syncLiveHistoryOnce(): HistoryFetchOutcome? = liveHistoryPoller?.syncOnce()

    /**
     * Computes descriptive historical statistics (frequencies, gaps, recent patterns) strictly
     * from actual cached ResultEntity rows. Never fabricates data and never labels past
     * frequencies as future probabilities.
     */
    fun computeHistoricalAnalysis(resultsNewestFirst: List<ResultEntity>): HistoricalAnalysisSummary {
        if (resultsNewestFirst.isEmpty()) return HistoricalAnalysisSummary.Empty

        val counts = IntArray(10)
        val firstSeenIndex = IntArray(10) { -1 }

        resultsNewestFirst.forEachIndexed { idx, row ->
            if (row.number in 0..9) {
                counts[row.number]++
                if (firstSeenIndex[row.number] == -1) {
                    firstSeenIndex[row.number] = idx
                }
            }
        }

        val numberStats = (0..9).map { num ->
            NumberGapStat(
                number = num,
                count = counts[num],
                currentGapDraws = if (firstSeenIndex[num] >= 0) firstSeenIndex[num] else null
            )
        }

        val colorCounts = WinGoColor.entries.associateWith { color ->
            resultsNewestFirst.count { WinGoColor.parsePrimary(it.color, it.number) == color }
        }
        val sizeCounts = WinGoSize.entries.associateWith { size ->
            resultsNewestFirst.count { WinGoSize.fromNumber(it.number) == size }
        }

        val firstSize = WinGoSize.fromNumber(resultsNewestFirst.first().number)
        var streak = 0
        for (row in resultsNewestFirst) {
            if (WinGoSize.fromNumber(row.number) == firstSize) streak++ else break
        }

        val pattern = RecentDrawPattern(
            latestIssue = resultsNewestFirst.first().issueNumber,
            streakLabel = "${firstSize.label} streak",
            streakLength = streak,
            recentNumbers = resultsNewestFirst.take(10).map { it.number }
        )

        return HistoricalAnalysisSummary(
            sampleCount = resultsNewestFirst.size,
            numberStats = numberStats,
            colorCounts = colorCounts,
            sizeCounts = sizeCounts,
            recentPattern = pattern
        )
    }
}
