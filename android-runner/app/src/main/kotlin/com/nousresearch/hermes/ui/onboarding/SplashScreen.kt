package com.nousresearch.hermes.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.nousresearch.hermes.HermesApi
import kotlinx.coroutines.delay

/**
 * SplashScreen — first screen the user sees on cold launch.
 *
 * Shows a brand mark + version for ~1s, then calls
 * [HermesApi.init] to inspect the install state. The init method
 * sets the [HermesApi.appState] to either Welcome (no install),
 * Setup (install verified but no profile), or Main (everything
 * ready). MainActivity's `setContent` then re-renders the
 * appropriate screen.
 */
@Composable
fun SplashScreen(hermes: HermesApi) {
    LaunchedEffect(Unit) {
        // Brief brand-mark pause. The real probe (install check,
        // Termux version, gateway status) happens inside
        // HermesApi.init() and is fast — usually <100ms.
        delay(800)
        hermes.init()
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "H",
                    style = MaterialTheme.typography.displayLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Text(
                text = "Hermes",
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = "v${hermes.getAppVersion()}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            CircularProgressIndicator(
                modifier = Modifier.size(32.dp),
                strokeWidth = 2.dp,
            )
        }
    }
}
