package com.nousresearch.hermes.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.net.Uri
import android.provider.MediaStore
import android.util.Base64
import com.nousresearch.hermes.HermesApi

/**
 * ChatInput — auto-resizing text field with send/stop, voice
 * capture, and attach-file affordances.
 *
 * Phase 5 wiring:
 * - Mic button: launches ACTION_RECORD_SOUND; the returned URI
 *   is read and base64-encoded, then passed to the
 *   ViewModel's `stopVoiceCapture`.
 * - Attach button: launches ACTION_GET_CONTENT (a generic
 *   file picker); the bytes are base64-encoded and passed
 *   to `attachFile`.
 * - Attachment chips below the input show the file paths
 *   with a × to remove.
 */
@Composable
fun ChatInput(
    value: String,
    isLoading: Boolean,
    isRecording: Boolean,
    attachments: List<String>,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onStartVoice: () -> Unit,
    onStopVoice: (String, String) -> Unit,
    onCancelVoice: () -> Unit,
    onAttachFile: (String, String) -> Unit,
    onRemoveAttachment: (String) -> Unit,
    hermes: HermesApi,
) {
    val context = LocalContext.current

    val voiceLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = result.data?.data ?: return@rememberLauncherForActivityResult
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@rememberLauncherForActivityResult
            val mime = context.contentResolver.getType(uri) ?: "audio/webm"
            val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            onStopVoice(b64, mime)
        } else {
            onCancelVoice()
        }
    }

    val attachLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        if (uri != null) {
            val name = uri.lastPathSegment ?: "attachment"
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@rememberLauncherForActivityResult
            val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            onAttachFile(name, b64)
        }
    }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        if (attachments.isNotEmpty()) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
            ) {
                items(attachments) { path ->
                    val name = path.substringAfterLast('/')
                    AssistChip(
                        onClick = { onRemoveAttachment(path) },
                        label = { Text(name) },
                        trailingIcon = {
                            Icon(Icons.Filled.Close, contentDescription = "Remove", modifier = Modifier.size(16.dp))
                        },
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            IconButton(onClick = { attachLauncher.launch("*/*") }) {
                Icon(Icons.Filled.AttachFile, contentDescription = "Attach file")
            }
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Message Hermes…") },
                maxLines = 6,
            )
            IconButton(
                onClick = {
                    if (isRecording) {
                        onCancelVoice()
                    } else {
                        onStartVoice()
                        voiceLauncher.launch(
                            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                            },
                        )
                    }
                },
            ) {
                Icon(
                    imageVector = Icons.Filled.Mic,
                    contentDescription = if (isRecording) "Recording…" else "Voice",
                    tint = if (isRecording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
            }
            IconButton(
                onClick = if (isLoading) onStop else onSend,
                enabled = isLoading || value.isNotBlank() || attachments.isNotEmpty(),
            ) {
                Icon(
                    imageVector = if (isLoading) Icons.Filled.Stop else Icons.Filled.Send,
                    contentDescription = if (isLoading) "Stop" else "Send",
                )
            }
        }
    }
}
