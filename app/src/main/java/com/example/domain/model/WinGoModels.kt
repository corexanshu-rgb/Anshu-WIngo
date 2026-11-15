package com.example.domain.model

object WinGoGameConfig {
    const val GAME_CODE = "WinGo_1M"
    const val LANGUAGE = "en"
    const val HISTORY_PAGE_SIZE = 10
    const val MAX_HISTORY_RESULTS = 500
    const val MAX_PREDICTION_RECORDS = 500

    const val REFERRAL_CODE = "A86A78N"
    const val INITIAL_REFERRAL_URL = "https://9kclub55.com/register?inviteCode=A86A78N&from=web"

    val ALLOWED_WEBVIEW_HOSTS = setOf(
        "9kclub55.com",
        "www.9kclub55.com",
        "m.9kclub55.com",
        "9kclub5.com",
        "www.9kclub5.com",
        "m.9kclub5.com"
    )

    val BRIDGE_ALLOWED_ORIGIN_RULES = setOf(
        "https://9kclub55.com",
        "https://www.9kclub55.com",
        "https://m.9kclub55.com",
        "https://9kclub5.com",
        "https://www.9kclub5.com",
        "https://m.9kclub5.com"
    )

    const val API_BASE_URL = "https://api.ar-lottery01.com/"
    const val API_ORIGIN = "https://9kclub5.com"
    const val API_REFERER = "https://9kclub5.com/"

    const val HISTORY_JSON_BASE_URL = "https://draw.ar-lottery01.com/"
    const val HISTORY_JSON_ENDPOINT = "https://draw.ar-lottery01.com/WinGo/WinGo_1M/GetHistoryIssuePage.json"
    const val HISTORY_POLL_INTERVAL_MS = 3_000L
    const val PHASE_1_AI_EXECUTION_ENABLED = false
}

enum class WinGoColor(val label: String) {
    RED("Red"),
    GREEN("Green"),
    VIOLET("Violet");

    companion object {
        fun primaryFromNumber(number: Int): WinGoColor = when (number) {
            1, 3, 5, 7, 9 -> GREEN
            0, 2, 4, 6, 8 -> RED
            else -> RED
        }

        fun allColorsForNumber(number: Int): Set<WinGoColor> = when (number) {
            0 -> setOf(RED, VIOLET)
            5 -> setOf(GREEN, VIOLET)
            1, 3, 7, 9 -> setOf(GREEN)
            2, 4, 6, 8 -> setOf(RED)
            else -> emptySet()
        }

        fun parsePrimary(rawColor: String, number: Int): WinGoColor {
            val normalized = rawColor.lowercase()
            return when {
                normalized.contains("green") -> GREEN
                normalized.contains("red") -> RED
                normalized.contains("violet") || normalized.contains("purple") -> VIOLET
                else -> primaryFromNumber(number)
            }
        }
    }
}

enum class WinGoSize(val label: String) {
    BIG("Big"),
    SMALL("Small");

    companion object {
        fun fromNumber(number: Int): WinGoSize = if (number >= 5) BIG else SMALL
    }
}

enum class PredictionStatus {
    DRAFT,
    LOCKED,
    INSUFFICIENT_SIGNAL,
    SKIPPED
}

enum class VerificationStatus {
    PENDING,
    VERIFIED,
    SKIPPED,
    UNVERIFIABLE
}

enum class DataFreshnessStatus(val label: String) {
    LIVE("Live Synchronized"),
    STALE("Stale Data"),
    OFFLINE("Offline (Cached)"),
    AWAITING_AUTH("Awaiting API Auth"),
    NO_DATA("No Data")
}

sealed interface NetworkStatus {
    data object Online : NetworkStatus
    data object Offline : NetworkStatus
}
