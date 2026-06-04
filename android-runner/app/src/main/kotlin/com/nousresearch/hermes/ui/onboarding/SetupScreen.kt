package com.nousresearch.hermes.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import com.nousresearch.hermes.HermesApi

/**
 * SetupScreen — first-launch configuration.
 *
 * Collects the bare minimum the agent needs to start:
 * - Provider: openai / anthropic / ollama / local
 * - Model: free-text, pre-filled with the provider's default
 * - Profile name: defaults to "default"
 *
 * On submit, writes the values to `~/.hermes/config.yaml` via
 * [HermesApi.setConfig] and creates the profile directory via
 * [HermesApi.createProfile]. Then routes to Main.
 */
@Composable
fun SetupScreen(hermes: HermesApi) {
    val providers = listOf("openai", "anthropic", "ollama", "local")
    val defaultModels = mapOf(
        "openai" to "gpt-4o-mini",
        "anthropic" to "claude-3-5-sonnet-latest",
        "ollama" to "llama3.2",
        "local" to "local-model",
    )

    var provider by remember { mutableStateOf(providers.first()) }
    var model by remember { mutableStateOf(defaultModels[providers.first()] ?: "") }
    var profileName by remember { mutableStateOf("default") }
    var providerExpanded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

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
            text = "Tell us which provider to use. You can change this later in Settings.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Provider dropdown
        ExposedDropdownMenuBox(
            expanded = providerExpanded,
            onExpandedChange = { providerExpanded = !providerExpanded },
        ) {
            OutlinedTextField(
                value = provider,
                onValueChange = {},
                readOnly = true,
                label = { Text("Provider") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = providerExpanded) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(),
            )
            DropdownMenu(
                expanded = providerExpanded,
                onDismissRequest = { providerExpanded = false },
            ) {
                providers.forEach { p ->
                    DropdownMenuItem(
                        text = { Text(p) },
                        onClick = {
                            provider = p
                            model = defaultModels[p] ?: ""
                            providerExpanded = false
                        },
                    )
                }
            }
        }

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
                // 3. advance to Main
                hermes.setAppState(HermesApi.AppState.Main)
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Continue")
        }
    }
}
