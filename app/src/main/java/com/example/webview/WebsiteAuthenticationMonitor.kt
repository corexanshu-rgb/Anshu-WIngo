package com.example.webview

import android.net.Uri
import com.example.security.SessionSignalSource
import com.example.security.WebsiteSessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class MultiSignalObservation(
    val sourceOrigin: String,
    val currentWebViewUrl: String?,
    val sanitizedUrl: String?,
    val routeCategory: UrlRouteCategory,
    val hasVisiblePasswordInput: Boolean = false,
    val hasVisibleLoginForm: Boolean = false,
    val hasVisibleRegisterForm: Boolean = false,
    val hasGuestAuthAction: Boolean = false,
    val hasLoginSubmitPending: Boolean = false,
    val hasLogoutControl: Boolean = false,
    val hasLogoutClickedPending: Boolean = false,
    val hasAccountProfileNav: Boolean = false,
    val hasSessionExpiredDom: Boolean = false,
    val explicitBridgeState: WebsiteAuthenticationState? = null,
    val explicitEvidenceTag: String? = null,
    val matchedIndicatorNames: List<String> = emptyList(),
    val runtimeTimestampMs: Long = 0L
)

data class AuthenticationMonitorSnapshot(
    val state: WebsiteAuthenticationState = WebsiteAuthenticationState.WEBVIEW_LOADING,
    val confidenceLevel: EvidenceConfidenceLevel = EvidenceConfidenceLevel.NONE,
    val lastVerifiedAtMillis: Long? = null,
    val detectionReason: String = "Waiting for 9K Club main-frame page load completion.",
    val trustedOrigin: String? = null,
    val urlRouteCategory: UrlRouteCategory = UrlRouteCategory.LOADING,
    val observerState: String = "STOPPED",
    val detectionMethod: String = "WebsiteAuthenticationMonitor (Awaiting load)",
    val lastKnownHintState: WebsiteAuthenticationState? = null
)

/**
 * Multi-signal automatic website authentication detection coordinator for 9K Club.
 *
 * Coordinates:
 * A. URL Monitor (navigation, redirects, pushState/replaceState/popstate/hashchange)
 * B. DOM Monitor (MutationObserver driven by [WebsiteAuthIndicatorRegistry])
 * C. SPA Monitor (route transitions & DOM replacement recovery)
 * D. WebView Lifecycle Monitor (start, finish, reload, background/foreground, process recreation, destroy)
 * E. Periodic Verification (lightweight ~2s verification loop with duplicate-observer prevention)
 */
class WebsiteAuthenticationMonitor(
    private val sessionManager: WebsiteSessionManager,
    private val indicatorRegistry: WebsiteAuthIndicatorRegistry = WebsiteAuthIndicatorRegistry(),
    private val wallTimeProvider: () -> Long = { System.currentTimeMillis() },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val ownsScope: Boolean = true,
    private val periodicVerifyIntervalMs: Long = PERIODIC_VERIFICATION_INTERVAL_MS,
    private val flickerSuppressionWindowMs: Long = DEFAULT_FLICKER_SUPPRESSION_MS,
    private val triggerPeriodicVerificationJs: () -> Unit = {}
) {
    private val verifyMutex = Mutex()
    private val stateLock = Any()

    private var periodicJob: Job? = null
    private var delayedInconclusiveJob: Job? = null

    private val _snapshot = MutableStateFlow(
        AuthenticationMonitorSnapshot(
            lastKnownHintState = sessionManager.diagnostics.value.persistedHintState
        )
    )
    val snapshot: StateFlow<AuthenticationMonitorSnapshot> = _snapshot.asStateFlow()

    @Volatile
    private var mainFrameLoaded: Boolean = false

    @Volatile
    private var isInForeground: Boolean = true

    @Volatile
    private var isDestroyed: Boolean = false

    @Volatile
    private var currentUrl: String? = null

    @Volatile
    private var observerMode: String = "STOPPED"

    @Volatile
    private var lastLoginConfirmedAtMs: Long? = null

    @Volatile
    var observerAttachmentCount: Int = 0
        private set

    fun isPeriodicVerificationActive(): Boolean = periodicJob?.isActive == true && !isDestroyed

    fun activePeriodicJobCount(): Int = if (periodicJob?.isActive == true && !isDestroyed) 1 else 0

    // --- D & A: WebView Lifecycle & URL Monitor callbacks ---

    fun onRedirectDetected(url: String?) {
        onPageStarted(url, isRedirect = true)
    }

    fun onPageStarted(url: String?, isRedirect: Boolean = false) {
        if (isDestroyed) return
        val now = wallTimeProvider()
        currentUrl = url
        mainFrameLoaded = false
        cancelPendingFlickerTimer()

        val approved = WebsiteAuthIndicatorRegistry.isExactApprovedOriginUrl(url)
        val routeCategory = WebsiteAuthIndicatorRegistry.classifyUrlRoute(url)
        val sanitizedUrl = WebsiteAuthIndicatorRegistry.sanitizeUrl(url)
        val origin = extractOrigin(url)

        if (!url.isNullOrBlank() && !approved) {
            stopPeriodicVerification("STOPPED (Untrusted origin)")
            updateSnapshotAndSession(
                url = url,
                sanitizedUrl = sanitizedUrl,
                state = WebsiteAuthenticationState.UNKNOWN,
                confidence = EvidenceConfidenceLevel.NONE,
                verifiedAtMillis = now,
                reason = "Navigation left approved 9K Club HTTPS origins.",
                method = "URL Monitor (Origin rejected)",
                routeCategory = UrlRouteCategory.UNTRUSTED_OR_UNKNOWN,
                observerState = "STOPPED",
                trustedOrigin = null
            )
            return
        }

        observerMode = "PAUSED (Loading)"
        stopPeriodicVerification(observerMode)
        updateSnapshotAndSession(
            url = url,
            sanitizedUrl = sanitizedUrl,
            state = WebsiteAuthenticationState.WEBVIEW_LOADING,
            confidence = EvidenceConfidenceLevel.LOW,
            verifiedAtMillis = now,
            reason = if (isRedirect) {
                "Following approved 9K Club redirect; revalidating session."
            } else {
                "WebView main frame loading; awaiting DOM verification."
            },
            method = "WebView Lifecycle Monitor (onPageStarted)",
            routeCategory = routeCategory,
            observerState = observerMode,
            trustedOrigin = origin
        )
    }

    fun onPageCommitVisible(url: String?) {
        if (isDestroyed) return
        currentUrl = url ?: currentUrl
        mainFrameLoaded = true
    }

    fun onPageFinished(url: String?, observerDescription: String = "ACTIVE (MutationObserver + 2s Verify)") {
        if (isDestroyed) return
        val effectiveUrl = url ?: currentUrl
        currentUrl = effectiveUrl
        mainFrameLoaded = true

        if (!WebsiteAuthIndicatorRegistry.isExactApprovedOriginUrl(effectiveUrl)) {
            stopPeriodicVerification("STOPPED (Untrusted origin)")
            return
        }
        observerMode = observerDescription
        observerAttachmentCount++
        startPeriodicVerificationIfEligible()
    }

    fun onSpaUrlUpdated(url: String?, isReload: Boolean = false) {
        if (isDestroyed) return
        val effectiveUrl = url ?: currentUrl
        currentUrl = effectiveUrl
        val routeCategory = WebsiteAuthIndicatorRegistry.classifyUrlRoute(effectiveUrl)
        val sanitized = WebsiteAuthIndicatorRegistry.sanitizeUrl(effectiveUrl)

        if (!WebsiteAuthIndicatorRegistry.isExactApprovedOriginUrl(effectiveUrl)) {
            stopPeriodicVerification("STOPPED (Untrusted origin)")
            updateSnapshotAndSession(
                url = effectiveUrl,
                sanitizedUrl = sanitized,
                state = WebsiteAuthenticationState.UNKNOWN,
                confidence = EvidenceConfidenceLevel.NONE,
                verifiedAtMillis = wallTimeProvider(),
                reason = "SPA route navigated outside approved 9K Club HTTPS origins.",
                method = "SPA Monitor (Off-origin stopped)",
                routeCategory = UrlRouteCategory.UNTRUSTED_OR_UNKNOWN,
                observerState = "STOPPED",
                trustedOrigin = null
            )
            return
        }

        val current = _snapshot.value
        val reason = when {
            isReload -> "Reload detected on ${routeCategory.label}; awaiting DOM verification."
            routeCategory == UrlRouteCategory.LOGOUT_ROUTE -> "Logout route observed via SPA navigation."
            else -> "SPA navigation to ${routeCategory.label}; URL alone does not prove login."
        }
        _snapshot.value = current.copy(
            urlRouteCategory = routeCategory,
            trustedOrigin = extractOrigin(effectiveUrl),
            detectionReason = reason
        )

        if (routeCategory == UrlRouteCategory.LOGOUT_ROUTE &&
            current.state == WebsiteAuthenticationState.LOGIN_CONFIRMED
        ) {
            updateSnapshotAndSession(
                url = effectiveUrl,
                sanitizedUrl = sanitized,
                state = WebsiteAuthenticationState.LOGOUT_IN_PROGRESS,
                confidence = EvidenceConfidenceLevel.MEDIUM,
                verifiedAtMillis = wallTimeProvider(),
                reason = "Navigated to logout route; awaiting post-logout DOM verification.",
                method = "URL + SPA Monitor",
                routeCategory = routeCategory,
                observerState = observerMode,
                trustedOrigin = extractOrigin(effectiveUrl)
            )
        }

        if (mainFrameLoaded && isInForeground) {
            startPeriodicVerificationIfEligible()
        }
    }

    fun onLoadError(url: String?, message: String) {
        if (isDestroyed) return
        mainFrameLoaded = false
        stopPeriodicVerification("STOPPED (Load error)")
        updateSnapshotAndSession(
            url = url,
            sanitizedUrl = WebsiteAuthIndicatorRegistry.sanitizeUrl(url),
            state = WebsiteAuthenticationState.UNKNOWN,
            confidence = EvidenceConfidenceLevel.NONE,
            verifiedAtMillis = wallTimeProvider(),
            reason = message,
            method = "WebView Lifecycle Monitor (Error)",
            routeCategory = WebsiteAuthIndicatorRegistry.classifyUrlRoute(url),
            observerState = "STOPPED (Error)",
            trustedOrigin = extractOrigin(url)
        )
    }

    fun onRenderProcessGone(url: String?) {
        if (isDestroyed) return
        mainFrameLoaded = false
        cancelPendingFlickerTimer()
        stopPeriodicVerification("RECOVERING (Process recreated)")
        updateSnapshotAndSession(
            url = url ?: currentUrl,
            sanitizedUrl = WebsiteAuthIndicatorRegistry.sanitizeUrl(url ?: currentUrl),
            state = WebsiteAuthenticationState.WEBVIEW_LOADING,
            confidence = EvidenceConfidenceLevel.NONE,
            verifiedAtMillis = wallTimeProvider(),
            reason = "WebView render process exited; reinitializing DOM monitor and revalidating session.",
            method = "WebView Lifecycle Monitor (Render process recovery)",
            routeCategory = WebsiteAuthIndicatorRegistry.classifyUrlRoute(url ?: currentUrl),
            observerState = "REINITIALIZING",
            trustedOrigin = extractOrigin(url ?: currentUrl)
        )
    }

    fun onAppPaused() {
        isInForeground = false
        stopPeriodicVerification("PAUSED (Background)")
        _snapshot.value = _snapshot.value.copy(observerState = "PAUSED (Background)")
    }

    fun onAppResumed() {
        if (isDestroyed) return
        isInForeground = true
        if (mainFrameLoaded && WebsiteAuthIndicatorRegistry.isExactApprovedOriginUrl(currentUrl)) {
            observerMode = if (observerMode.startsWith("FALLBACK")) {
                "ACTIVE (Fallback Periodic Verify)"
            } else {
                "ACTIVE (MutationObserver + 2s Verify)"
            }
            _snapshot.value = _snapshot.value.copy(observerState = observerMode)
            startPeriodicVerificationIfEligible()
            triggerPeriodicVerificationJs()
        }
    }

    fun setObserverMode(modeDescription: String, active: Boolean) {
        observerMode = modeDescription
        _snapshot.value = _snapshot.value.copy(observerState = modeDescription)
        if (!active) {
            stopPeriodicVerification(modeDescription)
        } else if (mainFrameLoaded && isInForeground) {
            startPeriodicVerificationIfEligible()
        }
    }

    fun destroy() {
        isDestroyed = true
        mainFrameLoaded = false
        cancelPendingFlickerTimer()
        stopPeriodicVerification("DESTROYED")
        if (ownsScope) {
            scope.cancel()
        }
    }

    // --- B & C: Multi-Signal DOM + SPA + Bridge Evaluation ---

    fun evaluateObservation(observation: MultiSignalObservation): AuthenticationMonitorSnapshot {
        if (isDestroyed) return _snapshot.value
        val now = if (observation.runtimeTimestampMs > 0L) observation.runtimeTimestampMs else wallTimeProvider()
        val effectiveUrl = observation.currentWebViewUrl ?: currentUrl
        val routeCategory = if (observation.routeCategory != UrlRouteCategory.LOADING) {
            observation.routeCategory
        } else {
            WebsiteAuthIndicatorRegistry.classifyUrlRoute(effectiveUrl)
        }
        val sanitizedUrl = observation.sanitizedUrl ?: WebsiteAuthIndicatorRegistry.sanitizeUrl(effectiveUrl)
        val trustedOrigin = extractOrigin(effectiveUrl)

        if (!WebsiteAuthIndicatorRegistry.isExactApprovedOriginUrl(effectiveUrl) ||
            !WebsiteAuthIndicatorRegistry.isExactApprovedOriginUrl(observation.sourceOrigin) ||
            extractOrigin(effectiveUrl) != extractOrigin(observation.sourceOrigin)
        ) {
            val rejected = AuthenticationMonitorSnapshot(
                state = WebsiteAuthenticationState.UNKNOWN,
                confidenceLevel = EvidenceConfidenceLevel.NONE,
                lastVerifiedAtMillis = now,
                detectionReason = "Rejected observation from unapproved origin.",
                trustedOrigin = null,
                urlRouteCategory = UrlRouteCategory.UNTRUSTED_OR_UNKNOWN,
                observerState = "STOPPED",
                detectionMethod = "Origin Validation Rejected",
                lastKnownHintState = sessionManager.diagnostics.value.persistedHintState
            )
            _snapshot.value = rejected
            return rejected
        }

        val resolved = resolveMultiSignalState(observation, routeCategory)

        // Rule 7: Debounce transient inconclusive drops immediately following confirmed login (e.g. SPA route transition)
        val previous = _snapshot.value
        val isTransientInconclusiveDrop =
            previous.state == WebsiteAuthenticationState.LOGIN_CONFIRMED &&
                resolved.state == WebsiteAuthenticationState.UNKNOWN &&
                resolved.confidenceLevel != EvidenceConfidenceLevel.CONFLICTING &&
                flickerSuppressionWindowMs > 0L &&
                (lastLoginConfirmedAtMs?.let { (now - it) >= 0L } == true)

        if (isTransientInconclusiveDrop) {
            scheduleDeferredInconclusiveCheck(
                observation = observation,
                resolved = resolved,
                effectiveUrl = effectiveUrl,
                sanitizedUrl = sanitizedUrl,
                routeCategory = routeCategory,
                trustedOrigin = trustedOrigin,
                observedAtMs = now
            )
            return previous
        }

        cancelPendingFlickerTimer()
        if (resolved.state == WebsiteAuthenticationState.LOGIN_CONFIRMED) {
            lastLoginConfirmedAtMs = now
        }

        return updateSnapshotAndSession(
            url = effectiveUrl,
            sanitizedUrl = sanitizedUrl,
            state = resolved.state,
            confidence = resolved.confidenceLevel,
            verifiedAtMillis = now,
            reason = resolved.reason,
            method = resolved.detectionMethod,
            routeCategory = routeCategory,
            observerState = observerMode,
            trustedOrigin = trustedOrigin,
            signalSource = resolved.signalSource
        )
    }

    private fun scheduleDeferredInconclusiveCheck(
        observation: MultiSignalObservation,
        resolved: StateResolution,
        effectiveUrl: String?,
        sanitizedUrl: String?,
        routeCategory: UrlRouteCategory,
        trustedOrigin: String?,
        observedAtMs: Long
    ) {
        synchronized(stateLock) {
            delayedInconclusiveJob?.cancel()
            delayedInconclusiveJob = scope.launch {
                delay(flickerSuppressionWindowMs)
                if (!isDestroyed &&
                    isActive &&
                    mainFrameLoaded &&
                    isInForeground &&
                    WebsiteAuthIndicatorRegistry.isExactApprovedOriginUrl(currentUrl)
                ) {
                    updateSnapshotAndSession(
                        url = effectiveUrl,
                        sanitizedUrl = sanitizedUrl,
                        state = resolved.state,
                        confidence = resolved.confidenceLevel,
                        verifiedAtMillis = wallTimeProvider().coerceAtLeast(observedAtMs),
                        reason = resolved.reason,
                        method = resolved.detectionMethod,
                        routeCategory = routeCategory,
                        observerState = observerMode,
                        trustedOrigin = trustedOrigin,
                        signalSource = resolved.signalSource
                    )
                }
            }
        }
    }

    private fun cancelPendingFlickerTimer() {
        synchronized(stateLock) {
            delayedInconclusiveJob?.cancel()
            delayedInconclusiveJob = null
        }
    }

    private fun startPeriodicVerificationIfEligible() {
        if (isDestroyed || !mainFrameLoaded || !isInForeground) return
        if (!WebsiteAuthIndicatorRegistry.isExactApprovedOriginUrl(currentUrl)) return
        synchronized(stateLock) {
            if (periodicJob?.isActive == true) return
            periodicJob = scope.launch {
                while (isActive && !isDestroyed) {
                    delay(periodicVerifyIntervalMs)
                    if (!isActive || isDestroyed || !mainFrameLoaded || !isInForeground) break
                    if (!WebsiteAuthIndicatorRegistry.isExactApprovedOriginUrl(currentUrl)) break
                    verifyMutex.withLock {
                        triggerPeriodicVerificationJs()
                    }
                }
            }
        }
    }

    private fun stopPeriodicVerification(newObserverState: String) {
        synchronized(stateLock) {
            periodicJob?.cancel()
            periodicJob = null
        }
        observerMode = newObserverState
    }

    private fun updateSnapshotAndSession(
        url: String?,
        sanitizedUrl: String?,
        state: WebsiteAuthenticationState,
        confidence: EvidenceConfidenceLevel,
        verifiedAtMillis: Long,
        reason: String,
        method: String,
        routeCategory: UrlRouteCategory,
        observerState: String,
        trustedOrigin: String?,
        signalSource: SessionSignalSource = SessionSignalSource.WEBVIEW_DOM_OBSERVER
    ): AuthenticationMonitorSnapshot {
        val next = AuthenticationMonitorSnapshot(
            state = state,
            confidenceLevel = confidence,
            lastVerifiedAtMillis = verifiedAtMillis,
            detectionReason = reason,
            trustedOrigin = trustedOrigin,
            urlRouteCategory = routeCategory,
            observerState = observerState,
            detectionMethod = method,
            lastKnownHintState = sessionManager.diagnostics.value.persistedHintState ?: state
        )
        _snapshot.value = next

        sessionManager.onMultiSignalEvaluation(
            currentWebViewUrl = url,
            sanitizedUrl = sanitizedUrl,
            authState = state,
            confidenceLevel = confidence,
            verifiedAtMillis = verifiedAtMillis,
            evidenceReason = reason,
            detectionMethod = method,
            urlRouteCategory = routeCategory,
            observerState = observerState,
            signalSource = signalSource
        )
        return next
    }

    internal data class StateResolution(
        val state: WebsiteAuthenticationState,
        val confidenceLevel: EvidenceConfidenceLevel,
        val reason: String,
        val detectionMethod: String,
        val signalSource: SessionSignalSource = SessionSignalSource.WEBVIEW_DOM_OBSERVER
    )

    internal fun resolveMultiSignalState(
        obs: MultiSignalObservation,
        routeCategory: UrlRouteCategory
    ): StateResolution {
        val hasLoginSignals = obs.hasVisiblePasswordInput ||
            obs.hasVisibleLoginForm ||
            obs.hasVisibleRegisterForm ||
            obs.hasGuestAuthAction

        val hasStrongAuthDom = obs.hasLogoutControl ||
            obs.explicitBridgeState == WebsiteAuthenticationState.LOGIN_CONFIRMED

        // Rule 6: Conflicting authenticated + login/logout indicators MUST resolve to UNKNOWN
        if ((hasStrongAuthDom || obs.hasAccountProfileNav) && hasLoginSignals) {
            return StateResolution(
                state = WebsiteAuthenticationState.UNKNOWN,
                confidenceLevel = EvidenceConfidenceLevel.CONFLICTING,
                reason = "Conflicting authenticated and login/registration DOM indicators detected; resolved to UNKNOWN.",
                detectionMethod = "Multi-Signal Conflict Guard"
            )
        }

        // Rule 8: Session expiry takes precedence when expired modal/dialog is visible and user is not actively authenticated
        if (obs.hasSessionExpiredDom || obs.explicitBridgeState == WebsiteAuthenticationState.SESSION_EXPIRED) {
            return StateResolution(
                state = WebsiteAuthenticationState.SESSION_EXPIRED,
                confidenceLevel = EvidenceConfidenceLevel.HIGH,
                reason = "Visible session expiration notice detected on approved 9K Club page.",
                detectionMethod = "DOM Session-Expiry Monitor"
            )
        }

        // Logout transition in progress
        if (obs.hasLogoutClickedPending || obs.explicitBridgeState == WebsiteAuthenticationState.LOGOUT_IN_PROGRESS) {
            return StateResolution(
                state = WebsiteAuthenticationState.LOGOUT_IN_PROGRESS,
                confidenceLevel = EvidenceConfidenceLevel.MEDIUM,
                reason = "Logout action initiated; waiting for post-logout login page confirmation.",
                detectionMethod = "DOM + SPA Logout Monitor"
            )
        }

        // Login submission in progress (do NOT mark LOGIN_CONFIRMED while login form/submit is pending)
        if (obs.hasLoginSubmitPending || obs.explicitBridgeState == WebsiteAuthenticationState.LOGIN_IN_PROGRESS) {
            return StateResolution(
                state = WebsiteAuthenticationState.LOGIN_IN_PROGRESS,
                confidenceLevel = EvidenceConfidenceLevel.MEDIUM,
                reason = "Login form submission in progress; awaiting authenticated UI verification.",
                detectionMethod = "DOM + SPA Login Submission Monitor"
            )
        }

        // Explicit callback or verified logout control / authenticated UI without login form
        if (obs.explicitBridgeState == WebsiteAuthenticationState.LOGIN_CONFIRMED && !hasLoginSignals) {
            val viaCallback = obs.explicitEvidenceTag?.contains("CALLBACK", ignoreCase = true) == true
            val method = if (viaCallback) {
                "Website Session Callback + Origin Check"
            } else {
                "WebMessageListener + Multi-Signal DOM (${obs.explicitEvidenceTag ?: "AUTHENTICATED_UI"})"
            }
            return StateResolution(
                state = WebsiteAuthenticationState.LOGIN_CONFIRMED,
                confidenceLevel = EvidenceConfidenceLevel.HIGH,
                reason = "Authenticated 9K Club UI verified (${obs.matchedIndicatorNames.ifEmpty { listOf(obs.explicitEvidenceTag ?: "AUTHENTICATED_UI") }.joinToString(", ")}).",
                detectionMethod = method,
                signalSource = if (viaCallback) SessionSignalSource.COOPERATIVE_BRIDGE else SessionSignalSource.WEBVIEW_DOM_OBSERVER
            )
        }

        if (obs.hasLogoutControl && !hasLoginSignals) {
            val indicators = (obs.matchedIndicatorNames + "visible_logout_control").distinct()
            val hasRegistrySupport = indicatorRegistry.getIndicators()
                .any { it.name == "visible_logout_control" }
            val confidence = if (routeCategory == UrlRouteCategory.GAME_ROUTE || routeCategory == UrlRouteCategory.ACCOUNT_ROUTE) {
                EvidenceConfidenceLevel.HIGH
            } else if (hasRegistrySupport) {
                EvidenceConfidenceLevel.MEDIUM
            } else {
                EvidenceConfidenceLevel.LOW
            }
            return StateResolution(
                state = WebsiteAuthenticationState.LOGIN_CONFIRMED,
                confidenceLevel = confidence,
                reason = "Authenticated logout control observed without login form (${indicators.joinToString(", ")}).",
                detectionMethod = "WebMessageListener + DOM Registry (${indicators.firstOrNull() ?: "visible_logout_control"})"
            )
        }

        // Rule 1: Visible login or registration form is strong evidence of LOGGED_OUT / LOGIN_PAGE
        if (obs.hasVisiblePasswordInput || obs.hasVisibleLoginForm || obs.hasVisibleRegisterForm) {
            val isLoginOrRegRoute = routeCategory == UrlRouteCategory.LOGIN_ROUTE ||
                routeCategory == UrlRouteCategory.REGISTER_ROUTE
            val targetState = if (isLoginOrRegRoute || obs.explicitBridgeState == WebsiteAuthenticationState.LOGIN_PAGE) {
                WebsiteAuthenticationState.LOGIN_PAGE
            } else {
                WebsiteAuthenticationState.LOGGED_OUT
            }
            return StateResolution(
                state = targetState,
                confidenceLevel = EvidenceConfidenceLevel.HIGH,
                reason = when (routeCategory) {
                    UrlRouteCategory.REGISTER_ROUTE -> "Visible registration form on approved domain (not logged in)."
                    UrlRouteCategory.LOGIN_ROUTE -> "Visible login form on approved domain (not logged in)."
                    else -> "Visible password/login form active on page (user logged out)."
                },
                detectionMethod = "WebMessageListener + DOM Form Monitor"
            )
        }

        if (obs.hasGuestAuthAction || obs.explicitBridgeState == WebsiteAuthenticationState.LOGGED_OUT) {
            return StateResolution(
                state = WebsiteAuthenticationState.LOGGED_OUT,
                confidenceLevel = EvidenceConfidenceLevel.MEDIUM,
                reason = "Visible Login/Register guest actions detected without authenticated UI.",
                detectionMethod = "WebMessageListener + DOM Guest CTA Monitor"
            )
        }

        // Rule 4: Generic homepage, promotional banner, or missing login form / account link alone is NOT proof of login
        return StateResolution(
            state = WebsiteAuthenticationState.UNKNOWN,
            confidenceLevel = if (obs.hasAccountProfileNav) EvidenceConfidenceLevel.LOW else EvidenceConfidenceLevel.NONE,
            reason = if (obs.hasAccountProfileNav) {
                "Account/profile nav link seen without strong logout control or session callback; keeping state UNKNOWN."
            } else {
                "No conclusive login or authenticated DOM indicators observed (missing password field or homepage URL alone does not prove login)."
            },
            detectionMethod = "Multi-Signal Monitor (Inconclusive DOM)"
        )
    }

    companion object {
        const val PERIODIC_VERIFICATION_INTERVAL_MS = 2_000L
        const val DEFAULT_FLICKER_SUPPRESSION_MS = 350L

        internal fun extractOrigin(urlString: String?): String? {
            if (urlString.isNullOrBlank()) return null
            val uri = runCatching { Uri.parse(urlString) }.getOrNull() ?: return null
            val scheme = uri.scheme?.lowercase() ?: return null
            val host = uri.host?.lowercase() ?: return null
            return "$scheme://$host"
        }
    }
}
