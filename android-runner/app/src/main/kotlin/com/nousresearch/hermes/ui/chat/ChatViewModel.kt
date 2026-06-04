package com.nousresearch.hermes.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nousresearch.hermes.HermesApi
import com.nousresearch.hermes.chat.MessageEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * ChatViewModel — drives the Chat screen. Holds the in-flight
 * message list, the streaming chunk buffer, and the loading
 * flag.
 *
 * Phase 4 wiring:
 * - `sendMessage` calls HermesApi.sendMessage on a viewModelScope
 *   coroutine; chunks accumulate into the active assistant bubble.
 * - The `isLoading` flag flips to true on send, back to false
 *   on chatDone / chatError / abortChat.
 * - On every `chatChunk` event, the in-flight assistant message
 *   gets the chunk appended; the LazyColumn re-renders.
 */
class ChatViewModel(private val hermes: HermesApi) : ViewModel() {

    data class State(
        val messages: List<MessageEntity> = emptyList(),
        val isLoading: Boolean = false,
        val currentInput: String = "",
        val activeSessionId: String? = null,
        val currentModel: String = "gpt-4o-mini",
        val currentProvider: String = "openai",
        val errorMessage: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        // Stream chunks → append to the in-flight assistant
        // bubble. The "active assistant" is the last message in
        // the list whose kind is "assistant" and which is being
        // streamed (we set isLoading while it accumulates).
        viewModelScope.launch {
            hermes.chatChunk.collect { chunk ->
                _state.update { s ->
                    val msgs = s.messages.toMutableList()
                    if (msgs.isNotEmpty() && msgs.last().kind == "assistant" && s.isLoading) {
                        val last = msgs.removeAt(msgs.lastIndex)
                        msgs.add(last.copy(content = (last.content ?: "") + chunk))
                    } else {
                        // First chunk of a new assistant turn.
                        msgs.add(
                            MessageEntity(
                                sessionId = s.activeSessionId ?: "",
                                kind = "assistant",
                                role = "assistant",
                                content = chunk,
                                timestamp = System.currentTimeMillis(),
                            ),
                        )
                    }
                    s.copy(messages = msgs)
                }
            }
        }
        viewModelScope.launch {
            hermes.chatDone.collect { _ ->
                _state.update { it.copy(isLoading = false) }
            }
        }
        viewModelScope.launch {
            hermes.chatError.collect { msg ->
                _state.update { it.copy(isLoading = false, errorMessage = msg) }
            }
        }
    }

    fun onInputChanged(text: String) {
        _state.update { it.copy(currentInput = text) }
    }

    fun send() {
        val text = _state.value.currentInput.trim()
        if (text.isEmpty() || _state.value.isLoading) return
        val sessionId = _state.value.activeSessionId ?: UUID.randomUUID().toString()
        // Optimistically insert the user message into the list
        // so the UI updates immediately. HermesApi.sendMessage
        // also writes the row to the DB; the optimistic insert
        // gets replaced on the next DB refresh (Phase D wires
        // Room's Flow as the source of truth — for now the
        // in-memory list is the source of truth and the DB
        // persists between sessions).
        _state.update {
            it.copy(
                messages = it.messages + MessageEntity(
                    sessionId = sessionId,
                    kind = "user",
                    role = "user",
                    content = text,
                    timestamp = System.currentTimeMillis(),
                ),
                currentInput = "",
                isLoading = true,
                activeSessionId = sessionId,
            )
        }
        viewModelScope.launch {
            hermes.sendMessage(message = text, resumeSessionId = sessionId)
        }
    }

    fun abort() {
        hermes.abortChat()
        _state.update { it.copy(isLoading = false) }
    }
}
