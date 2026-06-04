package com.nousresearch.hermes.ui.chat

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * MessageContextMenu — Compose DropdownMenu shown on long-press
 * of a chat bubble. Phase 5 implements the two desktop actions:
 * - **Copy** — calls [ChatViewModel.copyBubbleText] which
 *   delegates to HermesApi.copyToClipboard + emits the
 *   `onContextMenuCopyChat` SharedFlow.
 * - **Select** — emits `onContextMenuSelectBubble` so the
 *   caller can wire a future bulk-select UI.
 */
@Composable
fun MessageContextMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onSelect: () -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = Modifier.padding(8.dp),
    ) {
        DropdownMenuItem(
            text = { Text("Copy") },
            onClick = {
                onCopy()
                onDismiss()
            },
        )
        DropdownMenuItem(
            text = { Text("Select") },
            onClick = {
                onSelect()
                onDismiss()
            },
        )
    }
}
