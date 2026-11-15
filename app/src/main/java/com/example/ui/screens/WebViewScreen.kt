package com.example.ui.screens

import android.annotation.SuppressLint
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.domain.model.NetworkStatus
import com.example.domain.model.WinGoGameConfig
import com.example.network.HistoryApiStatus
import com.example.network.HistorySyncDiagnostics
import com.example.security.ApiAuthStatusCode
import com.example.security.DiagnosticLoginState
import com.example.security.LoginDiagnosticState
import com.example.security.WebsiteSessionManager
import com.example.security.WebsiteSessionState
import com.example.timer.TimerUiState
import com.example.ui.PredictorViewModel
import com.example.ui.UserActivationState
import com.example.ui.components.FloatingPredictorButton
import com.example.ui.components.FloatingPredictorPanel
import com.example.ui.theme.CobaltBlue
import com.example.ui.theme.DeepRoyalBlue
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.MutedSlate
import com.example.ui.theme.PureWhite
import com.example.ui.theme.SlateInk
import com.example.ui.theme.SoftBlueSurface
import com.example.ui.theme.StatusError
import com.example.ui.theme.StatusOnline
import com.example.ui.theme.StatusWarning
import com.example.ui.theme.SubtleBorder
import com.example.webview.SecureWebViewManager
import com.example.webview.WebViewController
import com.example.webview.WebViewLoadState
import com.example.webview.WebsiteAuthenticationState
import com.example.webview.WebsiteLoginState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Primary screen hosting the full-screen 9K Club WebView inside a Compose Box layout,
 * with the native activation prompt, diagnostics, FloatingPredictorButton, and
 * FloatingPredictorPanel overlaid above it.
 */
@SuppressLint("UnusedBoxWithConstraintsScope")
@Composable
fun WebViewScreen(
    viewModel: PredictorViewModel,
    sessionManager: WebsiteSessionManager,
    webViewController: WebViewController = remember { WebViewController() },
    isScreenActive: Boolean = true,
    onNavigateToAnalytics: () -> Unit
) {
    val websiteSessionState by viewModel.websiteSessionState.collectAsStateWithLifecycle()
    val userActivationState by viewModel.userActivationState.collectAsStateWithLifecycle()
    val activationPromptDismissed by viewModel.activationPromptDismissed.collectAsStateWithLifecycle()
    val loginDiagnostics by viewModel.loginDiagnostics.collectAsStateWithLifecycle()
    val historyDiagnostics by viewModel.historyDiagnostics.collectAsStateWithLifecycle()
    val apiAuthState by viewModel.apiAuthState.collectAsStateWithLifecycle()
    val networkStatus by viewModel.networkStatus.collectAsStateWithLifecycle()
    val timerState by viewModel.timerState.collectAsStateWithLifecycle()
    val panelUiState by viewModel.panelUiState.collectAsStateWithLifecycle()
    val webViewLoadState by viewModel.webViewLoadState.collectAsStateWithLifecycle()
    val canGoBack by viewModel.canWebViewGoBack.collectAsStateWithLifecycle()
    val historyResults by viewModel.latestHistory.collectAsStateWithLifecycle()
    val historyCount by viewModel.historyCount.collectAsStateWithLifecycle()
    val isHistorySyncing by viewModel.isHistorySyncing.collectAsStateWithLifecycle()
    val latestPredictions by viewModel.latestPredictions.collectAsStateWithLifecycle()
    val historicalAnalysis by viewModel.historicalAnalysis.collectAsStateWithLifecycle()
    val coordinatorState by viewModel.coordinatorState.collectAsStateWithLifecycle()
    val trainingSnapshot by viewModel.trainingSnapshot.collectAsStateWithLifecycle()
    val moduleStatuses by viewModel.moduleStatuses.collectAsStateWithLifecycle()
    val lastSyncMessage by viewModel.lastSyncMessage.collectAsStateWithLifecycle()

    val isWebsiteAuthenticated = websiteSessionState is WebsiteSessionState.Authenticated
    val isManuallyActivated = userActivationState == UserActivationState.ACTIVE
    val canShowPredictor = isWebsiteAuthenticated || isManuallyActivated
    val isFloatingButtonVisible = canShowPredictor && !panelUiState.isPanelOpen

    val showActivationPrompt = loginDiagnostics.webViewLoaded &&
        loginDiagnostics.isApprovedDomain &&
        !canShowPredictor &&
        !activationPromptDismissed &&
        webViewLoadState !is WebViewLoadState.Error

    DisposableEffect(webViewController) {
        onDispose {
            webViewController.pauseLoginDetection()
        }
    }

    BackHandler(enabled = isScreenActive && (panelUiState.isPanelOpen || canGoBack)) {
        if (panelUiState.isPanelOpen) {
            viewModel.minimizePredictorPanel()
        } else {
            webViewController.goBack()
        }
    }

    Scaffold(
        containerColor = PureWhite,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            WebViewTopBar(
                networkStatus = networkStatus,
                sessionState = websiteSessionState,
                userActivationState = userActivationState,
                onRefreshClick = { webViewController.reload() },
                onAnalyticsClick = onNavigateToAnalytics
            )
        }
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .navigationBarsPadding()
                .background(PureWhite)
        ) {
            val density = LocalDensity.current
            val containerWidthPx = with(density) { maxWidth.toPx() }
            val containerHeightPx = with(density) { maxHeight.toPx() }

            val renderGeneration by webViewController.renderGeneration.collectAsStateWithLifecycle()

            // Layer 1: Full-screen Original 9K Club WebView (reuses controller's instance if recomposed,
            // and recreates automatically if the Android WebView render process crashes)
            key(renderGeneration) {
                AndroidView(
                    factory = { ctx ->
                        webViewController.getOrCreateWebView(ctx) { webView ->
                            SecureWebViewManager.configureWebView(
                                webView = webView,
                                controller = webViewController,
                                sessionManager = sessionManager,
                                onUrlObserved = viewModel::onWebUrlObserved,
                                onCanGoBackChanged = viewModel::onCanGoBackChanged,
                                onLoadStateChanged = viewModel::onWebLoadStateChanged
                            )
                            webView.loadUrl(webViewController.lastApprovedUrl)
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }

            // Subtle loading progress bar at top edge of WebView
            if (webViewLoadState is WebViewLoadState.Loading) {
                val progress = (webViewLoadState as WebViewLoadState.Loading).progress / 100f
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0.05f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .align(Alignment.TopCenter),
                    color = CobaltBlue,
                    trackColor = SoftBlueSurface
                )
            }

            // Network / SSL Error Overlay
            if (webViewLoadState is WebViewLoadState.Error) {
                val errorState = webViewLoadState as WebViewLoadState.Error
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(PureWhite.copy(alpha = 0.96f))
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        color = PureWhite,
                        shape = RoundedCornerShape(18.dp),
                        border = BorderStroke(1.dp, SubtleBorder),
                        modifier = Modifier.widthIn(max = 400.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                imageVector = if (errorState.isSslError) {
                                    Icons.Outlined.Security
                                } else {
                                    Icons.Outlined.CloudOff
                                },
                                contentDescription = null,
                                tint = CobaltBlue,
                                modifier = Modifier.size(40.dp)
                            )
                            Text(
                                text = if (errorState.isSslError) {
                                    "Secure Connection Blocked"
                                } else {
                                    "Unable to Load 9K Club"
                                },
                                style = MaterialTheme.typography.titleMedium,
                                color = SlateInk
                            )
                            Text(
                                text = errorState.message,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MutedSlate,
                                textAlign = TextAlign.Center
                            )
                            Button(
                                onClick = { webViewController.loadReferralHome() },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = CobaltBlue,
                                    contentColor = PureWhite
                                ),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("retry_website_button")
                            ) {
                                Text("Retry Connection", style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                }
            }

            // Debug diagnostic panel showing Website, History, and GetPanel/API diagnostics separately
            LoginDiagnosticDebugPanel(
                diagnostics = loginDiagnostics,
                historyDiagnostics = historyDiagnostics,
                historyCount = maxOf(historyCount, historyResults.size),
                timerState = timerState,
                userActivationState = userActivationState,
                floatingButtonVisible = isFloatingButtonVisible,
                isPanelOpen = panelUiState.isPanelOpen,
                lastSyncedAtMillis = timerState.lastSyncedAtMillis,
                canPromptActivate = loginDiagnostics.webViewLoaded &&
                    loginDiagnostics.isApprovedDomain &&
                    !canShowPredictor &&
                    activationPromptDismissed,
                onActivateManual = viewModel::activateManualPredictor,
                onDeactivateManual = viewModel::deactivateManualPredictor,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(10.dp)
            )

            // Native user-confirmed activation prompt once approved 9K Club site finishes loading
            AnimatedVisibility(
                visible = showActivationPrompt,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 16.dp, vertical = 16.dp)
            ) {
                ManualActivationPromptCard(
                    onActivate = viewModel::activateManualPredictor,
                    onNotNow = viewModel::dismissActivationPrompt
                )
            }

            // Layer 2: Floating circular "A" predictor button ABOVE WebView
            // Visible when website session is verified OR user manually activated Anshu AI (independent of API auth)
            AnimatedVisibility(
                visible = isFloatingButtonVisible,
                enter = scaleIn() + fadeIn(),
                exit = scaleOut() + fadeOut()
            ) {
                FloatingPredictorButton(
                    containerWidthPx = containerWidthPx,
                    containerHeightPx = containerHeightPx,
                    savedOffset = panelUiState.buttonOffset,
                    onOffsetChanged = viewModel::updateFloatingButtonOffset,
                    onClick = viewModel::openPredictorPanel
                )
            }

            // Layer 3: Expandable, draggable, minimizable predictor panel ABOVE WebView & Button
            AnimatedVisibility(
                visible = canShowPredictor && panelUiState.isPanelOpen,
                enter = fadeIn() + scaleIn(initialScale = 0.94f),
                exit = fadeOut() + scaleOut(targetScale = 0.94f)
            ) {
                FloatingPredictorPanel(
                    containerWidthPx = containerWidthPx,
                    containerHeightPx = containerHeightPx,
                    panelUiState = panelUiState,
                    networkStatus = networkStatus,
                    timerState = timerState,
                    predictionResult = coordinatorState.latestResult,
                    trainingSnapshot = trainingSnapshot,
                    apiAuthState = apiAuthState,
                    lastSyncMessage = lastSyncMessage,
                    lastLockNote = coordinatorState.lastLockNote,
                    isEvaluating = coordinatorState.isEvaluating,
                    historicalAnalysis = historicalAnalysis,
                    moduleSummaries = viewModel.moduleSummaries,
                    moduleStatuses = moduleStatuses,
                    historyResults = historyResults,
                    historyPredictions = latestPredictions,
                    userActivationState = userActivationState,
                    onDragOffset = viewModel::updatePanelOffset,
                    onSelectTab = viewModel::selectPanelTab,
                    onToggleExpand = viewModel::togglePanelExpanded,
                    onMinimize = viewModel::minimizePredictorPanel,
                    onRefreshSync = viewModel::refreshWinGoData,
                    onDeactivateManual = viewModel::deactivateManualPredictor,
                    historyDiagnostics = historyDiagnostics,
                    isHistorySyncing = isHistorySyncing,
                    onOpenAnalytics = {
                        viewModel.minimizePredictorPanel()
                        onNavigateToAnalytics()
                    }
                )
            }
        }
    }
}

@Composable
private fun ManualActivationPromptCard(
    onActivate: () -> Unit,
    onNotNow: () -> Unit
) {
    Surface(
        color = PureWhite,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, SubtleBorder),
        shadowElevation = 8.dp,
        modifier = Modifier
            .widthIn(max = 420.dp)
            .fillMaxWidth()
            .testTag("manual_activation_prompt")
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = stringResource(R.string.activation_prompt_question),
                style = MaterialTheme.typography.titleSmall,
                color = SlateInk
            )
            Text(
                text = "Confirm after logging in on 9K Club to show the floating Anshu AI button. This enables local UI access only and does not verify website or API credentials.",
                style = MaterialTheme.typography.bodySmall,
                color = MutedSlate
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onNotNow,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, SubtleBorder),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("dismiss_activation_prompt_button")
                ) {
                    Text(
                        text = stringResource(R.string.action_not_now),
                        style = MaterialTheme.typography.labelLarge,
                        color = MutedSlate
                    )
                }
                Button(
                    onClick = onActivate,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CobaltBlue,
                        contentColor = PureWhite
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .weight(1.4f)
                        .height(48.dp)
                        .testTag("activate_anshu_ai_button")
                ) {
                    Text(
                        text = stringResource(R.string.action_activate_anshu_ai),
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }
    }
}

@Composable
private fun WebViewTopBar(
    networkStatus: NetworkStatus,
    sessionState: WebsiteSessionState,
    userActivationState: UserActivationState,
    onRefreshClick: () -> Unit,
    onAnalyticsClick: () -> Unit
) {
    Surface(
        color = PureWhite,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.statusBarsPadding()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(CobaltBlue)
                    ) {
                        Text(
                            text = "A",
                            color = PureWhite,
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(
                            text = stringResource(R.string.app_name),
                            style = MaterialTheme.typography.titleMedium,
                            color = SlateInk
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(
                                        when {
                                            networkStatus == NetworkStatus.Offline -> StatusError
                                            sessionState is WebsiteSessionState.Authenticated -> StatusOnline
                                            userActivationState == UserActivationState.ACTIVE -> CobaltBlue
                                            sessionState is WebsiteSessionState.Unauthenticated -> StatusError
                                            else -> MutedSlate
                                        }
                                    )
                            )
                            Spacer(Modifier.width(5.dp))
                            Text(
                                text = when {
                                    networkStatus == NetworkStatus.Offline -> "Offline"
                                    sessionState is WebsiteSessionState.Authenticated -> "Website Session Verified"
                                    userActivationState == UserActivationState.ACTIVE -> "Anshu AI Active (Unverified Website Auth)"
                                    sessionState is WebsiteSessionState.Unauthenticated -> "Website Logged Out"
                                    else -> "Website Session Unknown"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MutedSlate,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onRefreshClick,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("refresh_website_button")
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Refresh,
                            contentDescription = stringResource(R.string.action_refresh),
                            tint = SlateInk
                        )
                    }
                    IconButton(
                        onClick = onAnalyticsClick,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("analytics_nav_button")
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Analytics,
                            contentDescription = stringResource(R.string.action_open_analytics),
                            tint = CobaltBlue
                        )
                    }
                }
            }
            HorizontalDivider(color = SubtleBorder)
        }
    }
}

@Composable
private fun LoginDiagnosticDebugPanel(
    diagnostics: LoginDiagnosticState,
    historyDiagnostics: HistorySyncDiagnostics,
    historyCount: Int,
    timerState: TimerUiState,
    userActivationState: UserActivationState,
    floatingButtonVisible: Boolean,
    isPanelOpen: Boolean,
    lastSyncedAtMillis: Long?,
    canPromptActivate: Boolean,
    onActivateManual: () -> Unit,
    onDeactivateManual: () -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(true) }
    val syncFormatter = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val lastSyncedLabel = remember(lastSyncedAtMillis) {
        lastSyncedAtMillis?.let { syncFormatter.format(Date(it)) } ?: "Never"
    }
    val historySyncLabel = remember(historyDiagnostics.lastSuccessfulSyncAtMillis) {
        historyDiagnostics.lastSuccessfulSyncAtMillis?.let { syncFormatter.format(Date(it)) } ?: "Never"
    }
    val lastVerifiedLabel = remember(diagnostics.lastVerifiedAtMillis) {
        diagnostics.lastVerifiedAtMillis?.let { syncFormatter.format(Date(it)) } ?: "Never"
    }
    val effectiveHistoryCount = maxOf(historyCount, historyDiagnostics.totalStoredCount)
    val effectiveReason = when {
        floatingButtonVisible && diagnostics.loginState == DiagnosticLoginState.AUTHENTICATED ->
            "Visible (verified website session confirmed; independent of API)"
        floatingButtonVisible && userActivationState == UserActivationState.ACTIVE ->
            "Visible via user-confirmed manual activation (website state remains ${diagnostics.websiteDetectionState.label})"
        isPanelOpen ->
            "Floating button hidden while predictor panel is open"
        else -> diagnostics.hidingReason
    }

    Surface(
        color = PureWhite.copy(alpha = 0.95f),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, SubtleBorder),
        shadowElevation = 4.dp,
        modifier = modifier
            .widthIn(max = 328.dp)
            .testTag("login_diagnostic_panel")
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = stringResource(R.string.debug_login_status_title),
                    style = MaterialTheme.typography.labelMedium,
                    color = DeepRoyalBlue,
                    fontWeight = FontWeight.Bold
                )
                IconButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier
                        .size(48.dp)
                        .minimumInteractiveComponentSize()
                        .testTag("toggle_debug_panel_button")
                ) {
                    Icon(
                        imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        contentDescription = stringResource(R.string.action_toggle_debug_panel),
                        tint = SlateInk,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            if (expanded) {
                Column(
                    modifier = Modifier
                        .heightIn(max = 220.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = "Website Diagnostics",
                        style = MaterialTheme.typography.labelSmall,
                        color = DeepRoyalBlue,
                        fontWeight = FontWeight.Bold
                    )
                    DiagnosticRow("WebView loaded", if (diagnostics.webViewLoaded) "YES" else "NO")
                    DiagnosticRow(
                        "Approved domain",
                        if (diagnostics.isApprovedDomain) {
                            diagnostics.currentApprovedHost?.let { "YES ($it)" } ?: "YES"
                        } else {
                            "NO"
                        }
                    )
                    DiagnosticRow(
                        "Current URL category",
                        diagnostics.urlRouteCategory.label,
                        accent = SlateInk
                    )
                    DiagnosticRow(
                        "Website detection method",
                        diagnostics.websiteDetectionMethod,
                        accent = SlateInk
                    )
                    DiagnosticRow(
                        "Observer state",
                        diagnostics.observerState,
                        accent = if (diagnostics.isScannerPollingActive) CobaltBlue else MutedSlate
                    )
                    DiagnosticRow(
                        "Website authentication",
                        diagnostics.websiteLoginState.uiLabel,
                        accent = when (diagnostics.websiteLoginState) {
                            WebsiteLoginState.LOGIN_CONFIRMED -> StatusOnline
                            WebsiteLoginState.WEBVIEW_LOADING,
                            WebsiteLoginState.UNKNOWN -> StatusWarning
                            WebsiteLoginState.LOGGED_OUT,
                            WebsiteLoginState.SESSION_EXPIRED -> StatusError
                        }
                    )
                    DiagnosticRow(
                        "Website authentication state",
                        diagnostics.websiteAuthState.name,
                        accent = when (diagnostics.websiteAuthState) {
                            WebsiteAuthenticationState.LOGIN_CONFIRMED -> StatusOnline
                            WebsiteAuthenticationState.LOGIN_IN_PROGRESS,
                            WebsiteAuthenticationState.WEBVIEW_LOADING -> CobaltBlue
                            WebsiteAuthenticationState.UNKNOWN -> StatusWarning
                            WebsiteAuthenticationState.LOGIN_PAGE,
                            WebsiteAuthenticationState.LOGGED_OUT,
                            WebsiteAuthenticationState.LOGOUT_IN_PROGRESS,
                            WebsiteAuthenticationState.SESSION_EXPIRED -> StatusError
                        }
                    )
                    DiagnosticRow(
                        "Last verification time",
                        "$lastVerifiedLabel (${diagnostics.confidenceLevel.label})",
                        accent = if (diagnostics.lastVerifiedAtMillis != null) DeepRoyalBlue else MutedSlate
                    )
                    DiagnosticRow(
                        "Evidence reason",
                        diagnostics.evidenceReason,
                        accent = MutedSlate
                    )
                    DiagnosticRow(
                        "User activation",
                        userActivationState.label,
                        accent = if (userActivationState == UserActivationState.ACTIVE) CobaltBlue else MutedSlate
                    )
                    DiagnosticRow(
                        "Floating button",
                        if (floatingButtonVisible) "VISIBLE" else "HIDDEN",
                        accent = if (floatingButtonVisible) StatusOnline else MutedSlate
                    )

                    Spacer(Modifier.height(4.dp))
                    HorizontalDivider(color = SubtleBorder)
                    Spacer(Modifier.height(4.dp))

                    Text(
                        text = "History Diagnostics",
                        style = MaterialTheme.typography.labelSmall,
                        color = DeepRoyalBlue,
                        fontWeight = FontWeight.Bold
                    )
                    DiagnosticRow(
                        "History API status",
                        historyDiagnostics.status.label,
                        accent = when (historyDiagnostics.status) {
                            HistoryApiStatus.LIVE -> StatusOnline
                            HistoryApiStatus.SYNCING -> CobaltBlue
                            HistoryApiStatus.STALE,
                            HistoryApiStatus.IDLE -> StatusWarning
                            HistoryApiStatus.HISTORY_API_UNAVAILABLE -> StatusError
                        }
                    )
                    DiagnosticRow(
                        "Actual record count",
                        "$effectiveHistoryCount/500",
                        accent = if (effectiveHistoryCount > 0) StatusOnline else MutedSlate
                    )
                    DiagnosticRow(
                        "Last successful history sync",
                        historySyncLabel,
                        accent = if (historyDiagnostics.lastSuccessfulSyncAtMillis != null) StatusOnline else MutedSlate
                    )
                    DiagnosticRow(
                        "History HTTP status",
                        historyDiagnostics.lastHttpStatus?.toString() ?: "None",
                        accent = when (historyDiagnostics.lastHttpStatus) {
                            200 -> StatusOnline
                            null -> MutedSlate
                            else -> StatusError
                        }
                    )

                    Spacer(Modifier.height(4.dp))
                    HorizontalDivider(color = SubtleBorder)
                    Spacer(Modifier.height(4.dp))

                    Text(
                        text = "API Diagnostics",
                        style = MaterialTheme.typography.labelSmall,
                        color = DeepRoyalBlue,
                        fontWeight = FontWeight.Bold
                    )
                    DiagnosticRow(
                        "Credential availability",
                        if (diagnostics.credentialAvailable) "AVAILABLE (Keystore)" else "MISSING",
                        accent = if (diagnostics.credentialAvailable) StatusOnline else StatusWarning
                    )
                    DiagnosticRow(
                        "Signature provider",
                        if (diagnostics.signatureProviderAvailable) "CONFIGURED" else "NOT CONFIGURED",
                        accent = if (diagnostics.signatureProviderAvailable) StatusOnline else StatusWarning
                    )
                    DiagnosticRow(
                        "API authentication",
                        diagnostics.apiStatusCode.uiLabel,
                        accent = when (diagnostics.apiStatusCode) {
                            ApiAuthStatusCode.API_CONNECTED -> StatusOnline
                            ApiAuthStatusCode.API_CONNECTING -> CobaltBlue
                            ApiAuthStatusCode.API_REJECTED,
                            ApiAuthStatusCode.API_TOKEN_EXPIRED,
                            ApiAuthStatusCode.API_ERROR -> StatusError
                            ApiAuthStatusCode.API_NOT_CONFIGURED,
                            ApiAuthStatusCode.API_AUTH_REQUIRED,
                            ApiAuthStatusCode.API_SIGNATURE_NOT_CONFIGURED -> StatusWarning
                        }
                    )
                    DiagnosticRow(
                        "API authentication state",
                        diagnostics.apiStatusCode.name,
                        accent = SlateInk
                    )
                    DiagnosticRow(
                        "Current issue",
                        timerState.issueNumber ?: "NOT AVAILABLE",
                        accent = if (timerState.issueNumber != null) SlateInk else MutedSlate
                    )
                    DiagnosticRow(
                        "Current phase",
                        timerState.phase ?: "UNKNOWN",
                        accent = if (timerState.phase != null) SlateInk else MutedSlate
                    )
                    DiagnosticRow(
                        "Server countdown",
                        if (timerState.issueNumber != null) timerState.formattedCountdown else "NOT SYNCHRONIZED",
                        accent = if (timerState.issueNumber != null) CobaltBlue else StatusWarning
                    )
                    DiagnosticRow(
                        "Last request status",
                        diagnostics.lastRequestStatus,
                        accent = if (diagnostics.lastHttpStatus == 200) StatusOnline else SlateInk
                    )
                    DiagnosticRow(
                        "HTTP status",
                        diagnostics.lastHttpStatus?.toString() ?: "None",
                        accent = when (diagnostics.lastHttpStatus) {
                            200 -> StatusOnline
                            401, 403 -> StatusError
                            null -> MutedSlate
                            else -> StatusWarning
                        }
                    )
                    DiagnosticRow(
                        "Last successful sync",
                        lastSyncedLabel,
                        accent = if (lastSyncedAtMillis != null) StatusOnline else MutedSlate
                    )

                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = "Website: $effectiveReason",
                        style = MaterialTheme.typography.labelSmall,
                        color = MutedSlate,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "History: ${historyDiagnostics.lastErrorMessage ?: historyDiagnostics.statusDetail}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (historyDiagnostics.status == HistoryApiStatus.HISTORY_API_UNAVAILABLE) StatusError else SlateInk,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "API: ${diagnostics.apiAuthReason}",
                        style = MaterialTheme.typography.labelSmall,
                        color = DeepRoyalBlue,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (userActivationState == UserActivationState.ACTIVE) {
                    TextButton(
                        onClick = onDeactivateManual,
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("debug_deactivate_anshu_ai_button")
                    ) {
                        Text(
                            text = stringResource(R.string.action_deactivate_anshu_ai),
                            style = MaterialTheme.typography.labelMedium,
                            color = StatusError
                        )
                    }
                } else if (canPromptActivate) {
                    TextButton(
                        onClick = onActivateManual,
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("debug_activate_anshu_ai_button")
                    ) {
                        Text(
                            text = stringResource(R.string.action_activate_anshu_ai),
                            style = MaterialTheme.typography.labelMedium,
                            color = CobaltBlue
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagnosticRow(
    label: String,
    value: String,
    accent: androidx.compose.ui.graphics.Color = SlateInk
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MutedSlate
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = value,
            fontFamily = JetBrainsMonoFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 10.sp,
            color = accent,
            textAlign = TextAlign.End,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}
