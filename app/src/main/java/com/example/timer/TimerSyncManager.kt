package com.example.timer

import android.os.SystemClock
import com.example.domain.model.DataFreshnessStatus
import com.example.domain.model.NetworkStatus
import com.example.network.ApiCallOutcome
import com.example.network.ApiRequestManager
import com.example.network.GetPanelParser
import com.example.network.GetPanelValidationResult
import com.example.network.NetworkConnectivityMonitor
import com.example.network.ValidatedGetPanel
import com.example.network.ValidatedSnapshotItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.max

data class TimerUiState(
    val issueNumber: String? = null,
    val currentIssueNumber: String? = null,
    val issueConsistent: Boolean = true,
    val phase: String? = null,
    val remainingMillis: Long? = null,
    val roundDurationMillis: Long? = null,
    val formattedCountdown: String = "--:--",
    val freshness: DataFreshnessStatus = DataFreshnessStatus.NO_DATA,
    val statusDetail: String = "Awaiting GetPanel synchronization",
    val lastSyncedAtMillis: Long? = null,
    val estimatedLatencyMs: Long? = null,
    val serverClockOffsetMs: Long? = null,
    val serviceTimeOffsetMs: Long? = null,
    val serviceToServerDeltaMs: Long? = null,
    val usedServiceTimeFallback: Boolean = false,
    val isDeadlineExpired: Boolean = false,
    val snapshotNo: Int? = null,
    val snapshotItems: List<ValidatedSnapshotItem> = emptyList()
)

/**
 * Synchronizes the WinGo 1M countdown exclusively with authoritative GetPanel timestamps
 * (serverTime, serviceTime, startTime, endTime, issueNumber, currentIssueNumber, phase) and
 * advances locally every second via Android monotonic time (SystemClock.elapsedRealtime())
 * without polling the API every second.
 */
class TimerSyncManager(
    private val apiRequestManager: ApiRequestManager,
    private val networkMonitor: NetworkConnectivityMonitor,
    private val scope: CoroutineScope,
    private val onIssueChanged: (newIssue: String, previousIssue: String?) -> Unit = { _, _ -> },
    private val onPhaseChanged: (newPhase: String, previousPhase: String?) -> Unit = { _, _ -> },
    private val elapsedRealtimeProvider: () -> Long = { SystemClock.elapsedRealtime() },
    private val wallTimeProvider: () -> Long = { System.currentTimeMillis() }
) {
    private val _timerState = MutableStateFlow(TimerUiState())
    val timerState: StateFlow<TimerUiState> = _timerState.asStateFlow()

    private var tickerJob: Job? = null
    private var activeSyncEnabled = false

    private var authoritativeEndTimeMs: Long? = null
    private var estimatedServerTimeAtSyncMs: Long? = null
    private var syncElapsedRealtimeMs: Long? = null
    private var lastPeriodicSyncElapsedMs: Long = 0L
    private var preEndResyncAttemptedForIssue: String? = null
    private var postEndResyncAttemptedAtMs: Long = 0L

    fun startSynchronization() {
        if (activeSyncEnabled) return
        activeSyncEnabled = true
        scope.launch { synchronizeNow() }
        startLocalMonotonicTicker()
    }

    fun stopSynchronization() {
        activeSyncEnabled = false
        tickerJob?.cancel()
        tickerJob = null
        clearSyncClockState()
        _timerState.value = TimerUiState()
    }

    fun onAppResumed() {
        if (activeSyncEnabled) {
            scope.launch { synchronizeNow() }
        }
    }

    suspend fun synchronizeNow() {
        if (!networkMonitor.isOnline()) {
            markOfflineOrStale("Device offline. Cannot synchronize WinGo 1M timer.")
            return
        }

        val outcome = apiRequestManager.fetchPanel()
        val afterElapsed = elapsedRealtimeProvider()
        val afterWallTime = wallTimeProvider()

        when (outcome) {
            is ApiCallOutcome.MissingAuth -> {
                clearSyncClockState()
                markUnavailableOrStale(outcome.reason, DataFreshnessStatus.AWAITING_AUTH)
            }
            is ApiCallOutcome.SessionExpired -> {
                clearSyncClockState()
                markUnavailableOrStale(outcome.reason, DataFreshnessStatus.AWAITING_AUTH)
            }
            is ApiCallOutcome.NetworkError -> {
                clearSyncClockState()
                markUnavailableOrStale(outcome.message, DataFreshnessStatus.STALE)
            }
            is ApiCallOutcome.HttpError -> {
                clearSyncClockState()
                markUnavailableOrStale(outcome.message, DataFreshnessStatus.STALE)
            }
            is ApiCallOutcome.InvalidResponse -> {
                clearSyncClockState()
                markUnavailableOrStale(outcome.message, DataFreshnessStatus.STALE)
            }
            is ApiCallOutcome.Success -> {
                when (val validation = GetPanelParser.validate(outcome.body)) {
                    is GetPanelValidationResult.Invalid -> {
                        clearSyncClockState()
                        markUnavailableOrStale(validation.reason, DataFreshnessStatus.STALE)
                    }
                    is GetPanelValidationResult.Valid -> {
                        applyAuthoritativeSync(
                            panel = validation.panel,
                            syncElapsedRealtime = afterElapsed,
                            syncWallTimeMs = afterWallTime,
                            roundTripMs = outcome.roundTripMs
                        )
                    }
                }
            }
        }
    }

    private fun applyAuthoritativeSync(
        panel: ValidatedGetPanel,
        syncElapsedRealtime: Long,
        syncWallTimeMs: Long,
        roundTripMs: Long
    ) {
        val oneWayLatencyMs = (roundTripMs / 2L).coerceAtLeast(0L)
        val estimatedServerNow = panel.serverTimeMs + oneWayLatencyMs
        val serverClockOffsetMs = estimatedServerNow - syncWallTimeMs
        val serviceTimeOffsetMs = panel.serviceTimeMs?.let { (it + oneWayLatencyMs) - syncWallTimeMs }
        val serviceDeltaMs = panel.serviceTimeMs?.let { it - panel.serverTimeMs }

        val previousIssue = _timerState.value.issueNumber
        val previousPhase = _timerState.value.phase

        estimatedServerTimeAtSyncMs = estimatedServerNow
        syncElapsedRealtimeMs = syncElapsedRealtime
        authoritativeEndTimeMs = panel.endTimeMs
        lastPeriodicSyncElapsedMs = syncElapsedRealtime

        val remaining = max(0L, panel.endTimeMs - estimatedServerNow)
        val expired = remaining == 0L

        if (previousIssue != null && previousIssue != panel.activeIssueNumber) {
            preEndResyncAttemptedForIssue = null
        }

        val freshness = when {
            !panel.issueConsistent -> DataFreshnessStatus.STALE
            expired -> DataFreshnessStatus.STALE
            else -> DataFreshnessStatus.LIVE
        }
        val statusDetail = when {
            !panel.issueConsistent ->
                "Issue inconsistency detected (issueNumber=${panel.issueNumber}, currentIssueNumber=${panel.currentIssueNumber})."
            expired ->
                "Round deadline expired. Resynchronizing next WinGo_1M issue..."
            else -> {
                val offsetSign = if (serverClockOffsetMs >= 0) "+" else ""
                val sourceLabel = if (panel.usedServiceTimeFallback) "serviceTime fallback" else "GetPanel"
                "Synchronized with $sourceLabel (RTT ${roundTripMs}ms, offset ${offsetSign}${serverClockOffsetMs}ms)"
            }
        }

        _timerState.value = TimerUiState(
            issueNumber = panel.activeIssueNumber,
            currentIssueNumber = panel.currentIssueNumber ?: panel.activeIssueNumber,
            issueConsistent = panel.issueConsistent,
            phase = panel.phase,
            remainingMillis = remaining,
            roundDurationMillis = panel.roundDurationMs,
            formattedCountdown = formatRemaining(remaining),
            freshness = freshness,
            statusDetail = statusDetail,
            lastSyncedAtMillis = syncWallTimeMs,
            estimatedLatencyMs = oneWayLatencyMs,
            serverClockOffsetMs = serverClockOffsetMs,
            serviceTimeOffsetMs = serviceTimeOffsetMs,
            serviceToServerDeltaMs = serviceDeltaMs,
            usedServiceTimeFallback = panel.usedServiceTimeFallback,
            isDeadlineExpired = expired,
            snapshotNo = panel.snapshotNo,
            snapshotItems = panel.snapshotItems
        )

        if (previousIssue == null || previousIssue != panel.activeIssueNumber) {
            onIssueChanged(panel.activeIssueNumber, previousIssue)
        }

        if (previousPhase != null && previousPhase != panel.phase) {
            onPhaseChanged(panel.phase, previousPhase)
        }
    }

    private fun startLocalMonotonicTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (isActive) {
                delay(1000L)
                tickOnce()
            }
        }
    }

    internal suspend fun tickOnce() {
        val endTime = authoritativeEndTimeMs ?: return
        val baseServer = estimatedServerTimeAtSyncMs ?: return
        val baseElapsed = syncElapsedRealtimeMs ?: return

        val nowElapsed = elapsedRealtimeProvider()
        val elapsedSinceSync = max(0L, nowElapsed - baseElapsed)
        val estimatedServerNow = baseServer + elapsedSinceSync
        val remaining = max(0L, endTime - estimatedServerNow)
        val currentIssue = _timerState.value.issueNumber

        if (remaining == 0L) {
            _timerState.value = _timerState.value.copy(
                remainingMillis = 0L,
                formattedCountdown = "00:00",
                isDeadlineExpired = true,
                freshness = if (networkMonitor.networkStatus.value == NetworkStatus.Offline) {
                    DataFreshnessStatus.OFFLINE
                } else {
                    DataFreshnessStatus.STALE
                },
                statusDetail = "Round deadline reached. Resynchronizing next issue..."
            )
            if (nowElapsed - postEndResyncAttemptedAtMs >= POST_END_RETRY_INTERVAL_MS) {
                postEndResyncAttemptedAtMs = nowElapsed
                synchronizeNow()
            }
            return
        }

        val isStale = elapsedSinceSync >= STALE_DATA_THRESHOLD_MS || !_timerState.value.issueConsistent
        _timerState.value = _timerState.value.copy(
            remainingMillis = remaining,
            formattedCountdown = formatRemaining(remaining),
            isDeadlineExpired = false,
            freshness = when {
                networkMonitor.networkStatus.value == NetworkStatus.Offline -> DataFreshnessStatus.OFFLINE
                isStale -> DataFreshnessStatus.STALE
                else -> DataFreshnessStatus.LIVE
            },
            statusDetail = if (elapsedSinceSync >= STALE_DATA_THRESHOLD_MS) {
                "GetPanel timer stale (${elapsedSinceSync / 1000L}s without API refresh). Resynchronizing..."
            } else {
                _timerState.value.statusDetail
            }
        )

        if (remaining <= PRE_END_RESYNC_THRESHOLD_MS &&
            currentIssue != null &&
            preEndResyncAttemptedForIssue != currentIssue
        ) {
            preEndResyncAttemptedForIssue = currentIssue
            synchronizeNow()
            return
        }

        if (nowElapsed - lastPeriodicSyncElapsedMs >= PERIODIC_RESYNC_INTERVAL_MS) {
            synchronizeNow()
        }
    }

    private fun clearSyncClockState() {
        authoritativeEndTimeMs = null
        estimatedServerTimeAtSyncMs = null
        syncElapsedRealtimeMs = null
    }

    private fun markOfflineOrStale(detail: String) {
        val hadSync = _timerState.value.issueNumber != null
        clearSyncClockState()
        _timerState.value = _timerState.value.copy(
            freshness = if (hadSync) DataFreshnessStatus.OFFLINE else DataFreshnessStatus.NO_DATA,
            statusDetail = detail,
            remainingMillis = null,
            formattedCountdown = "--:--",
            isDeadlineExpired = false
        )
    }

    private fun markUnavailableOrStale(detail: String, status: DataFreshnessStatus) {
        _timerState.value = _timerState.value.copy(
            freshness = status,
            statusDetail = detail,
            remainingMillis = null,
            formattedCountdown = "--:--",
            isDeadlineExpired = false
        )
    }

    private fun formatRemaining(remainingMs: Long): String {
        val totalSeconds = (remainingMs / 1000L).coerceAtLeast(0L)
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        return "%02d:%02d".format(minutes, seconds)
    }

    companion object {
        const val PERIODIC_RESYNC_INTERVAL_MS = 20_000L
        const val STALE_DATA_THRESHOLD_MS = 25_000L
        const val PRE_END_RESYNC_THRESHOLD_MS = 3_000L
        const val POST_END_RETRY_INTERVAL_MS = 3_000L
    }
}
