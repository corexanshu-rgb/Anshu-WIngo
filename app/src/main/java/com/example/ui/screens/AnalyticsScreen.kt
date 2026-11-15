package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.outlined.Assessment
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.analytics.AccuracyTrendPoint
import com.example.analytics.AnalyticsSnapshot
import com.example.analytics.AnalyticsViewModel
import com.example.analytics.AnalyticsWindow
import com.example.data.local.entity.AnalysisEntity
import com.example.data.local.entity.PredictionEntity
import com.example.training.ModelTrainingSnapshot
import com.example.ui.theme.BrightAccentBlue
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
import com.example.ui.theme.SubtleBorder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun AnalyticsScreen(
    viewModel: AnalyticsViewModel,
    onBack: () -> Unit
) {
    val selectedWindow by viewModel.selectedWindow.collectAsStateWithLifecycle()
    val snapshot by viewModel.analyticsSnapshot.collectAsStateWithLifecycle()
    val analysisRuns by viewModel.persistedAnalysisRuns.collectAsStateWithLifecycle()
    val trainingSnapshot by viewModel.trainingSnapshot.collectAsStateWithLifecycle()

    BackHandler { onBack() }

    Scaffold(
        containerColor = PureWhite,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Surface(color = PureWhite, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.statusBarsPadding()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("analytics_back_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.action_back),
                                tint = SlateInk
                            )
                        }
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.Outlined.Analytics,
                            contentDescription = null,
                            tint = CobaltBlue,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.nav_analytics),
                            style = MaterialTheme.typography.titleLarge,
                            color = SlateInk
                        )
                    }
                    HorizontalDivider(color = SubtleBorder)
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(PureWhite)
                .clickable(
                    interactionSource = androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                )
                .padding(innerPadding)
                .navigationBarsPadding(),
            contentAlignment = Alignment.TopCenter
        ) {
            LazyColumn(
                modifier = Modifier
                    .widthIn(max = 680.dp)
                    .fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Window Filter Chips: Last 50 / 100 / 500
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        AnalyticsWindow.entries.forEach { window ->
                            val selected = window == selectedWindow
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (selected) CobaltBlue else IceSurface,
                                border = if (selected) null else BorderStroke(1.dp, SubtleBorder),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { viewModel.selectWindow(window) }
                                    .testTag("analytics_window_${window.limit}")
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = window.label,
                                        style = MaterialTheme.typography.labelLarge,
                                        color = if (selected) PureWhite else MutedSlate
                                    )
                                }
                            }
                        }
                    }
                }

                // Summary Counts
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CountStatCard("Total", snapshot.totalRecords.toString(), CobaltBlue, Modifier.weight(1f))
                        CountStatCard("Correct", snapshot.correctCount.toString(), StatusOnline, Modifier.weight(1f))
                        CountStatCard("Wrong", snapshot.wrongCount.toString(), StatusError, Modifier.weight(1f))
                        CountStatCard("Skipped", snapshot.skippedCount.toString(), MutedSlate, Modifier.weight(1f))
                    }
                }

                // Accuracy Breakdown
                item {
                    AccuracyBreakdownCard(snapshot = snapshot)
                }

                // Calibration, Baseline & Drift Comparison Card
                item {
                    CalibrationAndBaselineCard(trainingSnapshot = trainingSnapshot)
                }

                // Latest Model Evaluation Summary (Persisted in analysis_runs)
                if (analysisRuns.isNotEmpty()) {
                    item {
                        PersistedModulesCard(runs = analysisRuns)
                    }
                }

                if (snapshot.totalRecords == 0) {
                    item {
                        AnalyticsEmptyState()
                    }
                } else {
                    // Accuracy Trend Chart
                    item {
                        AccuracyTrendCard(trendPoints = snapshot.trendPoints)
                    }

                    // Model-wise Live Performance from Verified Predictions
                    item {
                        ModelPerformanceCard(snapshot = snapshot)
                    }

                    // Historical Prediction Log
                    item {
                        Text(
                            text = "Prediction History (${snapshot.records.size})",
                            style = MaterialTheme.typography.titleMedium,
                            color = SlateInk
                        )
                    }
                    items(snapshot.records, key = { it.issueNumber }) { record ->
                        PredictionHistoryRow(record = record)
                    }
                }

                item {
                    Text(
                        text = stringResource(R.string.educational_disclaimer),
                        style = MaterialTheme.typography.bodySmall,
                        color = MutedSlate,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun CountStatCard(
    label: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        color = PureWhite,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, SubtleBorder),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(vertical = 12.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = label, style = MaterialTheme.typography.labelMedium, color = MutedSlate)
            Spacer(Modifier.height(4.dp))
            Text(
                text = value,
                fontFamily = JetBrainsMonoFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
                color = accent
            )
        }
    }
}

@Composable
private fun AccuracyBreakdownCard(snapshot: AnalyticsSnapshot) {
    Surface(
        color = IceSurface,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, SubtleBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = "Verified Accuracy (${snapshot.window.label})",
                style = MaterialTheme.typography.titleMedium,
                color = SlateInk
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AccuracyMetricBox("Overall", snapshot.overallAccuracyPercent, Modifier.weight(1f))
                AccuracyMetricBox("Number", snapshot.numberAccuracyPercent, Modifier.weight(1f))
                AccuracyMetricBox("Colour", snapshot.colorAccuracyPercent, Modifier.weight(1f))
                AccuracyMetricBox("Big/Small", snapshot.sizeAccuracyPercent, Modifier.weight(1f))
            }
            Text(
                text = "Only predictions locked prior to result arrival are included (${snapshot.verifiedCount} verified). Overall counts exact full-match hits; Colour and Big/Small are tracked independently.",
                style = MaterialTheme.typography.bodySmall,
                color = MutedSlate
            )
        }
    }
}

@Composable
private fun CalibrationAndBaselineCard(trainingSnapshot: ModelTrainingSnapshot) {
    Surface(
        color = PureWhite,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, SubtleBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Baseline & Calibration Diagnostics",
                style = MaterialTheme.typography.titleMedium,
                color = SlateInk
            )
            val baseline = trainingSnapshot.baselineBenchmark
            if (baseline == null) {
                Text(
                    text = "Insufficient walk-forward data to benchmark baselines yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MutedSlate
                )
            } else {
                Text(
                    text = "Walk-Forward Baselines (${baseline.sampleCount} steps): Uniform Brier=${String.format(Locale.US, "%.4f", baseline.uniformBrierScore)}, Smoothed Freq Brier=${String.format(Locale.US, "%.4f", baseline.smoothedFreqBrierScore)}, Best LogLoss=${String.format(Locale.US, "%.3f", baseline.bestBaselineLogLoss)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = DeepRoyalBlue
                )
            }

            val cal = trainingSnapshot.calibrationReport
            if (cal == null) {
                Text(
                    text = "Probability Calibration: awaiting >=10 verified out-of-sample predictions.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MutedSlate
                )
            } else {
                Text(
                    text = "Temp Scaling (T=${cal.optimalTemperature}, n=${cal.sampleCount}): LogLoss=${String.format(Locale.US, "%.3f", cal.logLoss)} | Brier=${String.format(Locale.US, "%.4f", cal.brierScore)} | ECE=${String.format(Locale.US, "%.4f", cal.expectedCalibrationError)}",
                    fontFamily = JetBrainsMonoFamily,
                    fontSize = 11.sp,
                    color = CobaltBlue
                )
                cal.reliabilityBuckets.forEach { b ->
                    Text(
                        text = "Bucket ${b.bucketLabel} (n=${b.sampleCount}): Conf=${String.format(Locale.US, "%.1f%%", b.avgPredictedConfidence * 100)} vs Acc=${String.format(Locale.US, "%.1f%%", b.empiricalAccuracy * 100)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = SlateInk
                    )
                }
            }
        }
    }
}

@Composable
private fun PersistedModulesCard(runs: List<AnalysisEntity>) {
    Surface(
        color = PureWhite,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, SubtleBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Local AI Engine Evaluation (${runs.size} Modules)",
                style = MaterialTheme.typography.titleMedium,
                color = SlateInk
            )
            runs.forEach { run ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "${run.moduleName} (${run.status})",
                            style = MaterialTheme.typography.bodySmall,
                            color = SlateInk,
                            fontWeight = FontWeight.Medium
                        )
                        val brierStr = run.brierScore?.let { String.format(Locale.US, "%.4f", it) } ?: "--"
                        val baseStr = run.baselineBrierScore?.let { String.format(Locale.US, "%.4f", it) } ?: "--"
                        Text(
                            text = "n=${run.sampleSize} | Val=${run.validationSampleSize} | Brier=$brierStr vs $baseStr",
                            fontFamily = JetBrainsMonoFamily,
                            fontSize = 10.sp,
                            color = MutedSlate
                        )
                    }
                    Text(
                        text = if (run.assignedWeight > 0.0) {
                            "${String.format(Locale.US, "%.1f%%", run.assignedWeight * 100)} wt"
                        } else {
                            "0% wt"
                        },
                        fontFamily = JetBrainsMonoFamily,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (run.assignedWeight > 0.0) CobaltBlue else MutedSlate
                    )
                }
            }
        }
    }
}

@Composable
private fun AccuracyMetricBox(
    label: String,
    percent: Double?,
    modifier: Modifier = Modifier
) {
    Surface(
        color = PureWhite,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, SubtleBorder),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(vertical = 10.dp, horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = label, style = MaterialTheme.typography.labelSmall, color = MutedSlate)
            Spacer(Modifier.height(4.dp))
            Text(
                text = percent?.let { String.format(Locale.US, "%.1f%%", it) } ?: "--",
                fontFamily = JetBrainsMonoFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                color = if (percent != null) CobaltBlue else MutedSlate
            )
        }
    }
}

@Composable
private fun AccuracyTrendCard(trendPoints: List<AccuracyTrendPoint>) {
    Surface(
        color = PureWhite,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, SubtleBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = "Accuracy Trend",
                style = MaterialTheme.typography.titleMedium,
                color = SlateInk
            )
            if (trendPoints.size < 2) {
                Text(
                    text = "At least 2 verified locked predictions are required to plot a trend line.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MutedSlate
                )
            } else {
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp)
                ) {
                    val w = size.width
                    val h = size.height
                    val stepX = w / (trendPoints.size - 1).coerceAtLeast(1)

                    drawLine(
                        color = SubtleBorder,
                        start = Offset(0f, h * 0.5f),
                        end = Offset(w, h * 0.5f),
                        strokeWidth = 2f
                    )

                    val path = Path()
                    trendPoints.forEachIndexed { idx, pt ->
                        val x = idx * stepX
                        val y = h - ((pt.cumulativeAccuracyPercent.coerceIn(0f, 100f) / 100f) * h)
                        if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    drawPath(
                        path = path,
                        color = CobaltBlue,
                        style = Stroke(width = 5f, cap = StrokeCap.Round)
                    )

                    trendPoints.forEachIndexed { idx, pt ->
                        val x = idx * stepX
                        val y = h - ((pt.cumulativeAccuracyPercent.coerceIn(0f, 100f) / 100f) * h)
                        drawCircle(
                            color = BrightAccentBlue,
                            radius = 6f,
                            center = Offset(x, y)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelPerformanceCard(snapshot: AnalyticsSnapshot) {
    Surface(
        color = PureWhite,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, SubtleBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Verified Prediction Contribution Accuracy",
                style = MaterialTheme.typography.titleMedium,
                color = SlateInk
            )
            if (snapshot.modulePerformance.isEmpty()) {
                Text(
                    text = "No verified model contributions recorded yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MutedSlate
                )
            } else {
                snapshot.modulePerformance.forEach { mod ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "${mod.moduleId} (${mod.evaluatedCount})",
                            style = MaterialTheme.typography.bodyMedium,
                            color = SlateInk
                        )
                        Text(
                            text = mod.overallAccuracy?.let {
                                String.format(Locale.US, "%.1f%%", it * 100.0)
                            } ?: "--",
                            fontFamily = JetBrainsMonoFamily,
                            fontWeight = FontWeight.Bold,
                            color = CobaltBlue
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PredictionHistoryRow(record: PredictionEntity) {
    val formatter = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    Surface(
        color = PureWhite,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, SubtleBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = record.issueNumber,
                    fontFamily = JetBrainsMonoFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = SlateInk
                )
                Text(
                    text = "${formatter.format(Date(record.createdAt))} | ${record.predictionStatus.name}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MutedSlate
                )
                Text(
                    text = "Pred: ${record.predictedNumber ?: "--"} / ${record.predictedColor ?: "--"} / ${record.predictedSize ?: "--"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = DeepRoyalBlue
                )
            }

            Column(horizontalAlignment = Alignment.End) {
                Surface(
                    color = SoftBlueSurface,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = record.verificationStatus.name,
                        style = MaterialTheme.typography.labelSmall,
                        color = CobaltBlue,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
                if (record.actualNumber != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Actual: ${record.actualNumber} (${record.actualColor})",
                        style = MaterialTheme.typography.labelSmall,
                        color = MutedSlate
                    )
                }
            }
        }
    }
}

@Composable
private fun AnalyticsEmptyState() {
    Surface(
        color = IceSurface,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, SubtleBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(SoftBlueSurface)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Assessment,
                    contentDescription = null,
                    tint = CobaltBlue,
                    modifier = Modifier.size(28.dp)
                )
            }
            Text(
                text = "No Prediction Records Yet",
                style = MaterialTheme.typography.titleMedium,
                color = SlateInk
            )
            Text(
                text = "All Analytics metrics are calculated strictly from real locked predictions verified against actual WinGo 1M outcomes in Room. No placeholder or sample statistics are displayed.",
                style = MaterialTheme.typography.bodyMedium,
                color = MutedSlate,
                textAlign = TextAlign.Center
            )
        }
    }
}
