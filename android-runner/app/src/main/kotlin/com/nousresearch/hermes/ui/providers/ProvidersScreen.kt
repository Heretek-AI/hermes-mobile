package com.nousresearch.hermes.ui.providers

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nousresearch.hermes.HermesApi

/**
 * ProvidersScreen — list of provider credentials in the
 * auth.json credential pool.
 *
 * Backed by [HermesApi.getCredentialPool] /
 * [addCredentialPoolEntry] / [setCredentialPool].
 * FAB opens an add dialog. Each row shows the provider name
 * + a masked value (last 4 chars) and a delete button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProvidersScreen(hermes: HermesApi) {
    var pool by remember { mutableStateOf(hermes.getCredentialPool()) }
    var showAdd by remember { mutableStateOf(false) }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = { showAdd = true }) {
                Icon(Icons.Filled.Add, contentDescription = "Add provider")
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (pool.isEmpty()) {
                item {
                    Text(
                        "No providers configured. Tap + to add one.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(pool) { entry ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(entry.provider, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "${entry.kind} · ${entry.value}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = {
                            hermes.setCredentialPool(pool.filter { it != entry })
                            pool = hermes.getCredentialPool()
                        }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete")
                        }
                    }
                }
            }
        }
    }

    if (showAdd) {
        var provider by remember { mutableStateOf("openai") }
        var kind by remember { mutableStateOf("api_key") }
        var value by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("Add provider") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = provider, onValueChange = { provider = it }, label = { Text("Provider (openai/anthropic/...)") })
                    OutlinedTextField(value = kind, onValueChange = { kind = it }, label = { Text("Kind (api_key/oauth)") })
                    OutlinedTextField(value = value, onValueChange = { value = it }, label = { Text("API key / token") })
                    Spacer(Modifier.height(8.dp))
                    TextButton(
                        onClick = {
                            if (provider == "openai" || provider == "anthropic" || provider == "github") {
                                try {
                                    hermes.oauthLogin(provider)
                                } catch (_: Exception) {}
                            }
                        },
                    ) { Text("Or sign in with OAuth") }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (provider.isNotBlank() && value.isNotBlank()) {
                        hermes.addCredentialPoolEntry(
                            HermesApi.CredentialEntry(
                                provider = provider, kind = kind, value = value,
                                addedAt = System.currentTimeMillis(),
                            ),
                        )
                        pool = hermes.getCredentialPool()
                    }
                    showAdd = false
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showAdd = false }) { Text("Cancel") }
            },
        )
    }
}
