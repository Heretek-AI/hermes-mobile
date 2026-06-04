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
 * Phase 8 v1: the primary path is "just configure the API key and
 * go". The Hermes gateway bypass means the install flow is
 * optional — a user with a MiniMax/OpenAI/Anthropic API key can
 * skip straight to Setup and start chatting.
 *
 * The local-install CTAs (Termux / bundled Python) are still
 * present for the future Path B (full Hermes gateway on-device)
 * but are de-emphasized.
 */
@Composable
fun WelcomeScreen(hermes: HermesApi) {
    val termux by remember { mutableStateOf(hermes.getTermuxStatus()) }

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
            text = "Choose how you'd like to get started:",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))

        InstallOptionCard(
            title = "Just configure the API key",
            subtitle = "Skip the local install and connect directly to an OpenAI-compatible API (e.g. MiniMax, OpenRouter). You'll just need the model name + API key.",
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
            subtitle = "Use the Python runtime bundled with the Hermes APK. No Termux required. (Coming soon — the bundled-Python path is a future Path B.)",
            enabled = false,
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
