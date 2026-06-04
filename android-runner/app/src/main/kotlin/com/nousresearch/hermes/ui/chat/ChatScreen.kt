package com.nousresearch.hermes.ui.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.nousresearch.hermes.HermesApi
import com.nousresearch.hermes.chat.MessageEntity

/**
 * ChatScreen — the root Composable for the Chat tab.
 *
 * Phase 5 polish: long-press on a chat bubble opens a
 * [MessageContextMenu] with Copy + Select actions.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(hermes: HermesApi) {
    val viewModel: ChatViewModel = viewModel(
        factory = viewModelFactory {
            initializer { ChatViewModel(hermes) }
        },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    var contextMenuFor by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(state.messages.size, state.isLoading) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.size - 1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Hermes") })
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
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onClick = {},
                                    onLongClick = { contextMenuFor = msg.id },
                                ),
                        ) {
                            MessageRow(msg)
                        }
                    }
                    if (state.isLoading) {
                        item { TypingIndicator() }
                    }
                }
            }
            // Phase 8: render the chat error so a failed send
            // doesn't show an infinite typing indicator. Tapping
            // Dismiss clears the field; the user can retry.
            state.errorMessage?.let { msg ->
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    shape = MaterialTheme.shapes.small,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(8.dp),
                    ) {
                        Text(
                            text = msg,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { viewModel.dismissError() }) {
                            Text("Dismiss")
                        }
                    }
                }
            }
            ChatInput(
                value = state.currentInput,
                isLoading = state.isLoading,
                isRecording = state.isRecording,
                attachments = state.attachments,
                onValueChange = viewModel::onInputChanged,
                onSend = viewModel::send,
                onStop = viewModel::abort,
                onStartVoice = viewModel::startVoiceCapture,
                onStopVoice = viewModel::stopVoiceCapture,
                onCancelVoice = viewModel::cancelVoiceCapture,
                onAttachFile = viewModel::attachFile,
                onRemoveAttachment = viewModel::removeAttachment,
                hermes = hermes,
            )
        }
    }

    contextMenuFor?.let { id ->
        MessageContextMenu(
            expanded = true,
            onDismiss = { contextMenuFor = null },
            onCopy = { viewModel.copyBubbleText(id) },
            onSelect = { viewModel.selectBubble(id) },
        )
    }
}
