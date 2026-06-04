package com.nousresearch.hermes.ui.office

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nousresearch.hermes.HermesApi

/**
 * OfficeScreen — file viewer.
 *
 * Phase 4.11 v1: list the ~/.hermes/ home directory contents
 * (a flat one-level list). Tapping an item would open the
 * file viewer in Phase 5; v1 just shows the names.
 */
@Composable
fun OfficeScreen(hermes: HermesApi) {
    val home = remember { hermes.getHermesHome() }
    var files by remember { mutableStateOf(hermes.readDirectory(home)) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Office — $home", style = MaterialTheme.typography.titleMedium)
        Text(
            "Tap a file to open. (Phase 5 adds the file viewer.)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LazyColumn(
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(files) { name ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        name,
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}
