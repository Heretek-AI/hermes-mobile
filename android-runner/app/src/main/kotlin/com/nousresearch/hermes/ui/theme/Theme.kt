package com.nousresearch.hermes.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * HermesAppTheme — thin Material3 wrapper that the Compose tree
 * applies at the root.
 *
 * Phase A colours: a neutral slate. The desktop Hermes app
 * uses a similar dark-mode default; Phase D can wire a real
 * brand palette from `~/.hermes/config.yaml` once the
 * `getActiveTheme()` IPC lands.
 */
@Composable
fun HermesAppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) {
        darkColorScheme(
            primary = Color(0xFFB8C5FF),
            onPrimary = Color(0xFF1A2B6D),
            primaryContainer = Color(0xFF33449A),
            onPrimaryContainer = Color(0xFFDFE3FF),
            secondary = Color(0xFFC1C5D6),
            onSecondary = Color(0xFF2A2F3D),
            secondaryContainer = Color(0xFF414655),
            onSecondaryContainer = Color(0xFFDFE1F2),
            background = Color(0xFF101117),
            onBackground = Color(0xFFE2E2E9),
            surface = Color(0xFF16171F),
            onSurface = Color(0xFFE2E2E9),
            surfaceVariant = Color(0xFF1B1D27),
            onSurfaceVariant = Color(0xFFC4C6D0),
        )
    } else {
        lightColorScheme(
            primary = Color(0xFF3D55CC),
            onPrimary = Color.White,
            primaryContainer = Color(0xFFDFE3FF),
            onPrimaryContainer = Color(0xFF001257),
            secondary = Color(0xFF5A5D72),
            onSecondary = Color.White,
            secondaryContainer = Color(0xFFDFE1F2),
            onSecondaryContainer = Color(0xFF171A2C),
            background = Color(0xFFFBF8FD),
            onBackground = Color(0xFF1B1B21),
            surface = Color(0xFFFBF8FD),
            onSurface = Color(0xFF1B1B21),
            surfaceVariant = Color(0xFFE2E1EC),
            onSurfaceVariant = Color(0xFF45464F),
        )
    }
    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
