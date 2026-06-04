package com.nousresearch.hermes.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * ChatEmptyState — shown when the user opens the Chat tab with
 * no messages. A title + hint + 2×3 grid of six suggestion
 * chips; tapping a chip fires the prompt.
 */
@Composable
fun ChatEmptyState(
    onChipClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val suggestions = listOf(
        "Summarise today's news",
        "Write a haiku about debugging",
        "Explain Compose state to me",
        "Plan a weekend trip to Kyoto",
        "Draft a polite OOO reply",
        "Brainstorm a name for my app",
    )
    Box(
        modifier = modifier.padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "Start a conversation",
                style = MaterialTheme.typography.headlineMedium,
            )
            Text(
                text = "Pick a starter — or type your own.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                items(suggestions) { s ->
                    AssistChip(
                        onClick = { onChipClick(s) },
                        label = { Text(s) },
                    )
                }
            }
        }
    }
}
