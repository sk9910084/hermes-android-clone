package com.hermes.clone.ui.memory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.clone.AppGraph
import kotlinx.coroutines.launch

class MemoriesViewModel(graph: AppGraph) : ViewModel() {
    private val repo = graph.repository

    val memories = repo.memories

    fun add(content: String) {
        viewModelScope.launch { repo.saveMemory(content.trim(), source = "user") }
    }

    fun update(id: Long, content: String) {
        viewModelScope.launch { repo.updateMemory(id, content.trim()) }
    }

    fun delete(id: Long) {
        viewModelScope.launch { repo.deleteMemory(id) }
    }
}
