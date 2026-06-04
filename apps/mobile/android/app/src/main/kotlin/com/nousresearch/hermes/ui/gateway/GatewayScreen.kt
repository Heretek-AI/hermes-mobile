package com.nousresearch.hermes.ui.gateway

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
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
import com.nousresearch.hermes.GatewaySupervisor

/**
 * GatewayScreen — start/stop the gateway + platform toggles.
 *
 * Phase 4.8 implementation. The status card reads
 * [GatewaySupervisor.State] from a polling loop (every 1s).
 * The platform toggle grid reads/writes via
 * [HermesApi.getPlatformEnabled] / [setPlatformEnabled].
 */
@Composable
fun GatewayScreen(hermes: HermesApi) {
    var running by remember { mutableStateOf(hermes.gatewayStatus()) }
    var platforms by remember { mutableStateOf(hermes.getPlatformEnabled()) }

    LaunchedEffect(Unit) {
        // Re-read the supervisor state every 1s so the UI follows
        // background changes (e.g. boot autostart).
        while (true) {
            running = hermes.gatewayStatus()
            platforms = hermes.getPlatformEnabled()
            kotlinx.coroutines.delay(1000)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Gateway", style = MaterialTheme.typography.titleLarge)
                    Text(
                        if (running) "Running" else "Stopped",
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (running) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (running) {
                            Button(
                                onClick = { hermes.stopGateway(); running = false },
                                modifier = Modifier.weight(1f),
                            ) { Text("Stop") }
                        } else {
                            Button(
                                onClick = { hermes.startGateway(); running = true },
                                modifier = Modifier.weight(1f),
                            ) { Text("Start") }
                        }
                    }
                }
            }
        }
        item { Text("Platforms", style = MaterialTheme.typography.titleMedium) }
        items(platforms) { p ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(p.platform, modifier = Modifier.weight(1f))
                    Switch(
                        checked = p.enabled,
                        onCheckedChange = { enabled ->
                            hermes.setPlatformEnabled(p.platform, enabled)
                            platforms = hermes.getPlatformEnabled()
                        },
                    )
                }
            }
        }
    }
}
