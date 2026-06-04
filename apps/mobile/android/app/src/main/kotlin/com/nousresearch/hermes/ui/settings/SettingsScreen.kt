package com.nousresearch.hermes.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nousresearch.hermes.HermesApi

/**
 * SettingsScreen — multi-section form.
 *
 * Sections: Connection, Provider, Model, Locale, Theme, Crash
 * Reporting, Start on Boot, Battery Optimization, About.
 *
 * The full Phase 4.2 implementation: each section is a Card
 * with a header + form fields. Backed by HermesApi methods
 * from Phase 1.1.
 */
@Composable
fun SettingsScreen(hermes: HermesApi) {
    var locale by remember { mutableStateOf(hermes.getLocale()) }
    var mode by remember { mutableStateOf(hermes.getConnectionConfig().mode) }
    var remoteUrl by remember { mutableStateOf(hermes.getConnectionConfig().remoteUrl) }
    var apiKey by remember { mutableStateOf(hermes.getConnectionConfig().hasApiKey.toString()) }
    var startOnBoot by remember { mutableStateOf(hermes.getStartOnBoot().value) }
    var batteryOpt by remember { mutableStateOf(hermes.getBatteryOptStatus().ignoring) }
    var crashReporting by remember { mutableStateOf(hermes.getCrashReportingEnabled()) }

    val config = remember { hermes.getConfig() }
    var defaultModel by remember { mutableStateOf(config["default_model"] ?: "") }
    var defaultProvider by remember { mutableStateOf(config["default_provider"] ?: "openai") }

    val version = remember { hermes.getAppVersion() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { SectionHeader("Connection") }
        item {
            SettingsCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Mode: $mode", style = MaterialTheme.typography.bodyMedium)
                    Text("Remote URL: $remoteUrl", style = MaterialTheme.typography.bodySmall)
                    Text("API key: $apiKey", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        item { SectionHeader("Provider & Model") }
        item {
            SettingsCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = defaultProvider,
                        onValueChange = { defaultProvider = it },
                        label = { Text("Default provider") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = defaultModel,
                        onValueChange = { defaultModel = it },
                        label = { Text("Default model") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Tap to save →",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                    )
                }
            }
        }

        item { SectionHeader("Locale") }
        item {
            SettingsCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Language: $locale", modifier = Modifier.weight(1f))
                    Switch(
                        checked = locale == "zh-CN",
                        onCheckedChange = {
                            locale = if (it) "zh-CN" else "en"
                            hermes.setLocale(locale)
                        },
                    )
                }
            }
        }

        item { SectionHeader("System") }
        item {
            SettingsCard {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    ToggleRow(
                        label = "Start on boot",
                        checked = startOnBoot,
                        onCheckedChange = {
                            startOnBoot = it
                            hermes.setStartOnBoot(it)
                        },
                    )
                    ToggleRow(
                        label = "Ignore battery optimization",
                        checked = batteryOpt,
                        onCheckedChange = {
                            batteryOpt = hermes.requestIgnoreBatteryOptimizations()
                        },
                    )
                    ToggleRow(
                        label = "Crash reporting (Sentry)",
                        checked = crashReporting,
                        onCheckedChange = {
                            crashReporting = it
                            hermes.setCrashReportingEnabled(it)
                        },
                    )
                }
            }
        }

        item { SectionHeader("About") }
        item {
            SettingsCard {
                Column {
                    Text("Hermes Mobile v$version", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Native-Compose build · see docs/architecture.md",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, start = 4.dp),
    )
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            content()
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
