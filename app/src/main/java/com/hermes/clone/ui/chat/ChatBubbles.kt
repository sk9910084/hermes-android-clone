package com.hermes.clone.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hermes.clone.data.MessageEntity
import com.hermes.clone.data.Role

/** One visible row of the conversation, built from stored messages. */
sealed interface ChatRow {
    val key: String

    data class User(val id: Long, val text: String) : ChatRow {
        override val key = "m$id"
    }

    data class Assistant(val id: Long, val text: String) : ChatRow {
        override val key = "m$id"
    }

    data class Tool(val id: Long, val name: String, val result: String) : ChatRow {
        override val key = "m$id"
    }
}

/** Assistant messages that only carry tool calls (no text) are hidden; their tool rows show the work. */
fun buildRows(messages: List<MessageEntity>): List<ChatRow> = messages.mapNotNull { m ->
    when (m.role) {
        Role.USER -> ChatRow.User(m.id, m.display ?: m.content)
        Role.ASSISTANT -> if (m.content.isBlank()) null else ChatRow.Assistant(m.id, m.content)
        Role.TOOL -> ChatRow.Tool(m.id, m.toolName ?: "tool", m.content)
        else -> null
    }
}

@Composable
fun MessageBubble(text: String, isUser: Boolean) {
    val maxWidth = (LocalConfiguration.current.screenWidthDp * 0.88f).dp
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            color = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            contentColor = if (isUser) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.widthIn(max = maxWidth),
        ) {
            SelectionContainer {
                if (isUser) {
                    Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(12.dp))
                } else {
                    MarkdownText(text, modifier = Modifier.padding(12.dp))
                }
            }
        }
    }
}

@Composable
fun ToolRow(name: String, result: String) {
    var expanded by remember { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Build, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Used tool: $name",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            if (expanded) {
                Text(
                    text = result.take(TOOL_PREVIEW_CHARS) + if (result.length > TOOL_PREVIEW_CHARS) "\u2026" else "",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

@Composable
fun StatusRow(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp)) {
        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun NoticeRow(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        fontStyle = FontStyle.Italic,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
    )
}

@Composable
fun ErrorBubble(
    error: ChatError,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(error.message, style = MaterialTheme.typography.bodyMedium)
            Row {
                if (error.retryable) TextButton(onClick = onRetry) { Text("Retry") }
                if (error.needsSettings) TextButton(onClick = onOpenSettings) { Text("Open Settings") }
            }
        }
    }
}

private const val TOOL_PREVIEW_CHARS = 1_500
