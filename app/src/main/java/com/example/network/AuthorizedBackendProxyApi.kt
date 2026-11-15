package com.example.network

import com.example.domain.model.WinGoGameConfig
import com.example.security.WebsiteSessionManager
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Query
import java.io.IOException

/**
 * Configuration for an optional authorized HTTPS backend proxy service.
 *
 * Security rules:
 * - Only HTTPS backend base URLs are accepted.
 * - Never embed upstream provider secrets, signing keys, or service credentials inside the APK.
 * - This interface remains unconfigured and non-operational until an officially authorized backend
 *   service URL is configured and verified.
 */
data class BackendProxyConfig(
    val baseUrl: String? = null
) {
    val isConfiguredHttps: Boolean
        get() = !baseUrl.isNullOrBlank() && baseUrl.trim().startsWith("https://", ignoreCase = true)
}

sealed interface BackendIntegrationStatus {
    data class NotConfigured(val reason: String) : BackendIntegrationStatus
    data class ConfiguredAwaitingVerification(val baseUrl: String) : BackendIntegrationStatus
}

/**
 * Android-facing Retrofit interface for an authorized HTTPS backend proxy that integrates
 * with official upstream WinGo_1M APIs server-side.
 */
interface AuthorizedBackendProxyService {

    @GET("api/wingo/v1/panel")
    suspend fun getPanel(
        @Header("Authorization") appClientAuthorization: String? = null,
        @Query("gameCode") gameCode: String = WinGoGameConfig.GAME_CODE,
        @Query("language") language: String = WinGoGameConfig.LANGUAGE
    ): Response<GetPanelResponse>

    @GET("api/wingo/v1/history")
    suspend fun getHistory(
        @Header("Authorization") appClientAuthorization: String? = null,
        @Query("gameCode") gameCode: String = WinGoGameConfig.GAME_CODE,
        @Query("pageNo") pageNo: Int,
        @Query("pageSize") pageSize: Int = WinGoGameConfig.HISTORY_PAGE_SIZE,
        @Query("language") language: String = WinGoGameConfig.LANGUAGE
    ): Response<HistoryPageResponse>
}

/**
 * Optional [ApiRequestManager] backed by [AuthorizedBackendProxyService].
 * Remains disabled unless a valid HTTPS backend proxy URL and optional client credential are supplied.
 */
class BackendProxyApiRequestManager(
    private val config: BackendProxyConfig,
    private val serviceProvider: (String) -> AuthorizedBackendProxyService,
    private val sessionManager: WebsiteSessionManager,
    private val networkMonitor: NetworkConnectivityMonitor,
    private val clientAuthorizationProvider: (suspend () -> String?)? = null
) : ApiRequestManager {

    val integrationStatus: BackendIntegrationStatus
        get() = if (config.isConfiguredHttps) {
            BackendIntegrationStatus.ConfiguredAwaitingVerification(config.baseUrl!!.trim())
        } else {
            BackendIntegrationStatus.NotConfigured(UNCONFIGURED_REASON)
        }

    override suspend fun fetchHistoryPage(
        pageNo: Int,
        pageSize: Int
    ): ApiCallOutcome<HistoryPageResponse> {
        val service = resolveConfiguredServiceOrNull()
            ?: return missingProxyOutcome()
        if (!networkMonitor.isOnline()) {
            return ApiCallOutcome.NetworkError("Device is offline.")
        }
        val authHeader = clientAuthorizationProvider?.invoke()
        return executeProxyCall(
            call = {
                service.getHistory(
                    appClientAuthorization = authHeader,
                    gameCode = WinGoGameConfig.GAME_CODE,
                    pageNo = pageNo,
                    pageSize = pageSize,
                    language = WinGoGameConfig.LANGUAGE
                )
            },
            codeExtractor = { it.code },
            msgExtractor = { it.msg }
        )
    }

    override suspend fun fetchPanel(): ApiCallOutcome<GetPanelResponse> {
        val service = resolveConfiguredServiceOrNull()
            ?: return missingProxyOutcome()
        if (!networkMonitor.isOnline()) {
            return ApiCallOutcome.NetworkError("Device is offline.")
        }
        val authHeader = clientAuthorizationProvider?.invoke()
        return executeProxyCall(
            call = {
                service.getPanel(
                    appClientAuthorization = authHeader,
                    gameCode = WinGoGameConfig.GAME_CODE,
                    language = WinGoGameConfig.LANGUAGE
                )
            },
            codeExtractor = { it.code ?: -1 },
            msgExtractor = { it.msg }
        )
    }

    private fun resolveConfiguredServiceOrNull(): AuthorizedBackendProxyService? {
        if (!config.isConfiguredHttps) return null
        val normalizedBase = config.baseUrl!!.trim().let { if (it.endsWith("/")) it else "$it/" }
        return serviceProvider(normalizedBase)
    }

    private fun missingProxyOutcome(): ApiCallOutcome.MissingAuth {
        sessionManager.onApiCredentialsMissing(
            missingBearerToken = true,
            missingSignatureAlgorithm = true,
            details = UNCONFIGURED_REASON,
            requestStatus = "Blocked (Proxy Not Configured)"
        )
        return ApiCallOutcome.MissingAuth(UNCONFIGURED_REASON)
    }

    private suspend fun <T> executeProxyCall(
        call: suspend () -> Response<T>,
        codeExtractor: (T) -> Int,
        msgExtractor: (T) -> String?
    ): ApiCallOutcome<T> {
        val startNanos = System.nanoTime()
        return try {
            val response = call()
            val rttMs = ((System.nanoTime() - startNanos) / 1_000_000L).coerceAtLeast(0L)
            if (response.code() == 401 || response.code() == 403) {
                val reason = "Backend proxy rejected authorization (HTTP ${response.code()})."
                sessionManager.onApiAuthenticationRejected(
                    httpCode = response.code(),
                    reason = reason,
                    requestStatus = "Proxy Rejected (HTTP ${response.code()})"
                )
                return ApiCallOutcome.SessionExpired(reason)
            }
            if (!response.isSuccessful) {
                val msg = "HTTP ${response.code()} ${response.message()}"
                sessionManager.onApiError(
                    message = msg,
                    httpStatus = response.code(),
                    requestStatus = "Proxy HTTP ${response.code()}"
                )
                return ApiCallOutcome.HttpError(response.code(), msg)
            }
            val body = response.body()
                ?: run {
                    val msg = "Empty backend proxy JSON response body."
                    sessionManager.onApiError(
                        message = msg,
                        httpStatus = response.code(),
                        requestStatus = "Proxy Empty Body"
                    )
                    return ApiCallOutcome.InvalidResponse(msg)
                }
            val apiCode = codeExtractor(body)
            if (apiCode != 0) {
                val msg = "Backend proxy returned code=$apiCode (${msgExtractor(body) ?: "Unknown"})"
                sessionManager.onApiError(
                    message = msg,
                    httpStatus = response.code(),
                    requestStatus = "Proxy API code=$apiCode"
                )
                return ApiCallOutcome.InvalidResponse(msg)
            }
            sessionManager.onApiAuthenticationVerified(
                httpStatus = response.code(),
                requestStatus = "Proxy HTTP ${response.code()} OK (code=0)"
            )
            ApiCallOutcome.Success(body = body, roundTripMs = rttMs)
        } catch (io: IOException) {
            val msg = io.message ?: "Backend proxy network failure."
            sessionManager.onApiError(
                message = msg,
                httpStatus = null,
                requestStatus = "Proxy Network Error"
            )
            ApiCallOutcome.NetworkError(msg)
        }
    }

    companion object {
        const val UNCONFIGURED_REASON =
            "Authorized HTTPS backend proxy is not configured (GET /api/wingo/v1/panel, GET /api/wingo/v1/history). Upstream provider credentials are never stored in the APK."
    }
}
