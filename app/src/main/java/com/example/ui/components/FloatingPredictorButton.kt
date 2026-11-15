package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.ui.theme.CobaltBlue
import com.example.ui.theme.PlusJakartaSansFamily
import com.example.ui.theme.PureWhite
import kotlin.math.roundToInt

/**
 * Floating circular blue predictor button with white "A" logo (~56.dp).
 * Rendered in-app over the WebView inside the root Compose Box (no SYSTEM_ALERT_WINDOW permission),
 * visible ONLY after WebsiteSessionManager confirms an authenticated website session.
 * Supports smooth horizontal and vertical dragging clamped strictly within visible screen bounds.
 */
@Composable
fun FloatingPredictorButton(
    containerWidthPx: Float,
    containerHeightPx: Float,
    savedOffset: Offset?,
    onOffsetChanged: (Offset) -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val buttonSizeDp = 56.dp
    val buttonSizePx = with(density) { buttonSizeDp.toPx() }
    val marginPx = with(density) { 16.dp.toPx() }
    val bottomSafeMarginPx = with(density) { 76.dp.toPx() }

    val maxX = (containerWidthPx - buttonSizePx - marginPx).coerceAtLeast(marginPx)
    val maxY = (containerHeightPx - buttonSizePx - bottomSafeMarginPx).coerceAtLeast(marginPx)

    val currentOffset = savedOffset ?: Offset(x = maxX, y = maxY)
    val clampedOffset = Offset(
        x = currentOffset.x.coerceIn(marginPx, maxX),
        y = currentOffset.y.coerceIn(marginPx, maxY)
    )

    val latestOffset by rememberUpdatedState(clampedOffset)
    val latestOnOffsetChanged by rememberUpdatedState(onOffsetChanged)
    val openLabel = stringResource(R.string.action_open_ai_panel)

    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = CobaltBlue,
        border = BorderStroke(2.dp, PureWhite),
        modifier = modifier
            .offset { IntOffset(clampedOffset.x.roundToInt(), clampedOffset.y.roundToInt()) }
            .size(buttonSizeDp)
            .shadow(elevation = 10.dp, shape = CircleShape, clip = false)
            .pointerInput(containerWidthPx, containerHeightPx) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    val next = Offset(
                        x = (latestOffset.x + dragAmount.x).coerceIn(marginPx, maxX),
                        y = (latestOffset.y + dragAmount.y).coerceIn(marginPx, maxY)
                    )
                    latestOnOffsetChanged(next)
                }
            }
            .semantics { contentDescription = openLabel }
            .testTag("floating_predictor_button")
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxSize()
                .background(CobaltBlue)
        ) {
            Text(
                text = "A",
                color = Color.White,
                fontFamily = PlusJakartaSansFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 24.sp
            )
        }
    }
}
