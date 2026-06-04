package com.nousresearch.hermes.ui.soul

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
 * SoulScreen — markdown editor for the active profile's soul.
 *
 * Loads via [HermesApi.readSoul] on first render. The user can
 * edit the markdown and tap "Save" to call [HermesApi.writeSoul],
 * or tap "Reset to default" to call [HermesApi.resetSoul].
 */
@Composable
fun SoulScreen(hermes: HermesApi) {
    val initial = remember { hermes.readSoul() }
    var text by remember { mutableStateOf(initial.content) }
    var savedMessage by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Soul", style = MaterialTheme.typography.headlineSmall)
        Text(
            "The agent's soul — its core identity and style.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("Markdown") },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = true),
            textStyle = MaterialTheme.typography.bodyMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    if (hermes.writeSoul(text)) {
                        savedMessage = "Saved"
                    } else {
                        savedMessage = "Save failed"
                    }
                },
                modifier = Modifier.weight(1f),
            ) { Text("Save") }
            OutlinedButton(
                onClick = {
                    if (hermes.resetSoul()) {
                        text = hermes.readSoul().content
                        savedMessage = "Reset to default"
                    }
                },
                modifier = Modifier.weight(1f),
            ) { Text("Reset to default") }
        }
        savedMessage?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
