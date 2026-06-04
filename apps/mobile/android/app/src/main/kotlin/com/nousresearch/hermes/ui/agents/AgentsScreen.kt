package com.nousresearch.hermes.ui.agents

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
 * AgentsScreen — list of profiles with add/delete/activate.
 *
 * Phase 4.10 implementation. Backed by
 * [HermesApi.listProfiles] / [createProfile] /
 * [deleteProfile] / [setActiveProfile]. The active profile
 * is highlighted with the primary container color.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentsScreen(hermes: HermesApi) {
    var profiles by remember { mutableStateOf(hermes.listProfiles()) }
    var showAdd by remember { mutableStateOf(false) }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = { showAdd = true }) {
                Icon(Icons.Filled.Add, contentDescription = "Add profile")
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(profiles) { profile ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (profile.isActive)
                            MaterialTheme.colorScheme.primaryContainer
                        else
                            MaterialTheme.colorScheme.surfaceVariant,
                    ),
                    onClick = {
                        hermes.setActiveProfile(profile.name)
                        profiles = hermes.listProfiles()
                    },
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(profile.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                buildString {
                                    if (profile.isActive) append("active · ")
                                    if (profile.hasMemory) append("memory · ")
                                    if (profile.hasSoul) append("soul · ")
                                    if (profile.hasUser) append("user · ")
                                    if (isNotEmpty()) setLength(length - 3)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (!profile.isActive) {
                            IconButton(onClick = {
                                hermes.deleteProfile(profile.name)
                                profiles = hermes.listProfiles()
                            }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Delete")
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAdd) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("New profile") },
            text = {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") })
            },
            confirmButton = {
                TextButton(onClick = {
                    if (name.isNotBlank() && hermes.createProfile(name)) {
                        profiles = hermes.listProfiles()
                    }
                    showAdd = false
                }) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showAdd = false }) { Text("Cancel") }
            },
        )
    }
}
