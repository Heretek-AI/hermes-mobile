package com.nousresearch.hermes.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * ChatInput — auto-resizing text field with a send / stop
 * toggle button. The mic button is a Phase D stub (placeholder
 * for voice input).
 */
@Composable
fun ChatInput(
    value: String,
    isLoading: Boolean,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text("Message Hermes…") },
            maxLines = 6,
        )
        IconButton(onClick = { /* Phase D: voice input */ }) {
            Icon(Icons.Filled.Mic, contentDescription = "Voice")
        }
        IconButton(
            onClick = if (isLoading) onStop else onSend,
            enabled = isLoading || value.isNotBlank(),
        ) {
            Icon(
                imageVector = if (isLoading) Icons.Filled.Stop else Icons.Filled.Send,
                contentDescription = if (isLoading) "Stop" else "Send",
            )
        }
    }
}
