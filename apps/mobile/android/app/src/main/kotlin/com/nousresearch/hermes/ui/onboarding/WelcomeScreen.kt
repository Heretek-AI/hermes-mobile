package com.nousresearch.hermes.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nousresearch.hermes.HermesApi

/**
 * WelcomeScreen — the first interactive onboarding screen.
 *
 * Three CTAs, matching the desktop's Welcome screen:
 * 1. **Connect to remote gateway** — set remote URL + API key in
 *    the HermesApi connection config; no local install needed.
 * 2. **Install locally (Termux)** — install via Termux's `pkg`
 *    and `pip` (if Termux is installed).
 * 3. **Install locally (bundled Python)** — install using the
 *    bundled Python asset shipped with the APK.
 *
 * Phase 2 v1: tapping any of the buttons dispatches to the
 * InstallScreen with the chosen path. The actual install logic
 * lives in [HermesInstaller].
 */
@Composable
fun WelcomeScreen(hermes: HermesApi) {
    val termux by remember { mutableStateOf(hermes.getTermuxStatus()) }
    val appState by hermes.appState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(32.dp))
        Text(
            text = "Welcome to Hermes",
            style = MaterialTheme.typography.headlineLarge,
        )
        Text(
            text = "Choose how you'd like to run the Hermes agent:",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))

        InstallOptionCard(
            title = "Connect to remote gateway",
            subtitle = "Use a Hermes gateway already running on another host. You'll need its URL and API key.",
            enabled = true,
            primary = true,
            onClick = {
                hermes.setAppState(HermesApi.AppState.Setup)
            },
        )

        InstallOptionCard(
            title = "Install locally (Termux)",
            subtitle = if (termux.installed) {
                "Termux ${termux.version ?: ""} detected. Hermes will use Termux's Python + pip."
            } else {
                "Termux not detected. Install Termux from F-Droid first, then come back."
            },
            enabled = termux.installed,
            primary = false,
            onClick = {
                hermes.setAppState(HermesApi.AppState.Installing)
            },
        )

        InstallOptionCard(
            title = "Install locally (bundled Python)",
            subtitle = "Use the Python runtime bundled with the Hermes APK. No Termux required.",
            enabled = true,
            primary = false,
            onClick = {
                hermes.setAppState(HermesApi.AppState.Installing)
            },
        )
    }
}

@Composable
private fun InstallOptionCard(
    title: String,
    subtitle: String,
    enabled: Boolean,
    primary: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (primary)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = if (primary)
                    MaterialTheme.colorScheme.onPrimaryContainer
                else
                    MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = if (primary)
                    MaterialTheme.colorScheme.onPrimaryContainer
                else
                    MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            if (primary) {
                Button(
                    onClick = onClick,
                    enabled = enabled,
                    colors = ButtonDefaults.buttonColors(),
                ) { Text("Continue") }
            } else {
                OutlinedButton(
                    onClick = onClick,
                    enabled = enabled,
                ) { Text("Continue") }
            }
        }
    }
}
