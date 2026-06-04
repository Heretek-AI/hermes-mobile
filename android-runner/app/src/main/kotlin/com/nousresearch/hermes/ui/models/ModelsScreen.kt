package com.nousresearch.hermes.ui.models

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
 * ModelsScreen — list of model configs with add/remove.
 *
 * Backed by [HermesApi.listModels] / [addModel] / [removeModel].
 * FAB opens an add dialog.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsScreen(hermes: HermesApi) {
    var models by remember { mutableStateOf(hermes.listModels()) }
    var showAdd by remember { mutableStateOf(false) }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = { showAdd = true }) {
                Icon(Icons.Filled.Add, contentDescription = "Add model")
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(models) { model ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(model.name.ifEmpty { model.id }, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "${model.provider} · max=${model.maxTokens} · t=${model.temperature}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = {
                            hermes.removeModel(model.id)
                            models = hermes.listModels()
                        }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete")
                        }
                    }
                }
            }
        }
    }

    if (showAdd) {
        var id by remember { mutableStateOf("") }
        var provider by remember { mutableStateOf("openai") }
        var name by remember { mutableStateOf("") }
        var maxTokens by remember { mutableStateOf("4096") }
        var temperature by remember { mutableStateOf("0.7") }
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("Add model") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = id, onValueChange = { id = it }, label = { Text("ID") })
                    OutlinedTextField(value = provider, onValueChange = { provider = it }, label = { Text("Provider") })
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Display name") })
                    OutlinedTextField(value = maxTokens, onValueChange = { maxTokens = it }, label = { Text("Max tokens") })
                    OutlinedTextField(value = temperature, onValueChange = { temperature = it }, label = { Text("Temperature") })
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (id.isNotBlank()) {
                        hermes.addModel(
                            HermesApi.ModelConfig(
                                id = id, provider = provider, name = name.ifEmpty { id },
                                maxTokens = maxTokens.toIntOrNull() ?: 4096,
                                temperature = temperature.toDoubleOrNull() ?: 0.7,
                            ),
                        )
                        models = hermes.listModels()
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
