package com.nousresearch.hermes.ui.discover

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nousresearch.hermes.HermesApi

/**
 * DiscoverScreen — registry marketplace.
 *
 * Phase 4.9 v1: shows the list of installed registry items.
 * The full registry browser (Phase 5) fetches from
 * https://registry.hermesagents.cc.
 */
@Composable
fun DiscoverScreen(hermes: HermesApi) {
    // v1: no remote registry fetch; just a placeholder showing
    // the user's installed skills/memory providers.
    val items = remember {
        buildList {
            add("Skills — see the Skills tab")
            add("Memory providers — see ~/.hermes/memory-providers/")
            hermes.listInstalledSkills().forEach { add("Skill: ${it.name}") }
        }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(items) { item ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(item, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
