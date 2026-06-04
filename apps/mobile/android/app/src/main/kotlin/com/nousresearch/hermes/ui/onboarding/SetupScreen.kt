package com.nousresearch.hermes.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nousresearch.hermes.HermesApi

/**
 * SetupScreen — first-launch configuration.
 *
 * Phase 8 v1: only the `openai` provider is wired. The user enters:
 * - Model: pre-filled with `MiniMax-M3`
 * - Profile name: defaults to "default"
 * - API key: bearer token for the OpenAI-compatible endpoint
 * - Base URL: pre-filled with `https://api.minimax.io/v1`
 *
 * On submit, writes the values to `~/.hermes/config.yaml` via
 * [HermesApi.setConfig], persists the connection config to
 * SharedPreferences, and routes to Main.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(hermes: HermesApi) {
    val provider = "openai"
    val defaultModel = "MiniMax-M3"
    val defaultBaseUrl = "https://api.minimax.io/v1"

    // Pre-fill from existing prefs so a re-entry of Setup (e.g.
    // via the Welcome screen "Just configure the API key" path
    // when prefs already exist) doesn't wipe the user's API key.
    val existing = remember { hermes.getConnectionConfig() }
    val existingConfig = remember { hermes.getConfig() }

    var model by remember { mutableStateOf(existingConfig["default_model"] ?: defaultModel) }
    var profileName by remember { mutableStateOf("default") }
    var error by remember { mutableStateOf<String?>(null) }
    var remoteUrl by remember { mutableStateOf(if (existing.remoteUrl.isNotEmpty()) existing.remoteUrl else defaultBaseUrl) }
    // apiKey stays empty by default. If the user re-enters Setup
    // and already has a key in prefs, we show a placeholder
    // (in the label) and preserve the existing key when they tap
    // Continue without typing anything new. The actual pre-fill
    // is unsafe: we'd need to show the real key (a security
    // smell) OR a placeholder (which we can't reliably detect
    // later as "user didn't change it"). The cleanest UX is
    // "type a new key to change it" — leave the field empty,
    // and if the user submits empty we keep what's in prefs.
    var apiKey by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(32.dp))
        Text(
            text = "Set up Hermes",
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = "Connect to an OpenAI-compatible API. You can change this later in Settings.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Phase 8: provider locked to openai.
        OutlinedTextField(
            value = provider,
            onValueChange = {},
            readOnly = true,
            enabled = false,
            label = { Text("Provider") },
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = model,
            onValueChange = { model = it },
            label = { Text("Model") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = profileName,
            onValueChange = { profileName = it.replace(" ", "_") },
            label = { Text("Profile name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        // Connection card
        Text(
            "Connection",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = 8.dp),
        )
        OutlinedTextField(
            value = remoteUrl,
            onValueChange = { remoteUrl = it },
            label = { Text("OpenAI-compatible base URL") },
            placeholder = { Text(defaultBaseUrl) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            label = {
                Text(
                    if (existing.hasApiKey) "API key (bearer token) — leave blank to keep existing"
                    else "API key (bearer token)"
                )
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        error?.let {
            Text(
                text = it,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                error = null
                if (model.isBlank()) {
                    error = "Please enter a model name"
                    return@Button
                }
                if (profileName.isBlank()) {
                    error = "Please enter a profile name"
                    return@Button
                }
                // 1. write config.yaml
                hermes.setConfig(mapOf(
                    "default_provider" to provider,
                    "default_model" to model,
                ))
                // 2. create + activate the profile
                if (hermes.createProfile(profileName)) {
                    hermes.setActiveProfile(profileName)
                } else {
                    hermes.setActiveProfile(profileName)
                }
                // 3. persist connection config (Phase 8: always remote mode).
                //    Only overwrite the apiKey if the user typed
                //    something. Leaving the field empty is treated
                //    as "keep the existing key" — we don't call
                //    setConnectionConfig at all in that case so the
                //    raw key in SharedPreferences is preserved.
                if (apiKey.isNotEmpty()) {
                    hermes.setConnectionConfig(
                        mode = "remote",
                        remoteUrl = remoteUrl,
                        apiKey = apiKey,
                    )
                } else if (!hermes.getConnectionConfig().hasApiKey) {
                    // First-time setup: no key yet, write empty so
                    // the prefs file has the right shape. The user
                    // is expected to type a key and Continue again.
                    hermes.setConnectionConfig(
                        mode = "remote",
                        remoteUrl = remoteUrl,
                        apiKey = "",
                    )
                }
                // else: existing key is kept untouched.
                // 4. advance to Main
                hermes.setAppState(HermesApi.AppState.Main)
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Continue")
        }
    }
}
