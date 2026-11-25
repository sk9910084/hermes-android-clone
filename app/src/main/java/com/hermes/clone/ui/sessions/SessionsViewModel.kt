package com.hermes.clone.ui.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.clone.AppGraph
import kotlinx.coroutines.launch

class SessionsViewModel(graph: AppGraph) : ViewModel() {
    private val repo = graph.repository

    /** Live-updating list of all sessions, newest first. */
    val sessions = repo.sessions

    fun rename(sessionId: String, title: String) {
        viewModelScope.launch { repo.renameSession(sessionId, title) }
    }

    fun delete(sessionId: String) {
        viewModelScope.launch { repo.deleteSession(sessionId) }
    }
}
