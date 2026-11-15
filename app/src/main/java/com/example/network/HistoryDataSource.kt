package com.example.network

import com.example.data.local.dao.ResultDao
import com.example.data.local.entity.ResultEntity
import com.example.domain.model.WinGoGameConfig
import com.squareup.moshi.JsonDataException
import com.squareup.moshi.JsonEncodingException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlin.math.min

/**
 * Retrofit client for the verified public WinGo_1M completed-draw history endpoint:
 * https://draw.ar-lottery01.com/WinGo/WinGo_1M/GetHistoryIssuePage.json?ts=<timestamp>
 */
interface DrawHistoryJsonService {
    @GET("WinGo/WinGo_1M/GetHistoryIssuePage.json")
    suspend fun getHistoryIssuePage(
        @Query("ts") timestamp: Long
    ): Response<HistoryPageResponse>
}

enum class HistoryApiStatus(val label: String) {
    IDLE("IDLE"),
    SYNCING("SYNCING"),
    LIVE("LIVE"),
    STALE("STALE"),
    HISTORY_API_UNAVAILABLE("HISTORY_API_UNAVAILABLE")
}

data class HistorySyncDiagnostics(
    val status: HistoryApiStatus = HistoryApiStatus.IDLE,
    val endpoint: String = WinGoGameConfig.HISTORY_JSON_ENDPOINT,
    val lastHttpStatus: Int? = null,
    val lastSuccessfulSyncAtMillis: Long? = null,
    val lastAttemptAtMillis: Long? = null,
    val serverServiceTimeMs: Long? = null,
    val totalStoredCount: Int = 0,
    val lastInsertedCount: Int = 0,
    val consecutiveFailures: Int = 0,
    val isPollingActive: Boolean = false,
    val lastErrorMessage: String? = null,
    val statusDetail: String = "Awaiting live WinGo_1M history sync"
)

sealed interface HistoryFetchOutcome {
    data class Success(
        val records: List<HistoryRecordDto>,
        val serviceTimeMs: Long?,
        val totalRemoteCount: Int,
        val httpCode: Int,
        val roundTripMs: Long
    ) : HistoryFetchOutcome

    data class Empty(
        val serviceTimeMs: Long?,
        val httpCode: Int,
        val roundTripMs: Long
    ) : HistoryFetchOutcome

    data class HttpFailure(
        val httpCode: Int,
        val reason: String
    ) : HistoryFetchOutcome

    data class NetworkFailure(
        val reason: String,
        val isTimeout: Boolean
    ) : HistoryFetchOutcome

    data class ParseFailure(
        val reason: String
    ) : HistoryFetchOutcome

    data object Offline : HistoryFetchOutcome
}

/**
 * Dedicated live data source for WinGo_1M completed history from draw.ar-lottery01.com.
 *
 * Guarantees:
 * - Strict separation from api.ar-lottery01.com GetPanel authentication.
 * - Explicit timeout handling, HTTP error reporting, and malformed JSON resilience.
 * - Single-flight polling with exponential backoff and zero fabricated/fixture records.
 */
class HistoryDataSource(
    private val service: DrawHistoryJsonService,
    private val networkMonitor: NetworkConnectivityMonitor,
    private val wallTimeProvider: () -> Long = { System.currentTimeMillis() },
    private val staleThresholdMs: Long = DEFAULT_STALE_THRESHOLD_MS
) {
    private val fetchMutex = Mutex()

    private val _diagnostics = MutableStateFlow(HistorySyncDiagnostics())
    val diagnostics: StateFlow<HistorySyncDiagnostics> = _diagnostics.asStateFlow()

    suspend fun fetchLatestHistory(): HistoryFetchOutcome {
        return fetchMutex.withLock {
            val attemptAt = wallTimeProvider()
            if (!networkMonitor.isOnline()) {
                val hadData = _diagnostics.value.lastSuccessfulSyncAtMillis != null
                _diagnostics.value = _diagnostics.value.copy(
                    status = if (hadData) HistoryApiStatus.STALE else HistoryApiStatus.HISTORY_API_UNAVAILABLE,
                    lastAttemptAtMillis = attemptAt,
                    lastErrorMessage = "Device offline",
                    statusDetail = "Device offline; displaying cached Room records only."
                )
                return@withLock HistoryFetchOutcome.Offline
            }

            _diagnostics.value = _diagnostics.value.copy(
                status = HistoryApiStatus.SYNCING,
                lastAttemptAtMillis = attemptAt
            )

            val startNanos = System.nanoTime()
            try {
                val response = service.getHistoryIssuePage(timestamp = attemptAt)
                val rttMs = ((System.nanoTime() - startNanos) / 1_000_000L).coerceAtLeast(0L)

                if (!response.isSuccessful) {
                    val failures = _diagnostics.value.consecutiveFailures + 1
                    val msg = "HTTP ${response.code()} ${response.message()}".trim()
                    _diagnostics.value = _diagnostics.value.copy(
                        status = HistoryApiStatus.HISTORY_API_UNAVAILABLE,
                        lastHttpStatus = response.code(),
                        consecutiveFailures = failures,
                        lastErrorMessage = msg,
                        statusDetail = "History endpoint failed ($msg)"
                    )
                    return@withLock HistoryFetchOutcome.HttpFailure(response.code(), msg)
                }

                val body = response.body()
                    ?: run {
                        val failures = _diagnostics.value.consecutiveFailures + 1
                        val msg = "Empty response body from history endpoint"
                        _diagnostics.value = _diagnostics.value.copy(
                            status = HistoryApiStatus.HISTORY_API_UNAVAILABLE,
                            lastHttpStatus = response.code(),
                            consecutiveFailures = failures,
                            lastErrorMessage = msg,
                            statusDetail = msg
                        )
                        return@withLock HistoryFetchOutcome.ParseFailure(msg)
                    }

                if (body.code != 0) {
                    val failures = _diagnostics.value.consecutiveFailures + 1
                    val msg = "History API returned code=${body.code} (${body.msg ?: "Unknown"})"
                    _diagnostics.value = _diagnostics.value.copy(
                        status = HistoryApiStatus.HISTORY_API_UNAVAILABLE,
                        lastHttpStatus = response.code(),
                        consecutiveFailures = failures,
                        lastErrorMessage = msg,
                        statusDetail = msg
                    )
                    return@withLock HistoryFetchOutcome.ParseFailure(msg)
                }

                val data = body.data
                if (data == null || data.list.isEmpty()) {
                    val syncedAt = wallTimeProvider()
                    _diagnostics.value = _diagnostics.value.copy(
                        status = HistoryApiStatus.LIVE,
                        lastHttpStatus = response.code(),
                        lastSuccessfulSyncAtMillis = syncedAt,
                        serverServiceTimeMs = body.serviceTime,
                        consecutiveFailures = 0,
                        lastErrorMessage = null,
                        statusDetail = "History endpoint reachable (0 items returned)"
                    )
                    return@withLock HistoryFetchOutcome.Empty(
                        serviceTimeMs = body.serviceTime,
                        httpCode = response.code(),
                        roundTripMs = rttMs
                    )
                }

                val syncedAt = wallTimeProvider()
                _diagnostics.value = _diagnostics.value.copy(
                    status = HistoryApiStatus.LIVE,
                    lastHttpStatus = response.code(),
                    lastSuccessfulSyncAtMillis = syncedAt,
                    serverServiceTimeMs = body.serviceTime,
                    consecutiveFailures = 0,
                    lastErrorMessage = null,
                    statusDetail = "Live history synced (${data.list.size} items, RTT ${rttMs}ms)"
                )
                return@withLock HistoryFetchOutcome.Success(
                    records = data.list,
                    serviceTimeMs = body.serviceTime,
                    totalRemoteCount = data.totalCount,
                    httpCode = response.code(),
                    roundTripMs = rttMs
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (jsonEx: JsonDataException) {
                val failures = _diagnostics.value.consecutiveFailures + 1
                val msg = "Malformed history JSON: ${jsonEx.message ?: "invalid schema"}"
                _diagnostics.value = _diagnostics.value.copy(
                    status = HistoryApiStatus.HISTORY_API_UNAVAILABLE,
                    consecutiveFailures = failures,
                    lastErrorMessage = msg,
                    statusDetail = msg
                )
                return@withLock HistoryFetchOutcome.ParseFailure(msg)
            } catch (jsonEx: JsonEncodingException) {
                val failures = _diagnostics.value.consecutiveFailures + 1
                val msg = "Invalid history JSON encoding: ${jsonEx.message ?: "malformed"}"
                _diagnostics.value = _diagnostics.value.copy(
                    status = HistoryApiStatus.HISTORY_API_UNAVAILABLE,
                    consecutiveFailures = failures,
                    lastErrorMessage = msg,
                    statusDetail = msg
                )
                return@withLock HistoryFetchOutcome.ParseFailure(msg)
            } catch (timeout: SocketTimeoutException) {
                val failures = _diagnostics.value.consecutiveFailures + 1
                val msg = timeout.message ?: "History request timed out"
                _diagnostics.value = _diagnostics.value.copy(
                    status = HistoryApiStatus.HISTORY_API_UNAVAILABLE,
                    consecutiveFailures = failures,
                    lastErrorMessage = msg,
                    statusDetail = "History timeout: $msg"
                )
                return@withLock HistoryFetchOutcome.NetworkFailure(msg, isTimeout = true)
            } catch (io: IOException) {
                val failures = _diagnostics.value.consecutiveFailures + 1
                val msg = io.message ?: "History network error"
                _diagnostics.value = _diagnostics.value.copy(
                    status = HistoryApiStatus.HISTORY_API_UNAVAILABLE,
                    consecutiveFailures = failures,
                    lastErrorMessage = msg,
                    statusDetail = "History network error: $msg"
                )
                return@withLock HistoryFetchOutcome.NetworkFailure(msg, isTimeout = false)
            } catch (e: Exception) {
                val failures = _diagnostics.value.consecutiveFailures + 1
                val msg = e.message ?: e::class.java.simpleName
                _diagnostics.value = _diagnostics.value.copy(
                    status = HistoryApiStatus.HISTORY_API_UNAVAILABLE,
                    consecutiveFailures = failures,
                    lastErrorMessage = msg,
                    statusDetail = "History sync error: $msg"
                )
                return@withLock HistoryFetchOutcome.ParseFailure(msg)
            }
        }
    }

    internal fun updateStoredStats(totalStoredCount: Int, newlyMappedCount: Int) {
        _diagnostics.value = _diagnostics.value.copy(
            totalStoredCount = totalStoredCount,
            lastInsertedCount = newlyMappedCount
        )
    }

    internal fun setPollingActive(active: Boolean) {
        _diagnostics.value = _diagnostics.value.copy(isPollingActive = active)
    }

    fun evaluateStaleState() {
        val diag = _diagnostics.value
        val lastSuccess = diag.lastSuccessfulSyncAtMillis ?: return
        if (diag.status == HistoryApiStatus.LIVE) {
            val ageMs = wallTimeProvider() - lastSuccess
            if (ageMs >= staleThresholdMs) {
                _diagnostics.value = diag.copy(
                    status = HistoryApiStatus.STALE,
                    statusDetail = "History data stale (${ageMs / 1000L}s since last sync)"
                )
            }
        }
    }

    companion object {
        const val DEFAULT_STALE_THRESHOLD_MS = 15_000L
        const val DEFAULT_HTTP_TIMEOUT_SECONDS = 10L

        fun createDefaultService(
            baseUrl: String = WinGoGameConfig.HISTORY_JSON_BASE_URL,
            okHttpClient: OkHttpClient = RetrofitClient.createOkHttpClient()
                .newBuilder()
                .connectTimeout(DEFAULT_HTTP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(DEFAULT_HTTP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .writeTimeout(DEFAULT_HTTP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .callTimeout(DEFAULT_HTTP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build()
        ): DrawHistoryJsonService {
            val moshi = RetrofitClient.createMoshi()
            return Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(okHttpClient)
                .addConverterFactory(MoshiConverterFactory.create(moshi))
                .build()
                .create(DrawHistoryJsonService::class.java)
        }

        fun computeBackoffDelayMs(
            consecutiveFailures: Int,
            baseIntervalMs: Long = WinGoGameConfig.HISTORY_POLL_INTERVAL_MS,
            maxBackoffMs: Long = 30_000L
        ): Long {
            if (consecutiveFailures <= 0) return baseIntervalMs
            val exponent = min(consecutiveFailures - 1, 4)
            return (baseIntervalMs * (1L shl exponent)).coerceAtMost(maxBackoffMs)
        }
    }
}

/**
 * Coordinates single-flight periodic polling of [HistoryDataSource], validates/maps real records,
 * deduplicates by issueNumber, and caps Room history storage at 500 results.
 */
class LiveHistoryPoller(
    private val historyDataSource: HistoryDataSource,
    private val resultDao: ResultDao,
    private val scope: CoroutineScope,
    private val mapRecord: (HistoryRecordDto, Long) -> ResultEntity?,
    private val onNewResultsStored: suspend (List<ResultEntity>) -> Unit = {},
    private val pollIntervalMs: Long = WinGoGameConfig.HISTORY_POLL_INTERVAL_MS,
    private val wallTimeProvider: () -> Long = { System.currentTimeMillis() }
) {
    private val syncMutex = Mutex()
    private var pollingJob: Job? = null

    @Volatile
    private var isPaused: Boolean = false

    fun isPollingActive(): Boolean = pollingJob?.isActive == true && !isPaused

    fun startPolling() {
        isPaused = false
        if (pollingJob?.isActive == true) return
        historyDataSource.setPollingActive(true)
        pollingJob = scope.launch {
            while (isActive) {
                if (!isPaused) {
                    syncOnce()
                }
                val failures = historyDataSource.diagnostics.value.consecutiveFailures
                val waitMs = HistoryDataSource.computeBackoffDelayMs(
                    consecutiveFailures = failures,
                    baseIntervalMs = pollIntervalMs
                )
                delay(waitMs)
            }
        }
    }

    fun pausePolling() {
        isPaused = true
        historyDataSource.setPollingActive(false)
    }

    fun resumePolling() {
        isPaused = false
        if (pollingJob?.isActive == true) {
            historyDataSource.setPollingActive(true)
        } else {
            startPolling()
        }
    }

    fun stopPolling() {
        isPaused = false
        pollingJob?.cancel()
        pollingJob = null
        historyDataSource.setPollingActive(false)
    }

    suspend fun syncOnce(): HistoryFetchOutcome {
        return syncMutex.withLock {
            val outcome = historyDataSource.fetchLatestHistory()
            val storedBefore = resultDao.getResultCount()
            when (outcome) {
                is HistoryFetchOutcome.Success -> {
                    val now = wallTimeProvider()
                    val deduplicated = LinkedHashMap<String, ResultEntity>()
                    for (dto in outcome.records) {
                        val entity = mapRecord(dto, now) ?: continue
                        if (!deduplicated.containsKey(entity.issueNumber)) {
                            deduplicated[entity.issueNumber] = entity
                        }
                    }
                    val validEntities = deduplicated.values.toList()
                    if (validEntities.isNotEmpty()) {
                        resultDao.upsertAndTrimToLatest500(validEntities)
                        onNewResultsStored(validEntities)
                    }
                    val totalAfter = resultDao.getResultCount()
                    historyDataSource.updateStoredStats(
                        totalStoredCount = totalAfter,
                        newlyMappedCount = validEntities.size
                    )
                }
                is HistoryFetchOutcome.Empty -> {
                    historyDataSource.updateStoredStats(
                        totalStoredCount = storedBefore,
                        newlyMappedCount = 0
                    )
                }
                else -> {
                    historyDataSource.evaluateStaleState()
                    historyDataSource.updateStoredStats(
                        totalStoredCount = storedBefore,
                        newlyMappedCount = 0
                    )
                }
            }
            outcome
        }
    }
}
