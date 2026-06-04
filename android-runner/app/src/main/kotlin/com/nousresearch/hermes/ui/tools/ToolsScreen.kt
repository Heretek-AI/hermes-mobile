package com.nousresearch.hermes.ui.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
 * ToolsScreen — list of toolset toggles.
 *
 * Backed by [HermesApi.getToolsets] / [setToolsetEnabled].
 * Each row shows the toolset name + a Switch. Phase 4.6
 * implementation; the desktop equivalent is
 * `screens/Tools/Tools.tsx`.
 */
@Composable
fun ToolsScreen(hermes: HermesApi) {
    var toolsets by remember { mutableStateOf(hermes.getToolsets()) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(toolsets) { ts ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(ts.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            ts.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = ts.enabled,
                        onCheckedChange = { newEnabled ->
                            hermes.setToolsetEnabled(ts.name, newEnabled)
                            toolsets = hermes.getToolsets()
                        },
                    )
                }
            }
        }
    }
}
