package com.drivercall.presentation.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkScheme = darkColorScheme(
    primary = DriverGreen,
    onPrimary = DeepTeal,
    primaryContainer = DriverGreenDark,
    onPrimaryContainer = DeepTeal,
    secondary = DriverGreen,
    background = DeepTeal,
    onBackground = Color.White,
    surface = DeepTealSurface,
    onSurface = Color.White,
    surfaceVariant = DeepTealCard,
    onSurfaceVariant = Color(0xFFC9D6D2),
    error = DangerRed
)

private val LightScheme = lightColorScheme(
    primary = DriverGreenDark,
    onPrimary = Color.White,
    primaryContainer = DriverGreen,
    onPrimaryContainer = DeepTeal,
    secondary = DriverGreenDark,
    background = LightBackground,
    onBackground = Color(0xFF101815),
    surface = LightSurface,
    onSurface = Color(0xFF101815),
    surfaceVariant = Color(0xFFE7EFEc),
    onSurfaceVariant = Color(0xFF3E4A46),
    error = DangerRed
)

@Composable
fun DriverCallTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        typography = DriverCallTypography,
        content = content
    )
}
