package com.example.network

import com.example.domain.model.WinGoGameConfig
import com.example.security.WebsiteSessionManager
import retrofit2.Response
import java.io.IOException

sealed interface ApiCallOutcome<out T> {
    data class Success<T>(val body: T, val roundTripMs: Long) : ApiCallOutcome<T>
    data class MissingAuth(val reason: String) : ApiCallOutcome<Nothing>
    data class SessionExpired(val reason: String) : ApiCallOutcome<Nothing>
    data class HttpError(val code: Int, val message: String) : ApiCallOutcome<Nothing>
    data class NetworkError(val message: String) : ApiCallOutcome<Nothing>
    data class InvalidResponse(val message: String) : ApiCallOutcome<Nothing>
}

/**
 * Contract for fetching WinGo_1M panel and history data from either an officially
 * authenticated upstream endpoint or an authorized HTTPS backend proxy.
 *
 * Implementations MUST:
 * - Halt requests immediately when credentials/signature or proxy config are unavailable.
 * - Keep [WebsiteSessionManager] API auth state strictly separate from WebView login state.
 * - Never fabricate responses or bypass upstream authentication.
 */
interface ApiRequestManager {
    suspend fun fetchHistoryPage(
        pageNo: Int,
        pageSize: Int = WinGoGameConfig.HISTORY_PAGE_SIZE
    ): ApiCallOutcome<HistoryPageResponse>

    suspend fun fetchPanel(): ApiCallOutcome<GetPanelResponse>

    companion object {
        operator fun invoke(
            historyApiService: HistoryApiService,
            getPanelApiService: GetPanelApiService,
            authProvider: ApiAuthenticationProvider,
            sessionManager: WebsiteSessionManager,
            networkMonitor: NetworkConnectivityMonitor,
            tokenRefreshProvider: ApiTokenRefreshProvider? = null
        ): ApiRequestManager = DefaultApiRequestManager(
            historyApiService = historyApiService,
            getPanelApiService = getPanelApiService,
            authProvider = authProvider,
            sessionManager = sessionManager,
            networkMonitor = networkMonitor,
            tokenRefreshProvider = tokenRefreshProvider
        )
    }
}

/**
 * Default [ApiRequestManager] coordinating direct authorized HTTP calls to
 * [HistoryApiService] and [GetPanelApiService].
 */
class DefaultApiRequestManager(
    private val historyApiService: HistoryApiService,
    private val getPanelApiService: GetPanelApiService,
    private val authProvider: ApiAuthenticationProvider,
    private val sessionManager: WebsiteSessionManager,
    private val networkMonitor: NetworkConnectivityMonitor,
    private val tokenRefreshProvider: ApiTokenRefreshProvider? = null
) : ApiRequestManager {

    override suspend fun fetchHistoryPage(
        pageNo: Int,
        pageSize: Int
    ): ApiCallOutcome<HistoryPageResponse> {
        if (!networkMonitor.isOnline()) {
            val msg = "Device is offline."
            sessionManager.onApiError(message = msg, httpStatus = null, requestStatus = "Offline (Blocked)")
            return ApiCallOutcome.NetworkError(msg)
        }

        val queryParams = mapOf(
            "gameCode" to WinGoGameConfig.GAME_CODE,
            "pageNo" to pageNo.toString(),
            "pageSize" to pageSize.toString(),
            "language" to WinGoGameConfig.LANGUAGE
        )

        sessionManager.onApiAuthorizing(
            detail = "Validating authorized session for WinGo_1M history...",
            requestStatus = "Authorizing GetHistoryIssuePage"
        )
        val auth = authProvider.getAuthorizedParams(queryParams).getOrElse { err ->
            val reason = err.message ?: "Missing authorized API authentication parameters."
            val missingEx = err as? MissingApiAuthenticationException
            sessionManager.onApiCredentialsMissing(
                missingBearerToken = missingEx?.missingBearerToken ?: true,
                missingSignatureAlgorithm = missingEx?.missingSignatureAlgorithm ?: true,
                details = reason,
                requestStatus = "Blocked (Missing Auth)"
            )
            return ApiCallOutcome.MissingAuth(reason)
        }

        val firstOutcome = executeCall(
            endpointLabel = "GetHistoryIssuePage",
            call = {
                historyApiService.getHistoryIssuePage(
                    authorization = auth.bearerAuthorization,
                    gameCode = WinGoGameConfig.GAME_CODE,
                    pageNo = pageNo,
                    pageSize = pageSize,
                    language = WinGoGameConfig.LANGUAGE,
                    random = auth.random,
                    signature = auth.signature,
                    timestamp = auth.timestamp
                )
            },
            codeExtractor = { it.code },
            msgExtractor = { it.msg }
        )

        if (firstOutcome is ApiCallOutcome.SessionExpired && tokenRefreshProvider?.isRefreshSupported() == true) {
            sessionManager.onApiAuthorizing(
                detail = "Refreshing expired API session token...",
                requestStatus = "Refreshing Session Token"
            )
            val refreshResult = tokenRefreshProvider.refreshBearerAuthorization(auth.bearerAuthorization)
            val refreshedBearer = refreshResult.getOrNull()
            if (refreshedBearer.isNullOrBlank()) {
                val refreshReason = refreshResult.exceptionOrNull()?.message
                    ?: "Session token refresh failed; please re-authenticate."
                sessionManager.onApiTokenExpired(
                    reason = refreshReason,
                    httpStatus = 401,
                    requestStatus = "Refresh Failed"
                )
                return ApiCallOutcome.SessionExpired(refreshReason)
            }
            val refreshedAuth = authProvider.getAuthorizedParams(queryParams).getOrElse { err ->
                val reason = err.message ?: "Failed to sign refreshed request."
                sessionManager.onApiTokenExpired(
                    reason = reason,
                    httpStatus = 401,
                    requestStatus = "Refreshed Sign Failed"
                )
                return ApiCallOutcome.SessionExpired(reason)
            }
            return executeCall(
                endpointLabel = "GetHistoryIssuePage",
                call = {
                    historyApiService.getHistoryIssuePage(
                        authorization = refreshedBearer,
                        gameCode = WinGoGameConfig.GAME_CODE,
                        pageNo = pageNo,
                        pageSize = pageSize,
                        language = WinGoGameConfig.LANGUAGE,
                        random = refreshedAuth.random,
                        signature = refreshedAuth.signature,
                        timestamp = refreshedAuth.timestamp
                    )
                },
                codeExtractor = { it.code },
                msgExtractor = { it.msg }
            )
        }
        return firstOutcome
    }

    override suspend fun fetchPanel(): ApiCallOutcome<GetPanelResponse> {
        if (!networkMonitor.isOnline()) {
            val msg = "Device is offline."
            sessionManager.onApiError(message = msg, httpStatus = null, requestStatus = "Offline (Blocked)")
            return ApiCallOutcome.NetworkError(msg)
        }

        val queryParams = mapOf(
            "gameCode" to WinGoGameConfig.GAME_CODE,
            "language" to WinGoGameConfig.LANGUAGE
        )

        sessionManager.onApiAuthorizing(
            detail = "Validating authorized session for WinGo_1M GetPanel...",
            requestStatus = "Authorizing GetPanel"
        )
        val auth = authProvider.getAuthorizedParams(queryParams).getOrElse { err ->
            val reason = err.message ?: "Missing authorized API authentication parameters."
            val missingEx = err as? MissingApiAuthenticationException
            sessionManager.onApiCredentialsMissing(
                missingBearerToken = missingEx?.missingBearerToken ?: true,
                missingSignatureAlgorithm = missingEx?.missingSignatureAlgorithm ?: true,
                details = reason,
                requestStatus = "Blocked (Missing Auth)"
            )
            return ApiCallOutcome.MissingAuth(reason)
        }

        val firstOutcome = executeCall(
            endpointLabel = "GetPanel",
            call = {
                getPanelApiService.getPanel(
                    authorization = auth.bearerAuthorization,
                    gameCode = WinGoGameConfig.GAME_CODE,
                    language = WinGoGameConfig.LANGUAGE,
                    random = auth.random,
                    signature = auth.signature,
                    timestamp = auth.timestamp
                )
            },
            codeExtractor = { it.code ?: -1 },
            msgExtractor = { it.msg }
        )

        if (firstOutcome is ApiCallOutcome.SessionExpired && tokenRefreshProvider?.isRefreshSupported() == true) {
            sessionManager.onApiAuthorizing(
                detail = "Refreshing expired API session token...",
                requestStatus = "Refreshing Session Token"
            )
            val refreshResult = tokenRefreshProvider.refreshBearerAuthorization(auth.bearerAuthorization)
            val refreshedBearer = refreshResult.getOrNull()
            if (refreshedBearer.isNullOrBlank()) {
                val refreshReason = refreshResult.exceptionOrNull()?.message
                    ?: "Session token refresh failed; please re-authenticate."
                sessionManager.onApiTokenExpired(
                    reason = refreshReason,
                    httpStatus = 401,
                    requestStatus = "Refresh Failed"
                )
                return ApiCallOutcome.SessionExpired(refreshReason)
            }
            val refreshedAuth = authProvider.getAuthorizedParams(queryParams).getOrElse { err ->
                val reason = err.message ?: "Failed to sign refreshed request."
                sessionManager.onApiTokenExpired(
                    reason = reason,
                    httpStatus = 401,
                    requestStatus = "Refreshed Sign Failed"
                )
                return ApiCallOutcome.SessionExpired(reason)
            }
            return executeCall(
                endpointLabel = "GetPanel",
                call = {
                    getPanelApiService.getPanel(
                        authorization = refreshedBearer,
                        gameCode = WinGoGameConfig.GAME_CODE,
                        language = WinGoGameConfig.LANGUAGE,
                        random = refreshedAuth.random,
                        signature = refreshedAuth.signature,
                        timestamp = refreshedAuth.timestamp
                    )
                },
                codeExtractor = { it.code ?: -1 },
                msgExtractor = { it.msg }
            )
        }
        return firstOutcome
    }

    private suspend fun <T> executeCall(
        endpointLabel: String,
        call: suspend () -> Response<T>,
        codeExtractor: (T) -> Int,
        msgExtractor: (T) -> String?
    ): ApiCallOutcome<T> {
        val startNanos = System.nanoTime()
        return try {
            val response = call()
            val rttMs = ((System.nanoTime() - startNanos) / 1_000_000L).coerceAtLeast(0L)

            if (response.code() == 401 || response.code() == 403) {
                val reason = "API rejected authorization (HTTP ${response.code()})."
                sessionManager.onApiAuthenticationRejected(
                    httpCode = response.code(),
                    reason = reason,
                    requestStatus = "$endpointLabel Rejected (HTTP ${response.code()})"
                )
                return ApiCallOutcome.SessionExpired(reason)
            }

            if (!response.isSuccessful) {
                val msg = "HTTP ${response.code()} ${response.message()}"
                sessionManager.onApiError(
                    message = msg,
                    httpStatus = response.code(),
                    requestStatus = "$endpointLabel HTTP ${response.code()}"
                )
                return ApiCallOutcome.HttpError(
                    code = response.code(),
                    message = msg
                )
            }

            val body = response.body()
                ?: run {
                    val msg = "Empty JSON response body."
                    sessionManager.onApiError(
                        message = msg,
                        httpStatus = response.code(),
                        requestStatus = "$endpointLabel Empty Body"
                    )
                    return ApiCallOutcome.InvalidResponse(msg)
                }

            val apiCode = codeExtractor(body)
            if (apiCode != 0) {
                if (apiCode == 401 || apiCode == 403) {
                    val reason = msgExtractor(body) ?: "API session rejected (code $apiCode)."
                    sessionManager.onApiAuthenticationRejected(
                        httpCode = apiCode,
                        reason = reason,
                        requestStatus = "$endpointLabel Rejected (code=$apiCode)"
                    )
                    return ApiCallOutcome.SessionExpired(reason)
                }
                val msg = "API returned code=$apiCode (${msgExtractor(body) ?: "Unknown"})"
                sessionManager.onApiError(
                    message = msg,
                    httpStatus = response.code(),
                    requestStatus = "$endpointLabel API code=$apiCode"
                )
                return ApiCallOutcome.InvalidResponse(msg)
            }

            sessionManager.onApiAuthenticationVerified(
                httpStatus = response.code(),
                requestStatus = "$endpointLabel HTTP ${response.code()} OK (code=0)"
            )
            ApiCallOutcome.Success(body = body, roundTripMs = rttMs)
        } catch (io: IOException) {
            val msg = io.message ?: "Network communication failure."
            sessionManager.onApiError(
                message = msg,
                httpStatus = null,
                requestStatus = "$endpointLabel Network Error"
            )
            ApiCallOutcome.NetworkError(msg)
        }
    }
}
