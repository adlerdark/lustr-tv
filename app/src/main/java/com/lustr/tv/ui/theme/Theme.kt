package com.lustr.tv.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

private val colors = darkColorScheme(
    primary = Color(0xFFE5677F),
    onPrimary = Color(0xFF2B0A12),
    background = Color(0xFF101014),
    onBackground = Color(0xFFECE6EA),
    surface = Color(0xFF1B1A20),
    onSurface = Color(0xFFECE6EA),
    surfaceVariant = Color(0xFF2A2830),
    onSurfaceVariant = Color(0xFFB9B2BC),
    error = Color(0xFFFF8A80),
)

@Composable
fun LustrTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, content = content)
}
