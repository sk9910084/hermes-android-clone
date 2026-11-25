package com.hermes.clone.ui.todos

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hermes.clone.data.TodoEntity
import com.hermes.clone.data.TodoStatus
import com.hermes.clone.ui.graphViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodosScreen(onBack: () -> Unit) {
    val vm = graphViewModel { TodosViewModel(it) }
    val todos by vm.todos.collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()

    var showAdd by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<TodoEntity?>(null) }
    var deleteTarget by remember { mutableStateOf<TodoEntity?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("To-do list") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAdd = true }) {
                Icon(Icons.Default.Add, contentDescription = "Add task")
            }
        },
    ) { padding ->
        if (todos.isEmpty()) {
            Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No tasks yet.", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "The agent can add tasks here, or use the + button yourself.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(bottom = 88.dp, top = 8.dp),
            ) {
                items(todos, key = { it.id }) { todo ->
                    TodoRow(
                        todo = todo,
                        onToggleDone = { done ->
                            scope.launch {
                                vm.setStatus(
                                    todo.id,
                                    if (done) TodoStatus.COMPLETED else TodoStatus.PENDING,
                                )
                            }
                        },
                        onEdit = { editTarget = todo },
                        onDelete = { deleteTarget = todo },
                        onStatusChange = { status -> scope.launch { vm.setStatus(todo.id, status) } },
                    )
                }
            }
        }
    }

    // Add dialog
    if (showAdd) {
        var draft by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("New task") },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    label = { Text("Task") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch { vm.add(draft) }
                        showAdd = false
                    },
                    enabled = draft.isNotBlank(),
                ) { Text("Add") }
            },
            dismissButton = {
                TextButton(onClick = { showAdd = false }) { Text("Cancel") }
            },
        )
    }

    // Edit dialog
    editTarget?.let { todo ->
        var draft by remember(todo.id) { mutableStateOf(todo.content) }
        AlertDialog(
            onDismissRequest = { editTarget = null },
            title = { Text("Edit task") },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    label = { Text("Task") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch { vm.rename(todo.id, draft) }
                        editTarget = null
                    },
                    enabled = draft.isNotBlank(),
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { editTarget = null }) { Text("Cancel") }
            },
        )
    }

    // Delete confirmation
    deleteTarget?.let { todo ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete task?") },
            text = { Text("\"${todo.content}\" will be deleted.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch { vm.delete(todo.id) }
                        deleteTarget = null
                    },
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun TodoRow(
    todo: TodoEntity,
    onToggleDone: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onStatusChange: (String) -> Unit,
) {
    val done = todo.status == TodoStatus.COMPLETED || todo.status == TodoStatus.CANCELLED
    var statusMenuOpen by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Checkbox(
            checked = done,
            onCheckedChange = onToggleDone,
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = todo.content,
                style = MaterialTheme.typography.bodyMedium.let {
                    if (done) it.copy(textDecoration = TextDecoration.LineThrough) else it
                },
                color = if (done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
            if (todo.status != TodoStatus.PENDING) {
                Text(
                    text = todo.status.replace('_', ' ').replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.labelSmall,
                    color = statusColor(todo.status),
                )
            }
        }
        Box {
            IconButton(onClick = { statusMenuOpen = true }) {
                Text("…", style = MaterialTheme.typography.labelLarge)
            }
            DropdownMenu(expanded = statusMenuOpen, onDismissRequest = { statusMenuOpen = false }) {
                TodoStatus.all.forEach { s ->
                    DropdownMenuItem(
                        text = { Text(s.replace('_', ' ').replaceFirstChar { it.uppercase() }) },
                        onClick = { statusMenuOpen = false; onStatusChange(s) },
                    )
                }
            }
        }
        IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, contentDescription = "Edit") }
        IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, contentDescription = "Delete") }
    }
}

@Composable
private fun statusColor(status: String) = when (status) {
    TodoStatus.COMPLETED -> MaterialTheme.colorScheme.primary
    TodoStatus.IN_PROGRESS -> MaterialTheme.colorScheme.tertiary
    TodoStatus.CANCELLED -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}
