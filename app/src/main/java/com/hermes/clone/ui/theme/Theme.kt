package com.hermes.clone.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// Palette derived from the launcher background (#1F2A44).
private val LightColors = lightColorScheme(
    primary = Color(0xFF3B5BA5),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDBE4FF),
    onPrimaryContainer = Color(0xFF0B1B45),
    secondary = Color(0xFF5A6280),
    secondaryContainer = Color(0xFFDFE2F5),
    onSecondaryContainer = Color(0xFF171C32),
    background = Color(0xFFFAF9FD),
    surface = Color(0xFFFAF9FD),
    surfaceVariant = Color(0xFFE3E4EC),
    onSurfaceVariant = Color(0xFF45464F),
    error = Color(0xFFBA1A1A),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB2C5FF),
    onPrimary = Color(0xFF0F2A6B),
    primaryContainer = Color(0xFF2A4283),
    onPrimaryContainer = Color(0xFFDBE4FF),
    secondary = Color(0xFFC1C5E0),
    secondaryContainer = Color(0xFF424866),
    onSecondaryContainer = Color(0xFFDFE2F5),
    background = Color(0xFF12131A),
    surface = Color(0xFF12131A),
    surfaceVariant = Color(0xFF2B2D38),
    onSurfaceVariant = Color(0xFFC6C6D0),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

@Composable
fun HermesCloneTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}
