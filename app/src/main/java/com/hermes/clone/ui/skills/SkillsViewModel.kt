package com.hermes.clone.ui.skills

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.clone.AppGraph
import com.hermes.clone.data.Skill
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface SkillsUiState {
    data object Loading : SkillsUiState
    data object Empty : SkillsUiState
    data class Ready(val skills: List<Skill>) : SkillsUiState
}

class SkillsViewModel(graph: AppGraph) : ViewModel() {
    private val store = graph.skills

    private val _state = MutableStateFlow<SkillsUiState>(SkillsUiState.Loading)
    val state: StateFlow<SkillsUiState> = _state.asStateFlow()

    val enabled: StateFlow<Set<String>> = store.enabled.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        store.enabled.value,
    )

    init {
        viewModelScope.launch {
            val skills = store.all()
            _state.value = if (skills.isEmpty()) SkillsUiState.Empty else SkillsUiState.Ready(skills)
        }
    }

    fun toggle(name: String, enable: Boolean) {
        store.setEnabled(name, enable)
    }

    /** Looks up a single skill by name (suspend — reads from asset cache). */
    suspend fun findSkill(name: String): Skill? = store.find(name)
}
