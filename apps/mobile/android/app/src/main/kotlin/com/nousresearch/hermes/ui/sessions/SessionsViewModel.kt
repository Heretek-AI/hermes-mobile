package com.nousresearch.hermes.ui.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nousresearch.hermes.HermesApi
import com.nousresearch.hermes.chat.MessageEntity
import com.nousresearch.hermes.chat.SessionSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * SessionsViewModel — drives the Sessions tab. Holds the list
 * of local chat sessions and the messages for the currently-
 * selected session.
 */
class SessionsViewModel(private val hermes: HermesApi) : ViewModel() {

    data class State(
        val sessions: List<SessionSummary> = emptyList(),
        val isLoading: Boolean = false,
        val selectedSessionId: String? = null,
        val selectedMessages: List<MessageEntity> = emptyList(),
        val error: String? = null,
    )

    private val _state = MutableStateFlow(State(isLoading = true))
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _state.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            try {
                val sessions = hermes.listSessions(limit = 100)
                _state.update { it.copy(sessions = sessions, isLoading = false) }
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    fun selectSession(sessionId: String) {
        if (_state.value.selectedSessionId == sessionId) return
        _state.update { it.copy(selectedSessionId = sessionId, isLoading = true) }
        viewModelScope.launch {
            try {
                val msgs = hermes.getSessionMessages(sessionId)
                _state.update { it.copy(selectedMessages = msgs, isLoading = false) }
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    fun clearSelection() {
        _state.update { it.copy(selectedSessionId = null, selectedMessages = emptyList()) }
    }

    fun deleteSession(sessionId: String) {
        viewModelScope.launch {
            val ok = hermes.deleteSession(sessionId)
            if (ok) {
                _state.update { s ->
                    s.copy(
                        sessions = s.sessions.filter { it.id != sessionId },
                        selectedSessionId = if (s.selectedSessionId == sessionId) null else s.selectedSessionId,
                        selectedMessages = if (s.selectedSessionId == sessionId) emptyList() else s.selectedMessages,
                    )
                }
            }
        }
    }
}
