package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val QuantColorScheme = darkColorScheme(
    primary = QuantGreen,
    onPrimary = Color(0xFF00391A),
    primaryContainer = QuantGreenDim,
    onPrimaryContainer = QuantGreen,
    secondary = QuantCyan,
    onSecondary = Color(0xFF00363D),
    secondaryContainer = QuantCyanDim,
    onSecondaryContainer = QuantCyan,
    tertiary = QuantPurple,
    onTertiary = Color(0xFF2C0066),
    tertiaryContainer = QuantPurpleDim,
    onTertiaryContainer = QuantPurple,
    background = TerminalBg,
    onBackground = TextWhite,
    surface = CardDark,
    onSurface = TextWhite,
    surfaceVariant = CardDarkElevated,
    onSurfaceVariant = TextMuted,
    outline = CardBorder,
    error = QuantRed,
    onError = Color.White
)

@Composable
fun QuantAITheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = QuantColorScheme,
        typography = Typography,
        content = content
    )
}
