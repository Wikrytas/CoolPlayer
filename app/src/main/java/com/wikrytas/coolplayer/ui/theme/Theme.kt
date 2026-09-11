package com.wikrytas.coolplayer.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Бренд-палитра CoolPlayer: неон cyan/purple на почти чёрном #05070F.
 * Приложение тёмное по дизайну, поэтому схема одна для обоих режимов.
 */
private val BrandColorScheme = darkColorScheme(
    primary = Color(0xFF38E8FF),
    onPrimary = Color(0xFF05070F),
    primaryContainer = Color(0xFF0E2A33),
    onPrimaryContainer = Color(0xFF38E8FF),
    secondary = Color(0xFFB06BFF),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF2A1A33),
    onSecondaryContainer = Color(0xFFB06BFF),
    background = Color(0xFF05070F),
    onBackground = Color.White,
    surface = Color(0xFF101A33),
    onSurface = Color.White,
    surfaceVariant = Color(0xFF1A2440),
    onSurfaceVariant = Color(0xFF8FA3C8),
    outline = Color(0xFF2A3550),
    outlineVariant = Color(0xFF1A2440),
    error = Color(0xFFFF5252),
    onError = Color.White
)

@Composable
fun CoolPlayerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = BrandColorScheme,
        content = content
    )
}