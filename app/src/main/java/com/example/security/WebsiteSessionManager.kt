package com.example.security

import android.net.Uri
import android.webkit.JavascriptInterface
import com.example.domain.model.WinGoGameConfig
import com.example.webview.EvidenceConfidenceLevel
import com.example.webview.UrlRouteCategory
import com.example.webview.WebsiteAuthIndicatorRegistry
import com.example.webview.WebsiteAuthenticationState
import com.example.webview.WebsiteLoginState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class SessionSignalSource(val label: String) {
    WEBSITE_COOKIE("Verified Website Session"),
    COOPERATIVE_BRIDGE("Origin-Validated Website Session Bridge"),
    WEBVIEW_DOM_OBSERVER("WebMessageListener DOM Observer")
}

enum class ApiAuthStatusCode(val uiLabel: String) {
    API_NOT_CONFIGURED("NOT CONFIGURED"),
    API_AUTH_REQUIRED("AUTH REQUIRED"),
    API_CONNECTING("CONNECTING"),
    API_CONNECTED("CONNECTED"),
    API_TOKEN_EXPIRED("TOKEN EXPIRED"),
    API_SIGNATURE_NOT_CONFIGURED("SIGNATURE NOT CONFIGURED"),
    API_REJECTED("REJECTED"),
    API_ERROR("ERROR")
}

enum class WebsiteDetectionState(val label: String) {
    WEBVIEW_LOADING("WEBVIEW_LOADING"),
    WEBSITE_NOT_LOGGED_IN("WEBSITE_NOT_LOGGED_IN"),
    WEBSITE_AUTHENTICATION_UNKNOWN("WEBSITE_AUTHENTICATION_UNKNOWN"),
    WEBSITE_LOGIN_CONFIRMED("WEBSITE_LOGIN_CONFIRMED"),
    SESSION_EXCHANGE_PENDING("SESSION_EXCHANGE_PENDING"),
    SESSION_EXPIRED("SESSION_EXPIRED")
}

sealed interface WebsiteSessionState {
    data class Unknown(
        val limitationNotice: String = WebsiteSessionManager.DEFAULT_LIMITATION_NOTICE
    ) : WebsiteSessionState

    data class Authenticated(
        val signalSource: SessionSignalSource,
        val detectedAtMillis: Long,
        val originHost: String
    ) : WebsiteSessionState

    data class Unauthenticated(
        val reason: String
    ) : WebsiteSessionState
}

sealed interface ApiAuthenticationState {
    data object Ready : ApiAuthenticationState

    data class Authorizing(
        val detail: String = "Exchanging or validating authorized API session..."
    ) : ApiAuthenticationState

    data class AuthRequired(
        val missingBearerToken: Boolean = true,
        val missingSignatureAlgorithm: Boolean = true,
        val details: String = WebsiteSessionManager.DEFAULT_API_AUTH_NOTICE
    ) : ApiAuthenticationState

    data class SignatureNotConfigured(
        val details: String = WebsiteSessionManager.MISSING_SIGNATURE_NOTICE
    ) : ApiAuthenticationState

    data class TokenExpired(
        val reason: String
    ) : ApiAuthenticationState

    data class Rejected(
        val httpCode: Int?,
        val reason: String
    ) : ApiAuthenticationState

    data class ApiError(
        val message: String
    ) : ApiAuthenticationState
}

enum class DiagnosticLoginState(val label: String) {
    UNKNOWN("UNKNOWN"),
    AUTHENTICATED("AUTHENTICATED"),
    UNAUTHENTICATED("UNAUTHENTICATED")
}

enum class DiagnosticSessionCheck(val label: String) {
    SUCCESS("SUCCESS"),
    FAILED("FAILED"),
    NOT_AVAILABLE("NOT AVAILABLE")
}

enum class DiagnosticApiAuthStatus(
    val label: String,
    val stateCode: String
) {
    READY("API CONNECTED", "API_CONNECTED"),
    AUTHORIZING("AUTHORIZING", "SESSION_EXCHANGE_PENDING"),
    AUTH_REQUIRED("LOGIN REQUIRED", "API_AUTH_REQUIRED"),
    SIGNATURE_NOT_CONFIGURED("SIGNATURE NOT CONFIGURED", "SIGNATURE_NOT_CONFIGURED"),
    TOKEN_EXPIRED("TOKEN EXPIRED", "TOKEN_EXPIRED"),
    REJECTED("AUTHORIZATION REJECTED", "AUTHORIZATION_REJECTED"),
    API_ERROR("API ERROR", "API_ERROR")
}

data class LoginDiagnosticState(
    val webViewLoaded: Boolean = false,
    val isApprovedDomain: Boolean = false,
    val currentApprovedHost: String? = null,
    val lastReportedWebUrl: String? = null,
    val urlRouteCategory: UrlRouteCategory = UrlRouteCategory.LOADING,
    val isScannerPollingActive: Boolean = false,
    val observerState: String = "STOPPED",
    val websiteDetectionMethod: String = "WebMessageListener + DOM MutationObserver (Awaiting load)",
    val websiteDetectionState: WebsiteDetectionState = WebsiteDetectionState.WEBVIEW_LOADING,
    val websiteAuthState: WebsiteAuthenticationState = WebsiteAuthenticationState.WEBVIEW_LOADING,
    val websiteLoginState: WebsiteLoginState = WebsiteLoginState.WEBVIEW_LOADING,
    val confidenceLevel: EvidenceConfidenceLevel = EvidenceConfidenceLevel.NONE,
    val lastVerifiedAtMillis: Long? = null,
    val evidenceReason: String = "Waiting for 9K Club main-frame page load completion.",
    val persistedHintState: WebsiteAuthenticationState? = null,
    val loginState: DiagnosticLoginState = DiagnosticLoginState.UNKNOWN,
    val sessionCheck: DiagnosticSessionCheck = DiagnosticSessionCheck.NOT_AVAILABLE,
    val credentialAvailable: Boolean = false,
    val signatureProviderAvailable: Boolean = false,
    val apiAuthStatus: DiagnosticApiAuthStatus = DiagnosticApiAuthStatus.AUTH_REQUIRED,
    val apiStatusCode: ApiAuthStatusCode = ApiAuthStatusCode.API_AUTH_REQUIRED,
    val lastRequestStatus: String = "Not Attempted",
    val lastHttpStatus: Int? = null,
    val apiAuthReason: String = WebsiteSessionManager.DEFAULT_API_AUTH_NOTICE,
    val floatingButtonVisible: Boolean = false,
    val hidingReason: String = "Waiting for 9K Club main-frame page load completion."
)

/**
 * Legitimate website session detector and Keystore-backed session grant manager.
 *
 * Security & correctness guarantees:
 * - Strictly separates WebsiteSessionState (WebView user session) from ApiAuthenticationState
 *   (api.ar-lottery01.com API credentials). An API 401/403 NEVER marks the website user logged out.
 * - Never guesses login state from URL/registration/hash path changes alone.
 * - Never extracts, logs, displays, or stores passwords, OTPs, raw cookies, or unencrypted tokens.
 * - Never scrapes private JS memory or injects scripts to steal tokens.
 * - Only accepts explicit origin-validated signals or session exchanges via the cooperative
 *   WebsiteSessionBridge when invoked on an approved 9K Club HTTPS origin.
 * - Never exposes stored credentials back to WebView JavaScript.
 */
class WebsiteSessionManager(
    private val cryptoManager: KeystoreCryptoManager,
    private val wallTimeProvider: () -> Long = { System.currentTimeMillis() }
) {
    private val _websiteSessionState = MutableStateFlow<WebsiteSessionState>(
        WebsiteSessionState.Unknown()
    )
    val websiteSessionState: StateFlow<WebsiteSessionState> = _websiteSessionState.asStateFlow()

    private val _apiAuthState = MutableStateFlow<ApiAuthenticationState>(
        ApiAuthenticationState.AuthRequired(
            missingBearerToken = true,
            missingSignatureAlgorithm = true,
            details = DEFAULT_API_AUTH_NOTICE
        )
    )
    val apiAuthState: StateFlow<ApiAuthenticationState> = _apiAuthState.asStateFlow()

    private val _diagnostics = MutableStateFlow(
        LoginDiagnosticState(persistedHintState = readPersistedAuthHint())
    )
    val diagnostics: StateFlow<LoginDiagnosticState> = _diagnostics.asStateFlow()

    @Volatile
    private var lastVerifiedOriginUrl: String? = null

    @Volatile
    private var isMainFrameLoaded: Boolean = false

    @Volatile
    private var sessionExpiredFlag: Boolean = false

    private fun readPersistedAuthHint(): WebsiteAuthenticationState? {
        val raw = cryptoManager.getDecryptedString(KEY_LAST_AUTH_HINT_STATE) ?: return null
        return runCatching { WebsiteAuthenticationState.valueOf(raw) }.getOrNull()
    }

    private fun persistAuthHint(state: WebsiteAuthenticationState, timestampMs: Long) {
        if (state == WebsiteAuthenticationState.WEBVIEW_LOADING ||
            state == WebsiteAuthenticationState.LOGIN_IN_PROGRESS ||
            state == WebsiteAuthenticationState.LOGOUT_IN_PROGRESS
        ) {
            return
        }
        cryptoManager.putEncryptedString(KEY_LAST_AUTH_HINT_STATE, state.name)
        cryptoManager.putEncryptedString(KEY_LAST_AUTH_HINT_TIME_MS, timestampMs.toString())
    }

    fun onWebViewNavigationState(
        url: String?,
        mainFrameLoaded: Boolean,
        loadError: String? = null
    ) {
        isMainFrameLoaded = mainFrameLoaded
        val approved = isApprovedHttpsUrl(url)
        val approvedHost = if (approved) runCatching { Uri.parse(url).host?.lowercase() }.getOrNull() else null
        if (approved && !url.isNullOrBlank()) {
            lastVerifiedOriginUrl = url
        }

        val currentWebsite = _websiteSessionState.value
        val currentDiag = _diagnostics.value

        if (loadError != null) {
            isMainFrameLoaded = false
            emitDiagnostics {
                it.copy(
                    webViewLoaded = false,
                    isApprovedDomain = approved,
                    currentApprovedHost = approvedHost ?: currentDiag.currentApprovedHost,
                    isScannerPollingActive = false,
                    websiteDetectionMethod = if (currentWebsite is WebsiteSessionState.Authenticated) {
                        currentWebsite.signalSource.label
                    } else {
                        "WebView Load Failed"
                    },
                    websiteDetectionState = resolveWebsiteDetectionState(currentWebsite, mainFrameLoaded = false),
                    loginState = mapWebsiteLoginState(currentWebsite),
                    sessionCheck = DiagnosticSessionCheck.FAILED,
                    floatingButtonVisible = currentWebsite is WebsiteSessionState.Authenticated,
                    hidingReason = if (currentWebsite is WebsiteSessionState.Authenticated) {
                        "Authenticated website session active."
                    } else {
                        "WebView load error: $loadError"
                    }
                )
            }
            return
        }

        if (!approved && !url.isNullOrBlank()) {
            emitDiagnostics {
                it.copy(
                    webViewLoaded = mainFrameLoaded,
                    isApprovedDomain = false,
                    currentApprovedHost = null,
                    isScannerPollingActive = false,
                    websiteDetectionMethod = "Origin Check Failed (Non-approved host)",
                    websiteDetectionState = resolveWebsiteDetectionState(currentWebsite, mainFrameLoaded),
                    loginState = mapWebsiteLoginState(currentWebsite),
                    sessionCheck = DiagnosticSessionCheck.FAILED,
                    floatingButtonVisible = currentWebsite is WebsiteSessionState.Authenticated,
                    hidingReason = if (currentWebsite is WebsiteSessionState.Authenticated) {
                        "Authenticated website session active."
                    } else {
                        "Current WebView origin is not an approved HTTPS 9K Club domain."
                    }
                )
            }
            return
        }

        emitDiagnostics {
            it.copy(
                webViewLoaded = mainFrameLoaded,
                isApprovedDomain = approved,
                currentApprovedHost = approvedHost ?: currentDiag.currentApprovedHost,
                websiteDetectionMethod = when {
                    currentWebsite is WebsiteSessionState.Authenticated -> currentWebsite.signalSource.label
                    !mainFrameLoaded -> "WebView Main Frame Loading..."
                    else -> currentDiag.websiteDetectionMethod
                },
                websiteDetectionState = resolveWebsiteDetectionState(currentWebsite, mainFrameLoaded),
                loginState = mapWebsiteLoginState(currentWebsite),
                floatingButtonVisible = currentWebsite is WebsiteSessionState.Authenticated,
                hidingReason = when {
                    currentWebsite is WebsiteSessionState.Authenticated -> "Authenticated website session active."
                    !mainFrameLoaded -> "Waiting for 9K Club main-frame page load completion."
                    else -> currentDiag.hidingReason
                }
            )
        }
    }

    /**
     * Evaluates origin compliance on navigation without inspecting raw cookies or storage secrets.
     */
    fun evaluateCookieSession(url: String?, mainFrameLoaded: Boolean = isMainFrameLoaded) {
        isMainFrameLoaded = mainFrameLoaded
        val currentWebsite = _websiteSessionState.value

        if (url.isNullOrBlank()) {
            emitDiagnostics {
                it.copy(
                    webViewLoaded = mainFrameLoaded,
                    isApprovedDomain = false,
                    currentApprovedHost = null,
                    websiteDetectionMethod = "Origin Verification (Empty URL)",
                    websiteDetectionState = resolveWebsiteDetectionState(currentWebsite, mainFrameLoaded),
                    loginState = mapWebsiteLoginState(currentWebsite),
                    sessionCheck = DiagnosticSessionCheck.FAILED,
                    floatingButtonVisible = currentWebsite is WebsiteSessionState.Authenticated,
                    hidingReason = "WebView URL is empty; waiting for approved 9K Club origin."
                )
            }
            return
        }

        val uri = runCatching { Uri.parse(url) }.getOrNull()
        val host = uri?.host?.lowercase()
        if (uri == null || host == null || !uri.scheme.equals("https", ignoreCase = true) || !isApprovedHost(host)) {
            emitDiagnostics {
                it.copy(
                    webViewLoaded = mainFrameLoaded,
                    isApprovedDomain = false,
                    currentApprovedHost = null,
                    isScannerPollingActive = false,
                    websiteDetectionMethod = "Origin Check Failed (Non-approved host)",
                    websiteDetectionState = resolveWebsiteDetectionState(currentWebsite, mainFrameLoaded),
                    loginState = mapWebsiteLoginState(currentWebsite),
                    sessionCheck = DiagnosticSessionCheck.FAILED,
                    floatingButtonVisible = currentWebsite is WebsiteSessionState.Authenticated,
                    hidingReason = if (currentWebsite is WebsiteSessionState.Authenticated) {
                        "Authenticated website session active."
                    } else {
                        "Current WebView domain is not in approved HTTPS 9K Club hosts."
                    }
                )
            }
            return
        }

        lastVerifiedOriginUrl = url
        if (currentWebsite is WebsiteSessionState.Authenticated) {
            emitDiagnostics {
                it.copy(
                    webViewLoaded = mainFrameLoaded,
                    isApprovedDomain = true,
                    currentApprovedHost = host,
                    websiteDetectionMethod = currentWebsite.signalSource.label,
                    websiteDetectionState = resolveWebsiteDetectionState(currentWebsite, mainFrameLoaded),
                    loginState = DiagnosticLoginState.AUTHENTICATED,
                    sessionCheck = DiagnosticSessionCheck.SUCCESS,
                    floatingButtonVisible = true,
                    hidingReason = "Visible (verified website session; independent of predictor API state)."
                )
            }
        } else {
            emitDiagnostics {
                it.copy(
                    webViewLoaded = mainFrameLoaded,
                    isApprovedDomain = true,
                    currentApprovedHost = host,
                    websiteDetectionMethod = if (mainFrameLoaded) {
                        "WebMessageListener + DOM MutationObserver"
                    } else {
                        "WebView Main Frame Loading..."
                    },
                    websiteDetectionState = resolveWebsiteDetectionState(currentWebsite, mainFrameLoaded),
                    loginState = mapWebsiteLoginState(currentWebsite),
                    sessionCheck = if (currentWebsite is WebsiteSessionState.Unauthenticated) {
                        DiagnosticSessionCheck.FAILED
                    } else {
                        DiagnosticSessionCheck.NOT_AVAILABLE
                    },
                    floatingButtonVisible = false,
                    hidingReason = when {
                        currentWebsite is WebsiteSessionState.Unauthenticated -> currentWebsite.reason
                        !mainFrameLoaded -> "Waiting for 9K Club main-frame page load completion."
                        else -> MISSING_WEBSITE_SIGNAL_REASON
                    }
                )
            }
        }
    }

    fun onLoginDetectorStatusChanged(
        active: Boolean,
        methodDescription: String,
        observerStateLabel: String = if (active) "ACTIVE (MutationObserver)" else "STOPPED"
    ) {
        emitDiagnostics {
            it.copy(
                isScannerPollingActive = active,
                observerState = observerStateLabel,
                websiteDetectionMethod = if (_websiteSessionState.value is WebsiteSessionState.Authenticated) {
                    (_websiteSessionState.value as WebsiteSessionState.Authenticated).signalSource.label
                } else {
                    methodDescription
                }
            )
        }
    }

    fun onLoginBridgeRejected(reason: String) {
        emitDiagnostics {
            it.copy(
                isScannerPollingActive = false,
                websiteDetectionMethod = "Bridge Rejected ($reason)",
                sessionCheck = if (_websiteSessionState.value is WebsiteSessionState.Authenticated) {
                    DiagnosticSessionCheck.SUCCESS
                } else {
                    DiagnosticSessionCheck.FAILED
                }
            )
        }
    }

    fun onScannerUrlReported(sanitizedUrl: String?, currentWebViewUrl: String?) {
        val host = validateApprovedHost(currentWebViewUrl)
        if (host == null) {
            emitDiagnostics {
                it.copy(
                    isApprovedDomain = false,
                    isScannerPollingActive = false,
                    websiteDetectionMethod = "Origin Rejected (Unapproved host)",
                    sessionCheck = DiagnosticSessionCheck.FAILED
                )
            }
            return
        }
        // Reporting a URL alone NEVER authenticates a user; it only refreshes sanitized route telemetry.
        emitDiagnostics {
            it.copy(
                isApprovedDomain = true,
                currentApprovedHost = host,
                lastReportedWebUrl = sanitizedUrl ?: it.lastReportedWebUrl
            )
        }
    }

    fun onDomLoginStateObserved(
        currentWebViewUrl: String?,
        sanitizedUrl: String?,
        resolvedState: WebsiteLoginState,
        indicatorKind: String? = null
    ) {
        val host = validateApprovedHost(currentWebViewUrl)
        if (host == null) {
            emitDiagnostics {
                it.copy(
                    isApprovedDomain = false,
                    isScannerPollingActive = false,
                    websiteDetectionMethod = "DOM Detector Rejected (Untrusted origin)",
                    sessionCheck = DiagnosticSessionCheck.FAILED
                )
            }
            return
        }

        val currentWebsite = _websiteSessionState.value
        when (resolvedState) {
            WebsiteLoginState.SESSION_EXPIRED -> {
                onWebsiteSessionExpired("Website session expired indicator detected in DOM.")
            }

            WebsiteLoginState.LOGIN_CONFIRMED -> {
                sessionExpiredFlag = false
                val existingStrongSignal = currentWebsite is WebsiteSessionState.Authenticated &&
                    currentWebsite.signalSource != SessionSignalSource.WEBVIEW_DOM_OBSERVER
                val updatedWebsite = if (existingStrongSignal) {
                    currentWebsite
                } else {
                    val now = wallTimeProvider()
                    cryptoManager.putEncryptedString(KEY_LAST_VERIFIED_HOST, host)
                    val auth = WebsiteSessionState.Authenticated(
                        signalSource = SessionSignalSource.WEBVIEW_DOM_OBSERVER,
                        detectedAtMillis = now,
                        originHost = host
                    )
                    _websiteSessionState.value = auth
                    auth
                }

                // Never claim API_CONNECTED from website login; keep GetPanel in AUTH_REQUIRED until genuine API credentials exist.
                val reasonNote = if (_apiAuthState.value is ApiAuthenticationState.AuthRequired) {
                    "Website login confirmed (${indicatorKind ?: "DOM"}), but api.ar-lottery01.com GetPanel Bearer token and signing specification remain unavailable (AUTH REQUIRED)."
                } else {
                    _diagnostics.value.apiAuthReason
                }
                if (_apiAuthState.value is ApiAuthenticationState.AuthRequired) {
                    _apiAuthState.value = ApiAuthenticationState.AuthRequired(
                        missingBearerToken = !hasValidStoredTokenNonMutating(),
                        missingSignatureAlgorithm = !_diagnostics.value.signatureProviderAvailable,
                        details = reasonNote
                    )
                }

                val now = wallTimeProvider()
                emitDiagnostics {
                    it.copy(
                        webViewLoaded = true,
                        isApprovedDomain = true,
                        currentApprovedHost = host,
                        lastReportedWebUrl = sanitizedUrl ?: it.lastReportedWebUrl,
                        websiteDetectionMethod = if (existingStrongSignal) {
                            currentWebsite.signalSource.label
                        } else {
                            "WebMessageListener DOM (${indicatorKind ?: "dom_logout_control"})"
                        },
                        websiteAuthState = WebsiteAuthenticationState.LOGIN_CONFIRMED,
                        websiteDetectionState = resolveWebsiteDetectionState(updatedWebsite, mainFrameLoaded = true),
                        confidenceLevel = EvidenceConfidenceLevel.HIGH,
                        lastVerifiedAtMillis = now,
                        evidenceReason = "Website login confirmed via DOM (${indicatorKind ?: "dom_logout_control"}).",
                        loginState = DiagnosticLoginState.AUTHENTICATED,
                        sessionCheck = DiagnosticSessionCheck.SUCCESS,
                        apiAuthReason = reasonNote,
                        floatingButtonVisible = true,
                        hidingReason = "Visible (website login confirmed via DOM observer; independent of GetPanel API)."
                    )
                }
            }

            WebsiteLoginState.LOGGED_OUT -> {
                sessionExpiredFlag = false
                val now = wallTimeProvider()
                val logoutReason = "Login/register form active on 9K Club without authenticated DOM indicators."
                if (currentWebsite is WebsiteSessionState.Authenticated) {
                    markWebsiteLoggedOut(logoutReason)
                } else {
                    _websiteSessionState.value = WebsiteSessionState.Unauthenticated(logoutReason)
                }
                emitDiagnostics {
                    it.copy(
                        webViewLoaded = true,
                        isApprovedDomain = true,
                        currentApprovedHost = host,
                        lastReportedWebUrl = sanitizedUrl ?: it.lastReportedWebUrl,
                        websiteDetectionMethod = "WebMessageListener DOM (${indicatorKind ?: "login_form_dom"})",
                        websiteAuthState = WebsiteAuthenticationState.LOGGED_OUT,
                        websiteDetectionState = WebsiteDetectionState.WEBSITE_NOT_LOGGED_IN,
                        confidenceLevel = EvidenceConfidenceLevel.HIGH,
                        lastVerifiedAtMillis = now,
                        evidenceReason = logoutReason,
                        loginState = DiagnosticLoginState.UNAUTHENTICATED,
                        sessionCheck = DiagnosticSessionCheck.FAILED,
                        floatingButtonVisible = false,
                        hidingReason = logoutReason
                    )
                }
            }

            WebsiteLoginState.UNKNOWN, WebsiteLoginState.WEBVIEW_LOADING -> {
                sessionExpiredFlag = false
                val now = wallTimeProvider()
                if (currentWebsite is WebsiteSessionState.Authenticated &&
                    currentWebsite.signalSource == SessionSignalSource.WEBVIEW_DOM_OBSERVER
                ) {
                    cryptoManager.remove(KEY_LAST_VERIFIED_HOST)
                    _websiteSessionState.value = WebsiteSessionState.Unknown(
                        "DOM indicators inconclusive or conflicting; login state UNKNOWN."
                    )
                } else if (currentWebsite is WebsiteSessionState.Unauthenticated) {
                    _websiteSessionState.value = WebsiteSessionState.Unknown(
                        "DOM indicators inconclusive or conflicting; login state UNKNOWN."
                    )
                }
                val resolvedAuth = if (resolvedState == WebsiteLoginState.WEBVIEW_LOADING) {
                    WebsiteAuthenticationState.WEBVIEW_LOADING
                } else {
                    WebsiteAuthenticationState.UNKNOWN
                }
                val reason = if (indicatorKind == "conflicting_dom") {
                    "Conflicting login and logout indicators detected; state set to UNKNOWN."
                } else {
                    MISSING_WEBSITE_SIGNAL_REASON
                }
                emitDiagnostics {
                    it.copy(
                        webViewLoaded = true,
                        isApprovedDomain = true,
                        currentApprovedHost = host,
                        lastReportedWebUrl = sanitizedUrl ?: it.lastReportedWebUrl,
                        websiteDetectionMethod = "WebMessageListener DOM (${indicatorKind ?: "inconclusive_dom"})",
                        websiteAuthState = resolvedAuth,
                        websiteDetectionState = resolveWebsiteDetectionState(_websiteSessionState.value, mainFrameLoaded = true),
                        confidenceLevel = if (indicatorKind == "conflicting_dom") {
                            EvidenceConfidenceLevel.CONFLICTING
                        } else {
                            EvidenceConfidenceLevel.NONE
                        },
                        lastVerifiedAtMillis = now,
                        evidenceReason = reason,
                        loginState = mapWebsiteLoginState(_websiteSessionState.value),
                        sessionCheck = if (_websiteSessionState.value is WebsiteSessionState.Authenticated) {
                            DiagnosticSessionCheck.SUCCESS
                        } else {
                            DiagnosticSessionCheck.NOT_AVAILABLE
                        },
                        floatingButtonVisible = _websiteSessionState.value is WebsiteSessionState.Authenticated,
                        hidingReason = reason
                    )
                }
            }
        }
    }

    fun onMultiSignalEvaluation(
        currentWebViewUrl: String?,
        sanitizedUrl: String?,
        authState: WebsiteAuthenticationState,
        confidenceLevel: EvidenceConfidenceLevel,
        verifiedAtMillis: Long,
        evidenceReason: String,
        detectionMethod: String,
        urlRouteCategory: UrlRouteCategory,
        observerState: String,
        signalSource: SessionSignalSource = SessionSignalSource.WEBVIEW_DOM_OBSERVER
    ) {
        val host = validateApprovedHost(currentWebViewUrl)
        if (host == null && authState != WebsiteAuthenticationState.WEBVIEW_LOADING) {
            emitDiagnostics {
                it.copy(
                    isApprovedDomain = false,
                    isScannerPollingActive = false,
                    observerState = "STOPPED (Untrusted Origin)",
                    urlRouteCategory = UrlRouteCategory.UNTRUSTED_OR_UNKNOWN,
                    websiteAuthState = WebsiteAuthenticationState.UNKNOWN,
                    confidenceLevel = EvidenceConfidenceLevel.NONE,
                    lastVerifiedAtMillis = verifiedAtMillis,
                    evidenceReason = "Rejected signal outside approved 9K Club HTTPS origins.",
                    websiteDetectionMethod = "Multi-Signal Monitor Rejected (Untrusted origin)",
                    sessionCheck = DiagnosticSessionCheck.FAILED
                )
            }
            return
        }

        val currentWebsite = _websiteSessionState.value
        val existingStrongSignal = currentWebsite is WebsiteSessionState.Authenticated &&
            currentWebsite.signalSource != SessionSignalSource.WEBVIEW_DOM_OBSERVER

        when (authState) {
            WebsiteAuthenticationState.WEBVIEW_LOADING -> {
                sessionExpiredFlag = false
                if (currentWebsite is WebsiteSessionState.Authenticated &&
                    currentWebsite.signalSource == SessionSignalSource.WEBVIEW_DOM_OBSERVER
                ) {
                    // Require fresh revalidation after reload/recreation rather than keeping stale DOM auth
                    _websiteSessionState.value = WebsiteSessionState.Unknown("Revalidating session after page reload.")
                }
                emitDiagnostics {
                    it.copy(
                        webViewLoaded = false,
                        isApprovedDomain = host != null,
                        currentApprovedHost = host,
                        lastReportedWebUrl = sanitizedUrl ?: it.lastReportedWebUrl,
                        urlRouteCategory = urlRouteCategory,
                        observerState = observerState,
                        websiteDetectionMethod = detectionMethod,
                        websiteAuthState = WebsiteAuthenticationState.WEBVIEW_LOADING,
                        confidenceLevel = confidenceLevel,
                        lastVerifiedAtMillis = verifiedAtMillis,
                        evidenceReason = evidenceReason,
                        websiteDetectionState = resolveWebsiteDetectionState(_websiteSessionState.value, mainFrameLoaded = false),
                        loginState = mapWebsiteLoginState(_websiteSessionState.value),
                        floatingButtonVisible = _websiteSessionState.value is WebsiteSessionState.Authenticated,
                        hidingReason = evidenceReason
                    )
                }
            }

            WebsiteAuthenticationState.SESSION_EXPIRED -> {
                persistAuthHint(WebsiteAuthenticationState.SESSION_EXPIRED, verifiedAtMillis)
                onWebsiteSessionExpired(evidenceReason)
                emitDiagnostics {
                    it.copy(
                        webViewLoaded = true,
                        isApprovedDomain = host != null,
                        currentApprovedHost = host,
                        lastReportedWebUrl = sanitizedUrl ?: it.lastReportedWebUrl,
                        urlRouteCategory = urlRouteCategory,
                        observerState = observerState,
                        websiteDetectionMethod = detectionMethod,
                        websiteAuthState = WebsiteAuthenticationState.SESSION_EXPIRED,
                        confidenceLevel = confidenceLevel,
                        lastVerifiedAtMillis = verifiedAtMillis,
                        evidenceReason = evidenceReason,
                        persistedHintState = WebsiteAuthenticationState.SESSION_EXPIRED
                    )
                }
            }

            WebsiteAuthenticationState.LOGIN_CONFIRMED -> {
                sessionExpiredFlag = false
                val effectiveHost = host ?: return
                val updatedWebsite = if (existingStrongSignal) {
                    currentWebsite
                } else {
                    cryptoManager.putEncryptedString(KEY_LAST_VERIFIED_HOST, effectiveHost)
                    val auth = WebsiteSessionState.Authenticated(
                        signalSource = signalSource,
                        detectedAtMillis = verifiedAtMillis,
                        originHost = effectiveHost
                    )
                    _websiteSessionState.value = auth
                    auth
                }
                persistAuthHint(WebsiteAuthenticationState.LOGIN_CONFIRMED, verifiedAtMillis)

                val reasonNote = if (_apiAuthState.value is ApiAuthenticationState.AuthRequired) {
                    "Website login confirmed ($detectionMethod), but api.ar-lottery01.com GetPanel Bearer token and signing specification remain unavailable (AUTH REQUIRED)."
                } else {
                    _diagnostics.value.apiAuthReason
                }
                if (_apiAuthState.value is ApiAuthenticationState.AuthRequired) {
                    _apiAuthState.value = ApiAuthenticationState.AuthRequired(
                        missingBearerToken = !hasValidStoredTokenNonMutating(),
                        missingSignatureAlgorithm = !_diagnostics.value.signatureProviderAvailable,
                        details = reasonNote
                    )
                }

                emitDiagnostics {
                    it.copy(
                        webViewLoaded = true,
                        isApprovedDomain = true,
                        currentApprovedHost = effectiveHost,
                        lastReportedWebUrl = sanitizedUrl ?: it.lastReportedWebUrl,
                        urlRouteCategory = urlRouteCategory,
                        observerState = observerState,
                        websiteDetectionMethod = if (existingStrongSignal) currentWebsite.signalSource.label else detectionMethod,
                        websiteAuthState = WebsiteAuthenticationState.LOGIN_CONFIRMED,
                        websiteDetectionState = resolveWebsiteDetectionState(updatedWebsite, mainFrameLoaded = true),
                        confidenceLevel = confidenceLevel,
                        lastVerifiedAtMillis = verifiedAtMillis,
                        evidenceReason = evidenceReason,
                        persistedHintState = WebsiteAuthenticationState.LOGIN_CONFIRMED,
                        loginState = DiagnosticLoginState.AUTHENTICATED,
                        sessionCheck = DiagnosticSessionCheck.SUCCESS,
                        apiAuthReason = reasonNote,
                        floatingButtonVisible = true,
                        hidingReason = "Visible ($evidenceReason; independent of GetPanel API)."
                    )
                }
            }

            WebsiteAuthenticationState.LOGIN_PAGE,
            WebsiteAuthenticationState.LOGGED_OUT -> {
                sessionExpiredFlag = false
                persistAuthHint(authState, verifiedAtMillis)
                val wasAuthenticatedOrLoggingOut = currentWebsite is WebsiteSessionState.Authenticated ||
                    _diagnostics.value.websiteAuthState == WebsiteAuthenticationState.LOGOUT_IN_PROGRESS
                if (wasAuthenticatedOrLoggingOut) {
                    cryptoManager.remove(KEY_LAST_VERIFIED_HOST)
                    if (hasValidStoredTokenNonMutating()) {
                        clearStoredApiCredentials()
                        _apiAuthState.value = ApiAuthenticationState.AuthRequired(
                            missingBearerToken = true,
                            missingSignatureAlgorithm = !_diagnostics.value.signatureProviderAvailable,
                            details = DEFAULT_API_AUTH_NOTICE
                        )
                    }
                }
                _websiteSessionState.value = WebsiteSessionState.Unauthenticated(evidenceReason)
                emitDiagnostics {
                    it.copy(
                        webViewLoaded = true,
                        isApprovedDomain = host != null,
                        currentApprovedHost = host,
                        lastReportedWebUrl = sanitizedUrl ?: it.lastReportedWebUrl,
                        urlRouteCategory = urlRouteCategory,
                        observerState = observerState,
                        websiteDetectionMethod = detectionMethod,
                        websiteAuthState = authState,
                        websiteDetectionState = WebsiteDetectionState.WEBSITE_NOT_LOGGED_IN,
                        confidenceLevel = confidenceLevel,
                        lastVerifiedAtMillis = verifiedAtMillis,
                        evidenceReason = evidenceReason,
                        persistedHintState = authState,
                        loginState = DiagnosticLoginState.UNAUTHENTICATED,
                        sessionCheck = DiagnosticSessionCheck.FAILED,
                        credentialAvailable = hasValidStoredTokenNonMutating(),
                        floatingButtonVisible = false,
                        hidingReason = evidenceReason
                    )
                }
            }

            WebsiteAuthenticationState.LOGIN_IN_PROGRESS -> {
                sessionExpiredFlag = false
                if (currentWebsite is WebsiteSessionState.Authenticated &&
                    currentWebsite.signalSource == SessionSignalSource.WEBVIEW_DOM_OBSERVER
                ) {
                    _websiteSessionState.value = WebsiteSessionState.Unknown(evidenceReason)
                }
                emitDiagnostics {
                    it.copy(
                        webViewLoaded = true,
                        isApprovedDomain = host != null,
                        currentApprovedHost = host,
                        lastReportedWebUrl = sanitizedUrl ?: it.lastReportedWebUrl,
                        urlRouteCategory = urlRouteCategory,
                        observerState = observerState,
                        websiteDetectionMethod = detectionMethod,
                        websiteAuthState = WebsiteAuthenticationState.LOGIN_IN_PROGRESS,
                        websiteDetectionState = WebsiteDetectionState.SESSION_EXCHANGE_PENDING,
                        confidenceLevel = confidenceLevel,
                        lastVerifiedAtMillis = verifiedAtMillis,
                        evidenceReason = evidenceReason,
                        loginState = DiagnosticLoginState.UNKNOWN,
                        sessionCheck = DiagnosticSessionCheck.NOT_AVAILABLE,
                        floatingButtonVisible = false,
                        hidingReason = evidenceReason
                    )
                }
            }

            WebsiteAuthenticationState.LOGOUT_IN_PROGRESS -> {
                sessionExpiredFlag = false
                cryptoManager.remove(KEY_LAST_VERIFIED_HOST)
                if (hasValidStoredTokenNonMutating()) {
                    clearStoredApiCredentials()
                }
                _websiteSessionState.value = WebsiteSessionState.Unauthenticated(evidenceReason)
                emitDiagnostics {
                    it.copy(
                        webViewLoaded = true,
                        isApprovedDomain = host != null,
                        currentApprovedHost = host,
                        lastReportedWebUrl = sanitizedUrl ?: it.lastReportedWebUrl,
                        urlRouteCategory = urlRouteCategory,
                        observerState = observerState,
                        websiteDetectionMethod = detectionMethod,
                        websiteAuthState = WebsiteAuthenticationState.LOGOUT_IN_PROGRESS,
                        websiteDetectionState = WebsiteDetectionState.WEBSITE_NOT_LOGGED_IN,
                        confidenceLevel = confidenceLevel,
                        lastVerifiedAtMillis = verifiedAtMillis,
                        evidenceReason = evidenceReason,
                        loginState = DiagnosticLoginState.UNAUTHENTICATED,
                        sessionCheck = DiagnosticSessionCheck.FAILED,
                        credentialAvailable = hasValidStoredTokenNonMutating(),
                        floatingButtonVisible = false,
                        hidingReason = evidenceReason
                    )
                }
            }

            WebsiteAuthenticationState.UNKNOWN -> {
                sessionExpiredFlag = false
                if (currentWebsite is WebsiteSessionState.Authenticated &&
                    currentWebsite.signalSource == SessionSignalSource.WEBVIEW_DOM_OBSERVER
                ) {
                    cryptoManager.remove(KEY_LAST_VERIFIED_HOST)
                    _websiteSessionState.value = WebsiteSessionState.Unknown(evidenceReason)
                } else if (currentWebsite is WebsiteSessionState.Unauthenticated) {
                    _websiteSessionState.value = WebsiteSessionState.Unknown(evidenceReason)
                }
                emitDiagnostics {
                    it.copy(
                        webViewLoaded = isMainFrameLoaded,
                        isApprovedDomain = host != null,
                        currentApprovedHost = host,
                        lastReportedWebUrl = sanitizedUrl ?: it.lastReportedWebUrl,
                        urlRouteCategory = urlRouteCategory,
                        observerState = observerState,
                        websiteDetectionMethod = detectionMethod,
                        websiteAuthState = WebsiteAuthenticationState.UNKNOWN,
                        websiteDetectionState = resolveWebsiteDetectionState(_websiteSessionState.value, isMainFrameLoaded),
                        confidenceLevel = confidenceLevel,
                        lastVerifiedAtMillis = verifiedAtMillis,
                        evidenceReason = evidenceReason,
                        loginState = mapWebsiteLoginState(_websiteSessionState.value),
                        sessionCheck = if (_websiteSessionState.value is WebsiteSessionState.Authenticated) {
                            DiagnosticSessionCheck.SUCCESS
                        } else {
                            DiagnosticSessionCheck.NOT_AVAILABLE
                        },
                        floatingButtonVisible = _websiteSessionState.value is WebsiteSessionState.Authenticated,
                        hidingReason = evidenceReason
                    )
                }
            }
        }
    }

    fun onBridgeSessionSignal(isAuthenticated: Boolean, currentWebViewUrl: String?) {
        val host = validateApprovedHost(currentWebViewUrl)
        if (host == null) {
            emitDiagnostics {
                it.copy(
                    isApprovedDomain = false,
                    websiteDetectionMethod = "Origin Bridge Rejected (Untrusted origin)",
                    sessionCheck = DiagnosticSessionCheck.FAILED,
                    floatingButtonVisible = _websiteSessionState.value is WebsiteSessionState.Authenticated,
                    hidingReason = "Rejected session signal from unapproved or unknown WebView origin."
                )
            }
            return
        }

        if (isAuthenticated) {
            val now = wallTimeProvider()
            cryptoManager.putEncryptedString(KEY_LAST_VERIFIED_HOST, host)
            val updated = WebsiteSessionState.Authenticated(
                signalSource = SessionSignalSource.COOPERATIVE_BRIDGE,
                detectedAtMillis = now,
                originHost = host
            )
            _websiteSessionState.value = updated
            emitDiagnostics {
                it.copy(
                    webViewLoaded = isMainFrameLoaded,
                    isApprovedDomain = true,
                    currentApprovedHost = host,
                    websiteDetectionMethod = SessionSignalSource.COOPERATIVE_BRIDGE.label,
                    websiteDetectionState = resolveWebsiteDetectionState(updated, isMainFrameLoaded),
                    loginState = DiagnosticLoginState.AUTHENTICATED,
                    sessionCheck = DiagnosticSessionCheck.SUCCESS,
                    floatingButtonVisible = true,
                    hidingReason = "Visible (verified via origin-validated session bridge; independent of predictor API state)."
                )
            }
        } else {
            markWebsiteLoggedOut("Website reported user logout.")
        }
    }

    /**
     * Handles an explicit, cooperative session exchange emitted by the approved website or
     * authorized auth flow. Stores the Bearer credential encrypted in Android Keystore prefs.
     * Returns true if accepted from an approved HTTPS host with valid non-expired payload.
     */
    fun onAuthorizedSessionExchange(
        bearerToken: String?,
        expiresAtEpochMillis: Long?,
        currentWebViewUrl: String?
    ): Boolean {
        val host = validateApprovedHost(currentWebViewUrl) ?: return false
        val trimmed = bearerToken?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        val now = wallTimeProvider()
        if (expiresAtEpochMillis != null && expiresAtEpochMillis <= now) {
            onApiTokenExpired("Exchanged website session token is already expired.")
            return false
        }

        val normalized = if (trimmed.startsWith("Bearer ", ignoreCase = true)) trimmed else "Bearer $trimmed"
        cryptoManager.putEncryptedString(KEY_LAST_VERIFIED_HOST, host)
        cryptoManager.putEncryptedString(KEY_API_TOKEN, normalized)
        if (expiresAtEpochMillis != null) {
            cryptoManager.putEncryptedString(KEY_API_TOKEN_EXPIRES_AT, expiresAtEpochMillis.toString())
        } else {
            cryptoManager.remove(KEY_API_TOKEN_EXPIRES_AT)
        }

        val updated = WebsiteSessionState.Authenticated(
            signalSource = SessionSignalSource.COOPERATIVE_BRIDGE,
            detectedAtMillis = now,
            originHost = host
        )
        _websiteSessionState.value = updated
        emitDiagnostics {
            it.copy(
                webViewLoaded = isMainFrameLoaded,
                isApprovedDomain = true,
                currentApprovedHost = host,
                websiteDetectionMethod = "Origin-Validated Session Exchange Bridge",
                websiteDetectionState = WebsiteDetectionState.WEBSITE_LOGIN_CONFIRMED,
                loginState = DiagnosticLoginState.AUTHENTICATED,
                sessionCheck = DiagnosticSessionCheck.SUCCESS,
                credentialAvailable = true,
                floatingButtonVisible = true,
                hidingReason = "Visible (verified via cooperative website session exchange)."
            )
        }
        return true
    }

    fun clearStoredApiCredentials() {
        cryptoManager.remove(KEY_API_TOKEN)
        cryptoManager.remove(KEY_API_TOKEN_EXPIRES_AT)
    }

    fun onWebsiteSessionExpired(reason: String) {
        sessionExpiredFlag = true
        val hadToken = hasValidStoredTokenNonMutating()
        cryptoManager.remove(KEY_LAST_VERIFIED_HOST)
        clearStoredApiCredentials()
        _websiteSessionState.value = WebsiteSessionState.Unauthenticated(reason)
        if (hadToken) {
            _apiAuthState.value = ApiAuthenticationState.TokenExpired(reason)
        }
        emitDiagnostics {
            it.copy(
                websiteDetectionMethod = "Website Session Expired",
                websiteDetectionState = WebsiteDetectionState.SESSION_EXPIRED,
                loginState = DiagnosticLoginState.UNAUTHENTICATED,
                sessionCheck = DiagnosticSessionCheck.FAILED,
                credentialAvailable = false,
                apiAuthStatus = if (hadToken) DiagnosticApiAuthStatus.TOKEN_EXPIRED else it.apiAuthStatus,
                lastRequestStatus = if (hadToken) "Session Expired" else it.lastRequestStatus,
                apiAuthReason = if (hadToken) reason else it.apiAuthReason,
                floatingButtonVisible = false,
                hidingReason = reason
            )
        }
    }

    fun markWebsiteLoggedOut(reason: String = "User logged out or website cleared session.") {
        sessionExpiredFlag = false
        cryptoManager.remove(KEY_LAST_VERIFIED_HOST)
        clearStoredApiCredentials()
        _websiteSessionState.value = WebsiteSessionState.Unauthenticated(reason)
        _apiAuthState.value = ApiAuthenticationState.AuthRequired(
            missingBearerToken = true,
            missingSignatureAlgorithm = true,
            details = DEFAULT_API_AUTH_NOTICE
        )
        emitDiagnostics {
            it.copy(
                websiteDetectionMethod = "Website Logout Detected",
                websiteDetectionState = WebsiteDetectionState.WEBSITE_NOT_LOGGED_IN,
                loginState = DiagnosticLoginState.UNAUTHENTICATED,
                sessionCheck = DiagnosticSessionCheck.FAILED,
                credentialAvailable = false,
                apiAuthStatus = DiagnosticApiAuthStatus.AUTH_REQUIRED,
                lastRequestStatus = "Cleared on Logout",
                lastHttpStatus = null,
                apiAuthReason = DEFAULT_API_AUTH_NOTICE,
                floatingButtonVisible = false,
                hidingReason = reason
            )
        }
    }

    fun resetApplicationSession(reason: String = "Application session reset.") {
        sessionExpiredFlag = false
        cryptoManager.remove(KEY_LAST_VERIFIED_HOST)
        clearStoredApiCredentials()
        _websiteSessionState.value = WebsiteSessionState.Unknown()
        _apiAuthState.value = ApiAuthenticationState.AuthRequired(
            missingBearerToken = true,
            missingSignatureAlgorithm = true,
            details = DEFAULT_API_AUTH_NOTICE
        )
        emitDiagnostics {
            it.copy(
                websiteDetectionMethod = "WebMessageListener + DOM MutationObserver (Reset)",
                websiteAuthState = if (isMainFrameLoaded) {
                    WebsiteAuthenticationState.UNKNOWN
                } else {
                    WebsiteAuthenticationState.WEBVIEW_LOADING
                },
                websiteDetectionState = if (isMainFrameLoaded) {
                    WebsiteDetectionState.WEBSITE_AUTHENTICATION_UNKNOWN
                } else {
                    WebsiteDetectionState.WEBVIEW_LOADING
                },
                confidenceLevel = EvidenceConfidenceLevel.NONE,
                lastVerifiedAtMillis = wallTimeProvider(),
                evidenceReason = reason,
                loginState = DiagnosticLoginState.UNKNOWN,
                sessionCheck = DiagnosticSessionCheck.NOT_AVAILABLE,
                credentialAvailable = false,
                apiAuthStatus = DiagnosticApiAuthStatus.AUTH_REQUIRED,
                lastRequestStatus = "Session Reset",
                lastHttpStatus = null,
                apiAuthReason = DEFAULT_API_AUTH_NOTICE,
                floatingButtonVisible = false,
                hidingReason = reason
            )
        }
    }

    fun onApiAuthorizing(
        detail: String = "Validating authorized API session...",
        requestStatus: String = "Authorizing / Validating..."
    ) {
        _apiAuthState.value = ApiAuthenticationState.Authorizing(detail)
        val hasToken = hasValidStoredTokenNonMutating()
        emitDiagnostics {
            it.copy(
                credentialAvailable = hasToken,
                apiAuthStatus = DiagnosticApiAuthStatus.AUTHORIZING,
                lastRequestStatus = requestStatus,
                apiAuthReason = detail
            )
        }
    }

    fun onApiTokenExpired(
        reason: String,
        httpStatus: Int? = null,
        requestStatus: String = "Token Expired / Refresh Failed"
    ) {
        clearStoredApiCredentials()
        _apiAuthState.value = ApiAuthenticationState.TokenExpired(reason)
        val currentWebsite = _websiteSessionState.value
        emitDiagnostics {
            it.copy(
                websiteDetectionState = resolveWebsiteDetectionState(currentWebsite, isMainFrameLoaded),
                credentialAvailable = false,
                apiAuthStatus = DiagnosticApiAuthStatus.TOKEN_EXPIRED,
                lastRequestStatus = requestStatus,
                lastHttpStatus = httpStatus ?: it.lastHttpStatus,
                apiAuthReason = reason
            )
        }
    }

    /**
     * Updates API authentication state ONLY (e.g., on HTTP 401/403 from api.ar-lottery01.com).
     * Intentionally leaves [websiteSessionState] and the floating button visibility untouched.
     */
    fun onApiAuthenticationRejected(
        httpCode: Int?,
        reason: String,
        requestStatus: String = "Rejected (HTTP ${httpCode ?: "Auth"})"
    ) {
        clearStoredApiCredentials()
        _apiAuthState.value = ApiAuthenticationState.Rejected(
            httpCode = httpCode,
            reason = reason
        )
        val currentWebsite = _websiteSessionState.value
        emitDiagnostics {
            it.copy(
                websiteDetectionState = resolveWebsiteDetectionState(currentWebsite, isMainFrameLoaded),
                credentialAvailable = false,
                apiAuthStatus = DiagnosticApiAuthStatus.REJECTED,
                lastRequestStatus = requestStatus,
                lastHttpStatus = httpCode,
                apiAuthReason = reason
            )
        }
    }

    fun onApiCredentialsMissing(
        missingBearerToken: Boolean,
        missingSignatureAlgorithm: Boolean,
        details: String,
        requestStatus: String = "Blocked (Missing Auth)"
    ) {
        val credAvailable = !missingBearerToken && hasValidStoredTokenNonMutating()
        val sigAvailable = !missingSignatureAlgorithm
        val currentWebsite = _websiteSessionState.value
        if (!missingBearerToken && missingSignatureAlgorithm) {
            _apiAuthState.value = ApiAuthenticationState.SignatureNotConfigured(details)
            emitDiagnostics {
                it.copy(
                    websiteDetectionState = resolveWebsiteDetectionState(currentWebsite, isMainFrameLoaded),
                    credentialAvailable = credAvailable,
                    signatureProviderAvailable = sigAvailable,
                    apiAuthStatus = DiagnosticApiAuthStatus.SIGNATURE_NOT_CONFIGURED,
                    lastRequestStatus = requestStatus,
                    lastHttpStatus = null,
                    apiAuthReason = details
                )
            }
        } else {
            _apiAuthState.value = ApiAuthenticationState.AuthRequired(
                missingBearerToken = missingBearerToken,
                missingSignatureAlgorithm = missingSignatureAlgorithm,
                details = details
            )
            emitDiagnostics {
                it.copy(
                    websiteDetectionState = resolveWebsiteDetectionState(currentWebsite, isMainFrameLoaded),
                    credentialAvailable = credAvailable,
                    signatureProviderAvailable = sigAvailable,
                    apiAuthStatus = DiagnosticApiAuthStatus.AUTH_REQUIRED,
                    lastRequestStatus = requestStatus,
                    lastHttpStatus = null,
                    apiAuthReason = details
                )
            }
        }
    }

    fun onApiError(
        message: String,
        httpStatus: Int? = null,
        requestStatus: String = if (httpStatus != null) "HTTP $httpStatus Error" else "Network / Response Error"
    ) {
        _apiAuthState.value = ApiAuthenticationState.ApiError(message)
        emitDiagnostics {
            it.copy(
                apiAuthStatus = DiagnosticApiAuthStatus.API_ERROR,
                lastRequestStatus = requestStatus,
                lastHttpStatus = httpStatus,
                apiAuthReason = message
            )
        }
    }

    fun onApiAuthenticationVerified(
        httpStatus: Int = 200,
        requestStatus: String = "HTTP $httpStatus OK (code=0)"
    ) {
        _apiAuthState.value = ApiAuthenticationState.Ready
        emitDiagnostics {
            it.copy(
                credentialAvailable = true,
                signatureProviderAvailable = true,
                apiAuthStatus = DiagnosticApiAuthStatus.READY,
                lastRequestStatus = requestStatus,
                lastHttpStatus = httpStatus,
                apiAuthReason = "Authorized WinGo_1M API credentials accepted."
            )
        }
    }

    fun getStoredTokenOrNull(): String? {
        val token = cryptoManager.getDecryptedString(KEY_API_TOKEN) ?: return null
        val expiresAt = cryptoManager.getDecryptedString(KEY_API_TOKEN_EXPIRES_AT)?.toLongOrNull()
        if (expiresAt != null && expiresAt <= wallTimeProvider()) {
            onApiTokenExpired("Stored API session token expired.")
            return null
        }
        return token
    }

    private fun hasValidStoredTokenNonMutating(): Boolean {
        val token = cryptoManager.getDecryptedString(KEY_API_TOKEN) ?: return false
        if (token.isBlank()) return false
        val expiresAt = cryptoManager.getDecryptedString(KEY_API_TOKEN_EXPIRES_AT)?.toLongOrNull()
        return expiresAt == null || expiresAt > wallTimeProvider()
    }

    private fun resolveWebsiteDetectionState(
        websiteState: WebsiteSessionState,
        mainFrameLoaded: Boolean
    ): WebsiteDetectionState = when (websiteState) {
        is WebsiteSessionState.Unauthenticated -> WebsiteDetectionState.WEBSITE_NOT_LOGGED_IN
        is WebsiteSessionState.Authenticated -> {
            if (hasValidStoredTokenNonMutating()) {
                WebsiteDetectionState.WEBSITE_LOGIN_CONFIRMED
            } else {
                WebsiteDetectionState.SESSION_EXCHANGE_PENDING
            }
        }
        is WebsiteSessionState.Unknown -> {
            if (mainFrameLoaded) {
                WebsiteDetectionState.WEBSITE_AUTHENTICATION_UNKNOWN
            } else {
                WebsiteDetectionState.WEBVIEW_LOADING
            }
        }
    }

    private fun validateApprovedHost(currentWebViewUrl: String?): String? {
        val url = currentWebViewUrl ?: lastVerifiedOriginUrl
        val uri = url?.let { runCatching { Uri.parse(it) }.getOrNull() } ?: return null
        val host = uri.host?.lowercase() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true) || !isApprovedHost(host)) return null
        return host
    }

    private fun mapWebsiteLoginState(state: WebsiteSessionState): DiagnosticLoginState = when (state) {
        is WebsiteSessionState.Authenticated -> DiagnosticLoginState.AUTHENTICATED
        is WebsiteSessionState.Unauthenticated -> DiagnosticLoginState.UNAUTHENTICATED
        is WebsiteSessionState.Unknown -> DiagnosticLoginState.UNKNOWN
    }

    private fun emitDiagnostics(transform: (LoginDiagnosticState) -> LoginDiagnosticState) {
        val next = transform(_diagnostics.value)
        _diagnostics.value = next.copy(
            websiteLoginState = resolveWebsiteLoginState(_websiteSessionState.value, isMainFrameLoaded),
            apiStatusCode = mapApiStatusCode(next.apiAuthStatus)
        )
    }

    private fun resolveWebsiteLoginState(
        websiteState: WebsiteSessionState,
        mainFrameLoaded: Boolean
    ): WebsiteLoginState = when (websiteState) {
        is WebsiteSessionState.Authenticated -> WebsiteLoginState.LOGIN_CONFIRMED
        is WebsiteSessionState.Unauthenticated -> if (sessionExpiredFlag) {
            WebsiteLoginState.SESSION_EXPIRED
        } else {
            WebsiteLoginState.LOGGED_OUT
        }
        is WebsiteSessionState.Unknown -> if (mainFrameLoaded) {
            WebsiteLoginState.UNKNOWN
        } else {
            WebsiteLoginState.WEBVIEW_LOADING
        }
    }

    private fun mapApiStatusCode(status: DiagnosticApiAuthStatus): ApiAuthStatusCode = when (status) {
        DiagnosticApiAuthStatus.READY -> ApiAuthStatusCode.API_CONNECTED
        DiagnosticApiAuthStatus.AUTHORIZING -> ApiAuthStatusCode.API_CONNECTING
        DiagnosticApiAuthStatus.AUTH_REQUIRED -> ApiAuthStatusCode.API_AUTH_REQUIRED
        DiagnosticApiAuthStatus.SIGNATURE_NOT_CONFIGURED -> ApiAuthStatusCode.API_SIGNATURE_NOT_CONFIGURED
        DiagnosticApiAuthStatus.TOKEN_EXPIRED -> ApiAuthStatusCode.API_TOKEN_EXPIRED
        DiagnosticApiAuthStatus.REJECTED -> ApiAuthStatusCode.API_REJECTED
        DiagnosticApiAuthStatus.API_ERROR -> ApiAuthStatusCode.API_ERROR
    }

    private fun isApprovedHttpsUrl(urlString: String?): Boolean {
        if (urlString.isNullOrBlank()) return false
        val uri = runCatching { Uri.parse(urlString) }.getOrNull() ?: return false
        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        if (uri.port != -1 && uri.port != 443) return false
        val host = uri.host?.lowercase() ?: return false
        return isApprovedHost(host)
    }

    private fun isApprovedHost(host: String): Boolean {
        return WinGoGameConfig.ALLOWED_WEBVIEW_HOSTS.any { allowed ->
            host.equals(allowed, ignoreCase = true)
        }
    }

    companion object {
        internal const val KEY_LAST_VERIFIED_HOST = "last_verified_host"
        internal const val KEY_LAST_AUTH_HINT_STATE = "last_auth_hint_state"
        internal const val KEY_LAST_AUTH_HINT_TIME_MS = "last_auth_hint_time_ms"
        internal const val KEY_API_TOKEN = "authorized_api_token"
        internal const val KEY_API_TOKEN_EXPIRES_AT = "authorized_api_token_expires_at"

        const val DEFAULT_LIMITATION_NOTICE =
            "Awaiting verified website session signal. URL changes alone are not treated as login; the 9K Club site must set a readable auth cookie or emit a cooperative session event."

        const val MISSING_WEBSITE_SIGNAL_REASON =
            "Login state UNKNOWN: external 9K Club SPA has not emitted AnshuSessionBridge.onSessionStatusChanged(true) or set a readable HTTP auth cookie on 9kclub55.com/9kclub5.com. Registration/URL changes do not confirm login."

        const val DEFAULT_API_AUTH_NOTICE =
            "AUTH_REQUIRED: External 9K Club SPA does not provide a supported website-to-native OAuth/session exchange, and api.ar-lottery01.com request signature specification (random/signature/timestamp) is not configured."

        const val MISSING_SIGNATURE_NOTICE =
            "SIGNATURE NOT CONFIGURED: Authorized request signature specification (random, signature, timestamp) for api.ar-lottery01.com is not available."
    }
}

/**
 * Narrowly scoped, origin-validated JavaScript bridge.
 * Note: Android WebView exposes addJavascriptInterface objects to all frames, so invocations
 * are strictly gated on the main thread against an approved HTTPS main-frame origin and loaded state.
 * Never exposes stored credentials back to WebView JavaScript and never reads arbitrary DOM/storage.
 */
class WebsiteSessionBridge(
    private val sessionManager: WebsiteSessionManager,
    private val currentUrlProvider: () -> String?,
    private val isMainFrameLoadedProvider: () -> Boolean,
    private val mainThreadExecutor: ((() -> Unit) -> Unit)
) {
    @JavascriptInterface
    fun onSessionStatusChanged(isAuthenticated: Boolean) {
        mainThreadExecutor {
            if (!isMainFrameLoadedProvider()) return@mainThreadExecutor
            sessionManager.onBridgeSessionSignal(
                isAuthenticated = isAuthenticated,
                currentWebViewUrl = currentUrlProvider()
            )
        }
    }

    @JavascriptInterface
    fun onAuthorizedSessionExchange(bearerToken: String?, expiresAtEpochMillis: Long) {
        mainThreadExecutor {
            if (!isMainFrameLoadedProvider()) return@mainThreadExecutor
            val expiry = if (expiresAtEpochMillis > 0L) expiresAtEpochMillis else null
            sessionManager.onAuthorizedSessionExchange(
                bearerToken = bearerToken,
                expiresAtEpochMillis = expiry,
                currentWebViewUrl = currentUrlProvider()
            )
        }
    }

    @JavascriptInterface
    fun onSessionExpired() {
        mainThreadExecutor {
            if (!isMainFrameLoadedProvider()) return@mainThreadExecutor
            sessionManager.onWebsiteSessionExpired("Website reported session expiration.")
        }
    }
}
