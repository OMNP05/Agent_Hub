package dev.agenthub.app.core.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape

enum class ThemeMode { System, Light, Dark }

private val DarkColors = darkColorScheme(
    primary = Violet60,
    onPrimary = Ink,
    primaryContainer = Violet20,
    onPrimaryContainer = Violet10,
    secondary = Mint70,
    onSecondary = Ink,
    background = Ink,
    onBackground = Paper,
    surface = InkSurface,
    onSurface = Paper,
    surfaceVariant = InkSurfaceHigh,
    onSurfaceVariant = Violet80,
    error = ErrorDark,
)

private val LightColors = lightColorScheme(
    primary = Violet40,
    onPrimary = Paper,
    primaryContainer = Violet10,
    onPrimaryContainer = Violet20,
    secondary = Mint40,
    onSecondary = Paper,
    background = Paper,
    onBackground = Ink,
    surface = PaperSurface,
    onSurface = Ink,
    surfaceVariant = Violet10,
    onSurfaceVariant = Violet20,
    error = ErrorLight,
)

private val AppShapes = Shapes(
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
)

@Composable
fun AgentHubTheme(mode: ThemeMode = ThemeMode.System, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}
