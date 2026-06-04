package com.nousresearch.hermes.ui.chat

import android.content.Context
import android.widget.TextView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.nousresearch.hermes.chat.MessageEntity
import io.noties.markwon.Markwon

/**
 * MessageRow — a single chat bubble. User messages are
 * right-aligned with the primary container color; assistant
 * messages are left-aligned with the surface variant color.
 *
 * The body is rendered with Markwon for markdown (the de-facto
 * Kotlin equivalent of the desktop's `react-markdown` +
 * `remark-gfm` + `highlight.js` stack).
 */
@Composable
fun MessageRow(message: MessageEntity) {
    val isUser = message.kind == "user"
    val arrangement = if (isUser) Arrangement.End else Arrangement.Start
    val bubbleColor = if (isUser) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val onBubbleColor = if (isUser) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val text = when (message.kind) {
        "reasoning" -> message.text.orEmpty()
        else -> message.content.orEmpty()
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
    ) {
        Box(
            modifier = Modifier
                .padding(vertical = 4.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(bubbleColor)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            if (isUser) {
                Text(
                    text = text,
                    color = onBubbleColor,
                )
            } else {
                // Assistant messages get the full Markwon
                // treatment (markdown, gfm tables, strikethrough,
                // linkify). The Markwon instance is held in
                // remember to avoid re-construction per recompose.
                MarkdownText(text = text)
            }
        }
    }
}

@Composable
private fun MarkdownText(text: String) {
    val context = LocalContext.current
    val markwon = remember(context) { Markwon.create(context) }
    AndroidView(
        modifier = Modifier.fillMaxWidth(),
        factory = { ctx ->
            TextView(ctx).apply {
                setTextColor(android.graphics.Color.TRANSPARENT)
            }
        },
        update = { tv ->
            markwon.setMarkdown(tv, text)
        },
    )
}
