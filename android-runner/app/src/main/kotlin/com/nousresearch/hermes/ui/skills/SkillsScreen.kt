package com.nousresearch.hermes.ui.skills

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nousresearch.hermes.HermesApi

/**
 * SkillsScreen — installed + bundled skills browser.
 *
 * Top-level layout: a 2-tab TabRow ("Installed" / "Bundled")
 * with a LazyColumn of skill cards below. Each card shows
 * the name, description, and a context action button
 * (install/uninstall). Tapping a card opens a detail dialog
 * with the skill's markdown content.
 *
 * Phase 4: matches the desktop's Skills screen at
 * `review/hermes-desktop/src/renderer/src/screens/Skills/`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillsScreen(hermes: HermesApi) {
    var tab by remember { mutableStateOf(0) }
    var installed by remember { mutableStateOf<List<HermesApi.SkillInfo>>(emptyList()) }
    var bundled by remember { mutableStateOf<List<HermesApi.SkillInfo>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var detail by remember { mutableStateOf<HermesApi.SkillInfo?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        loading = true
        installed = hermes.listInstalledSkills()
        bundled = hermes.listBundledSkills()
        loading = false
    }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = { showAdd = true }) {
                Icon(Icons.Filled.Add, contentDescription = "Add skill")
            }
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Installed (${installed.size})") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Bundled (${bundled.size})") })
            }
            if (loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                val list = if (tab == 0) installed else bundled
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(list) { skill ->
                        SkillRow(
                            skill = skill,
                            onTap = { detail = skill },
                            onUninstall = if (tab == 0) {
                                {
                                    if (hermes.uninstallSkill(skill.name)) {
                                        installed = hermes.listInstalledSkills()
                                    }
                                }
                            } else null,
                            onInstall = if (tab == 1) {
                                {
                                    if (hermes.installSkill(skill.path)) {
                                        installed = hermes.listInstalledSkills()
                                    }
                                }
                            } else null,
                        )
                    }
                }
            }
        }
    }

    detail?.let { skill ->
        AlertDialog(
            onDismissRequest = { detail = null },
            title = { Text(skill.name) },
            text = {
                Text(
                    text = hermes.getSkillContent(skill.path),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = { detail = null }) { Text("Close") }
            },
        )
    }

    if (showAdd) {
        var url by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("Install skill") },
            text = {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Local path or URL") },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (hermes.installSkill(url)) {
                        installed = hermes.listInstalledSkills()
                    }
                    showAdd = false
                }) { Text("Install") }
            },
            dismissButton = {
                TextButton(onClick = { showAdd = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun SkillRow(
    skill: HermesApi.SkillInfo,
    onTap: () -> Unit,
    onUninstall: (() -> Unit)? = null,
    onInstall: (() -> Unit)? = null,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onTap),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(skill.name, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(2.dp))
                Text(
                    skill.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
            if (onUninstall != null) {
                IconButton(onClick = onUninstall) {
                    Icon(Icons.Filled.Delete, contentDescription = "Uninstall")
                }
            }
            if (onInstall != null) {
                IconButton(onClick = onInstall) {
                    Icon(Icons.Filled.Download, contentDescription = "Install")
                }
            }
        }
    }
}
