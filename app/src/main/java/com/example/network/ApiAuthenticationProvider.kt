package com.example.network

import com.example.security.WebsiteSessionManager

data class AuthorizedApiParams(
    val bearerAuthorization: String,
    val random: String,
    val signature: String,
    val timestamp: Long
)

data class SignedRequestParams(
    val random: String,
    val signature: String,
    val timestamp: Long
)

class MissingApiAuthenticationException(
    val missingBearerToken: Boolean,
    val missingSignatureAlgorithm: Boolean,
    message: String
) : IllegalStateException(message)

/**
 * Contract for acquiring an authorized `Authorization: Bearer <token>` credential from an
 * officially supported login/session flow.
 *
 * Security requirements:
 * - Never hardcode tokens or reuse expired credentials.
 * - Never scrape passwords, OTPs, localStorage, or private storage from external SPAs.
 * - Return Result.failure(MissingApiAuthenticationException) when unavailable.
 */
interface AuthorizedApiCredentialProvider {
    suspend fun getBearerAuthorization(): Result<String>
    suspend fun isCredentialConfigured(): Boolean
}

/**
 * Contract for renewing an expired or expiring Bearer token via an officially documented
 * token-refresh endpoint or session grant.
 *
 * Security requirements:
 * - Must fail cleanly without fabricating credentials or silently replaying user passwords.
 * - Implementations must only retry once per expired request cycle.
 */
interface ApiTokenRefreshProvider {
    fun isRefreshSupported(): Boolean
    suspend fun refreshBearerAuthorization(expiredAuthorization: String?): Result<String>
}

/**
 * Contract for producing the verified `random`, `signature`, and `timestamp` query tuple
 * required by `api.ar-lottery01.com` for signed GET requests.
 *
 * Security requirements:
 * - Must use only an officially documented/authorized signing specification or authorized backend.
 * - Never guess, reverse-bruteforce, or fabricate signatures.
 */
interface ApiSignatureProvider {
    fun isSignatureAlgorithmConfigured(): Boolean
    suspend fun signRequest(queryParams: Map<String, String>): Result<SignedRequestParams>
}

/**
 * Unified provider supplying all authorized parameters required for direct WinGo_1M API calls.
 */
interface ApiAuthenticationProvider {
    suspend fun getAuthorizedParams(queryParams: Map<String, String>): Result<AuthorizedApiParams>
}

/**
 * Composes [AuthorizedApiCredentialProvider] and [ApiSignatureProvider] without inventing
 * missing credentials or signature algorithms.
 */
class CompositeApiAuthenticationProvider(
    private val credentialProvider: AuthorizedApiCredentialProvider,
    private val signatureProvider: ApiSignatureProvider
) : ApiAuthenticationProvider {

    override suspend fun getAuthorizedParams(queryParams: Map<String, String>): Result<AuthorizedApiParams> {
        val bearerResult = credentialProvider.getBearerAuthorization()
        val sigConfigured = signatureProvider.isSignatureAlgorithmConfigured()
        val sigResult = if (sigConfigured) {
            signatureProvider.signRequest(queryParams)
        } else {
            Result.failure(
                MissingApiAuthenticationException(
                    missingBearerToken = false,
                    missingSignatureAlgorithm = true,
                    message = "dynamic request signature algorithm (random, signature, timestamp) is not configured"
                )
            )
        }

        val missingBearer = bearerResult.isFailure
        val missingSig = !sigConfigured || sigResult.isFailure

        if (missingBearer || missingSig) {
            val missingReasons = buildList {
                if (missingBearer) {
                    add(bearerResult.exceptionOrNull()?.message ?: "Authorization: Bearer <valid session token>")
                }
                if (missingSig) {
                    add(sigResult.exceptionOrNull()?.message ?: "dynamic request signature algorithm (random, signature, timestamp)")
                }
            }
            return Result.failure(
                MissingApiAuthenticationException(
                    missingBearerToken = missingBearer,
                    missingSignatureAlgorithm = missingSig,
                    message = "Request stopped: ${missingReasons.joinToString(" and ")}."
                )
            )
        }

        val bearer = bearerResult.getOrThrow()
        val signed = sigResult.getOrThrow()
        return Result.success(
            AuthorizedApiParams(
                bearerAuthorization = bearer,
                random = signed.random,
                signature = signed.signature,
                timestamp = signed.timestamp
            )
        )
    }
}

/**
 * Reads cooperative website-granted Bearer tokens stored via Android Keystore encryption
 * in [WebsiteSessionManager]. Never scrapes WebView JS memory or exposes secrets to JS.
 */
class KeystoreBackedCredentialProvider(
    private val sessionManager: WebsiteSessionManager
) : AuthorizedApiCredentialProvider {
    override suspend fun isCredentialConfigured(): Boolean =
        !sessionManager.getStoredTokenOrNull().isNullOrBlank()

    override suspend fun getBearerAuthorization(): Result<String> {
        val token = sessionManager.getStoredTokenOrNull()
        if (token.isNullOrBlank()) {
            return Result.failure(
                MissingApiAuthenticationException(
                    missingBearerToken = true,
                    missingSignatureAlgorithm = false,
                    message = "authorized Bearer session credential (LOGIN REQUIRED)"
                )
            )
        }
        val normalized = if (token.startsWith("Bearer ", ignoreCase = true)) token else "Bearer $token"
        return Result.success(normalized)
    }
}

/**
 * Default unconfigured provider that intentionally blocks direct API calls because
 * authorized token acquisition, official token refresh, and verified signature generation
 * are unavailable from the external SPA.
 */
class UnconfiguredApiAuthenticationProvider(
    private val sessionManager: WebsiteSessionManager
) : ApiAuthenticationProvider,
    AuthorizedApiCredentialProvider,
    ApiTokenRefreshProvider,
    ApiSignatureProvider {

    override suspend fun isCredentialConfigured(): Boolean =
        !sessionManager.getStoredTokenOrNull().isNullOrBlank()

    override suspend fun getBearerAuthorization(): Result<String> {
        val token = sessionManager.getStoredTokenOrNull()
        if (token.isNullOrBlank()) {
            return Result.failure(
                MissingApiAuthenticationException(
                    missingBearerToken = true,
                    missingSignatureAlgorithm = true,
                    message = "Authorization: Bearer <valid session token>"
                )
            )
        }
        val normalized = if (token.startsWith("Bearer ", ignoreCase = true)) token else "Bearer $token"
        return Result.success(normalized)
    }

    override fun isRefreshSupported(): Boolean = false

    override suspend fun refreshBearerAuthorization(expiredAuthorization: String?): Result<String> =
        Result.failure(
            IllegalStateException("Official Bearer token refresh endpoint is unavailable/unconfigured.")
        )

    override fun isSignatureAlgorithmConfigured(): Boolean = false

    override suspend fun signRequest(queryParams: Map<String, String>): Result<SignedRequestParams> =
        Result.failure(
            MissingApiAuthenticationException(
                missingBearerToken = false,
                missingSignatureAlgorithm = true,
                message = "dynamic request signature algorithm (random, signature, timestamp)"
            )
        )

    override suspend fun getAuthorizedParams(queryParams: Map<String, String>): Result<AuthorizedApiParams> {
        val token = sessionManager.getStoredTokenOrNull()
        val missingToken = token.isNullOrBlank()
        val missingSignature = !isSignatureAlgorithmConfigured()

        val missingItems = buildList {
            if (missingToken) add("Authorization: Bearer <valid session token>")
            if (missingSignature) add("dynamic request signature algorithm (random, signature, timestamp)")
        }
        return Result.failure(
            MissingApiAuthenticationException(
                missingBearerToken = missingToken,
                missingSignatureAlgorithm = missingSignature,
                message = if (missingToken) {
                    "AUTH_REQUIRED: missing ${missingItems.joinToString(" and ")}. " +
                        "External 9K Club SPA does not expose a supported session exchange, and WebView cookies alone do not authenticate api.ar-lottery01.com OkHttp requests."
                } else {
                    "SIGNATURE NOT CONFIGURED: session token present, but official request signature algorithm (random, signature, timestamp) for api.ar-lottery01.com is not configured."
                }
            )
        )
    }
}
