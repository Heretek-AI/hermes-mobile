package com.nousresearch.hermes.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.nousresearch.hermes.HermesApi
import com.nousresearch.hermes.chat.MessageEntity

/**
 * ChatScreen — the root Composable for the Chat tab. Composes
 * the message list, the input row, and the empty state.
 *
 * Phase 4 scope:
 * - ✅ Message list (LazyColumn) with auto-scroll to bottom
 * - ✅ User / assistant bubbles (MessageRow with Markwon)
 * - ✅ Composer with send / stop / slash menu
 * - ✅ Empty state with 6 suggestion chips
 * - ❌ Voice input — stub (Phase D)
 * - ❌ Drag-and-drop file attachments — Phase D
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(hermes: HermesApi) {
    val viewModel: ChatViewModel = viewModel(
        factory = viewModelFactory {
            initializer { ChatViewModel(hermes) }
        },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    // Auto-scroll to bottom when a new message lands.
    LaunchedEffect(state.messages.size, state.isLoading) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.size - 1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Hermes") },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (state.messages.isEmpty() && !state.isLoading) {
                ChatEmptyState(
                    onChipClick = { text ->
                        viewModel.onInputChanged(text)
                        viewModel.send()
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.messages, key = { it.id.takeIf { id -> id != 0L } ?: it.hashCode().toLong() }) { msg ->
                        MessageRow(msg)
                    }
                    if (state.isLoading) {
                        item { TypingIndicator() }
                    }
                }
            }
            ChatInput(
                value = state.currentInput,
                isLoading = state.isLoading,
                onValueChange = viewModel::onInputChanged,
                onSend = viewModel::send,
                onStop = viewModel::abort,
            )
        }
    }
}
