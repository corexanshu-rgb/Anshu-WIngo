package com.example.webview

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.example.domain.model.WinGoGameConfig
import com.example.security.WebsiteSessionManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * Legacy-compatible Phase 1 diagnostic enum mapped from [WebsiteAuthenticationState].
 */
enum class WebsiteLoginState(val uiLabel: String) {
    WEBVIEW_LOADING("LOADING"),
    LOGGED_OUT("LOGGED OUT"),
    LOGIN_CONFIRMED("LOGGED IN"),
    UNKNOWN("UNKNOWN"),
    SESSION_EXPIRED("SESSION EXPIRED")
}

data class DomLoginObservation(
    val observedOrigin: String,
    val sanitizedUrl: String?,
    val hasAuthenticatedDom: Boolean,
    val hasLoginFormDom: Boolean,
    val hasSessionExpiredDom: Boolean,
    val indicatorKind: String?
)

/**
 * Dedicated WebView login-state detector using AndroidX WebKit [WebViewCompat.addWebMessageListener]
 * (with restricted main-frame evaluateJavascript fallback when unsupported) and a single-instance
 * debounced DOM/SPA monitor (`MutationObserver` + `pushState` + `replaceState` + `popstate` +
 * `hashchange` + `pageshow`) feeding [WebsiteAuthenticationMonitor].
 *
 * Security & privacy invariants:
 * - Zero `addJavascriptInterface` exposure.
 * - Strictly restricted to approved HTTPS origins (`9kclub55.com`, `9kclub5.com` + `www.`).
 * - Verifies `isMainFrame == true`, HTTPS scheme, exact approved host, `sourceOrigin` match,
 *   runtime-generated timestamp, and rejects payloads with sensitive keys.
 * - Never inspects browser storage, cookies, passwords, OTPs, phones, balances, or tokens.
 */
class WebsiteLoginDetector(
    private val sessionManager: WebsiteSessionManager,
    private val currentUrlProvider: () -> String?,
    private val isMainFrameLoadedProvider: () -> Boolean,
    private val evaluateJavascript: (String) -> Unit,
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
    private val indicatorRegistry: WebsiteAuthIndicatorRegistry = WebsiteAuthIndicatorRegistry(),
    externalMonitor: WebsiteAuthenticationMonitor? = null,
    private val evaluateJavascriptWithCallback: ((String, (String?) -> Unit) -> Unit)? = null
) {
    private val ownsMonitor = externalMonitor == null

    val monitor: WebsiteAuthenticationMonitor = externalMonitor ?: WebsiteAuthenticationMonitor(
        sessionManager = sessionManager,
        indicatorRegistry = indicatorRegistry,
        triggerPeriodicVerificationJs = { triggerLightVerification() }
    )

    @Volatile
    private var detached: Boolean = false

    @Volatile
    private var bridgeAttached: Boolean = false

    @Volatile
    private var detectorActive: Boolean = false

    private var webMessageListenerSupported: Boolean = true

    val webMessageListener: WebViewCompat.WebMessageListener =
        WebViewCompat.WebMessageListener { view, message, sourceOrigin, isMainFrame, _ ->
            if (detached) return@WebMessageListener
            val payload = message.data
            val originStr = sourceOrigin.toString()
            val viewUrl = runCatching { view.url }.getOrNull() ?: currentUrlProvider()
            mainHandler.post {
                if (!detached) {
                    handleWebMessage(
                        rawJsonPayload = payload,
                        sourceOrigin = originStr,
                        isMainFrame = isMainFrame,
                        currentWebViewUrl = viewUrl
                    )
                }
            }
        }

    fun attachToWebView(
        webView: WebView,
        isSupportedProvider: () -> Boolean = {
            WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
        }
    ): Boolean {
        if (detached) return false
        val supported = runCatching { isSupportedProvider() }.getOrDefault(false)
        webMessageListenerSupported = supported
        if (!supported) {
            bridgeAttached = false
            val modeMsg = if (evaluateJavascriptWithCallback != null) {
                "Restricted Origin-Validated Evaluate Fallback (WebMessageListener Unsupported)"
            } else {
                "WebMessageListener Unsupported (State remains UNKNOWN)"
            }
            monitor.setObserverMode(
                modeDescription = if (evaluateJavascriptWithCallback != null) "FALLBACK_STANDBY" else "UNSUPPORTED",
                active = false
            )
            sessionManager.onLoginDetectorStatusChanged(
                active = false,
                methodDescription = modeMsg,
                observerStateLabel = if (evaluateJavascriptWithCallback != null) "FALLBACK_STANDBY" else "UNSUPPORTED"
            )
            return false
        }
        if (!bridgeAttached) {
            WebViewCompat.addWebMessageListener(
                webView,
                BRIDGE_NAME,
                WinGoGameConfig.BRIDGE_ALLOWED_ORIGIN_RULES,
                webMessageListener
            )
            bridgeAttached = true
        }
        return true
    }

    fun isWebMessageListenerSupported(): Boolean = webMessageListenerSupported

    fun isDetectorActive(): Boolean = detectorActive && !detached

    fun onPageStarted(url: String?, isRedirect: Boolean = false) {
        if (detached) return
        if (!url.isNullOrBlank() && !isExactApprovedOriginUrl(url)) {
            stopAndCleanup("Stopped detector: navigation outside approved 9K Club HTTPS origins.")
            monitor.onPageStarted(url, isRedirect = isRedirect)
            return
        }
        detectorActive = false
        monitor.onPageStarted(url, isRedirect = isRedirect)
        sessionManager.onLoginDetectorStatusChanged(
            active = false,
            methodDescription = "WebView Loading (DOM observer paused)",
            observerStateLabel = "PAUSED (Loading)"
        )
    }

    fun onPageCommitVisible(url: String?) {
        if (detached) return
        monitor.onPageCommitVisible(url)
    }

    fun onRedirectDetected(url: String?) {
        if (detached) return
        if (!url.isNullOrBlank() && !isExactApprovedOriginUrl(url)) {
            stopAndCleanup("Stopped detector: redirect outside approved 9K Club HTTPS origins.")
            monitor.onRedirectDetected(url)
            return
        }
        detectorActive = false
        monitor.onRedirectDetected(url)
    }

    fun onPageLoadProgressComplete(url: String?) {
        if (detached || !isMainFrameLoadedProvider()) return
        val effectiveUrl = url ?: currentUrlProvider()
        if (!isExactApprovedOriginUrl(effectiveUrl)) return
        if (detectorActive) {
            triggerLightVerification()
        }
    }

    fun onPageFinished(url: String?) {
        if (detached) return
        val effectiveUrl = url ?: currentUrlProvider()
        if (!isExactApprovedOriginUrl(effectiveUrl)) {
            stopAndCleanup("Stopped detector: origin not in approved HTTPS allowlist.")
            return
        }
        monitor.onPageFinished(
            url = effectiveUrl,
            observerDescription = if (webMessageListenerSupported) {
                "ACTIVE (MutationObserver + 2s Verify)"
            } else {
                "FALLBACK_POLLING (Restricted Origin Check)"
            }
        )
        injectOrRefreshDetector()
    }

    fun onSpaUrlUpdated(url: String?, isReload: Boolean = false) {
        if (detached) return
        val effectiveUrl = url ?: currentUrlProvider()
        if (!isExactApprovedOriginUrl(effectiveUrl)) {
            stopAndCleanup("Stopped detector: SPA navigated off approved origin.")
            monitor.onSpaUrlUpdated(effectiveUrl, isReload = isReload)
            return
        }
        monitor.onSpaUrlUpdated(effectiveUrl, isReload = isReload)
        if (isMainFrameLoadedProvider()) {
            if (detectorActive) {
                triggerLightVerification()
            } else {
                injectOrRefreshDetector()
            }
        }
    }

    fun onRenderProcessGone(url: String?) {
        if (detached) return
        detectorActive = false
        bridgeAttached = false
        monitor.onRenderProcessGone(url)
    }

    fun pauseDetection() {
        monitor.onAppPaused()
        if (!detectorActive) return
        detectorActive = false
        evaluateJavascript(CLEANUP_JS)
        sessionManager.onLoginDetectorStatusChanged(
            active = false,
            methodDescription = "DOM MutationObserver (Paused)",
            observerStateLabel = "PAUSED (Background)"
        )
    }

    fun resumeDetection() {
        if (detached) return
        monitor.onAppResumed()
        val currentUrl = currentUrlProvider()
        if (isMainFrameLoadedProvider() && isExactApprovedOriginUrl(currentUrl)) {
            injectOrRefreshDetector()
        }
    }

    fun detach(webView: WebView? = null) {
        detached = true
        stopAndCleanup("WebView closed or detached.")
        monitor.destroy()
        if (webView != null && bridgeAttached) {
            runCatching { WebViewCompat.removeWebMessageListener(webView, BRIDGE_NAME) }
        }
        bridgeAttached = false
    }

    internal fun injectOrRefreshDetector(): Boolean {
        if (detached) return false
        val currentUrl = currentUrlProvider()
        if (!isMainFrameLoadedProvider() || !isExactApprovedOriginUrl(currentUrl)) {
            stopAndCleanup("Refusing JS detector outside loaded approved origin.")
            return false
        }
        if (!webMessageListenerSupported) {
            if (evaluateJavascriptWithCallback != null) {
                detectorActive = true
                sessionManager.onLoginDetectorStatusChanged(
                    active = true,
                    methodDescription = "Restricted Evaluate Fallback (Origin-Validated)",
                    observerStateLabel = "FALLBACK_POLLING"
                )
                triggerRestrictedFallbackInspection(currentUrl)
                return true
            }
            detectorActive = false
            return false
        }
        detectorActive = true
        sessionManager.onLoginDetectorStatusChanged(
            active = true,
            methodDescription = "WebMessageListener + Debounced DOM/SPA MutationObserver",
            observerStateLabel = "ACTIVE (MutationObserver + 2s Verify)"
        )
        evaluateJavascript(buildDetectorScript())
        return true
    }

    internal fun triggerLightVerification() {
        if (detached || !isMainFrameLoadedProvider()) return
        val currentUrl = currentUrlProvider()
        if (!isExactApprovedOriginUrl(currentUrl)) {
            stopAndCleanup("Stopped verification outside approved origin.")
            return
        }
        if (webMessageListenerSupported) {
            evaluateJavascript(LIGHT_RECHECK_JS)
        } else if (evaluateJavascriptWithCallback != null) {
            triggerRestrictedFallbackInspection(currentUrl)
        }
    }

    private fun triggerRestrictedFallbackInspection(expectedUrl: String?) {
        val callbackInvoker = evaluateJavascriptWithCallback ?: return
        if (detached || !isMainFrameLoadedProvider() || !isExactApprovedOriginUrl(expectedUrl)) return
        val expectedOrigin = WebsiteAuthenticationMonitor.extractOrigin(expectedUrl) ?: return
        callbackInvoker(buildFallbackOnceScript()) { rawResult ->
            mainHandler.post {
                if (detached || !isMainFrameLoadedProvider()) return@post
                val latestUrl = currentUrlProvider()
                if (!isExactApprovedOriginUrl(latestUrl)) return@post
                val unquoted = unquoteJsJsonString(rawResult) ?: return@post
                handleWebMessage(
                    rawJsonPayload = unquoted,
                    sourceOrigin = expectedOrigin,
                    isMainFrame = true,
                    currentWebViewUrl = latestUrl
                )
            }
        }
    }

    internal fun stopAndCleanup(reason: String) {
        val wasActive = detectorActive
        detectorActive = false
        if (wasActive) {
            runCatching { evaluateJavascript(CLEANUP_JS) }
        }
        sessionManager.onLoginDetectorStatusChanged(
            active = false,
            methodDescription = reason,
            observerStateLabel = "STOPPED"
        )
    }

    /**
     * Validates a raw WebMessage received via [WebViewCompat.WebMessageListener] or restricted
     * main-frame fallback, ensuring `isMainFrame == true`, HTTPS scheme, exact approved host,
     * zero sensitive payload fields, and JSON schema integrity.
     */
    internal fun handleWebMessage(
        rawJsonPayload: String?,
        sourceOrigin: String?,
        isMainFrame: Boolean,
        currentWebViewUrl: String?
    ) {
        if (detached || !isMainFrameLoadedProvider()) return

        if (!isMainFrame) {
            sessionManager.onLoginBridgeRejected("Rejected WebMessage from subframe (isMainFrame=false).")
            return
        }

        if (!isExactApprovedOriginUrl(sourceOrigin)) {
            stopAndCleanup("Rejected WebMessage from unapproved sourceOrigin.")
            sessionManager.onLoginBridgeRejected("Rejected WebMessage from unapproved sourceOrigin.")
            return
        }

        if (!isExactApprovedOriginUrl(currentWebViewUrl)) {
            stopAndCleanup("Rejected WebMessage while WebView URL is outside approved hosts.")
            sessionManager.onLoginBridgeRejected("Rejected WebMessage outside approved main-frame host.")
            return
        }

        val sourceHost = runCatching { Uri.parse(sourceOrigin).host?.lowercase() }.getOrNull()
        val currentHost = runCatching { Uri.parse(currentWebViewUrl).host?.lowercase() }.getOrNull()
        if (sourceHost == null || sourceHost != currentHost) {
            stopAndCleanup("Rejected WebMessage due to sourceOrigin/WebView host mismatch.")
            sessionManager.onLoginBridgeRejected("Rejected WebMessage host mismatch.")
            return
        }

        val parsed = parseMultiSignalPayload(rawJsonPayload, sourceOrigin, sourceHost, currentWebViewUrl)
        if (parsed == null) {
            sessionManager.onLoginBridgeRejected("Rejected malformed or sensitive WebMessage payload.")
            return
        }

        val cleanUrl = WebsiteAuthIndicatorRegistry.sanitizeUrl(parsed.sanitizedUrl ?: currentWebViewUrl)
        sessionManager.onScannerUrlReported(
            sanitizedUrl = cleanUrl,
            currentWebViewUrl = currentWebViewUrl
        )

        monitor.evaluateObservation(parsed)
    }

    internal fun parseMultiSignalPayload(
        rawJsonPayload: String?,
        sourceOrigin: String?,
        expectedHost: String,
        currentWebViewUrl: String?
    ): MultiSignalObservation? {
        if (rawJsonPayload.isNullOrBlank()) return null
        val json = runCatching { JSONObject(rawJsonPayload) }.getOrNull() ?: return null

        // Reject any message containing disallowed sensitive fields (token, password, otp, cookie, phone, balance, etc.)
        val keys = mutableListOf<String>()
        val keyIter = json.keys()
        while (keyIter.hasNext()) {
            keys.add(keyIter.next())
        }
        if (WebsiteAuthIndicatorRegistry.hasSensitivePayloadKeys(keys)) {
            return null
        }
        if (json.has("hasVisiblePasswordInput") && json.opt("hasVisiblePasswordInput") !is Boolean) {
            return null
        }

        val payloadOrigin = json.optString("origin", "").trim()
        if (payloadOrigin.isNotEmpty() && !payloadOrigin.equals(sourceOrigin?.trim(), ignoreCase = true)) {
            return null
        }

        val rawReportedUrl = json.optString("cleanUrl", "").trim().takeIf { it.isNotEmpty() }
        if (rawReportedUrl != null) {
            val reportedUri = runCatching { Uri.parse(rawReportedUrl) }.getOrNull() ?: return null
            if (!reportedUri.scheme.equals("https", ignoreCase = true)) return null
            val reportedHost = reportedUri.host?.lowercase() ?: return null
            if (reportedHost != expectedHost) return null
        }
        val reportedUrl = rawReportedUrl?.let { WebsiteAuthIndicatorRegistry.sanitizeUrl(it) }

        val effectiveUrlForRoute = reportedUrl ?: currentWebViewUrl
        val routeCategory = WebsiteAuthIndicatorRegistry.classifyUrlRoute(effectiveUrlForRoute)
        val matchedList = parseMatchedIndicators(json.optJSONArray("matchedIndicators"))

        val msgType = json.optString("type", "")
        return when (msgType) {
            MESSAGE_TYPE_AUTH_STATE -> {
                val rawState = json.optString("state", "").trim()
                val parsedState = runCatching { WebsiteAuthenticationState.valueOf(rawState) }.getOrNull()
                    ?: return null
                val runtimeTs = json.optLong("timestamp", -1L)
                if (runtimeTs <= 0L) return null
                val evidenceTag = WebsiteAuthIndicatorRegistry.sanitizeIndicatorName(
                    json.optString("evidence", "")
                )
                val hasExplicitFlags = json.has("hasLogoutControl") ||
                    json.has("hasVisiblePasswordInput") ||
                    json.has("hasSessionExpiredDom") ||
                    json.has("hasGuestAuthAction")

                if (hasExplicitFlags) {
                    MultiSignalObservation(
                        sourceOrigin = sourceOrigin.orEmpty(),
                        currentWebViewUrl = currentWebViewUrl,
                        sanitizedUrl = reportedUrl,
                        routeCategory = routeCategory,
                        hasVisiblePasswordInput = json.optBoolean("hasVisiblePasswordInput", false),
                        hasVisibleLoginForm = json.optBoolean("hasVisibleLoginForm", false),
                        hasVisibleRegisterForm = json.optBoolean("hasVisibleRegisterForm", false),
                        hasGuestAuthAction = json.optBoolean("hasGuestAuthAction", false),
                        hasLoginSubmitPending = json.optBoolean("hasLoginSubmitPending", false),
                        hasLogoutControl = json.optBoolean("hasLogoutControl", false),
                        hasLogoutClickedPending = json.optBoolean("hasLogoutClickedPending", false),
                        hasAccountProfileNav = json.optBoolean("hasAccountProfileNav", false),
                        hasSessionExpiredDom = json.optBoolean("hasSessionExpiredDom", false),
                        explicitBridgeState = if (parsedState == WebsiteAuthenticationState.LOGIN_CONFIRMED &&
                            evidenceTag?.contains("CALLBACK", ignoreCase = true) == true
                        ) WebsiteAuthenticationState.LOGIN_CONFIRMED else null,
                        explicitEvidenceTag = evidenceTag,
                        matchedIndicatorNames = matchedList,
                        runtimeTimestampMs = runtimeTs
                    )
                } else {
                    val strongAuthEvidence = parsedState == WebsiteAuthenticationState.LOGIN_CONFIRMED &&
                        isStrongMinimalAuthEvidence(evidenceTag)
                    MultiSignalObservation(
                        sourceOrigin = sourceOrigin.orEmpty(),
                        currentWebViewUrl = currentWebViewUrl,
                        sanitizedUrl = reportedUrl,
                        routeCategory = routeCategory,
                        hasVisibleLoginForm = parsedState == WebsiteAuthenticationState.LOGIN_PAGE,
                        hasVisibleRegisterForm = parsedState == WebsiteAuthenticationState.LOGIN_PAGE &&
                            routeCategory == UrlRouteCategory.REGISTER_ROUTE,
                        hasGuestAuthAction = parsedState == WebsiteAuthenticationState.LOGGED_OUT,
                        hasLoginSubmitPending = parsedState == WebsiteAuthenticationState.LOGIN_IN_PROGRESS,
                        hasLogoutClickedPending = parsedState == WebsiteAuthenticationState.LOGOUT_IN_PROGRESS,
                        hasSessionExpiredDom = parsedState == WebsiteAuthenticationState.SESSION_EXPIRED,
                        explicitBridgeState = if (parsedState == WebsiteAuthenticationState.LOGIN_CONFIRMED && !strongAuthEvidence) {
                            WebsiteAuthenticationState.UNKNOWN
                        } else {
                            parsedState
                        },
                        explicitEvidenceTag = evidenceTag,
                        matchedIndicatorNames = matchedList,
                        runtimeTimestampMs = runtimeTs
                    )
                }
            }

            MESSAGE_TYPE_LEGACY_DOM -> {
                val hasAuthDom = json.optBoolean("hasAuthenticatedDom", false)
                val hasLoginDom = json.optBoolean("hasLoginFormDom", false)
                val hasExpiredDom = json.optBoolean("hasSessionExpiredDom", false)
                val indicator = WebsiteAuthIndicatorRegistry.sanitizeIndicatorName(
                    json.optString("indicatorKind", "")
                )
                MultiSignalObservation(
                    sourceOrigin = sourceOrigin.orEmpty(),
                    currentWebViewUrl = currentWebViewUrl,
                    sanitizedUrl = reportedUrl,
                    routeCategory = routeCategory,
                    hasVisiblePasswordInput = json.optBoolean("hasVisiblePasswordInput", hasLoginDom),
                    hasVisibleLoginForm = hasLoginDom,
                    hasVisibleRegisterForm = routeCategory == UrlRouteCategory.REGISTER_ROUTE && hasLoginDom,
                    hasGuestAuthAction = json.optBoolean("hasGuestAuthAction", false),
                    hasLoginSubmitPending = json.optBoolean("hasLoginSubmitPending", false),
                    hasLogoutControl = json.optBoolean("hasLogoutControl", hasAuthDom),
                    hasLogoutClickedPending = json.optBoolean("hasLogoutClickedPending", false),
                    hasAccountProfileNav = json.optBoolean("hasAccountProfileNav", false),
                    hasSessionExpiredDom = hasExpiredDom,
                    explicitBridgeState = null,
                    explicitEvidenceTag = indicator,
                    matchedIndicatorNames = if (matchedList.isNotEmpty()) matchedList else listOfNotNull(indicator),
                    runtimeTimestampMs = System.currentTimeMillis()
                )
            }

            else -> null
        }
    }

    internal fun parseObservationJson(
        rawJsonPayload: String?,
        sourceOrigin: String?,
        expectedHost: String
    ): DomLoginObservation? {
        val multi = parseMultiSignalPayload(rawJsonPayload, sourceOrigin, expectedHost, sourceOrigin)
            ?: return null
        return DomLoginObservation(
            observedOrigin = multi.sourceOrigin,
            sanitizedUrl = multi.sanitizedUrl,
            hasAuthenticatedDom = multi.hasLogoutControl ||
                multi.explicitBridgeState == WebsiteAuthenticationState.LOGIN_CONFIRMED,
            hasLoginFormDom = multi.hasVisiblePasswordInput ||
                multi.hasVisibleLoginForm ||
                multi.hasVisibleRegisterForm ||
                multi.hasGuestAuthAction,
            hasSessionExpiredDom = multi.hasSessionExpiredDom,
            indicatorKind = multi.explicitEvidenceTag
        )
    }

    private fun parseMatchedIndicators(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        val out = mutableListOf<String>()
        val limit = minOf(array.length(), 16)
        for (i in 0 until limit) {
            val clean = WebsiteAuthIndicatorRegistry.sanitizeIndicatorName(array.optString(i, ""))
            if (clean != null && !WebsiteAuthIndicatorRegistry.containsSensitiveKeyword(clean)) {
                out.add(clean)
            }
        }
        return out
    }

    private fun buildDetectorScript(): String {
        val customLogout = JSONArray(
            indicatorRegistry.exportSafeCustomSelectorsForState(WebsiteAuthenticationState.LOGIN_CONFIRMED)
        ).toString()
        val customLogin = JSONArray(
            indicatorRegistry.exportSafeCustomSelectorsForState(WebsiteAuthenticationState.LOGIN_PAGE) +
                indicatorRegistry.exportSafeCustomSelectorsForState(WebsiteAuthenticationState.LOGGED_OUT)
        ).toString()
        val customExpired = JSONArray(
            indicatorRegistry.exportSafeCustomSelectorsForState(WebsiteAuthenticationState.SESSION_EXPIRED)
        ).toString()
        return DETECTOR_JS_TEMPLATE
            .replace("__CUSTOM_LOGOUT_SELECTORS__", customLogout)
            .replace("__CUSTOM_LOGIN_SELECTORS__", customLogin)
            .replace("__CUSTOM_EXPIRED_SELECTORS__", customExpired)
    }

    private fun buildFallbackOnceScript(): String {
        val customLogout = JSONArray(
            indicatorRegistry.exportSafeCustomSelectorsForState(WebsiteAuthenticationState.LOGIN_CONFIRMED)
        ).toString()
        val customLogin = JSONArray(
            indicatorRegistry.exportSafeCustomSelectorsForState(WebsiteAuthenticationState.LOGIN_PAGE) +
                indicatorRegistry.exportSafeCustomSelectorsForState(WebsiteAuthenticationState.LOGGED_OUT)
        ).toString()
        val customExpired = JSONArray(
            indicatorRegistry.exportSafeCustomSelectorsForState(WebsiteAuthenticationState.SESSION_EXPIRED)
        ).toString()
        return FALLBACK_INSPECT_JS_TEMPLATE
            .replace("__CUSTOM_LOGOUT_SELECTORS__", customLogout)
            .replace("__CUSTOM_LOGIN_SELECTORS__", customLogin)
            .replace("__CUSTOM_EXPIRED_SELECTORS__", customExpired)
    }

    companion object {
        const val BRIDGE_NAME = "AnshuLoginBridge"
        const val MESSAGE_TYPE_AUTH_STATE = "WEBSITE_AUTH_STATE"
        const val MESSAGE_TYPE_LEGACY_DOM = "dom_login_observation"
        const val DEBOUNCE_DELAY_MS = 300L

        /**
         * Resolves legacy [DomLoginObservation] indicator flags into [WebsiteLoginState].
         */
        fun resolveWebsiteLoginState(obs: DomLoginObservation): WebsiteLoginState {
            if (obs.hasSessionExpiredDom && !obs.hasAuthenticatedDom) {
                return WebsiteLoginState.SESSION_EXPIRED
            }
            if (obs.hasAuthenticatedDom && obs.hasLoginFormDom) {
                return WebsiteLoginState.UNKNOWN
            }
            if (obs.hasAuthenticatedDom) {
                return WebsiteLoginState.LOGIN_CONFIRMED
            }
            if (obs.hasLoginFormDom) {
                return WebsiteLoginState.LOGGED_OUT
            }
            return WebsiteLoginState.UNKNOWN
        }

        fun isExactApprovedOriginUrl(urlString: String?): Boolean =
            WebsiteAuthIndicatorRegistry.isExactApprovedOriginUrl(urlString)

        internal fun sanitizeUrl(rawUrl: String?): String? =
            WebsiteAuthIndicatorRegistry.sanitizeUrl(rawUrl)

        internal fun isStrongMinimalAuthEvidence(evidenceTag: String?): Boolean {
            if (evidenceTag.isNullOrBlank()) return false
            val upper = evidenceTag.uppercase()
            return upper == "AUTHENTICATED_UI" ||
                upper == "AUTHENTICATED_SHELL" ||
                upper == "LOGOUT_CONTROL" ||
                upper.contains("CALLBACK")
        }

        internal fun unquoteJsJsonString(rawResult: String?): String? {
            if (rawResult.isNullOrBlank() || rawResult == "null") return null
            val trimmed = rawResult.trim()
            return if (trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
                runCatching { JSONObject("{\"v\":$trimmed}").getString("v") }.getOrNull()
            } else {
                trimmed
            }
        }

        val CLEANUP_JS: String = """
            (function() {
              try {
                if (typeof window.__anshuDetectorCleanup === 'function') {
                  window.__anshuDetectorCleanup();
                }
              } catch (e) {}
            })();
        """.trimIndent()

        val LIGHT_RECHECK_JS: String = """
            (function() {
              try {
                if (typeof window.__anshuTriggerRecheck === 'function') {
                  window.__anshuTriggerRecheck();
                }
              } catch (e) {}
            })();
        """.trimIndent()

        private fun buildInspectBodyJs(): String = """
            function isVisible(el) {
              if (!el) return false;
              var style = window.getComputedStyle ? window.getComputedStyle(el) : null;
              if (style && (style.display === 'none' || style.visibility === 'hidden' || style.opacity === '0')) return false;
              return !!(el.offsetWidth || el.offsetHeight || (el.getClientRects && el.getClientRects().length));
            }
            function anyCustomVisible(selectors) {
              if (!selectors || !selectors.length) return false;
              for (var s = 0; s < selectors.length; s++) {
                try {
                  var matched = document.querySelectorAll(selectors[s]);
                  for (var m = 0; m < matched.length; m++) {
                    if (isVisible(matched[m])) return true;
                  }
                } catch (e) {}
              }
              return false;
            }
            function isPromoOrBanner(el) {
              if (!el || !el.closest) return false;
              return !!el.closest('[class*="banner" i], [class*="promo" i], [class*="swiper" i], [class*="marquee" i], [class*="notice" i]');
            }
            function isInteractiveElement(el) {
              if (!el) return false;
              var tag = (el.tagName || '').toUpperCase();
              if (tag === 'BUTTON' || tag === 'A') return true;
              if (el.getAttribute && (el.getAttribute('role') === 'button' || el.getAttribute('data-action') === 'logout')) return true;
              var cls = (typeof el.className === 'string') ? el.className : '';
              if (/(van-button|van-tabbar-item|btn|button|logout|signout)/i.test(cls)) return true;
              var style = window.getComputedStyle ? window.getComputedStyle(el) : null;
              return !!(style && style.cursor === 'pointer');
            }
            function collectDomState() {
              if (window.location.protocol !== 'https:') return null;
              var hashRoute = (window.location.hash || '').split('?')[0];
              var cleanUrl = window.location.origin + window.location.pathname + hashRoute;
              var matchedIndicators = [];

              var visiblePasswordCount = 0;
              var pwdInputs = document.querySelectorAll('input[type="password"]');
              for (var p = 0; p < pwdInputs.length; p++) {
                if (isVisible(pwdInputs[p])) {
                  visiblePasswordCount++;
                }
              }
              var hasVisiblePasswordInput = visiblePasswordCount > 0;
              if (hasVisiblePasswordInput) {
                matchedIndicators.push('visible_password_input');
              }

              var hasCustomLogin = anyCustomVisible(__CUSTOM_LOGIN_SELECTORS__);
              var hasVisibleLoginForm = hasVisiblePasswordInput || hasCustomLogin;
              var hasVisibleRegisterForm = hasVisibleLoginForm && (/(register|signup)/i.test(cleanUrl) || visiblePasswordCount >= 2);

              var hasLogoutControl = anyCustomVisible(__CUSTOM_LOGOUT_SELECTORS__);
              var hasGuestAuthAction = false;
              var hasAccountProfileNav = false;

              var actionEls = document.querySelectorAll('button, a, [role="button"], [data-action="logout"], .van-button, .van-tabbar-item, [class*="logout" i], [class*="signout" i]');
              var limit = Math.min(actionEls.length, 160);
              for (var a = 0; a < limit; a++) {
                var el = actionEls[a];
                if (!isVisible(el) || isPromoOrBanner(el) || !isInteractiveElement(el)) continue;
                var href = (el.getAttribute && el.getAttribute('href')) ? el.getAttribute('href') : '';
                var dataAction = (el.getAttribute && el.getAttribute('data-action')) ? el.getAttribute('data-action') : '';
                var label = (el.textContent || '').trim();
                if (dataAction === 'logout' || (label.length <= 40 && /^(log\s*out|sign\s*out)$/i.test(label))) {
                  hasLogoutControl = true;
                } else if (label.length <= 40 && /^(log\s*in|sign\s*in|register|create\s*account)$/i.test(label)) {
                  hasGuestAuthAction = true;
                } else if (href && /\/(account|profile|center|main|wallet)/i.test(href)) {
                  hasAccountProfileNav = true;
                }
              }
              if (hasLogoutControl) matchedIndicators.push('visible_logout_control');
              if (hasGuestAuthAction) matchedIndicators.push('visible_guest_auth_action');
              if (hasAccountProfileNav && !hasGuestAuthAction) matchedIndicators.push('visible_account_profile_nav');

              var hasSessionExpiredDom = anyCustomVisible(__CUSTOM_EXPIRED_SELECTORS__);
              if (!hasSessionExpiredDom) {
                var dialogs = document.querySelectorAll('[role="dialog"], [role="alert"], .van-dialog, .van-toast, .el-message-box');
                var dLimit = Math.min(dialogs.length, 25);
                for (var d = 0; d < dLimit; d++) {
                  var dlg = dialogs[d];
                  if (!isVisible(dlg)) continue;
                  var txt = (dlg.innerText || dlg.textContent || '').trim();
                  if (txt.length > 0 && txt.length < 160 && /(session\s*expired|please\s*log\s*in\s*again|login\s*expired|token\s*expired)/i.test(txt)) {
                    hasSessionExpiredDom = true;
                    matchedIndicators.push('visible_session_expired_dialog');
                    break;
                  }
                }
              }

              var pendingKind = window.__anshuPendingTransition || null;
              var hasLoginSubmitPending = (pendingKind === 'LOGIN_IN_PROGRESS') && !hasLogoutControl;
              var hasLogoutClickedPending = (pendingKind === 'LOGOUT_IN_PROGRESS') && !hasVisibleLoginForm;
              if (hasLogoutControl || hasVisibleLoginForm || hasSessionExpiredDom) {
                if (hasLogoutControl && pendingKind === 'LOGIN_IN_PROGRESS') window.__anshuPendingTransition = null;
                if (hasVisibleLoginForm && pendingKind === 'LOGOUT_IN_PROGRESS') window.__anshuPendingTransition = null;
              }

              var hasLoginSide = hasVisibleLoginForm || hasGuestAuthAction;
              var state = hasSessionExpiredDom ? 'SESSION_EXPIRED'
                : (hasLogoutControl && hasLoginSide) ? 'UNKNOWN'
                : hasLogoutClickedPending ? 'LOGOUT_IN_PROGRESS'
                : hasLoginSubmitPending ? 'LOGIN_IN_PROGRESS'
                : hasLogoutControl ? 'LOGIN_CONFIRMED'
                : hasVisibleLoginForm ? 'LOGIN_PAGE'
                : hasGuestAuthAction ? 'LOGGED_OUT'
                : 'UNKNOWN';

              var evidence = hasSessionExpiredDom ? 'SESSION_EXPIRED_DIALOG'
                : (hasLogoutControl && hasLoginSide) ? 'CONFLICTING_DOM'
                : hasLogoutClickedPending ? 'LOGOUT_SUBMIT_PENDING'
                : hasLoginSubmitPending ? 'LOGIN_SUBMIT_PENDING'
                : hasLogoutControl ? 'AUTHENTICATED_UI'
                : hasVisibleLoginForm ? 'LOGIN_FORM_DOM'
                : hasGuestAuthAction ? 'GUEST_AUTH_ACTIONS'
                : 'INCONCLUSIVE_DOM';

              return {
                type: '$MESSAGE_TYPE_AUTH_STATE',
                state: state,
                evidence: evidence,
                timestamp: Date.now(),
                origin: window.location.origin,
                cleanUrl: cleanUrl,
                hasVisiblePasswordInput: !!hasVisiblePasswordInput,
                hasVisibleLoginForm: !!hasVisibleLoginForm,
                hasVisibleRegisterForm: !!hasVisibleRegisterForm,
                hasGuestAuthAction: !!hasGuestAuthAction,
                hasLoginSubmitPending: !!hasLoginSubmitPending,
                hasLogoutControl: !!hasLogoutControl,
                hasLogoutClickedPending: !!hasLogoutClickedPending,
                hasAccountProfileNav: !!(hasAccountProfileNav && !hasGuestAuthAction),
                hasSessionExpiredDom: !!hasSessionExpiredDom,
                matchedIndicators: matchedIndicators
              };
            }
        """.trimIndent()

        private val DETECTOR_JS_TEMPLATE: String = """
            (function() {
              try {
                if (window.location.protocol !== 'https:') return;
                var bridge = window.$BRIDGE_NAME;
                if (!bridge || typeof bridge.postMessage !== 'function') return;

                if (typeof window.__anshuDetectorCleanup === 'function') {
                  window.__anshuDetectorCleanup();
                }

                var debounceTimer = null;
                var lastPayloadHash = '';
                ${buildInspectBodyJs()}

                function inspectDomAndPost(forcePost) {
                  try {
                    if (window.location.protocol !== 'https:' || !window.$BRIDGE_NAME) return;
                    var payload = collectDomState();
                    if (!payload) return;
                    var payloadHash = payload.cleanUrl + '|' + payload.state + '|' + payload.evidence + '|' + payload.hasLogoutControl + '|' + payload.hasVisibleLoginForm + '|' + payload.hasSessionExpiredDom;
                    if (!forcePost && payloadHash === lastPayloadHash) return;
                    lastPayloadHash = payloadHash;
                    window.$BRIDGE_NAME.postMessage(JSON.stringify(payload));
                  } catch (innerErr) {}
                }

                function scheduleInspection() {
                  if (debounceTimer) clearTimeout(debounceTimer);
                  debounceTimer = setTimeout(function() { inspectDomAndPost(false); }, $DEBOUNCE_DELAY_MS);
                }

                function onClickInteraction(evt) {
                  try {
                    var target = evt && evt.target ? evt.target.closest('button, a, [role="button"], input[type="submit"], .van-button, [class*="logout" i], [class*="btn" i]') : null;
                    if (!target || isPromoOrBanner(target)) return;
                    var txt = (target.textContent || target.value || '').trim();
                    if (txt.length > 40) return;
                    if (/^(log\s*out|sign\s*out)$/i.test(txt)) {
                      window.__anshuPendingTransition = 'LOGOUT_IN_PROGRESS';
                      scheduleInspection();
                    } else if (/^(log\s*in|sign\s*in|submit)$/i.test(txt) && document.querySelector('input[type="password"]')) {
                      window.__anshuPendingTransition = 'LOGIN_IN_PROGRESS';
                      scheduleInspection();
                    }
                  } catch (e) {}
                }

                var observer = null;
                var rootTarget = document.documentElement || document.body;
                if (typeof MutationObserver !== 'undefined' && rootTarget) {
                  observer = new MutationObserver(scheduleInspection);
                  observer.observe(rootTarget, {
                    childList: true,
                    subtree: true,
                    attributes: true,
                    attributeFilter: ['class', 'style', 'hidden', 'aria-hidden']
                  });
                }

                var origPushState = window.history && window.history.pushState;
                var origReplaceState = window.history && window.history.replaceState;
                if (origPushState && !window.history.__anshuWrapped) {
                  window.history.pushState = function() {
                    var res = origPushState.apply(this, arguments);
                    scheduleInspection();
                    return res;
                  };
                  window.history.replaceState = function() {
                    var res = origReplaceState.apply(this, arguments);
                    scheduleInspection();
                    return res;
                  };
                  window.history.__anshuWrapped = true;
                }

                function onRouteEvent() { scheduleInspection(); }
                window.addEventListener('popstate', onRouteEvent);
                window.addEventListener('hashchange', onRouteEvent);
                window.addEventListener('pageshow', onRouteEvent);
                document.addEventListener('click', onClickInteraction, true);

                window.__anshuTriggerRecheck = function() { inspectDomAndPost(true); };

                window.__anshuDetectorCleanup = function() {
                  if (debounceTimer) { clearTimeout(debounceTimer); debounceTimer = null; }
                  if (observer) { try { observer.disconnect(); } catch (e) {} observer = null; }
                  if (origPushState && window.history.__anshuWrapped) {
                    window.history.pushState = origPushState;
                    window.history.replaceState = origReplaceState;
                    window.history.__anshuWrapped = false;
                  }
                  window.removeEventListener('popstate', onRouteEvent);
                  window.removeEventListener('hashchange', onRouteEvent);
                  window.removeEventListener('pageshow', onRouteEvent);
                  document.removeEventListener('click', onClickInteraction, true);
                  window.__anshuTriggerRecheck = null;
                  window.__anshuDetectorCleanup = null;
                };

                inspectDomAndPost(true);
              } catch (err) {}
            })();
        """.trimIndent()

        private val FALLBACK_INSPECT_JS_TEMPLATE: String = """
            (function() {
              try {
                ${buildInspectBodyJs()}
                var payload = collectDomState();
                return payload ? JSON.stringify(payload) : null;
              } catch (e) { return null; }
            })();
        """.trimIndent()

        val DETECTOR_JS: String = DETECTOR_JS_TEMPLATE
            .replace("__CUSTOM_LOGOUT_SELECTORS__", "[]")
            .replace("__CUSTOM_LOGIN_SELECTORS__", "[]")
            .replace("__CUSTOM_EXPIRED_SELECTORS__", "[]")
    }
}
