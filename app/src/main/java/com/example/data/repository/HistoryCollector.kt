package com.example.data.repository

import com.example.data.local.dao.ResultDao
import com.example.data.local.entity.ResultEntity
import com.example.domain.model.WinGoGameConfig
import com.example.network.ApiCallOutcome
import com.example.network.ApiRequestManager
import com.example.network.HistoryRecordDto
import com.example.network.NetworkConnectivityMonitor
import kotlinx.coroutines.delay
import kotlin.math.min

sealed interface HistorySyncResult {
    data class Success(
        val collectedUniqueCount: Int,
        val pagesFetched: Int,
        val totalRemoteCount: Int
    ) : HistorySyncResult

    data class AuthUnavailable(val reason: String) : HistorySyncResult
    data class SessionExpired(val reason: String) : HistorySyncResult
    data class NetworkError(val reason: String) : HistorySyncResult
    data class ValidationError(val reason: String) : HistorySyncResult
}

/**
 * Collects up to 500 unique WinGo_1M historical results across paginated GetHistoryIssuePage calls.
 * Reconciles moving/overlapping pages by issueNumber, stops early once incremental sync hits the
 * already-cached 500-draw window, retries transient errors with backoff, and stops immediately
 * when authentication/signature details are missing or rejected.
 */
class HistoryCollector(
    private val apiRequestManager: ApiRequestManager,
    private val resultDao: ResultDao,
    private val networkMonitor: NetworkConnectivityMonitor,
    private val onNewResultsStored: suspend (List<ResultEntity>) -> Unit = {}
) {

    suspend fun collectLatest500(): HistorySyncResult {
        if (!networkMonitor.isOnline()) {
            return HistorySyncResult.NetworkError("Device is offline. Displaying cached local history.")
        }

        val cachedIssues = resultDao.getLatestIssueNumbers(WinGoGameConfig.MAX_HISTORY_RESULTS).toHashSet()
        val hasFullCacheWindow = cachedIssues.size >= WinGoGameConfig.MAX_HISTORY_RESULTS

        val collectedByIssue = LinkedHashMap<String, ResultEntity>()
        var pageNo = 1
        var totalPages = 1
        var totalRemoteCount = 0
        var pagesFetched = 0
        var consecutiveNoNewPages = 0

        while (pageNo <= totalPages && collectedByIssue.size < WinGoGameConfig.MAX_HISTORY_RESULTS) {
            when (val outcome = fetchPageWithRetry(pageNo)) {
                is ApiCallOutcome.MissingAuth -> return HistorySyncResult.AuthUnavailable(outcome.reason)
                is ApiCallOutcome.SessionExpired -> return HistorySyncResult.SessionExpired(outcome.reason)
                is ApiCallOutcome.NetworkError -> return HistorySyncResult.NetworkError(outcome.message)
                is ApiCallOutcome.HttpError -> return HistorySyncResult.NetworkError(outcome.message)
                is ApiCallOutcome.InvalidResponse -> return HistorySyncResult.ValidationError(outcome.message)
                is ApiCallOutcome.Success -> {
                    val data = outcome.body.data
                        ?: return HistorySyncResult.ValidationError("Missing data payload on page $pageNo")

                    totalPages = min(data.totalPage.coerceAtLeast(1), MAX_PAGES_TO_SCAN)
                    totalRemoteCount = data.totalCount
                    pagesFetched++

                    if (data.list.isEmpty()) break

                    val now = System.currentTimeMillis()
                    var newInBatch = 0
                    var validCountInPage = 0
                    var unseenByCacheCount = 0
                    for (dto in data.list) {
                        val mapped = validateAndMapRecord(dto, now) ?: continue
                        validCountInPage++
                        if (!cachedIssues.contains(mapped.issueNumber)) {
                            unseenByCacheCount++
                        }
                        if (!collectedByIssue.containsKey(mapped.issueNumber)) {
                            collectedByIssue[mapped.issueNumber] = mapped
                            newInBatch++
                        }
                    }

                    // Incremental early-stop: when we already have a full 500-item cache and every
                    // valid item on this page is already cached, older pages are already present.
                    if (hasFullCacheWindow && validCountInPage > 0 && unseenByCacheCount == 0) {
                        break
                    }

                    if (newInBatch == 0) {
                        consecutiveNoNewPages++
                        if (consecutiveNoNewPages >= MAX_CONSECUTIVE_EMPTY_PAGES) break
                    } else {
                        consecutiveNoNewPages = 0
                    }

                    if (pageNo >= totalPages) break
                    pageNo++
                }
            }
        }

        val orderedUnique = collectedByIssue.values
            .sortedWith { a, b -> compareIssuesDescending(a.issueNumber, b.issueNumber) }
            .take(WinGoGameConfig.MAX_HISTORY_RESULTS)

        if (orderedUnique.isNotEmpty()) {
            resultDao.upsertAndTrimToLatest500(orderedUnique)
            onNewResultsStored(orderedUnique)
        }

        return HistorySyncResult.Success(
            collectedUniqueCount = orderedUnique.size,
            pagesFetched = pagesFetched,
            totalRemoteCount = totalRemoteCount
        )
    }

    private suspend fun fetchPageWithRetry(pageNo: Int): ApiCallOutcome<com.example.network.HistoryPageResponse> {
        var attempt = 0
        var backoffMs = INITIAL_BACKOFF_MS

        while (true) {
            val outcome = apiRequestManager.fetchHistoryPage(pageNo = pageNo)
            val retryable = outcome is ApiCallOutcome.NetworkError ||
                (outcome is ApiCallOutcome.HttpError && outcome.code >= 500)

            if (!retryable || attempt >= MAX_RETRIES) {
                return outcome
            }
            attempt++
            delay(backoffMs)
            backoffMs *= 2
        }
    }

    internal fun validateAndMapRecord(dto: HistoryRecordDto, receivedAt: Long): ResultEntity? {
        val issue = dto.issueNumber?.trim()
        if (issue.isNullOrEmpty() || !ISSUE_REGEX.matches(issue)) return null
        val number = dto.number ?: return null
        if (number !in 0..9) return null

        val rawColor = dto.color?.trim().orEmpty()
        val parsedSum = dto.sum ?: -1

        return ResultEntity(
            issueNumber = issue,
            number = number,
            color = rawColor,
            premium = dto.premium.orEmpty(),
            sum = parsedSum,
            blockId = dto.blockId.orEmpty(),
            blockNumber = dto.blockNumber.orEmpty(),
            blockTimestamp = dto.blockTimestamp.orEmpty(),
            source = "GetHistoryIssuePage",
            receivedAt = receivedAt
        )
    }

    companion object {
        private const val MAX_PAGES_TO_SCAN = 55
        private const val MAX_CONSECUTIVE_EMPTY_PAGES = 2
        private const val MAX_RETRIES = 3
        private const val INITIAL_BACKOFF_MS = 500L
        private val ISSUE_REGEX = Regex("^[A-Za-z0-9_-]{4,32}$")

        fun compareIssuesDescending(a: String, b: String): Int {
            val lenCmp = b.length.compareTo(a.length)
            return if (lenCmp != 0) lenCmp else b.compareTo(a)
        }
    }
}
