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
 * message list, the streaming chunk buffer, the loading flag,
 * voice capture state, and attachment state.
 *
 * Phase 5 wiring (in addition to Phase 4):
 * - Voice capture: [startVoiceCapture] calls HermesApi
 *   (which checks RECORD_AUDIO permission); on success
 *   transitions to Recording. [stopVoiceCapture] reads the
 *   recorded bytes and calls [HermesApi.transcribeAudio] →
 *   the transcript becomes the new chat input.
 * - Attachments: [attachFile] calls [HermesApi.stageAttachment]
 *   which writes the base64-decoded bytes to a per-session
 *   staging dir. The next send includes the paths in the
 *   message.
 * - Context menu: long-press on a chat bubble emits
 *   [HermesApi.onContextMenuCopyChat] / [onContextMenuSelectBubble].
 * - Deep link: [handleDeepLink] inspects hermes:// URIs and
 *   routes chat/skill URIs to the right screen.
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
        val isRecording: Boolean = false,
        val attachments: List<String> = emptyList(),
        val transcript: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var voiceBase64: String = ""
    private var voiceMime: String = "audio/webm"

    init {
        // Stream chunks → append to the in-flight assistant
        // bubble.
        viewModelScope.launch {
            hermes.chatChunk.collect { chunk ->
                _state.update { s ->
                    val msgs = s.messages.toMutableList()
                    if (msgs.isNotEmpty() && msgs.last().kind == "assistant" && s.isLoading) {
                        val last = msgs.removeAt(msgs.lastIndex)
                        msgs.add(last.copy(content = (last.content ?: "") + chunk))
                    } else {
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
        // Phase 5: when the user shares text from another app,
        // the MainActivity onNewIntent emits to sharedText. We
        // pre-fill the chat input with the shared text.
        viewModelScope.launch {
            hermes.sharedText.collect { text ->
                _state.update { it.copy(currentInput = text) }
            }
        }
        // Phase 5: deep links route to chat by setting the
        // session id (the URI's last path segment).
        viewModelScope.launch {
            hermes.deepLink.collect { uri ->
                handleDeepLink(uri)
            }
        }
    }

    fun onInputChanged(text: String) {
        _state.update { it.copy(currentInput = text) }
    }

    fun send() {
        val text = _state.value.currentInput.trim()
        if ((text.isEmpty() && _state.value.attachments.isEmpty()) || _state.value.isLoading) return
        val sessionId = _state.value.activeSessionId ?: UUID.randomUUID().toString()
        // Compose the user message body: text + attachment list
        val body = buildString {
            if (text.isNotEmpty()) append(text)
            if (_state.value.attachments.isNotEmpty()) {
                if (isNotEmpty()) append("\n\n")
                append("Attached:\n")
                _state.value.attachments.forEach { append("  - file://$it\n") }
            }
        }
        _state.update {
            it.copy(
                messages = it.messages + MessageEntity(
                    sessionId = sessionId,
                    kind = "user",
                    role = "user",
                    content = body,
                    timestamp = System.currentTimeMillis(),
                ),
                currentInput = "",
                isLoading = true,
                activeSessionId = sessionId,
                attachments = emptyList(),
            )
        }
        viewModelScope.launch {
            hermes.sendMessage(message = body, resumeSessionId = sessionId)
        }
    }

    fun abort() {
        hermes.abortChat()
        _state.update { it.copy(isLoading = false) }
    }

    // ── Phase 5: voice capture ────────────────────────────────

    /** Start a voice recording. The actual MediaRecorder work
     *  is delegated to the system intent (ACTION_RECORD_SOUND)
     *  for v1; the returned URI is read back to base64. */
    fun startVoiceCapture() {
        val start = hermes.startVoiceCapture() ?: run {
            _state.update { it.copy(errorMessage = "RECORD_AUDIO permission denied") }
            return
        }
        _state.update { it.copy(isRecording = true) }
        // Mark the start of the recording; the actual capture
        // happens via the system recorder launched from the
        // ChatScreen. We track a session id in the ViewModel
        // for stopVoiceCapture.
        voiceSessionId = start.id
    }

    private var voiceSessionId: String? = null

    fun stopVoiceCapture(base64: String, mimeType: String) {
        voiceBase64 = base64
        voiceMime = mimeType
        _state.update { it.copy(isRecording = false) }
        viewModelScope.launch {
            val sid = voiceSessionId ?: return@launch
            val result = hermes.stopVoiceCapture(sid, base64, mimeType)
            // Transcribe via the gateway; populate currentInput
            val transcript = hermes.transcribeAudio(
                bytes = android.util.Base64.decode(result.base64, android.util.Base64.DEFAULT),
                mimeType = result.mimeType,
            )
            _state.update { it.copy(currentInput = transcript, transcript = transcript) }
        }
    }

    fun cancelVoiceCapture() {
        _state.update { it.copy(isRecording = false) }
    }

    // ── Phase 5: attachments ──────────────────────────────────

    fun attachFile(name: String, base64: String) {
        val sessionId = _state.value.activeSessionId ?: UUID.randomUUID().toString()
        val path = hermes.stageAttachment(sessionId, name, base64)
        _state.update {
            it.copy(
                attachments = it.attachments + path,
                activeSessionId = sessionId,
            )
        }
    }

    fun removeAttachment(path: String) {
        _state.update { it.copy(attachments = it.attachments.filter { it != path }) }
    }

    // ── Phase 5: context menu ─────────────────────────────────

    fun copyBubbleText(messageId: Long) {
        val msg = _state.value.messages.firstOrNull { it.id == messageId } ?: return
        val text = msg.content ?: msg.text ?: return
        hermes.copyToClipboard(text)
        viewModelScope.launch { hermes.onContextMenuCopyChat(text) }
    }

    fun selectBubble(messageId: Long) {
        viewModelScope.launch { hermes.onContextMenuSelectBubble(messageId.toString()) }
    }

    // ── Phase 5: deep link ────────────────────────────────────

    private fun handleDeepLink(uri: String) {
        // hermes://chat/<sessionId> — resume that session
        // hermes://skill/<name> — open skill detail (Phase 5 v1: no-op)
        val parsed = android.net.Uri.parse(uri)
        when (parsed.host) {
            "chat" -> {
                val sid = parsed.lastPathSegment
                if (sid != null) {
                    _state.update { it.copy(activeSessionId = sid) }
                }
            }
            // skill / others are no-ops in v1; Phase 5 final
            // wires skill -> SkillsScreen nav.
        }
    }
}
