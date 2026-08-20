package com.antidetect.browser.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val BackgroundDark = Color(0xFF121218)
val SurfaceDark = Color(0xFF1E1E28)
val SurfaceVariant = Color(0xFF2A2A36)
val AccentPurple = Color(0xFF6366F1)
val AccentPurpleLight = Color(0xFF818CF8)
val TextPrimary = Color(0xFFFFFFFF)
val TextSecondary = Color(0xFFA0A0B0)
val ErrorRed = Color(0xFFEF4444)
val SuccessGreen = Color(0xFF22C55E)
val Divider = Color(0xFF333340)

private val DarkColorScheme = darkColorScheme(
    primary = AccentPurple,
    onPrimary = TextPrimary,
    primaryContainer = AccentPurple,
    secondary = AccentPurpleLight,
    background = BackgroundDark,
    surface = SurfaceDark,
    surfaceVariant = SurfaceVariant,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    onSurfaceVariant = TextSecondary,
    error = ErrorRed,
    outline = Divider
)

@Composable
fun AntiDetectTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography(),
        content = content
    )
}
