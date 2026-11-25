package com.hermes.clone.ui.todos

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.clone.AppGraph
import kotlinx.coroutines.launch

class TodosViewModel(graph: AppGraph) : ViewModel() {
    private val repo = graph.repository

    val todos = repo.todos

    fun add(content: String) {
        viewModelScope.launch { repo.addTodo(content.trim()) }
    }

    fun setStatus(id: Long, status: String) {
        viewModelScope.launch { repo.setTodoStatus(id, status) }
    }

    fun rename(id: Long, content: String) {
        viewModelScope.launch { repo.renameTodo(id, content.trim()) }
    }

    fun delete(id: Long) {
        viewModelScope.launch { repo.deleteTodo(id) }
    }
}
