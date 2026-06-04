package com.nousresearch.hermes.ui.memory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nousresearch.hermes.HermesApi
import com.nousresearch.hermes.memory.MemoryEntry
import com.nousresearch.hermes.memory.MemoryReadResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * MemoryViewModel — drives the Memory tab. Phase B v1: list the
 * entries parsed from `~/.hermes/profiles/default/memory.md`,
 * support add / edit / remove, and a search bar that filters
 * the visible entries by substring.
 */
class MemoryViewModel(private val hermes: HermesApi) : ViewModel() {

    data class State(
        val readResult: MemoryReadResult? = null,
        val entries: List<MemoryEntry> = emptyList(),
        val isLoading: Boolean = false,
        val error: String? = null,
        val searchQuery: String = "",
    ) {
        val filteredEntries: List<MemoryEntry>
            get() = if (searchQuery.isBlank()) entries
            else entries.filter {
                it.heading.contains(searchQuery, ignoreCase = true) ||
                    it.body.contains(searchQuery, ignoreCase = true)
            }
    }

    private val _state = MutableStateFlow(State(isLoading = true))
    val state: StateFlow<State> = _state.asStateFlow()

    init { refresh() }

    fun refresh() {
        _state.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            try {
                val r = hermes.readMemory()
                val parsed = hermes.parseMemoryEntriesPublic(r.memory.content)
                _state.update { it.copy(readResult = r, entries = parsed, isLoading = false) }
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    fun onSearchChanged(q: String) = _state.update { it.copy(searchQuery = q) }

    fun add(content: String) {
        viewModelScope.launch {
            val r = hermes.addMemoryEntry(content)
            if (r.success) refresh() else _state.update { it.copy(error = r.error) }
        }
    }

    fun update(index: Int, content: String) {
        viewModelScope.launch {
            val r = hermes.updateMemoryEntry(index, content)
            if (r.success) refresh() else _state.update { it.copy(error = r.error) }
        }
    }

    fun remove(index: Int) {
        viewModelScope.launch {
            val ok = hermes.removeMemoryEntry(index)
            if (ok) refresh()
        }
    }
}

/**
 * Tiny extension wrapper so the screen doesn't need to call a
 * private HermesApi member. The implementation lives on HermesApi
 * (kept private to that class) and re-exports via this public
 * forwarder.
 */
fun HermesApi.parseMemoryEntriesPublic(text: String): List<MemoryEntry> =
    this.parseMemoryEntriesForUi(text)
