package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloseFullscreen
import androidx.compose.material.icons.outlined.DragIndicator
import androidx.compose.material.icons.outlined.Minimize
import androidx.compose.material.icons.outlined.OpenInFull
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.analysis.ModelStatus
import com.example.analysis.ModuleDescriptorSummary
import com.example.data.local.entity.PredictionEntity
import com.example.data.local.entity.ResultEntity
import com.example.data.repository.HistoricalAnalysisSummary
import com.example.domain.model.DataFreshnessStatus
import com.example.domain.model.NetworkStatus
import com.example.network.HistorySyncDiagnostics
import com.example.prediction.PredictionResult
import com.example.security.ApiAuthenticationState
import com.example.timer.TimerUiState
import com.example.training.ModelTrainingSnapshot
import com.example.ui.PredictorPanelTab
import com.example.ui.PredictorPanelUiState
import com.example.ui.UserActivationState
import com.example.ui.theme.CobaltBlue
import com.example.ui.theme.DeepRoyalBlue
import com.example.ui.theme.IceSurface
import com.example.ui.theme.MutedSlate
import com.example.ui.theme.PureWhite
import com.example.ui.theme.SlateInk
import com.example.ui.theme.StatusError
import com.example.ui.theme.StatusOnline
import com.example.ui.theme.StatusWarning
import com.example.ui.theme.SubtleBorder
import kotlin.math.roundToInt

/**
 * Draggable, expandable, minimizable, and closable Floating Predictor Panel rendered above
 * the 9K Club WebView. Minimizing or closing hides the panel and restores the floating "A"
 * button while leaving the original website interactive behind it.
 */
@Composable
fun FloatingPredictorPanel(
    containerWidthPx: Float,
    containerHeightPx: Float,
    panelUiState: PredictorPanelUiState,
    networkStatus: NetworkStatus,
    timerState: TimerUiState,
    predictionResult: PredictionResult?,
    trainingSnapshot: ModelTrainingSnapshot,
    apiAuthState: ApiAuthenticationState,
    lastSyncMessage: String?,
    lastLockNote: String?,
    isEvaluating: Boolean,
    historicalAnalysis: HistoricalAnalysisSummary,
    moduleSummaries: List<ModuleDescriptorSummary>,
    moduleStatuses: Map<String, ModelStatus>,
    historyResults: List<ResultEntity>,
    historyPredictions: List<PredictionEntity>,
    onDragOffset: (Offset) -> Unit,
    onSelectTab: (PredictorPanelTab) -> Unit,
    onToggleExpand: () -> Unit,
    onMinimize: () -> Unit,
    onRefreshSync: () -> Unit,
    onOpenAnalytics: () -> Unit,
    userActivationState: UserActivationState = UserActivationState.INACTIVE,
    onDeactivateManual: () -> Unit = {},
    historyDiagnostics: HistorySyncDiagnostics = HistorySyncDiagnostics(),
    isHistorySyncing: Boolean = false,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val marginPx = with(density) { 12.dp.toPx() }
    val panelWidthDp = if (panelUiState.isExpanded) 380.dp else 336.dp
    val panelWidthPx = with(density) { panelWidthDp.toPx() }.coerceAtMost((containerWidthPx - marginPx * 2).coerceAtLeast(200f))
    val availableHeightDp = with(density) { (containerHeightPx - marginPx * 2).coerceAtLeast(0f).toDp() }
    val targetMaxHeightDp = if (panelUiState.isExpanded) 560.dp else 440.dp
    val maxPanelHeightDp = if (availableHeightDp > 220.dp) {
        minOf(targetMaxHeightDp, availableHeightDp)
    } else {
        targetMaxHeightDp
    }
    val approxHeightPx = with(density) { maxPanelHeightDp.toPx() }.coerceAtMost(containerHeightPx - marginPx * 2)

    val maxX = (containerWidthPx - panelWidthPx - marginPx).coerceAtLeast(marginPx)
    val maxY = (containerHeightPx - approxHeightPx - marginPx).coerceAtLeast(marginPx)

    val defaultX = ((containerWidthPx - panelWidthPx) / 2f).coerceIn(marginPx, maxX)
    val defaultY = with(density) { 48.dp.toPx() }.coerceIn(marginPx, maxY)

    val rawOffset = if (panelUiState.panelOffset == Offset.Zero) {
        Offset(defaultX, defaultY)
    } else {
        panelUiState.panelOffset
    }
    val clampedOffset = Offset(
        x = rawOffset.x.coerceIn(marginPx, maxX),
        y = rawOffset.y.coerceIn(marginPx, maxY)
    )

    val latestOffset by rememberUpdatedState(clampedOffset)
    val latestOnDragOffset by rememberUpdatedState(onDragOffset)

    val headerConnectionText = when {
        isEvaluating -> "WinGo 1M | DATA SYNCHRONIZING"
        networkStatus == NetworkStatus.Offline -> "WinGo 1M | API ERROR (Offline)"
        timerState.freshness == DataFreshnessStatus.LIVE && apiAuthState is ApiAuthenticationState.Ready ->
            "WinGo 1M | API CONNECTED"
        apiAuthState is ApiAuthenticationState.Authorizing -> "WinGo 1M | AUTHORIZING"
        apiAuthState is ApiAuthenticationState.TokenExpired -> "WinGo 1M | TOKEN EXPIRED"
        apiAuthState is ApiAuthenticationState.Rejected -> "WinGo 1M | AUTHORIZATION REJECTED"
        apiAuthState is ApiAuthenticationState.SignatureNotConfigured -> "WinGo 1M | SIGNATURE NOT CONFIGURED"
        apiAuthState is ApiAuthenticationState.AuthRequired -> "WinGo 1M | LOGIN REQUIRED"
        apiAuthState is ApiAuthenticationState.ApiError -> "WinGo 1M | API ERROR"
        else -> "WinGo 1M | ${timerState.freshness.label}"
    }
    val headerDotColor = when {
        isEvaluating -> CobaltBlue
        networkStatus == NetworkStatus.Offline -> StatusError
        apiAuthState is ApiAuthenticationState.Rejected ||
            apiAuthState is ApiAuthenticationState.TokenExpired ||
            apiAuthState is ApiAuthenticationState.ApiError -> StatusError
        timerState.freshness == DataFreshnessStatus.LIVE -> StatusOnline
        else -> StatusWarning
    }

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = PureWhite,
        border = BorderStroke(1.dp, SubtleBorder),
        modifier = modifier
            .offset { IntOffset(clampedOffset.x.roundToInt(), clampedOffset.y.roundToInt()) }
            .widthIn(max = panelWidthDp)
            .fillMaxWidth(if (panelUiState.isExpanded) 0.94f else 0.88f)
            .heightIn(max = maxPanelHeightDp)
            .shadow(elevation = 16.dp, shape = RoundedCornerShape(20.dp))
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Draggable Header Bar: Anshu AI + WinGo 1M connection status + Expand + Minimize + Close
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(IceSurface)
                    .pointerInput(containerWidthPx, containerHeightPx, panelUiState.isExpanded) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            val next = Offset(
                                x = (latestOffset.x + dragAmount.x).coerceIn(marginPx, maxX),
                                y = (latestOffset.y + dragAmount.y).coerceIn(marginPx, maxY)
                            )
                            latestOnDragOffset(next)
                        }
                    }
                    .padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.DragIndicator,
                        contentDescription = "Drag predictor panel",
                        tint = MutedSlate,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(CobaltBlue)
                    ) {
                        Text(
                            text = "A",
                            color = PureWhite,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.panel_title_anshu_ai),
                            style = MaterialTheme.typography.titleSmall,
                            color = SlateInk,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(headerDotColor)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = headerConnectionText,
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
                        onClick = onToggleExpand,
                        modifier = Modifier
                            .size(48.dp)
                            .minimumInteractiveComponentSize()
                            .testTag("predictor_panel_expand_button")
                    ) {
                        Icon(
                            imageVector = if (panelUiState.isExpanded) {
                                Icons.Outlined.CloseFullscreen
                            } else {
                                Icons.Outlined.OpenInFull
                            },
                            contentDescription = stringResource(R.string.action_expand_panel),
                            tint = DeepRoyalBlue,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    IconButton(
                        onClick = onMinimize,
                        modifier = Modifier
                            .size(48.dp)
                            .minimumInteractiveComponentSize()
                            .testTag("predictor_panel_minimize_button")
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Minimize,
                            contentDescription = stringResource(R.string.action_minimize_panel),
                            tint = SlateInk,
                            modifier = Modifier.size(19.dp)
                        )
                    }
                    IconButton(
                        onClick = onMinimize,
                        modifier = Modifier
                            .size(48.dp)
                            .minimumInteractiveComponentSize()
                            .testTag("predictor_panel_close_button")
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = stringResource(R.string.action_close_expanded_panel),
                            tint = SlateInk,
                            modifier = Modifier.size(19.dp)
                        )
                    }
                }
            }

            HorizontalDivider(color = SubtleBorder)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                PanelTabChip(
                    title = stringResource(R.string.tab_prediction),
                    selected = panelUiState.activeTab == PredictorPanelTab.PREDICTION,
                    onClick = { onSelectTab(PredictorPanelTab.PREDICTION) },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("tab_prediction")
                )
                PanelTabChip(
                    title = stringResource(R.string.tab_analysis),
                    selected = panelUiState.activeTab == PredictorPanelTab.ANALYSIS,
                    onClick = { onSelectTab(PredictorPanelTab.ANALYSIS) },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("tab_analysis")
                )
                PanelTabChip(
                    title = stringResource(R.string.tab_history),
                    selected = panelUiState.activeTab == PredictorPanelTab.HISTORY,
                    onClick = { onSelectTab(PredictorPanelTab.HISTORY) },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("tab_history")
                )
            }

            HorizontalDivider(color = SubtleBorder)

            when (panelUiState.activeTab) {
                PredictorPanelTab.PREDICTION -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState())
                    ) {
                        PredictionTabContent(
                            timerState = timerState,
                            networkStatus = networkStatus,
                            predictionResult = predictionResult,
                            trainingSnapshot = trainingSnapshot,
                            apiAuthState = apiAuthState,
                            lastSyncMessage = lastSyncMessage,
                            lastLockNote = lastLockNote,
                            isEvaluating = isEvaluating,
                            historyCount = maxOf(historyResults.size, trainingSnapshot.historySampleSize),
                            userActivationState = userActivationState,
                            onRefreshSync = onRefreshSync,
                            onOpenAnalytics = onOpenAnalytics,
                            onDeactivateManual = onDeactivateManual
                        )
                    }
                }

                PredictorPanelTab.ANALYSIS -> {
                    Box(modifier = Modifier.height(if (panelUiState.isExpanded) 380.dp else 280.dp)) {
                        AnalysisTabContent(
                            historicalAnalysis = historicalAnalysis,
                            snapshotNo = timerState.snapshotNo,
                            snapshotItems = timerState.snapshotItems,
                            trainingSnapshot = trainingSnapshot,
                            moduleSummaries = moduleSummaries,
                            moduleStatuses = moduleStatuses,
                            predictionResult = predictionResult
                        )
                    }
                }

                PredictorPanelTab.HISTORY -> {
                    Box(modifier = Modifier.height(if (panelUiState.isExpanded) 380.dp else 280.dp)) {
                        HistoryTabContent(
                            results = historyResults,
                            predictions = historyPredictions,
                            syncStatusMessage = lastSyncMessage,
                            networkStatus = networkStatus,
                            isLoading = isEvaluating || isHistorySyncing,
                            historyDiagnostics = historyDiagnostics
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PanelTabChip(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (selected) CobaltBlue else IceSurface,
        border = if (selected) null else BorderStroke(1.dp, SubtleBorder),
        modifier = modifier
            .height(48.dp)
            .minimumInteractiveComponentSize()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) PureWhite else MutedSlate,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
            )
        }
    }
}
