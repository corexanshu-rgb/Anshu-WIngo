package com.example.network

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

data class ValidatedGetPanel(
    val code: Int,
    val msg: String?,
    val msgCode: Int?,
    val serviceTimeMs: Long?,
    val serverTimeMs: Long,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val roundDurationMs: Long,
    val activeIssueNumber: String,
    val issueNumber: String?,
    val currentIssueNumber: String?,
    val issueConsistent: Boolean,
    val phase: String,
    val snapshotNo: Int?,
    val snapshotItems: List<ValidatedSnapshotItem>,
    val unlockedNumbers: List<Int>,
    val repeatNumbers: List<Int>,
    val firstBetEnergy: Double?,
    val earnCap: Double?,
    val cumulativeBetAmount: Double?,
    val cumulativeBetEnergy: Double?,
    val usedServiceTimeFallback: Boolean = false
)

sealed interface GetPanelValidationResult {
    data class Valid(val panel: ValidatedGetPanel) : GetPanelValidationResult
    data class Invalid(val reason: String) : GetPanelValidationResult
}

/**
 * Parses and validates WinGo_1M GetPanel JSON responses cleanly.
 * Never fabricates missing timer/issue fields and keeps snapshot stats separate from AI models.
 */
object GetPanelParser {

    fun buildMoshi(): Moshi = Moshi.Builder()
        .add(HistoryRecordDtoAdapter())
        .add(FlexibleJsonValueAdapter())
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private val moshi: Moshi by lazy { buildMoshi() }
    private val responseAdapter by lazy { moshi.adapter(GetPanelResponse::class.java) }

    fun normalizeEpochMillis(timestamp: Long): Long {
        if (timestamp <= 0L) return 0L
        return if (timestamp in 1L..99_999_999_999L) timestamp * 1000L else timestamp
    }

    fun parseJson(rawJson: String): GetPanelValidationResult {
        if (rawJson.isBlank()) {
            return GetPanelValidationResult.Invalid("Empty GetPanel JSON response.")
        }
        val parsed = runCatching { responseAdapter.fromJson(rawJson) }.getOrElse { ex ->
            return GetPanelValidationResult.Invalid(
                "Malformed GetPanel JSON: ${ex.message ?: ex::class.java.simpleName}"
            )
        }
        return validate(parsed)
    }

    fun validate(response: GetPanelResponse?): GetPanelValidationResult {
        if (response == null) {
            return GetPanelValidationResult.Invalid("Empty GetPanel response.")
        }

        val code = response.code
            ?: return GetPanelValidationResult.Invalid("Missing top-level 'code' field in GetPanel response.")
        if (code != 0) {
            val detail = response.msg?.takeIf { it.isNotBlank() } ?: "Unknown error"
            return GetPanelValidationResult.Invalid(
                "GetPanel API returned code=$code (msgCode=${response.msgCode ?: "--"}): $detail"
            )
        }

        val data = response.data
            ?: return GetPanelValidationResult.Invalid("Missing 'data' payload in GetPanel response.")

        val usedServiceTimeFallback = data.serverTime == null && response.serviceTime != null
        val rawServerTime = data.serverTime ?: response.serviceTime
            ?: return GetPanelValidationResult.Invalid("Missing required timer field: data.serverTime and root serviceTime.")
        val rawStartTime = data.startTime
            ?: return GetPanelValidationResult.Invalid("Missing required timer field: data.startTime.")
        val rawEndTime = data.endTime
            ?: return GetPanelValidationResult.Invalid("Missing required timer field: data.endTime.")

        val serverTimeMs = normalizeEpochMillis(rawServerTime)
        val startTimeMs = normalizeEpochMillis(rawStartTime)
        val endTimeMs = normalizeEpochMillis(rawEndTime)
        val serviceTimeMs = response.serviceTime?.let { normalizeEpochMillis(it) }?.takeIf { it > 0L }

        if (serverTimeMs <= 0L || startTimeMs <= 0L || endTimeMs <= 0L) {
            return GetPanelValidationResult.Invalid("Invalid non-positive timer epoch timestamps in GetPanel response.")
        }
        if (endTimeMs <= startTimeMs) {
            return GetPanelValidationResult.Invalid("Invalid GetPanel round bounds: endTime ($endTimeMs) <= startTime ($startTimeMs).")
        }

        val rawIssue = data.issueNumber?.trim()?.takeIf { it.isNotEmpty() }
        val rawCurrentIssue = data.currentIssueNumber?.trim()?.takeIf { it.isNotEmpty() }
        if (rawIssue == null && rawCurrentIssue == null) {
            return GetPanelValidationResult.Invalid("Missing required issueNumber and currentIssueNumber.")
        }

        val issueConsistent = rawIssue == null || rawCurrentIssue == null || rawIssue == rawCurrentIssue
        val activeIssue = rawCurrentIssue ?: rawIssue!!
        val phase = data.phase?.trim()?.takeIf { it.isNotEmpty() } ?: "Unknown"

        val validatedSnapshotItems = data.snapshot?.list.orEmpty()
            .asSequence()
            .filterNotNull()
            .mapNotNull { item ->
                val num = item.number ?: return@mapNotNull null
                val pct = item.percent ?: return@mapNotNull null
                val rank = item.rank ?: return@mapNotNull null
                if (num !in 0..9 || !pct.isFinite() || pct < 0.0 || rank < 1) {
                    return@mapNotNull null
                }
                ValidatedSnapshotItem(
                    number = num,
                    percent = pct,
                    rank = rank,
                    medal = item.medal ?: 0,
                    prevRank = item.prevRank
                )
            }
            .sortedWith(compareBy<ValidatedSnapshotItem> { it.rank }.thenBy { it.number })
            .distinctBy { it.number }
            .toList()

        return GetPanelValidationResult.Valid(
            ValidatedGetPanel(
                code = code,
                msg = response.msg,
                msgCode = response.msgCode,
                serviceTimeMs = serviceTimeMs,
                serverTimeMs = serverTimeMs,
                startTimeMs = startTimeMs,
                endTimeMs = endTimeMs,
                roundDurationMs = endTimeMs - startTimeMs,
                activeIssueNumber = activeIssue,
                issueNumber = rawIssue,
                currentIssueNumber = rawCurrentIssue,
                issueConsistent = issueConsistent,
                phase = phase,
                snapshotNo = data.snapshot?.snapNo,
                snapshotItems = validatedSnapshotItems,
                unlockedNumbers = data.unlockedNumbers.orEmpty().filterNotNull().filter { it in 0..9 }.distinct(),
                repeatNumbers = data.repeatNumbers.orEmpty().filterNotNull().filter { it in 0..9 }.distinct(),
                firstBetEnergy = data.firstBetEnergy,
                earnCap = data.earnCap,
                cumulativeBetAmount = data.cumulativeBetAmount,
                cumulativeBetEnergy = data.cumulativeBetEnergy,
                usedServiceTimeFallback = usedServiceTimeFallback
            )
        )
    }
}
