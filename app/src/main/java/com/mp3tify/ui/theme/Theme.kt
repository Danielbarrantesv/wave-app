package com.mp3tify.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColors = darkColorScheme(
    primary = SpotifyGreen,
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF0E4D26),
    onPrimaryContainer = Color.White,
    secondary = Color(0xFFB3B3B3),
    onSecondary = Color.Black,
    background = AppBackground,
    onBackground = AppOnBackground,
    surface = AppSurface,
    onSurface = AppOnBackground,
    surfaceVariant = AppSurfaceVariant,
    onSurfaceVariant = AppOnSurfaceVariant,
    error = Color(0xFFCF6679),
    onError = Color.Black,
    outline = AppOutlineDark
)

private val LightColors = lightColorScheme(
    primary = SpotifyGreen,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB7F0C9),
    onPrimaryContainer = Color(0xFF00210A),
    secondary = Color(0xFF4F4F4F),
    onSecondary = Color.White,
    background = LightBackground,
    onBackground = LightOnBackground,
    surface = LightSurface,
    onSurface = LightOnBackground,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    outline = LightOutline
)

/**
 * App theme: Spotify-like dark by default (forceDark=true), selectable accent,
 * optional light theme. Status bar icons adapt: light icons in dark theme (!darkTheme).
 */
@Composable
fun Mp3tifyTheme(
    forceDark: Boolean = true,
    accent: AccentColor = AccentColor.VERDE,
    content: @Composable () -> Unit
) {
    val darkTheme = forceDark
    val colors = if (darkTheme) {
        DarkColors.copy(
            primary = accent.dark,
            primaryContainer = accent.darkContainer
        )
    } else {
        LightColors.copy(
            primary = accent.light,
            primaryContainer = accent.lightContainer
        )
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            try {
                val window = (view.context as Activity).window
                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    MaterialTheme(
        colorScheme = colors,
        typography = Typography,
        shapes = Shapes,
        content = content
    )
}
