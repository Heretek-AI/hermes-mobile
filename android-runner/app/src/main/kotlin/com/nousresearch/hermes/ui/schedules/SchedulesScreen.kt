package com.nousresearch.hermes.ui.schedules

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
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nousresearch.hermes.HermesApi
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * SchedulesScreen — list of cron jobs.
 *
 * Backed by [HermesApi.listCronJobs] / [createCronJob] /
 * [removeCronJob] / [pauseCronJob] / [resumeCronJob] /
 * [triggerCronJob]. FAB opens an add dialog. Each row has
 * play/pause/delete buttons.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SchedulesScreen(hermes: HermesApi) {
    var jobs by remember { mutableStateOf(hermes.listCronJobs()) }
    var showAdd by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = { showAdd = true }) {
                Icon(Icons.Filled.Add, contentDescription = "Add job")
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (jobs.isEmpty()) {
                item {
                    Text(
                        "No scheduled jobs. Tap + to create one.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(jobs) { job ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(job.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                job.cronExpr,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = job.enabled,
                            onCheckedChange = { enabled ->
                                if (enabled) hermes.resumeCronJob(job.id)
                                else hermes.pauseCronJob(job.id)
                                jobs = hermes.listCronJobs()
                            },
                        )
                        IconButton(onClick = {
                            scope.launch { hermes.triggerCronJob(job.id) }
                        }) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = "Run now")
                        }
                        IconButton(onClick = {
                            hermes.removeCronJob(job.id)
                            jobs = hermes.listCronJobs()
                        }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete")
                        }
                    }
                }
            }
        }
    }

    if (showAdd) {
        var name by remember { mutableStateOf("") }
        var cronExpr by remember { mutableStateOf("0 * * * *") }
        var command by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("New cron job") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") })
                    OutlinedTextField(value = cronExpr, onValueChange = { cronExpr = it }, label = { Text("Cron expression") })
                    OutlinedTextField(value = command, onValueChange = { command = it }, label = { Text("Command") })
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (name.isNotBlank() && cronExpr.isNotBlank() && command.isNotBlank()) {
                        hermes.createCronJob(
                            HermesApi.CronJob(
                                id = UUID.randomUUID().toString(),
                                name = name, cronExpr = cronExpr, command = command,
                                enabled = true, lastRun = null, nextRun = null,
                            ),
                        )
                        jobs = hermes.listCronJobs()
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
