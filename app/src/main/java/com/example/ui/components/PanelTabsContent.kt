package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.analysis.AnalysisOutput
import com.example.analysis.ModuleDescriptorSummary
import com.example.analysis.ModelStatus
import com.example.data.local.entity.PredictionEntity
import com.example.data.local.entity.ResultEntity
import com.example.data.repository.HistoricalAnalysisSummary
import com.example.domain.model.DataFreshnessStatus
import com.example.domain.model.NetworkStatus
import com.example.domain.model.PredictionStatus
import com.example.domain.model.VerificationStatus
import com.example.domain.model.WinGoColor
import com.example.domain.model.WinGoGameConfig
import com.example.network.HistoryApiStatus
import com.example.network.HistorySyncDiagnostics
import com.example.network.ValidatedSnapshotItem
import com.example.prediction.PredictionResult
import com.example.security.ApiAuthenticationState
import com.example.timer.TimerUiState
import com.example.training.ModelTrainingSnapshot
import com.example.ui.UserActivationState
import com.example.ui.theme.CobaltBlue
import com.example.ui.theme.DeepRoyalBlue
import com.example.ui.theme.IceSurface
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.MutedSlate
import com.example.ui.theme.PureWhite
import com.example.ui.theme.SlateInk
import com.example.ui.theme.SoftBlueSurface
import com.example.ui.theme.StatusError
import com.example.ui.theme.StatusOnline
import com.example.ui.theme.StatusWarning
import com.example.ui.theme.SubtleBorder
import com.example.ui.theme.WinGoGreen
import com.example.ui.theme.WinGoGreenContainer
import com.example.ui.theme.WinGoRed
import com.example.ui.theme.WinGoRedContainer
import com.example.ui.theme.WinGoViolet
import com.example.ui.theme.WinGoVioletContainer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class HistorySubTab(val label: String) {
    ACTUAL_RESULTS("Actual Results"),
    PREDICTIONS("Predictions")
}

@Composable
fun PredictionTabContent(
    timerState: TimerUiState,
    networkStatus: NetworkStatus,
    predictionResult: PredictionResult?,
    trainingSnapshot: ModelTrainingSnapshot,
    apiAuthState: ApiAuthenticationState,
    lastSyncMessage: String?,
    lastLockNote: String?,
    isEvaluating: Boolean,
    historyCount: Int = trainingSnapshot.historySampleSize,
    userActivationState: UserActivationState = UserActivationState.INACTIVE,
    onRefreshSync: () -> Unit,
    onOpenAnalytics: () -> Unit,
    onDeactivateManual: () -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        val apiConnectionStatus = when {
            isEvaluating -> "DATA SYNCHRONIZING"
            networkStatus == NetworkStatus.Offline -> "API ERROR"
            timerState.freshness == DataFreshnessStatus.LIVE && apiAuthState is ApiAuthenticationState.Ready -> "API CONNECTED"
            apiAuthState is ApiAuthenticationState.Authorizing -> "AUTHORIZING"
            apiAuthState is ApiAuthenticationState.TokenExpired -> "TOKEN EXPIRED"
            apiAuthState is ApiAuthenticationState.Rejected -> "AUTHORIZATION REJECTED"
            apiAuthState is ApiAuthenticationState.SignatureNotConfigured -> "SIGNATURE NOT CONFIGURED"
            apiAuthState is ApiAuthenticationState.AuthRequired -> "LOGIN REQUIRED"
            apiAuthState is ApiAuthenticationState.ApiError -> "API ERROR"
            else -> timerState.freshness.label
        }

        val syncTimeFormatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val lastSyncedText = timerState.lastSyncedAtMillis?.let { syncTimeFormatter.format(Date(it)) } ?: "Never"
        val freshnessPositive = timerState.freshness == DataFreshnessStatus.LIVE && networkStatus == NetworkStatus.Online

        if (apiAuthState !is ApiAuthenticationState.Ready) {
            ApiAuthenticationErrorBanner(apiAuthState = apiAuthState)
        }

        // WinGo 1M Authoritative Timer, Issue, Phase, Connection Status & API Freshness Card
        Surface(
            color = IceSurface,
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, SubtleBorder),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("panel_getpanel_header")
        ) {
            Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "WinGo 1M Issue",
                            style = MaterialTheme.typography.labelMedium,
                            color = MutedSlate
                        )
                        Text(
                            text = timerState.issueNumber ?: "NOT AVAILABLE",
                            fontFamily = JetBrainsMonoFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = SlateInk,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("panel_issue_number")
                        )
                        if (!timerState.issueConsistent && !timerState.currentIssueNumber.isNullOrBlank()) {
                            Text(
                                text = "Issue mismatch detected",
                                style = MaterialTheme.typography.labelSmall,
                                color = StatusError
                            )
                        }
                    }

                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "Server Countdown",
                            style = MaterialTheme.typography.labelMedium,
                            color = MutedSlate
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Outlined.Schedule,
                                contentDescription = "Server synchronized countdown",
                                tint = if (freshnessPositive) CobaltBlue else MutedSlate,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = timerState.formattedCountdown,
                                fontFamily = JetBrainsMonoFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp,
                                color = if (freshnessPositive) CobaltBlue else StatusWarning,
                                modifier = Modifier.testTag("panel_countdown")
                            )
                        }
                        if (timerState.issueNumber == null) {
                            Text(
                                text = "Timer: NOT SYNCHRONIZED",
                                fontFamily = JetBrainsMonoFamily,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 9.sp,
                                maxLines = 1,
                                softWrap = false,
                                color = StatusWarning
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StatusBadge(
                        label = apiConnectionStatus,
                        isPositive = freshnessPositive,
                        modifier = Modifier.testTag("panel_connection_status")
                    )
                    Text(
                        text = "Phase: ${timerState.phase ?: "UNKNOWN"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = SlateInk,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.testTag("panel_phase")
                    )
                }

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = "Data freshness: ${timerState.freshness.label}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (freshnessPositive) StatusOnline else StatusWarning,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.testTag("panel_api_freshness")
                    )
                    Text(
                        text = "Last successful sync: $lastSyncedText | History: $historyCount/500",
                        fontFamily = JetBrainsMonoFamily,
                        fontSize = 10.sp,
                        color = MutedSlate,
                        modifier = Modifier.testTag("panel_last_sync")
                    )
                }

                if (apiAuthState is ApiAuthenticationState.Ready) {
                    Text(
                        text = timerState.statusDetail,
                        style = MaterialTheme.typography.labelSmall,
                        color = DeepRoyalBlue,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        if (!WinGoGameConfig.PHASE_1_AI_EXECUTION_ENABLED) {
            EngineUnavailableCard(
                headline = "Phase 1: AI Prediction Execution Paused",
                details = "Phase 1 focuses on live WinGo_1M history collection ($historyCount/500 stored), website login detection, and GetPanel issue/timer sync. All 12 AI modules remain preserved in source and will execute in Phase 2.",
                sampleSize = historyCount,
                preserveCustomHeadlineWhenEmpty = true
            )
        } else {
            when (predictionResult) {
                is PredictionResult.Ready -> {
                    ReadyPredictionCard(
                        result = predictionResult,
                        lastLockNote = lastLockNote
                    )
                }
                is PredictionResult.Unavailable -> EngineUnavailableCard(
                    headline = predictionResult.headline,
                    details = predictionResult.details,
                    sampleSize = predictionResult.sampleSize
                )
                null -> EngineUnavailableCard(
                    headline = stringResource(R.string.ai_engine_not_installed),
                    details = timerState.statusDetail,
                    sampleSize = trainingSnapshot.historySampleSize
                )
            }
        }

        GetPanelSnapshotCard(
            snapshotNo = timerState.snapshotNo,
            snapshotItems = timerState.snapshotItems
        )

        if (!lastSyncMessage.isNullOrBlank()) {
            Text(
                text = lastSyncMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MutedSlate
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onRefreshSync,
                enabled = !isEvaluating,
                colors = ButtonDefaults.buttonColors(
                    containerColor = CobaltBlue,
                    contentColor = PureWhite
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .testTag("run_sync_button")
            ) {
                Icon(
                    imageVector = Icons.Outlined.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (isEvaluating) "Syncing..." else "Sync WinGo 1M",
                    style = MaterialTheme.typography.labelLarge
                )
            }

            OutlinedButton(
                onClick = onOpenAnalytics,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, CobaltBlue),
                modifier = Modifier
                    .height(48.dp)
                    .testTag("open_analytics_from_panel_button")
            ) {
                Icon(
                    imageVector = Icons.Outlined.Analytics,
                    contentDescription = stringResource(R.string.action_open_analytics),
                    tint = CobaltBlue,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        if (userActivationState == UserActivationState.ACTIVE) {
            OutlinedButton(
                onClick = onDeactivateManual,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, StatusError),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("panel_deactivate_anshu_ai_button")
            ) {
                Text(
                    text = stringResource(R.string.action_deactivate_anshu_ai),
                    style = MaterialTheme.typography.labelLarge,
                    color = StatusError
                )
            }
        }

        Text(
            text = stringResource(R.string.educational_disclaimer),
            style = MaterialTheme.typography.bodySmall,
            color = MutedSlate
        )
    }
}

@Composable
private fun ReadyPredictionCard(
    result: PredictionResult.Ready,
    lastLockNote: String?
) {
    val formatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    val analysisTime = formatter.format(Date(result.createdAtMillis))

    Surface(
        color = PureWhite,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, SubtleBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Ensemble Estimate",
                    style = MaterialTheme.typography.titleSmall,
                    color = SlateInk
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Outlined.Lock,
                        contentDescription = "Lock status",
                        tint = CobaltBlue,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "${result.predictionStatus.name} ($analysisTime)",
                        style = MaterialTheme.typography.labelSmall,
                        color = CobaltBlue
                    )
                }
            }

            if (result.driftReport?.driftDetected == true) {
                Surface(
                    color = Color(0xFFFEF3C7),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = result.driftReport.reason ?: "Distribution or accuracy drift detected; confidence penalized.",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF92400E),
                        modifier = Modifier.padding(8.dp)
                    )
                }
            }

            // Top Estimated Values (explicitly distinct from calibrated distributions below)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MetricTile(
                    title = "Top Number",
                    value = result.predictedNumber.toString(),
                    accent = CobaltBlue,
                    modifier = Modifier.weight(1f)
                )
                MetricTile(
                    title = "Top Colour",
                    value = result.predictedColor?.label ?: "Unverified",
                    accent = result.predictedColor?.let { colorForWinGoColor(it) } ?: MutedSlate,
                    modifier = Modifier.weight(1f)
                )
                MetricTile(
                    title = "Top Size",
                    value = result.predictedSize?.label ?: "Unverified",
                    accent = if (result.predictedSize != null) DeepRoyalBlue else MutedSlate,
                    modifier = Modifier.weight(1f)
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Calibrated Confidence (${result.validContributingCount} models)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MutedSlate
                    )
                    Text(
                        text = "${(result.confidence * 100).toInt()}%",
                        fontFamily = JetBrainsMonoFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = CobaltBlue
                    )
                }
                LinearProgressIndicator(
                    progress = { result.confidence.toFloat().coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(7.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    color = CobaltBlue,
                    trackColor = SoftBlueSurface
                )
                Text(
                    text = "Disagreement TV: ${String.format(Locale.US, "%.3f", result.disagreementScore)} | Cutoff: ${result.dataCutoffIssue ?: "--"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MutedSlate
                )
                if (!lastLockNote.isNullOrBlank()) {
                    Text(
                        text = lastLockNote,
                        style = MaterialTheme.typography.labelSmall,
                        color = DeepRoyalBlue
                    )
                }
            }

            HorizontalDivider(color = SubtleBorder)

            Text(
                text = "Colour & Size Probability Distributions",
                style = MaterialTheme.typography.labelMedium,
                color = SlateInk
            )
            if (result.colorProbabilities != null) {
                result.colorProbabilities.forEach { (label, prob) ->
                    ProbabilityRow(label = label, probability = prob)
                }
            } else {
                Text(
                    text = result.colorUnavailableReason ?: stringResource(R.string.msg_classification_not_verified),
                    style = MaterialTheme.typography.bodySmall,
                    color = MutedSlate
                )
            }

            if (result.sizeProbabilities != null) {
                result.sizeProbabilities.forEach { (label, prob) ->
                    ProbabilityRow(label = label, probability = prob)
                }
            } else {
                Text(
                    text = result.sizeUnavailableReason ?: stringResource(R.string.msg_classification_not_verified),
                    style = MaterialTheme.typography.bodySmall,
                    color = MutedSlate
                )
            }

            Text(
                text = "Number Probability Distribution (0-9)",
                style = MaterialTheme.typography.labelMedium,
                color = SlateInk
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                (0..9).forEach { num ->
                    val pct = ((result.numberProbabilities[num] ?: 0.0) * 100).toInt()
                    Surface(
                        color = if (num == result.predictedNumber) SoftBlueSurface else IceSurface,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(vertical = 4.dp)
                        ) {
                            Text(
                                text = num.toString(),
                                fontFamily = JetBrainsMonoFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                color = if (num == result.predictedNumber) CobaltBlue else SlateInk
                            )
                            Text(
                                text = "$pct%",
                                fontFamily = JetBrainsMonoFamily,
                                fontSize = 9.sp,
                                color = MutedSlate
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EngineUnavailableCard(
    headline: String,
    details: String,
    sampleSize: Int,
    preserveCustomHeadlineWhenEmpty: Boolean = false
) {
    Surface(
        color = PureWhite,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, SubtleBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.WarningAmber,
                    contentDescription = null,
                    tint = StatusWarning,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (sampleSize == 0 && !preserveCustomHeadlineWhenEmpty) {
                        stringResource(R.string.ai_engine_not_installed)
                    } else {
                        headline
                    },
                    style = MaterialTheme.typography.titleSmall,
                    color = SlateInk
                )
            }

            Text(
                text = details,
                style = MaterialTheme.typography.bodySmall,
                color = MutedSlate
            )

            // Number 0-9 probability area (honest unavailable state, no fake values)
            Text(
                text = "Number 0-9 Probability Area",
                style = MaterialTheme.typography.labelMedium,
                color = SlateInk
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                (0..9).forEach { num ->
                    Surface(
                        color = IceSurface,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(vertical = 4.dp)
                        ) {
                            Text(
                                text = num.toString(),
                                fontFamily = JetBrainsMonoFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                color = SlateInk
                            )
                            Text(
                                text = "--",
                                fontFamily = JetBrainsMonoFamily,
                                fontSize = 9.sp,
                                color = MutedSlate
                            )
                        }
                    }
                }
            }

            // Colour probability area & Big/Small probability area (honest unavailable state)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MetricTile(
                    title = "Colour Probability",
                    value = "--",
                    accent = MutedSlate,
                    modifier = Modifier.weight(1f)
                )
                MetricTile(
                    title = "Big / Small",
                    value = "--",
                    accent = MutedSlate,
                    modifier = Modifier.weight(1f)
                )
            }

            Text(
                text = "Cached historical draws: $sampleSize/500",
                fontFamily = JetBrainsMonoFamily,
                fontSize = 11.sp,
                color = DeepRoyalBlue
            )
        }
    }
}

@Composable
private fun ApiAuthenticationErrorBanner(apiAuthState: ApiAuthenticationState) {
    val isErrorSeverity = apiAuthState is ApiAuthenticationState.Rejected ||
        apiAuthState is ApiAuthenticationState.TokenExpired ||
        apiAuthState is ApiAuthenticationState.ApiError
    val title = when (apiAuthState) {
        is ApiAuthenticationState.Rejected ->
            "AUTHORIZATION REJECTED (${apiAuthState.httpCode ?: "Auth"}) — Website session remains active"
        is ApiAuthenticationState.TokenExpired ->
            "TOKEN EXPIRED — Website session remains active"
        is ApiAuthenticationState.SignatureNotConfigured ->
            "SIGNATURE NOT CONFIGURED — Signing contract required"
        is ApiAuthenticationState.AuthRequired ->
            "LOGIN REQUIRED — ${stringResource(R.string.api_authentication_required)}"
        is ApiAuthenticationState.Authorizing ->
            "AUTHORIZING — Validating authorized session"
        is ApiAuthenticationState.ApiError ->
            "API ERROR — Upstream request failed"
        ApiAuthenticationState.Ready -> return
    }
    val detailText = when (apiAuthState) {
        is ApiAuthenticationState.Rejected -> apiAuthState.reason
        is ApiAuthenticationState.TokenExpired -> apiAuthState.reason
        is ApiAuthenticationState.SignatureNotConfigured -> apiAuthState.details
        is ApiAuthenticationState.AuthRequired -> apiAuthState.details
        is ApiAuthenticationState.Authorizing -> apiAuthState.detail
        is ApiAuthenticationState.ApiError -> apiAuthState.message
        ApiAuthenticationState.Ready -> ""
    }

    Surface(
        color = if (isErrorSeverity) WinGoRedContainer else IceSurface,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, if (isErrorSeverity) StatusError else SubtleBorder),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("panel_api_auth_error")
    ) {
        Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (isErrorSeverity) Icons.Outlined.ErrorOutline else Icons.Outlined.Info,
                    contentDescription = null,
                    tint = if (isErrorSeverity) StatusError else CobaltBlue,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isErrorSeverity) StatusError else DeepRoyalBlue
                )
            }
            Text(
                text = detailText,
                style = MaterialTheme.typography.bodySmall,
                color = if (isErrorSeverity) SlateInk else DeepRoyalBlue
            )
        }
    }
}

@Composable
private fun GetPanelSnapshotCard(
    snapshotNo: Int?,
    snapshotItems: List<ValidatedSnapshotItem>
) {
    Surface(
        color = PureWhite,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, SubtleBorder),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("panel_getpanel_snapshot")
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "GetPanel Snapshot${snapshotNo?.let { " (#$it)" } ?: ""}",
                    style = MaterialTheme.typography.titleSmall,
                    color = SlateInk
                )
                Text(
                    text = "Stats Only (Not AI)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MutedSlate
                )
            }
            if (snapshotItems.isEmpty()) {
                Text(
                    text = "Awaiting GetPanel snapshot (0–9 ranks & percentages).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MutedSlate
                )
            } else {
                val sorted = snapshotItems.sortedBy { it.rank }
                sorted.chunked(2).forEach { pair ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        pair.forEach { item ->
                            Surface(
                                color = IceSurface,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 8.dp, vertical = 5.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "#${item.rank} Num ${item.number}",
                                        fontFamily = JetBrainsMonoFamily,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = SlateInk
                                    )
                                    Text(
                                        text = formatSnapshotPercent(item.percent),
                                        fontFamily = JetBrainsMonoFamily,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = CobaltBlue
                                    )
                                }
                            }
                        }
                        if (pair.size == 1) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

private fun formatSnapshotPercent(percent: Double): String =
    if (percent % 1.0 == 0.0) {
        "${percent.toInt()}%"
    } else {
        String.format(Locale.US, "%.1f%%", percent)
    }

@Composable
fun AnalysisTabContent(
    historicalAnalysis: HistoricalAnalysisSummary,
    snapshotNo: Int?,
    snapshotItems: List<ValidatedSnapshotItem>,
    trainingSnapshot: ModelTrainingSnapshot,
    moduleSummaries: List<ModuleDescriptorSummary>,
    moduleStatuses: Map<String, ModelStatus>,
    predictionResult: PredictionResult?
) {
    val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    val outputs = trainingSnapshot.moduleOutputs
    val readyWeights: Map<String, Double> = (predictionResult as? PredictionResult.Ready)
        ?.contributions
        ?.associate { it.moduleId to it.assignedWeight }
        ?: emptyMap()

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 1. Sample Size, Backtesting, Baseline & Drift Overview
        item {
            Surface(
                color = IceSurface,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, SubtleBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "Engine Validation & Baselines",
                        style = MaterialTheme.typography.titleSmall,
                        color = SlateInk
                    )
                    Text(
                        text = "History: ${trainingSnapshot.historySampleSize} draws | Backtest: ${trainingSnapshot.backtestingSampleSize} steps | Last run: ${trainingSnapshot.lastTrainedAtMillis?.let { timeFormatter.format(Date(it)) } ?: "Not run"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = DeepRoyalBlue
                    )
                    val baseline = trainingSnapshot.baselineBenchmark
                    if (baseline != null) {
                        Text(
                            text = "Baselines -> Uniform Brier: ${String.format(Locale.US, "%.4f", baseline.uniformBrierScore)} | Smoothed Freq Brier: ${String.format(Locale.US, "%.4f", baseline.smoothedFreqBrierScore)} (LogLoss ${String.format(Locale.US, "%.3f", baseline.bestBaselineLogLoss)})",
                            fontFamily = JetBrainsMonoFamily,
                            fontSize = 11.sp,
                            color = SlateInk
                        )
                    } else {
                        Text(
                            text = "Baselines: insufficient chronological data (< 21 draws).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MutedSlate
                        )
                    }
                    trainingSnapshot.calibrationReport?.let { cal ->
                        Text(
                            text = "Calibration (n=${cal.sampleCount}): Temp=${cal.optimalTemperature}, ECE=${String.format(Locale.US, "%.4f", cal.expectedCalibrationError)}, Brier=${String.format(Locale.US, "%.4f", cal.brierScore)}",
                            fontFamily = JetBrainsMonoFamily,
                            fontSize = 11.sp,
                            color = CobaltBlue
                        )
                    }
                    trainingSnapshot.driftReport?.let { drift ->
                        Text(
                            text = if (drift.driftDetected) {
                                "Drift Alert: ${drift.reason}"
                            } else {
                                "Drift Monitor: Stable (Shift TV=${String.format(Locale.US, "%.3f", drift.distributionShiftTv)})"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (drift.driftDetected) StatusWarning else StatusOnline
                        )
                    }
                }
            }
        }

        // 2. Historical Result Analysis (Frequency, Patterns, Gap Stats)
        item {
            Surface(
                color = PureWhite,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, SubtleBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Historical Frequency & Gaps (${historicalAnalysis.sampleCount} draws)",
                        style = MaterialTheme.typography.titleSmall,
                        color = SlateInk
                    )
                    if (historicalAnalysis.sampleCount == 0) {
                        Text(
                            text = stringResource(R.string.msg_no_historical_data),
                            style = MaterialTheme.typography.bodySmall,
                            color = MutedSlate
                        )
                    } else {
                        historicalAnalysis.recentPattern?.let { pattern ->
                            Text(
                                text = "Recent Sequence: ${pattern.streakLabel} (${pattern.streakLength}) | Last 10: ${pattern.recentNumbers.joinToString(", ")}",
                                style = MaterialTheme.typography.bodySmall,
                                color = DeepRoyalBlue
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            historicalAnalysis.numberStats.forEach { stat ->
                                Surface(
                                    color = IceSurface,
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier.padding(vertical = 5.dp)
                                    ) {
                                        Text(
                                            text = stat.number.toString(),
                                            fontFamily = JetBrainsMonoFamily,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 11.sp,
                                            color = CobaltBlue
                                        )
                                        Text(
                                            text = "f:${stat.count}",
                                            fontFamily = JetBrainsMonoFamily,
                                            fontSize = 9.sp,
                                            color = SlateInk
                                        )
                                        Text(
                                            text = "g:${stat.currentGapDraws ?: "-"}",
                                            fontFamily = JetBrainsMonoFamily,
                                            fontSize = 9.sp,
                                            color = MutedSlate
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 3. GetPanel API Snapshot Information (explicitly NOT winning probabilities)
        item {
            Surface(
                color = IceSurface,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, SubtleBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "GetPanel API Snapshot${snapshotNo?.let { " (#$it)" } ?: ""}",
                        style = MaterialTheme.typography.titleSmall,
                        color = SlateInk
                    )
                    if (snapshotItems.isEmpty()) {
                        Text(
                            text = "No live GetPanel snapshot received yet.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MutedSlate
                        )
                    } else {
                        Text(
                            text = "API panel statistics only (not winning probabilities):",
                            style = MaterialTheme.typography.bodySmall,
                            color = MutedSlate
                        )
                        snapshotItems.sortedBy { it.rank }.forEach { item ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "#${item.rank} Number ${item.number} (prev #${item.prevRank ?: "-"})",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = SlateInk
                                )
                                Text(
                                    text = "${formatSnapshotPercent(item.percent)} | Medal ${item.medal}",
                                    fontFamily = JetBrainsMonoFamily,
                                    fontSize = 11.sp,
                                    color = CobaltBlue
                                )
                            }
                        }
                    }
                }
            }
        }

        // 4. All 12 AI Modules
        item {
            Text(
                text = "Analysis Modules (${moduleSummaries.size})",
                style = MaterialTheme.typography.titleSmall,
                color = SlateInk
            )
        }

        if (outputs.isNotEmpty()) {
            items(outputs, key = { it.moduleId }) { out ->
                ModuleOutputCard(
                    output = out,
                    assignedWeight = readyWeights[out.moduleId]
                )
            }
        } else {
            items(moduleSummaries, key = { it.moduleId }) { summary ->
                val status = moduleStatuses[summary.moduleId] ?: summary.currentStatus
                ModuleStandbyCard(summary = summary, status = status)
            }
        }
    }
}

@Composable
private fun ModuleOutputCard(
    output: AnalysisOutput,
    assignedWeight: Double?
) {
    val metrics = output.validationMetrics
    Surface(
        color = PureWhite,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, SubtleBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = output.moduleName,
                        style = MaterialTheme.typography.titleSmall,
                        color = SlateInk
                    )
                    Text(
                        text = "${output.category.label} | v${output.moduleVersion} | n=${output.sampleSize}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MutedSlate
                    )
                }
                ModelStatusPill(status = output.status)
            }

            if (metrics != null) {
                Text(
                    text = "Val n=${metrics.evaluatedSamples} | Acc=${String.format(Locale.US, "%.1f%%", metrics.numberAccuracy * 100)} | Brier=${String.format(Locale.US, "%.4f", metrics.brierScore)} vs ${String.format(Locale.US, "%.4f", metrics.baselineBrierScore)} | Lift=${if (metrics.outperformsBaseline) "Yes" else "No"}",
                    fontFamily = JetBrainsMonoFamily,
                    fontSize = 10.sp,
                    color = if (metrics.outperformsBaseline) StatusOnline else DeepRoyalBlue
                )
            }

            if (assignedWeight != null && assignedWeight > 0.0) {
                Text(
                    text = "Ensemble Weight: ${String.format(Locale.US, "%.1f%%", assignedWeight * 100.0)}",
                    fontFamily = JetBrainsMonoFamily,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = CobaltBlue
                )
            }

            Text(
                text = output.explanation,
                style = MaterialTheme.typography.bodySmall,
                color = SlateInk
            )
        }
    }
}

@Composable
private fun ModuleStandbyCard(
    summary: ModuleDescriptorSummary,
    status: ModelStatus
) {
    Surface(
        color = PureWhite,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, SubtleBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = summary.moduleName,
                    style = MaterialTheme.typography.titleSmall,
                    color = SlateInk
                )
                Text(
                    text = "${summary.category.label} | Min ${summary.minimumRequiredSamples} draws",
                    style = MaterialTheme.typography.bodySmall,
                    color = MutedSlate
                )
            }
            ModelStatusPill(status = status)
        }
    }
}

@Composable
private fun ModelStatusPill(status: ModelStatus) {
    val (bg, fg, icon) = when (status) {
        ModelStatus.READY -> Triple(WinGoGreenContainer, WinGoGreen, Icons.Outlined.CheckCircle)
        ModelStatus.TRAINING, ModelStatus.VALIDATING -> Triple(SoftBlueSurface, CobaltBlue, Icons.Outlined.HourglassEmpty)
        ModelStatus.UNRELIABLE -> Triple(Color(0xFFFEF3C7), Color(0xFF92400E), Icons.Outlined.WarningAmber)
        ModelStatus.ERROR -> Triple(WinGoRedContainer, WinGoRed, Icons.Outlined.ErrorOutline)
        ModelStatus.DISABLED, ModelStatus.INSUFFICIENT_DATA, ModelStatus.NOT_INITIALIZED ->
            Triple(IceSurface, MutedSlate, Icons.Outlined.HourglassEmpty)
    }

    Surface(color = bg, shape = RoundedCornerShape(8.dp)) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = fg,
                modifier = Modifier.size(13.dp)
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = status.label,
                style = MaterialTheme.typography.labelSmall,
                color = fg
            )
        }
    }
}

@Composable
fun HistoryTabContent(
    results: List<ResultEntity>,
    predictions: List<PredictionEntity>,
    syncStatusMessage: String?,
    networkStatus: NetworkStatus,
    isLoading: Boolean = false,
    historyDiagnostics: HistorySyncDiagnostics = HistorySyncDiagnostics()
) {
    var activeSubTab by remember { mutableStateOf(HistorySubTab.ACTUAL_RESULTS) }
    val timeFormatter = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
    val lastSyncLabel = historyDiagnostics.lastSuccessfulSyncAtMillis?.let {
        timeFormatter.format(Date(it))
    } ?: "Never"

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                HistorySubTab.entries.forEach { tab ->
                    val selected = tab == activeSubTab
                    val count = if (tab == HistorySubTab.ACTUAL_RESULTS) results.size else predictions.size
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (selected) CobaltBlue else IceSurface,
                        border = if (selected) null else BorderStroke(1.dp, SubtleBorder),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { activeSubTab = tab }
                            .testTag(
                                if (tab == HistorySubTab.ACTUAL_RESULTS) {
                                    "history_subtab_results"
                                } else {
                                    "history_subtab_predictions"
                                }
                            )
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = "${tab.label} ($count/500)",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (selected) PureWhite else MutedSlate,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }

        item {
            Surface(
                color = IceSurface,
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, SubtleBorder),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("history_sync_summary_card")
            ) {
                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (activeSubTab == HistorySubTab.ACTUAL_RESULTS) {
                                "Actual record count: ${results.size}/500"
                            } else {
                                "Predictions stored: ${predictions.size}/500"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = DeepRoyalBlue,
                            fontWeight = FontWeight.Bold
                        )
                        StatusBadge(
                            label = historyDiagnostics.status.label,
                            isPositive = historyDiagnostics.status == HistoryApiStatus.LIVE
                        )
                    }
                    Text(
                        text = "Last sync: $lastSyncLabel${historyDiagnostics.lastHttpStatus?.let { " | HTTP $it" } ?: ""}",
                        fontFamily = JetBrainsMonoFamily,
                        fontSize = 10.sp,
                        color = SlateInk
                    )
                    Text(
                        text = when {
                            networkStatus == NetworkStatus.Offline -> "Offline: displaying cached Room records only."
                            !historyDiagnostics.lastErrorMessage.isNullOrBlank() -> historyDiagnostics.lastErrorMessage
                            !syncStatusMessage.isNullOrBlank() -> syncStatusMessage
                            else -> historyDiagnostics.statusDetail
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (!historyDiagnostics.lastErrorMessage.isNullOrBlank()) StatusError else MutedSlate
                    )
                }
            }
        }

        if (activeSubTab == HistorySubTab.ACTUAL_RESULTS) {
            if (isLoading && results.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CircularProgressIndicator(
                                color = CobaltBlue,
                                modifier = Modifier.size(28.dp),
                                strokeWidth = 3.dp
                            )
                            Text(
                                text = "Syncing WinGo_1M history...",
                                style = MaterialTheme.typography.bodySmall,
                                color = MutedSlate
                            )
                        }
                    }
                }
            } else if (results.isEmpty()) {
                item {
                    EmptyHistoryBox(
                        title = stringResource(R.string.msg_no_historical_data),
                        subtitle = historyDiagnostics.lastErrorMessage
                            ?: "Real draws from GetHistoryIssuePage.json will appear here as live polling runs."
                    )
                }
            } else {
                items(results, key = { it.issueNumber }) { row ->
                    val primaryColor = WinGoColor.parsePrimary(row.color, row.number)
                    val syncStamp = timeFormatter.format(Date(row.receivedAt))
                    val displayTime = if (row.blockTimestamp.isNotBlank()) {
                        "${row.blockTimestamp} · Sync $syncStamp"
                    } else {
                        "Synced $syncStamp"
                    }

                    Surface(
                        color = PureWhite,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, SubtleBorder),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("history_row_${row.issueNumber}")
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = row.issueNumber,
                                    fontFamily = JetBrainsMonoFamily,
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 12.sp,
                                    color = SlateInk
                                )
                                Text(
                                    text = displayTime,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MutedSlate
                                )
                                if (row.sum >= 0) {
                                    Text(
                                        text = "Sum: ${row.sum}",
                                        fontFamily = JetBrainsMonoFamily,
                                        fontSize = 10.sp,
                                        color = DeepRoyalBlue
                                    )
                                }
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Surface(
                                    color = containerForWinGoColor(primaryColor),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text(
                                        text = row.color.ifBlank { "--" },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (row.color.isNotBlank()) colorForWinGoColor(primaryColor) else MutedSlate,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                    )
                                }
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size(30.dp)
                                        .clip(CircleShape)
                                        .background(colorForWinGoColor(primaryColor))
                                ) {
                                    Text(
                                        text = row.number.toString(),
                                        fontFamily = JetBrainsMonoFamily,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        color = PureWhite
                                    )
                                }
                            }
                        }
                    }
                }
            }
        } else {
            if (predictions.isEmpty()) {
                item {
                    EmptyHistoryBox(
                        title = "No prediction records yet.",
                        subtitle = "Pre-outcome locked predictions and verified outcomes will be stored independently from actual_results."
                    )
                }
            } else {
                items(predictions, key = { it.issueNumber }) { pred ->
                    val outcomeBadge = when {
                        pred.predictionStatus == PredictionStatus.SKIPPED ||
                            pred.verificationStatus == VerificationStatus.SKIPPED -> "Skipped"
                        pred.verificationStatus == VerificationStatus.UNVERIFIABLE -> "Unverifiable"
                        pred.verificationStatus == VerificationStatus.PENDING -> "Pending"
                        pred.numberCorrect == true || pred.colorCorrect == true || pred.sizeCorrect == true -> "Correct"
                        else -> "Wrong"
                    }
                    Surface(
                        color = PureWhite,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, SubtleBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = pred.issueNumber,
                                    fontFamily = JetBrainsMonoFamily,
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 12.sp,
                                    color = SlateInk
                                )
                                Text(
                                    text = "Pred: ${pred.predictedNumber ?: "--"} / ${pred.predictedColor ?: "--"} / ${pred.predictedSize ?: "--"}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = DeepRoyalBlue
                                )
                                if (pred.actualNumber != null) {
                                    Text(
                                        text = "Actual: ${pred.actualNumber} / ${pred.actualColor ?: "--"} / ${pred.actualSize ?: "--"}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MutedSlate
                                    )
                                }
                            }
                            Surface(
                                color = when (outcomeBadge) {
                                    "Correct" -> WinGoGreenContainer
                                    "Wrong" -> WinGoRedContainer
                                    else -> IceSurface
                                },
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(
                                    text = outcomeBadge,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = when (outcomeBadge) {
                                        "Correct" -> WinGoGreen
                                        "Wrong" -> WinGoRed
                                        else -> MutedSlate
                                    },
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyHistoryBox(title: String, subtitle: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.HourglassEmpty,
                contentDescription = null,
                tint = CobaltBlue,
                modifier = Modifier.size(32.dp)
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = SlateInk
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MutedSlate
            )
        }
    }
}

@Composable
private fun MetricTile(
    title: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        color = IceSurface,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, SubtleBorder),
        modifier = modifier
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(vertical = 8.dp, horizontal = 6.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = MutedSlate
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = value,
                fontFamily = JetBrainsMonoFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                color = accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ProbabilityRow(label: String, probability: Double) {
    val pct = (probability * 100.0).coerceIn(0.0, 100.0)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = SlateInk,
            modifier = Modifier.width(56.dp)
        )
        LinearProgressIndicator(
            progress = { (pct / 100.0).toFloat() },
            modifier = Modifier
                .weight(1f)
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp)),
            color = CobaltBlue,
            trackColor = SoftBlueSurface
        )
        Text(
            text = "${pct.toInt()}%",
            fontFamily = JetBrainsMonoFamily,
            fontSize = 11.sp,
            color = MutedSlate,
            modifier = Modifier.width(36.dp)
        )
    }
}

@Composable
fun StatusBadge(label: String, isPositive: Boolean, modifier: Modifier = Modifier) {
    Surface(
        color = if (isPositive) WinGoGreenContainer else Color(0xFFFEF3C7),
        shape = RoundedCornerShape(20.dp),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(if (isPositive) StatusOnline else StatusWarning)
            )
            Spacer(Modifier.width(5.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = if (isPositive) WinGoGreen else Color(0xFF92400E)
            )
        }
    }
}

private fun colorForWinGoColor(color: WinGoColor): Color = when (color) {
    WinGoColor.GREEN -> WinGoGreen
    WinGoColor.RED -> WinGoRed
    WinGoColor.VIOLET -> WinGoViolet
}

private fun containerForWinGoColor(color: WinGoColor): Color = when (color) {
    WinGoColor.GREEN -> WinGoGreenContainer
    WinGoColor.RED -> WinGoRedContainer
    WinGoColor.VIOLET -> WinGoVioletContainer
}
