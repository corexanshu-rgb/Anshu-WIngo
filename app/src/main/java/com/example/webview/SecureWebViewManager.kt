package com.example.webview

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.example.domain.model.WinGoGameConfig
import com.example.security.WebsiteSessionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface WebViewLoadState {
    data object Idle : WebViewLoadState
    data class Loading(val progress: Int) : WebViewLoadState
    data object Loaded : WebViewLoadState
    data class Error(val message: String, val isSslError: Boolean = false) : WebViewLoadState
}

class WebViewController {
    var webView: WebView? = null
        internal set

    internal var loginDetector: WebsiteLoginDetector? = null

    private val _renderGeneration = MutableStateFlow(0)
    val renderGeneration: StateFlow<Int> = _renderGeneration.asStateFlow()

    @Volatile
    var lastApprovedUrl: String = WinGoGameConfig.INITIAL_REFERRAL_URL
        internal set

    internal fun notifyRenderProcessRecreated(lastUrl: String?) {
        if (SecureWebViewManager.isApprovedHttpsUrl(lastUrl)) {
            lastApprovedUrl = lastUrl!!
        }
        _renderGeneration.value = _renderGeneration.value + 1
    }

    fun getOrCreateWebView(
        context: Context,
        configureAndLoadInitial: (WebView) -> Unit
    ): WebView {
        val existing = webView
        if (existing != null) {
            (existing.parent as? ViewGroup)?.removeView(existing)
            return existing
        }
        return WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            configureAndLoadInitial(this)
            webView = this
        }
    }

    fun reload() {
        webView?.reload()
    }

    fun goBack(): Boolean {
        val view = webView ?: return false
        return if (view.canGoBack()) {
            view.goBack()
            true
        } else {
            false
        }
    }

    fun loadReferralHome() {
        webView?.loadUrl(WinGoGameConfig.INITIAL_REFERRAL_URL)
    }

    fun pauseLoginDetection() {
        loginDetector?.pauseDetection()
    }

    fun resumeLoginDetection() {
        loginDetector?.resumeDetection()
    }

    fun destroy() {
        val existing = webView
        loginDetector?.detach(existing)
        loginDetector = null
        if (existing == null) return
        (existing.parent as? ViewGroup)?.removeView(existing)
        existing.stopLoading()
        existing.destroy()
        webView = null
    }
}

object SecureWebViewManager {

    fun isApprovedHttpsUrl(urlString: String?): Boolean =
        WebsiteAuthIndicatorRegistry.isExactApprovedOriginUrl(urlString)

    @SuppressLint("SetJavaScriptEnabled")
    fun configureWebView(
        webView: WebView,
        controller: WebViewController,
        sessionManager: WebsiteSessionManager,
        onUrlObserved: (String?) -> Unit,
        onCanGoBackChanged: (Boolean) -> Unit,
        onLoadStateChanged: (WebViewLoadState) -> Unit
    ) {
        controller.webView = webView
        val mainHandler = Handler(Looper.getMainLooper())

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            javaScriptCanOpenWindowsAutomatically = false
            allowFileAccess = false
            allowContentAccess = false
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = false
            displayZoomControls = false
            setSupportMultipleWindows(false)
        }

        var mainFrameLoaded = false

        // Origin-restricted WebMessageListener (with origin-checked evaluateJavascript fallback when unsupported)
        val loginDetector = WebsiteLoginDetector(
            sessionManager = sessionManager,
            currentUrlProvider = { webView.url },
            isMainFrameLoadedProvider = { mainFrameLoaded },
            evaluateJavascript = { script ->
                if (controller.webView === webView) {
                    webView.evaluateJavascript(script, null)
                }
            },
            mainHandler = mainHandler,
            evaluateJavascriptWithCallback = { script, callback ->
                if (controller.webView === webView) {
                    webView.evaluateJavascript(script) { result -> callback(result) }
                }
            }
        )
        controller.loginDetector = loginDetector
        loginDetector.attachToWebView(webView)

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (newProgress in 1..99) {
                    onLoadStateChanged(WebViewLoadState.Loading(newProgress))
                } else if (newProgress == 100 && mainFrameLoaded) {
                    loginDetector.onPageLoadProgressComplete(view?.url)
                }
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val targetUrl = request?.url?.toString()
                val isMainFrame = request?.isForMainFrame ?: true
                if (!isMainFrame) {
                    return targetUrl.isNullOrBlank() || !targetUrl.startsWith("https://", ignoreCase = true)
                }
                val approved = isApprovedHttpsUrl(targetUrl)
                if (!approved) {
                    loginDetector.stopAndCleanup("Blocked navigation to non-approved host.")
                    sessionManager.onWebViewNavigationState(
                        url = targetUrl,
                        mainFrameLoaded = mainFrameLoaded,
                        loadError = "Blocked navigation to non-approved host."
                    )
                } else if (request?.isRedirect == true) {
                    mainFrameLoaded = false
                    loginDetector.onRedirectDetected(targetUrl)
                }
                return !approved
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                mainFrameLoaded = false
                loginDetector.onPageStarted(url, isRedirect = false)
                onLoadStateChanged(WebViewLoadState.Loading(10))
                onCanGoBackChanged(view?.canGoBack() == true)
                onUrlObserved(url)
                sessionManager.onWebViewNavigationState(url = url, mainFrameLoaded = false)
            }

            override fun onPageCommitVisible(view: WebView?, url: String?) {
                super.onPageCommitVisible(view, url)
                mainFrameLoaded = true
                loginDetector.onPageCommitVisible(url)
                sessionManager.onWebViewNavigationState(url = url, mainFrameLoaded = true)
                sessionManager.evaluateCookieSession(url, mainFrameLoaded = true)
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                onCanGoBackChanged(view?.canGoBack() == true)
                onUrlObserved(url)
                sessionManager.onWebViewNavigationState(url = url, mainFrameLoaded = mainFrameLoaded)
                sessionManager.evaluateCookieSession(url, mainFrameLoaded = mainFrameLoaded)
                loginDetector.onSpaUrlUpdated(url, isReload = isReload)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                mainFrameLoaded = true
                if (isApprovedHttpsUrl(url)) {
                    controller.lastApprovedUrl = url!!
                }
                CookieManager.getInstance().flush()
                onLoadStateChanged(WebViewLoadState.Loaded)
                onCanGoBackChanged(view?.canGoBack() == true)
                onUrlObserved(url)
                sessionManager.onWebViewNavigationState(url = url, mainFrameLoaded = true)
                sessionManager.evaluateCookieSession(url, mainFrameLoaded = true)
                loginDetector.onPageFinished(url)
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    mainFrameLoaded = false
                    loginDetector.stopAndCleanup("WebView main-frame error.")
                    val desc = error?.description?.toString()?.takeIf { it.isNotBlank() }
                        ?: "Unable to load 9K Club website"
                    onLoadStateChanged(WebViewLoadState.Error(desc, isSslError = false))
                    sessionManager.onWebViewNavigationState(
                        url = request.url?.toString() ?: view?.url,
                        mainFrameLoaded = false,
                        loadError = desc
                    )
                }
            }

            override fun onReceivedSslError(
                view: WebView?,
                handler: SslErrorHandler?,
                error: SslError?
            ) {
                mainFrameLoaded = false
                loginDetector.stopAndCleanup("SSL error blocked.")
                handler?.cancel()
                val msg = "SSL certificate validation failed. Connection blocked for security."
                onLoadStateChanged(
                    WebViewLoadState.Error(
                        message = msg,
                        isSslError = true
                    )
                )
                sessionManager.onWebViewNavigationState(
                    url = error?.url ?: view?.url,
                    mainFrameLoaded = false,
                    loadError = msg
                )
            }

            override fun onRenderProcessGone(
                view: WebView?,
                detail: RenderProcessGoneDetail?
            ): Boolean {
                mainFrameLoaded = false
                val lastUrl = view?.url
                loginDetector.onRenderProcessGone(lastUrl)
                if (controller.webView === view) {
                    (view?.parent as? ViewGroup)?.removeView(view)
                    runCatching { view?.destroy() }
                    controller.webView = null
                    controller.notifyRenderProcessRecreated(lastUrl)
                }
                onLoadStateChanged(WebViewLoadState.Loading(5))
                return true
            }
        }
    }
}
