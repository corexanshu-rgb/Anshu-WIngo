package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val AnshuLightColorScheme = lightColorScheme(
    primary = CobaltBlue,
    onPrimary = PureWhite,
    primaryContainer = SoftBlueSurface,
    onPrimaryContainer = DeepRoyalBlue,
    secondary = BrightAccentBlue,
    onSecondary = PureWhite,
    secondaryContainer = SkyTint,
    onSecondaryContainer = DeepRoyalBlue,
    tertiary = WinGoViolet,
    onTertiary = PureWhite,
    background = PureWhite,
    onBackground = SlateInk,
    surface = PureWhite,
    onSurface = SlateInk,
    surfaceVariant = IceSurface,
    onSurfaceVariant = MutedSlate,
    outline = SubtleBorder,
    outlineVariant = SubtleBorder,
    error = StatusError,
    onError = PureWhite
)

private val AnshuShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp)
)

@Composable
fun AnshuWingoTheme(
    content: @Composable () -> Unit
) {
    // Light theme only by design requirement (pure white background, blue accents, no dark/gold mode)
    MaterialTheme(
        colorScheme = AnshuLightColorScheme,
        typography = AppTypography,
        shapes = AnshuShapes,
        content = content
    )
}
