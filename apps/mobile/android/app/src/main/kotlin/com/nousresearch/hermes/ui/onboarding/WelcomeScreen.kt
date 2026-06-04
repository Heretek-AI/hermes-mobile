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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nousresearch.hermes.HermesApi
import com.nousresearch.hermes.R

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
 *
 * Workstream A (atomic-wondering-sunrise plan): when Termux is
 * missing, the Termux card renders two stacked CTAs that deep-link
 * to F-Droid (primary) and GitHub Releases (secondary) instead of
 * showing a dead disabled Continue button.
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
            primary = true,
        ) {
            Button(
                onClick = { hermes.setAppState(HermesApi.AppState.Setup) },
                colors = ButtonDefaults.buttonColors(),
            ) { Text("Continue") }
        }

        InstallOptionCard(
            title = "Install locally (Termux)",
            subtitle = if (termux.installed) {
                "Termux ${termux.version ?: ""} detected. Hermes will use Termux's Python + pip."
            } else {
                "Termux not detected. Install Termux from F-Droid first, then come back."
            },
            primary = false,
        ) {
            if (termux.installed) {
                OutlinedButton(
                    onClick = { hermes.setAppState(HermesApi.AppState.Installing) },
                ) { Text("Continue") }
            } else {
                Button(
                    onClick = { hermes.openExternal(F_DROID_TERMUX_URL) },
                    colors = ButtonDefaults.buttonColors(),
                ) { Text(stringResource(R.string.termux_install_action)) }
                Spacer(Modifier.height(4.dp))
                TextButton(
                    onClick = { hermes.openExternal(GITHUB_TERMUX_URL) },
                ) { Text(stringResource(R.string.termux_install_github)) }
            }
        }

        InstallOptionCard(
            title = "Install locally (bundled Python)",
            subtitle = "Use the Python runtime bundled with the Hermes APK. No Termux required. (Coming soon — the bundled-Python path is a future Path B.)",
            primary = false,
        ) {
            OutlinedButton(
                onClick = { hermes.setAppState(HermesApi.AppState.Installing) },
                enabled = false,
            ) { Text("Continue") }
        }
    }
}

/**
 * Termux install URLs surfaced from the Termux-missing card.
 *
 * F-Droid is the primary CTA because it's the only official
 * distribution channel maintained by the Termux project today
 * (the Play Store build was abandoned years ago and is
 * incompatible with current Termux:API). GitHub Releases is a
 * secondary fallback for users who don't have F-Droid or who
 * prefer side-loading the signed APK directly.
 */
private const val F_DROID_TERMUX_URL = "https://f-droid.org/packages/com.termux/"
private const val GITHUB_TERMUX_URL = "https://github.com/termux/termux-app/releases"

@Composable
private fun InstallOptionCard(
    title: String,
    subtitle: String,
    primary: Boolean,
    actions: @Composable () -> Unit,
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
            actions()
        }
    }
}
