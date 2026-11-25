package com.hermes.clone.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hermes.clone.data.Provider
import com.hermes.clone.ui.graphViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    sessionId: String?,
    onOpenChat: (String) -> Unit,
    onOpenSessions: () -> Unit,
    onOpenSkills: () -> Unit,
    onOpenMemories: () -> Unit,
    onOpenTodos: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val vm = graphViewModel(key = "chat:${sessionId ?: "latest"}") { ChatViewModel(it, sessionId) }
    val messages by vm.messages.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val title by vm.title.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var menuOpen by remember { mutableStateOf(false) }

    val rows = remember(messages) { buildRows(messages) }
    val listState = rememberLazyListState()
    val showStreaming = ui.streaming.isNotBlank()
    val showStatus = ui.running && !showStreaming
    val itemCount = rows.size + (if (showStreaming) 1 else 0) + (if (showStatus) 1 else 0) +
        (if (ui.notice != null) 1 else 0) + (if (ui.error != null) 1 else 0)

    // Follow the newest content unless the user is scrolling.
    LaunchedEffect(itemCount, ui.streaming.length, ui.status) {
        if (itemCount > 0 && !listState.isScrollInProgress) listState.scrollToItem(itemCount - 1)
    }

    val needsKey = !settings.hasApiKey && settings.provider != Provider.CUSTOM

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                actions = {
                    IconButton(onClick = {
                        scope.launch {
                            val id = vm.newChatId()
                            if (id != vm.currentSessionId()) onOpenChat(id)
                        }
                    }) { Icon(Icons.Default.Add, contentDescription = "New chat") }
                    IconButton(onClick = onOpenSessions) { Icon(Icons.Default.History, contentDescription = "Chats") }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(text = { Text("Skills") }, onClick = { menuOpen = false; onOpenSkills() })
                            DropdownMenuItem(text = { Text("Memories") }, onClick = { menuOpen = false; onOpenMemories() })
                            DropdownMenuItem(text = { Text("Todos") }, onClick = { menuOpen = false; onOpenTodos() })
                            DropdownMenuItem(text = { Text("Settings") }, onClick = { menuOpen = false; onOpenSettings() })
                        }
                    }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    OutlinedTextField(
                        value = vm.draft,
                        onValueChange = { vm.draft = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Message") },
                        maxLines = 6,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    )
                    if (ui.running) {
                        IconButton(onClick = vm::stop) { Icon(Icons.Default.Stop, contentDescription = "Stop") }
                    } else {
                        IconButton(onClick = vm::send, enabled = vm.draft.isNotBlank()) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                        }
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (needsKey) KeyBanner(onOpenSettings)
            if (rows.isEmpty() && !ui.running && ui.error == null) {
                EmptyChat(Modifier.weight(1f))
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(rows, key = { it.key }) { row ->
                        when (row) {
                            is ChatRow.User -> MessageBubble(row.text, isUser = true)
                            is ChatRow.Assistant -> MessageBubble(row.text, isUser = false)
                            is ChatRow.Tool -> ToolRow(row.name, row.result)
                        }
                    }
                    if (showStreaming) item(key = "streaming") { MessageBubble(ui.streaming, isUser = false) }
                    if (showStatus) item(key = "status") { StatusRow(ui.status.ifBlank { "Working\u2026" }) }
                    ui.notice?.let { note -> item(key = "notice") { NoticeRow(note) } }
                    ui.error?.let { error ->
                        item(key = "error") { ErrorBubble(error, onRetry = vm::retry, onOpenSettings = onOpenSettings) }
                    }
                }
            }
        }
    }
}

@Composable
private fun KeyBanner(onOpenSettings: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(12.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text("Add your API key in Settings to start chatting.", style = MaterialTheme.typography.bodyMedium)
            Text(
                "OpenRouter and Gemini both offer free models. The key stays on this phone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onOpenSettings) { Text("Open Settings") }
        }
    }
}

@Composable
private fun EmptyChat(modifier: Modifier) {
    Box(modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Hermes Clone", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Ask me anything. I can keep your to-do list, remember facts, search your past chats " +
                    "and use the skills you enable.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}
