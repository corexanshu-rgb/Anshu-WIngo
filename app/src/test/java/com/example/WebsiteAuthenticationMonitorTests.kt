package com.example

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.example.di.AppContainer
import com.example.domain.model.WinGoGameConfig
import com.example.security.ApiAuthStatusCode
import com.example.security.CryptoKeyProvider
import com.example.security.KeystoreCryptoManager
import com.example.security.WebsiteSessionManager
import com.example.security.WebsiteSessionState
import com.example.ui.PredictorViewModel
import com.example.ui.screens.WebViewScreen
import com.example.ui.theme.AnshuWingoTheme
import com.example.webview.AuthDomIndicator
import com.example.webview.EvidenceConfidenceLevel
import com.example.webview.UrlRouteCategory
import com.example.webview.WebsiteAuthIndicatorRegistry
import com.example.webview.WebsiteAuthenticationMonitor
import com.example.webview.WebsiteAuthenticationState
import com.example.webview.WebsiteLoginDetector
import com.example.webview.WebsiteLoginState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WebsiteAuthenticationMonitorTests {

    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private fun createTestCrypto(prefsName: String = "test_auth_monitor_prefs"): KeystoreCryptoManager {
        val keyBytes = ByteArray(32) { idx -> (idx + 11).toByte() }
        val fixedKeyProvider = object : CryptoKeyProvider {
            override fun getOrCreateSecretKey(): SecretKey = SecretKeySpec(keyBytes, "AES")
        }
        return KeystoreCryptoManager(context, fixedKeyProvider, prefsName = prefsName)
    }

    @Test
    fun `registry classifies routes safely rejects sensitive selectors and supports runtime discovery`() {
        val registry = WebsiteAuthIndicatorRegistry()
        assertEquals(
            UrlRouteCategory.REGISTER_ROUTE,
            WebsiteAuthIndicatorRegistry.classifyUrlRoute(WinGoGameConfig.INITIAL_REFERRAL_URL)
        )
        assertEquals(
            UrlRouteCategory.LOGIN_ROUTE,
            WebsiteAuthIndicatorRegistry.classifyUrlRoute("https://9kclub55.com/#/login")
        )
        assertEquals(
            UrlRouteCategory.LOGOUT_ROUTE,
            WebsiteAuthIndicatorRegistry.classifyUrlRoute("https://9kclub5.com/#/logout")
        )
        assertEquals(
            UrlRouteCategory.GAME_ROUTE,
            WebsiteAuthIndicatorRegistry.classifyUrlRoute("https://9kclub5.com/#/WinGo")
        )
        assertEquals(
            UrlRouteCategory.ACCOUNT_ROUTE,
            WebsiteAuthIndicatorRegistry.classifyUrlRoute("https://9kclub55.com/#/account")
        )
        assertEquals(
            UrlRouteCategory.UNTRUSTED_OR_UNKNOWN,
            WebsiteAuthIndicatorRegistry.classifyUrlRoute("https://evil.example.com/#/home")
        )

        var rejectedSensitive = false
        try {
            registry.registerDiscoveredIndicator(
                AuthDomIndicator(
                    name = "bad_token_reader",
                    selectorOrCondition = "css:.user-token-field",
                    expectedState = WebsiteAuthenticationState.LOGIN_CONFIRMED,
                    reliabilityWeight = 0.9,
                    isRequired = false
                )
            )
        } catch (_: IllegalArgumentException) {
            rejectedSensitive = true
        }
        assertTrue(rejectedSensitive)

        registry.registerDiscoveredIndicator(
            AuthDomIndicator(
                name = "verified_vip_shell",
                selectorOrCondition = "css:.member-vip-nav",
                expectedState = WebsiteAuthenticationState.LOGIN_CONFIRMED,
                reliabilityWeight = 0.85,
                isRequired = false,
                isVerifiedOnLiveSite = true
            )
        )
        assertTrue(registry.exportSafeCustomSelectorsForState(WebsiteAuthenticationState.LOGIN_CONFIRMED).contains(".member-vip-nav"))
    }

    @Test
    fun `multi-signal detector covers register login pending confirmed SPA reload logout expiry and conflicts`() {
        val crypto = createTestCrypto("test_multi_signal_flow")
        crypto.clearAll()
        var nowMs = 1_794_680_000_000L
        val sessionManager = WebsiteSessionManager(crypto, wallTimeProvider = { nowMs })

        var currentUrl: String? = WinGoGameConfig.INITIAL_REFERRAL_URL
        var mainFrameLoaded = false
        val scripts = mutableListOf<String>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val monitor = WebsiteAuthenticationMonitor(
            sessionManager = sessionManager,
            wallTimeProvider = { nowMs },
            scope = scope,
            ownsScope = false,
            periodicVerifyIntervalMs = 2_000L,
            flickerSuppressionWindowMs = 350L
        )
        val detector = WebsiteLoginDetector(
            sessionManager = sessionManager,
            currentUrlProvider = { currentUrl },
            isMainFrameLoadedProvider = { mainFrameLoaded },
            evaluateJavascript = { scripts.add(it) },
            mainHandler = Handler(Looper.getMainLooper()),
            externalMonitor = monitor
        )

        // 1. Opening initial registration page -> WEBVIEW_LOADING then LOGIN_PAGE (never LOGIN_CONFIRMED)
        detector.onPageStarted(currentUrl)
        assertEquals(WebsiteAuthenticationState.WEBVIEW_LOADING, sessionManager.diagnostics.value.websiteAuthState)

        mainFrameLoaded = true
        detector.onPageCommitVisible(currentUrl)
        detector.onPageFinished(currentUrl)
        assertTrue(scripts.last().contains("MutationObserver"))
        assertTrue(scripts.last().contains("history.pushState"))
        assertTrue(scripts.last().contains("history.replaceState"))
        assertTrue(scripts.last().contains("hashchange"))
        assertFalse(scripts.last().contains("localStorage"))
        assertFalse(scripts.last().contains("sessionStorage"))
        assertFalse(scripts.last().contains("document.cookie"))

        detector.handleWebMessage(
            rawJsonPayload = """
                {"type":"WEBSITE_AUTH_STATE","state":"LOGIN_PAGE","evidence":"LOGIN_FORM_DOM","timestamp":${nowMs + 100},"origin":"https://9kclub55.com","cleanUrl":"https://9kclub55.com/register?inviteCode=A86A78N","hasVisiblePasswordInput":true,"hasVisibleLoginForm":true,"hasVisibleRegisterForm":true}
            """.trimIndent(),
            sourceOrigin = "https://9kclub55.com",
            isMainFrame = true,
            currentWebViewUrl = currentUrl
        )
        assertEquals(WebsiteAuthenticationState.LOGIN_PAGE, sessionManager.diagnostics.value.websiteAuthState)
        assertEquals(UrlRouteCategory.REGISTER_ROUTE, sessionManager.diagnostics.value.urlRouteCategory)
        assertEquals(WebsiteLoginState.LOGGED_OUT, sessionManager.diagnostics.value.websiteLoginState)
        assertFalse(sessionManager.diagnostics.value.floatingButtonVisible)

        // 2. Login page & login in progress
        currentUrl = "https://9kclub55.com/#/login"
        detector.onSpaUrlUpdated(currentUrl)
        detector.handleWebMessage(
            rawJsonPayload = """
                {"type":"WEBSITE_AUTH_STATE","state":"LOGIN_IN_PROGRESS","evidence":"LOGIN_SUBMIT_PENDING","timestamp":${nowMs + 200},"origin":"https://9kclub55.com","cleanUrl":"https://9kclub55.com/#/login","hasLoginSubmitPending":true}
            """.trimIndent(),
            sourceOrigin = "https://9kclub55.com",
            isMainFrame = true,
            currentWebViewUrl = currentUrl
        )
        assertEquals(WebsiteAuthenticationState.LOGIN_IN_PROGRESS, sessionManager.diagnostics.value.websiteAuthState)
        assertFalse(sessionManager.diagnostics.value.floatingButtonVisible)

        // 3. Authenticated page verified -> LOGIN_CONFIRMED, API stays AUTH_REQUIRED
        currentUrl = "https://9kclub5.com/#/home"
        detector.onPageStarted(currentUrl, isRedirect = true)
        assertEquals(WebsiteAuthenticationState.WEBVIEW_LOADING, sessionManager.diagnostics.value.websiteAuthState)
        mainFrameLoaded = true
        detector.onPageFinished(currentUrl)
        detector.handleWebMessage(
            rawJsonPayload = """
                {"type":"WEBSITE_AUTH_STATE","state":"LOGIN_CONFIRMED","evidence":"AUTHENTICATED_UI","timestamp":${nowMs + 500},"origin":"https://9kclub5.com","cleanUrl":"https://9kclub5.com/#/home","hasLogoutControl":true,"hasAccountProfileNav":true,"matchedIndicators":["visible_logout_control","visible_account_profile_nav"]}
            """.trimIndent(),
            sourceOrigin = "https://9kclub5.com",
            isMainFrame = true,
            currentWebViewUrl = currentUrl
        )
        assertEquals(WebsiteAuthenticationState.LOGIN_CONFIRMED, sessionManager.diagnostics.value.websiteAuthState)
        assertEquals(EvidenceConfidenceLevel.MEDIUM, sessionManager.diagnostics.value.confidenceLevel)
        assertTrue(sessionManager.websiteSessionState.value is WebsiteSessionState.Authenticated)
        assertEquals(ApiAuthStatusCode.API_AUTH_REQUIRED, sessionManager.diagnostics.value.apiStatusCode)

        // API 401/403 remains independent of website session
        sessionManager.onApiAuthenticationRejected(401, "Missing Bearer credential.")
        assertEquals(WebsiteAuthenticationState.LOGIN_CONFIRMED, sessionManager.diagnostics.value.websiteAuthState)
        assertEquals(ApiAuthStatusCode.API_REJECTED, sessionManager.diagnostics.value.apiStatusCode)

        // 4. SPA tab navigation (WinGo -> Account) stays stable
        currentUrl = "https://9kclub5.com/#/WinGo"
        detector.onSpaUrlUpdated(currentUrl)
        detector.handleWebMessage(
            rawJsonPayload = """
                {"type":"WEBSITE_AUTH_STATE","state":"LOGIN_CONFIRMED","evidence":"AUTHENTICATED_UI","timestamp":${nowMs + 700},"origin":"https://9kclub5.com","cleanUrl":"https://9kclub5.com/#/WinGo","hasLogoutControl":true,"hasAccountProfileNav":true}
            """.trimIndent(),
            sourceOrigin = "https://9kclub5.com",
            isMainFrame = true,
            currentWebViewUrl = currentUrl
        )
        assertEquals(WebsiteAuthenticationState.LOGIN_CONFIRMED, sessionManager.diagnostics.value.websiteAuthState)
        assertEquals(EvidenceConfidenceLevel.HIGH, sessionManager.diagnostics.value.confidenceLevel)

        // 5. Missing DOM / homepage banner alone -> UNKNOWN (not LOGIN_CONFIRMED)
        // Account nav alone without logout control also stays UNKNOWN (after reset from logout)
        currentUrl = "https://9kclub5.com/#/logout"
        detector.onSpaUrlUpdated(currentUrl)
        assertEquals(WebsiteAuthenticationState.LOGOUT_IN_PROGRESS, sessionManager.diagnostics.value.websiteAuthState)
        detector.handleWebMessage(
            rawJsonPayload = """
                {"type":"WEBSITE_AUTH_STATE","state":"LOGGED_OUT","evidence":"GUEST_AUTH_ACTIONS","timestamp":${nowMs + 900},"origin":"https://9kclub5.com","cleanUrl":"https://9kclub5.com/#/login","hasGuestAuthAction":true,"hasVisibleLoginForm":true,"hasVisiblePasswordInput":true}
            """.trimIndent(),
            sourceOrigin = "https://9kclub5.com",
            isMainFrame = true,
            currentWebViewUrl = "https://9kclub5.com/#/login"
        )
        assertEquals(WebsiteAuthenticationState.LOGIN_PAGE, sessionManager.diagnostics.value.websiteAuthState)
        assertTrue(sessionManager.websiteSessionState.value is WebsiteSessionState.Unauthenticated)

        // 6. Account nav alone without logout control or callback -> UNKNOWN
        currentUrl = "https://9kclub5.com/#/home"
        detector.handleWebMessage(
            rawJsonPayload = """
                {"type":"WEBSITE_AUTH_STATE","state":"UNKNOWN","evidence":"INCONCLUSIVE_DOM","timestamp":${nowMs + 1000},"origin":"https://9kclub5.com","cleanUrl":"https://9kclub5.com/#/home","hasAccountProfileNav":true,"hasLogoutControl":false}
            """.trimIndent(),
            sourceOrigin = "https://9kclub5.com",
            isMainFrame = true,
            currentWebViewUrl = currentUrl
        )
        assertEquals(WebsiteAuthenticationState.UNKNOWN, sessionManager.diagnostics.value.websiteAuthState)

        // 7. Conflicting DOM indicators -> UNKNOWN + CONFLICTING
        detector.handleWebMessage(
            rawJsonPayload = """
                {"type":"WEBSITE_AUTH_STATE","state":"UNKNOWN","evidence":"CONFLICTING_DOM","timestamp":${nowMs + 1100},"origin":"https://9kclub5.com","cleanUrl":"https://9kclub5.com/#/home","hasLogoutControl":true,"hasVisiblePasswordInput":true}
            """.trimIndent(),
            sourceOrigin = "https://9kclub5.com",
            isMainFrame = true,
            currentWebViewUrl = currentUrl
        )
        assertEquals(WebsiteAuthenticationState.UNKNOWN, sessionManager.diagnostics.value.websiteAuthState)
        assertEquals(EvidenceConfidenceLevel.CONFLICTING, sessionManager.diagnostics.value.confidenceLevel)

        // 8. Session expired dialog -> SESSION_EXPIRED
        detector.handleWebMessage(
            rawJsonPayload = """
                {"type":"WEBSITE_AUTH_STATE","state":"SESSION_EXPIRED","evidence":"SESSION_EXPIRED_DIALOG","timestamp":${nowMs + 1200},"origin":"https://9kclub5.com","cleanUrl":"https://9kclub5.com/#/home","hasSessionExpiredDom":true}
            """.trimIndent(),
            sourceOrigin = "https://9kclub5.com",
            isMainFrame = true,
            currentWebViewUrl = currentUrl
        )
        assertEquals(WebsiteAuthenticationState.SESSION_EXPIRED, sessionManager.diagnostics.value.websiteAuthState)
        assertEquals(WebsiteLoginState.SESSION_EXPIRED, sessionManager.diagnostics.value.websiteLoginState)

        // 9. App restart persists hint only and NEVER starts in LOGIN_CONFIRMED without fresh verification
        detector.handleWebMessage(
            rawJsonPayload = """
                {"type":"WEBSITE_AUTH_STATE","state":"LOGIN_CONFIRMED","evidence":"AUTHENTICATED_UI","timestamp":${nowMs + 1300},"origin":"https://9kclub5.com","cleanUrl":"https://9kclub5.com/#/WinGo","hasLogoutControl":true}
            """.trimIndent(),
            sourceOrigin = "https://9kclub5.com",
            isMainFrame = true,
            currentWebViewUrl = "https://9kclub5.com/#/WinGo"
        )
        assertEquals(WebsiteAuthenticationState.LOGIN_CONFIRMED, sessionManager.diagnostics.value.websiteAuthState)

        val restartedSessionManager = WebsiteSessionManager(crypto, wallTimeProvider = { nowMs + 10_000L })
        assertEquals(WebsiteAuthenticationState.LOGIN_CONFIRMED, restartedSessionManager.diagnostics.value.persistedHintState)
        assertEquals(WebsiteAuthenticationState.WEBVIEW_LOADING, restartedSessionManager.diagnostics.value.websiteAuthState)
        assertTrue(restartedSessionManager.websiteSessionState.value is WebsiteSessionState.Unknown)

        detector.detach()
        scope.cancel()
    }

    @Test
    fun `bridge rejects sensitive fields untrusted origins non-main-frame and prevents duplicate observers`() = runBlocking {
        val crypto = createTestCrypto("test_security_guards")
        crypto.clearAll()
        var nowMs = 1_794_681_000_000L
        val sessionManager = WebsiteSessionManager(crypto, wallTimeProvider = { nowMs })

        var currentUrl = "https://9kclub55.com/#/WinGo"
        var mainFrameLoaded = true
        var periodicVerifyTicks = 0
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val monitor = WebsiteAuthenticationMonitor(
            sessionManager = sessionManager,
            wallTimeProvider = { nowMs },
            scope = scope,
            ownsScope = false,
            periodicVerifyIntervalMs = 40L,
            flickerSuppressionWindowMs = 0L,
            triggerPeriodicVerificationJs = { periodicVerifyTicks++ }
        )
        val detector = WebsiteLoginDetector(
            sessionManager = sessionManager,
            currentUrlProvider = { currentUrl },
            isMainFrameLoadedProvider = { mainFrameLoaded },
            evaluateJavascript = { },
            mainHandler = Handler(Looper.getMainLooper()),
            externalMonitor = monitor
        )

        monitor.onPageFinished(currentUrl)
        // Multiple rapid onPageFinished / onSpaUrlUpdated calls must NOT spawn duplicate periodic jobs
        monitor.onPageFinished(currentUrl)
        detector.onSpaUrlUpdated(currentUrl)
        assertTrue(monitor.isPeriodicVerificationActive())
        delay(100L)
        assertTrue(periodicVerifyTicks in 1..4)

        // Pause in background stops periodic verification; resume restarts it
        detector.pauseDetection()
        val pausedTicks = periodicVerifyTicks
        assertFalse(monitor.isPeriodicVerificationActive())
        delay(70L)
        assertEquals(pausedTicks, periodicVerifyTicks)

        detector.resumeDetection()
        assertTrue(monitor.isPeriodicVerificationActive())

        // WebView process recreation resets state cleanly to WEBVIEW_LOADING
        detector.onRenderProcessGone(currentUrl)
        assertEquals(WebsiteAuthenticationState.WEBVIEW_LOADING, sessionManager.diagnostics.value.websiteAuthState)
        assertFalse(monitor.isPeriodicVerificationActive())

        // Recover page load & confirm LOGIN_CONFIRMED via runtime-timestamped message
        mainFrameLoaded = true
        detector.onPageFinished(currentUrl)
        detector.handleWebMessage(
            rawJsonPayload = """
                {"type":"WEBSITE_AUTH_STATE","state":"LOGIN_CONFIRMED","evidence":"AUTHENTICATED_UI","timestamp":$nowMs,"origin":"https://9kclub55.com","cleanUrl":"https://9kclub55.com/#/WinGo","hasLogoutControl":true}
            """.trimIndent(),
            sourceOrigin = "https://9kclub55.com",
            isMainFrame = true,
            currentWebViewUrl = currentUrl
        )
        assertEquals(WebsiteAuthenticationState.LOGIN_CONFIRMED, sessionManager.diagnostics.value.websiteAuthState)

        // Reject payload containing sensitive fields (token, phone, otp, cookie, balance)
        detector.handleWebMessage(
            rawJsonPayload = """
                {"type":"WEBSITE_AUTH_STATE","state":"LOGGED_OUT","evidence":"SPOOF","timestamp":$nowMs,"token":"secret_jwt_123"}
            """.trimIndent(),
            sourceOrigin = "https://9kclub55.com",
            isMainFrame = true,
            currentWebViewUrl = currentUrl
        )
        assertEquals(WebsiteAuthenticationState.LOGIN_CONFIRMED, sessionManager.diagnostics.value.websiteAuthState)

        // Reject timestamp <= 0
        detector.handleWebMessage(
            rawJsonPayload = """
                {"type":"WEBSITE_AUTH_STATE","state":"LOGGED_OUT","evidence":"ZERO_TS","timestamp":0}
            """.trimIndent(),
            sourceOrigin = "https://9kclub55.com",
            isMainFrame = true,
            currentWebViewUrl = currentUrl
        )
        assertEquals(WebsiteAuthenticationState.LOGIN_CONFIRMED, sessionManager.diagnostics.value.websiteAuthState)

        // Reject subframe (isMainFrame = false)
        detector.handleWebMessage(
            rawJsonPayload = """
                {"type":"WEBSITE_AUTH_STATE","state":"LOGGED_OUT","evidence":"IFRAME","timestamp":$nowMs}
            """.trimIndent(),
            sourceOrigin = "https://9kclub55.com",
            isMainFrame = false,
            currentWebViewUrl = currentUrl
        )
        assertEquals(WebsiteAuthenticationState.LOGIN_CONFIRMED, sessionManager.diagnostics.value.websiteAuthState)

        // Reject untrusted origin
        detector.handleWebMessage(
            rawJsonPayload = """
                {"type":"WEBSITE_AUTH_STATE","state":"LOGGED_OUT","evidence":"UNTRUSTED","timestamp":$nowMs}
            """.trimIndent(),
            sourceOrigin = "https://phish.example.org",
            isMainFrame = true,
            currentWebViewUrl = currentUrl
        )
        assertEquals(WebsiteAuthenticationState.LOGIN_CONFIRMED, sessionManager.diagnostics.value.websiteAuthState)

        // Restricted evaluateJavascript fallback when WEB_MESSAGE_LISTENER is unsupported
        val fallbackSession = WebsiteSessionManager(createTestCrypto("test_fallback_prefs"), wallTimeProvider = { nowMs })
        val fallbackDetector = WebsiteLoginDetector(
            sessionManager = fallbackSession,
            currentUrlProvider = { currentUrl },
            isMainFrameLoadedProvider = { true },
            evaluateJavascript = { },
            mainHandler = Handler(Looper.getMainLooper()),
            evaluateJavascriptWithCallback = { _, callback ->
                val raw = """"{\"type\":\"WEBSITE_AUTH_STATE\",\"state\":\"LOGIN_PAGE\",\"evidence\":\"LOGIN_FORM_DOM\",\"timestamp\":$nowMs,\"origin\":\"https://9kclub55.com\",\"cleanUrl\":\"https://9kclub55.com/#/login\",\"hasVisiblePasswordInput\":true,\"hasVisibleLoginForm\":true}""""
                callback(raw)
            }
        )
        val wv = WebView(context)
        assertFalse(fallbackDetector.attachToWebView(wv) { false })
        fallbackDetector.onPageFinished("https://9kclub55.com/#/login")
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(WebsiteAuthenticationState.LOGIN_PAGE, fallbackSession.diagnostics.value.websiteAuthState)

        fallbackDetector.detach(wv)
        wv.destroy()
        detector.detach()
        scope.cancel()
    }

    @Test
    fun `session diagnostics UI displays full multi-signal Website History and API sections cleanly`() {
        val container = AppContainer(context)
        val viewModel = PredictorViewModel(container)

        composeRule.setContent {
            AnshuWingoTheme {
                WebViewScreen(
                    viewModel = viewModel,
                    sessionManager = container.websiteSessionManager,
                    onNavigateToAnalytics = {}
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Current URL category").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Observer state").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Website authentication state").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Last verification time").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Evidence reason").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("History Diagnostics").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("API Diagnostics").performScrollTo().assertIsDisplayed()

        container.networkMonitor.unregister()
    }

    @Test
    fun `exact origin validation rejects subdomains custom ports http and keeps API state independent across lifecycle`() {
        val crypto = createTestCrypto("test_exact_origin_and_api_sep")
        crypto.clearAll()
        var nowMs = 1_794_682_000_000L
        val sessionManager = WebsiteSessionManager(crypto, wallTimeProvider = { nowMs })

        // Exact origin validation checks
        assertTrue(WebsiteAuthIndicatorRegistry.isExactApprovedOriginUrl("https://9kclub55.com/#/home"))
        assertTrue(WebsiteAuthIndicatorRegistry.isExactApprovedOriginUrl("https://www.9kclub55.com/#/login"))
        assertTrue(WebsiteAuthIndicatorRegistry.isExactApprovedOriginUrl("https://9kclub5.com/#/WinGo"))
        assertTrue(WebsiteAuthIndicatorRegistry.isExactApprovedOriginUrl("https://www.9kclub5.com/"))
        assertFalse(WebsiteAuthIndicatorRegistry.isExactApprovedOriginUrl("http://9kclub55.com/#/login"))
        assertFalse(WebsiteAuthIndicatorRegistry.isExactApprovedOriginUrl("https://sub.9kclub55.com/#/home"))
        assertFalse(WebsiteAuthIndicatorRegistry.isExactApprovedOriginUrl("https://9kclub55.com:8443/#/home"))
        assertFalse(WebsiteAuthIndicatorRegistry.isExactApprovedOriginUrl("https://evil.example.com/#/home"))

        // Preserve independent API state (e.g. SIGNATURE_NOT_CONFIGURED) when website transitions through LOGIN_PAGE
        sessionManager.onApiCredentialsMissing(
            missingBearerToken = false,
            missingSignatureAlgorithm = true,
            details = "Signature provider not configured."
        )
        assertEquals(ApiAuthStatusCode.API_SIGNATURE_NOT_CONFIGURED, sessionManager.diagnostics.value.apiStatusCode)

        var currentUrl = "https://9kclub55.com/#/login"
        var mainFrameLoaded = true
        val scripts = mutableListOf<String>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val monitor = WebsiteAuthenticationMonitor(
            sessionManager = sessionManager,
            wallTimeProvider = { nowMs },
            scope = scope,
            ownsScope = false,
            periodicVerifyIntervalMs = 2_000L,
            flickerSuppressionWindowMs = 0L
        )
        val detector = WebsiteLoginDetector(
            sessionManager = sessionManager,
            currentUrlProvider = { currentUrl },
            isMainFrameLoadedProvider = { mainFrameLoaded },
            evaluateJavascript = { scripts.add(it) },
            mainHandler = Handler(Looper.getMainLooper()),
            externalMonitor = monitor
        )

        detector.onPageFinished(currentUrl)
        assertEquals(1, monitor.activePeriodicJobCount())
        assertTrue(scripts.last().contains("isPromoOrBanner"))
        assertTrue(scripts.last().contains("van-button"))

        // Minimal safe bridge LOGIN_PAGE must NOT clobber API_SIGNATURE_NOT_CONFIGURED
        detector.handleWebMessage(
            rawJsonPayload = """
                {"type":"WEBSITE_AUTH_STATE","state":"LOGIN_PAGE","evidence":"LOGIN_FORM_DOM","timestamp":${nowMs + 50}}
            """.trimIndent(),
            sourceOrigin = "https://9kclub55.com",
            isMainFrame = true,
            currentWebViewUrl = currentUrl
        )
        assertEquals(WebsiteAuthenticationState.LOGIN_PAGE, sessionManager.diagnostics.value.websiteAuthState)
        assertEquals(ApiAuthStatusCode.API_SIGNATURE_NOT_CONFIGURED, sessionManager.diagnostics.value.apiStatusCode)

        // Minimal safe bridge LOGIN_CONFIRMED with weak/homepage evidence MUST stay UNKNOWN (Rule 4)
        currentUrl = "https://9kclub55.com/#/home"
        detector.handleWebMessage(
            rawJsonPayload = """
                {"type":"WEBSITE_AUTH_STATE","state":"LOGIN_CONFIRMED","evidence":"HOMEPAGE_ONLY","timestamp":${nowMs + 100}}
            """.trimIndent(),
            sourceOrigin = "https://9kclub55.com",
            isMainFrame = true,
            currentWebViewUrl = currentUrl
        )
        assertEquals(WebsiteAuthenticationState.UNKNOWN, sessionManager.diagnostics.value.websiteAuthState)

        // Minimal safe bridge LOGIN_CONFIRMED with AUTHENTICATED_UI confirms login while API remains unconfigured
        detector.handleWebMessage(
            rawJsonPayload = """
                {"type":"WEBSITE_AUTH_STATE","state":"LOGIN_CONFIRMED","evidence":"AUTHENTICATED_UI","timestamp":${nowMs + 150}}
            """.trimIndent(),
            sourceOrigin = "https://9kclub55.com",
            isMainFrame = true,
            currentWebViewUrl = currentUrl
        )
        assertEquals(WebsiteAuthenticationState.LOGIN_CONFIRMED, sessionManager.diagnostics.value.websiteAuthState)
        assertEquals(ApiAuthStatusCode.API_SIGNATURE_NOT_CONFIGURED, sessionManager.diagnostics.value.apiStatusCode)

        // Redirect to related approved origin resets to WEBVIEW_LOADING until re-verified
        currentUrl = "https://9kclub5.com/#/WinGo"
        mainFrameLoaded = false
        detector.onRedirectDetected(currentUrl)
        assertEquals(WebsiteAuthenticationState.WEBVIEW_LOADING, sessionManager.diagnostics.value.websiteAuthState)
        assertFalse(monitor.isPeriodicVerificationActive())

        // Full page reload completes and re-attaches single observer
        mainFrameLoaded = true
        detector.onPageFinished(currentUrl)
        detector.onPageLoadProgressComplete(currentUrl)
        assertEquals(1, monitor.activePeriodicJobCount())

        // Subdomain spoofed origin message is rejected
        detector.handleWebMessage(
            rawJsonPayload = """
                {"type":"WEBSITE_AUTH_STATE","state":"LOGGED_OUT","evidence":"SPOOF","timestamp":${nowMs + 200}}
            """.trimIndent(),
            sourceOrigin = "https://sub.9kclub5.com",
            isMainFrame = true,
            currentWebViewUrl = currentUrl
        )
        assertEquals(WebsiteAuthenticationState.WEBVIEW_LOADING, sessionManager.diagnostics.value.websiteAuthState)

        // Reject non-boolean hasVisiblePasswordInput smuggling attempt
        detector.onPageFinished(currentUrl)
        detector.handleWebMessage(
            rawJsonPayload = """
                {"type":"WEBSITE_AUTH_STATE","state":"LOGIN_PAGE","evidence":"LOGIN_FORM_DOM","timestamp":${nowMs + 250},"hasVisiblePasswordInput":"leaked_secret"}
            """.trimIndent(),
            sourceOrigin = "https://9kclub5.com",
            isMainFrame = true,
            currentWebViewUrl = currentUrl
        )
        assertEquals(WebsiteAuthenticationState.WEBVIEW_LOADING, sessionManager.diagnostics.value.websiteAuthState)

        detector.detach()
        scope.cancel()
    }

    @Test
    fun `flicker suppression debounces transient inconclusive drops after login confirmation`() = runBlocking {
        val crypto = createTestCrypto("test_flicker_suppression")
        crypto.clearAll()
        val nowMs = 1_794_683_000_000L
        val sessionManager = WebsiteSessionManager(crypto, wallTimeProvider = { nowMs })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val currentUrl = "https://9kclub55.com/#/WinGo"

        val monitor = WebsiteAuthenticationMonitor(
            sessionManager = sessionManager,
            wallTimeProvider = { nowMs },
            scope = scope,
            ownsScope = false,
            periodicVerifyIntervalMs = 2_000L,
            flickerSuppressionWindowMs = 120L
        )
        val detector = WebsiteLoginDetector(
            sessionManager = sessionManager,
            currentUrlProvider = { currentUrl },
            isMainFrameLoadedProvider = { true },
            evaluateJavascript = { },
            mainHandler = Handler(Looper.getMainLooper()),
            externalMonitor = monitor
        )

        detector.onPageFinished(currentUrl)
        detector.handleWebMessage(
            rawJsonPayload = """
                {"type":"WEBSITE_AUTH_STATE","state":"LOGIN_CONFIRMED","evidence":"AUTHENTICATED_UI","timestamp":$nowMs,"hasLogoutControl":true}
            """.trimIndent(),
            sourceOrigin = "https://9kclub55.com",
            isMainFrame = true,
            currentWebViewUrl = currentUrl
        )
        assertEquals(WebsiteAuthenticationState.LOGIN_CONFIRMED, sessionManager.diagnostics.value.websiteAuthState)

        // Transient inconclusive DOM (e.g. during SPA swap) must NOT immediately flicker out of LOGIN_CONFIRMED
        detector.handleWebMessage(
            rawJsonPayload = """
                {"type":"WEBSITE_AUTH_STATE","state":"UNKNOWN","evidence":"INCONCLUSIVE_DOM","timestamp":${nowMs + 20},"hasLogoutControl":false}
            """.trimIndent(),
            sourceOrigin = "https://9kclub55.com",
            isMainFrame = true,
            currentWebViewUrl = currentUrl
        )
        assertEquals(WebsiteAuthenticationState.LOGIN_CONFIRMED, sessionManager.diagnostics.value.websiteAuthState)

        // After the debounce window elapses with no fresh LOGIN_CONFIRMED signal, state settles to UNKNOWN
        delay(200L)
        assertEquals(WebsiteAuthenticationState.UNKNOWN, sessionManager.diagnostics.value.websiteAuthState)

        detector.detach()
        scope.cancel()
    }
}
