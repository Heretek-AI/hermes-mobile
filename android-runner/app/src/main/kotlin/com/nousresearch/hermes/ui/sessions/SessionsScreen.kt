package com.nousresearch.hermes.ui.sessions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.nousresearch.hermes.HermesApi
import com.nousresearch.hermes.chat.SessionSummary
import com.nousresearch.hermes.ui.chat.MessageRow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * SessionsScreen — lists local chat sessions. Tapping a session
 * navigates to [SessionDetailScreen] which rehydrates the message
 * list. Phase B v1: a simple list with a delete affordance.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionsScreen(hermes: HermesApi) {
    val viewModel: SessionsViewModel = viewModel(
        factory = viewModelFactory { initializer { SessionsViewModel(hermes) } },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()

    val selectedId = state.selectedSessionId
    if (selectedId != null) {
        SessionDetailScreen(
            state = state,
            onBack = viewModel::clearSelection,
            onDelete = { viewModel.deleteSession(selectedId) },
        )
        return
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Sessions") }) },
    ) { padding ->
        when {
            state.isLoading -> Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
            state.error != null -> Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) { Text(state.error!!) }
            state.sessions.isEmpty() -> EmptyState(modifier = Modifier.padding(padding))
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.sessions, key = { it.id }) { s ->
                    SessionCard(s) { viewModel.selectSession(s.id) }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionDetailScreen(
    state: SessionsViewModel.State,
    onBack: () -> Unit,
    onDelete: () -> Unit,
) {
    val title = state.sessions.firstOrNull { it.id == state.selectedSessionId }
        ?.let { formatTitle(it) } ?: "Session"
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete")
                    }
                },
            )
        },
    ) { padding ->
        if (state.selectedMessages.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) { Text("No messages in this session.") }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.selectedMessages, key = { it.id }) { msg ->
                    MessageRow(msg)
                }
            }
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "No sessions yet",
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                "Sessions appear here after you send your first chat message.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SessionCard(session: SessionSummary, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = formatTitle(session),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "${session.messageCount} msg",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (session.preview.isNotBlank()) {
                Text(
                    text = session.preview,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
            Text(
                text = formatTimestamp(session.startedAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun formatTitle(session: SessionSummary): String {
    if (!session.title.isNullOrBlank()) return session.title
    val firstLine = session.preview.lineSequence().firstOrNull().orEmpty().take(48)
    return if (firstLine.isBlank()) "Session ${session.id.take(8)}" else firstLine
}

private fun formatTimestamp(epochMs: Long): String {
    if (epochMs <= 0) return ""
    val fmt = SimpleDateFormat("MMM d, yyyy 'at' h:mm a", Locale.getDefault())
    return fmt.format(Date(epochMs))
}
