package com.agon.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * Deep Space 2026 Color Scheme.
 * Background = Deep Space gradient (#0A0B14 → #050608).
 * Surfaces = glassmorphic tints that lift off the background.
 * Accents = Electric Indigo + Aurora Cyan with neon glow.
 */
private val DarkColorScheme = darkColorScheme(
    primary = PrimaryLight,
    onPrimary = OnPrimary,
    primaryContainer = PrimaryDark,
    onPrimaryContainer = PrimaryLight,
    secondary = SecondaryColor,
    onSecondary = OnSecondary,
    secondaryContainer = SecondaryDark,
    onSecondaryContainer = SecondaryLight,
    tertiary = AccentCyan,
    onTertiary = OnPrimary,
    tertiaryContainer = SecondaryDark,
    onTertiaryContainer = SecondaryLight,
    error = ErrorDark,
    onError = OnPrimary,
    errorContainer = ErrorLight,
    onErrorContainer = OnBackgroundLight,
    background = BackgroundDark,
    onBackground = OnBackgroundDark,
    surface = SurfaceDark,
    onSurface = OnSurfaceDark,
    surfaceVariant = CardBackgroundDark,
    onSurfaceVariant = OnSurfaceDark,
    outline = DividerDark,
    outlineVariant = DividerDark,
)

private val LightColorScheme = lightColorScheme(
    primary = PrimaryColor,
    onPrimary = OnPrimary,
    primaryContainer = PrimaryLight,
    onPrimaryContainer = PrimaryDark,
    secondary = SecondaryColor,
    onSecondary = OnSecondary,
    secondaryContainer = SecondaryLight,
    onSecondaryContainer = SecondaryDark,
    tertiary = AccentCyan,
    onTertiary = OnPrimary,
    tertiaryContainer = AccentVariant,
    onTertiaryContainer = OnPrimary,
    error = ErrorLight,
    onError = OnPrimary,
    errorContainer = ErrorDark,
    onErrorContainer = OnBackgroundDark,
    background = BackgroundLight,
    onBackground = OnBackgroundLight,
    surface = SurfaceLight,
    onSurface = OnSurfaceLight,
    surfaceVariant = CardBackgroundLight,
    onSurfaceVariant = OnSurfaceLight,
    outline = DividerLight,
    outlineVariant = DividerLight,
)

@Composable
fun AgonAppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
