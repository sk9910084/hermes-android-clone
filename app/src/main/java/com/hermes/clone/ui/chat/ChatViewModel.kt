package com.hermes.clone.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.clone.AppGraph
import com.hermes.clone.agent.AgentEvent
import com.hermes.clone.agent.ErrorKind
import com.hermes.clone.data.AppSettings
import com.hermes.clone.data.MessageEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** An error shown as a bubble at the bottom of the chat. Never contains a stack trace. */
data class ChatError(val kind: ErrorKind, val message: String, val retryable: Boolean) {
    /** Problems the user fixes in Settings (key, model, URL). */
    val needsSettings: Boolean
        get() = kind == ErrorKind.NO_API_KEY || kind == ErrorKind.NO_MODEL ||
            kind == ErrorKind.BAD_URL || kind == ErrorKind.UNAUTHORIZED
}

/** Transient state of the running turn. Stored messages come from Room, not from here. */
data class ChatUi(
    val running: Boolean = false,
    val status: String = "",
    val streaming: String = "",
    val notice: String? = null,
    val error: ChatError? = null,
)

class ChatViewModel(
    private val graph: AppGraph,
    initialSessionId: String?,
) : ViewModel() {
    private val repo = graph.repository
    private val _sessionId = MutableStateFlow(initialSessionId)
    private var job: Job? = null

    /** The text field. Compose state (not a Flow) so the cursor never jumps. */
    var draft by mutableStateOf("")

    private val _ui = MutableStateFlow(ChatUi())
    val ui: StateFlow<ChatUi> = _ui.asStateFlow()

    val settings: StateFlow<AppSettings> = graph.settings.settings

    @OptIn(ExperimentalCoroutinesApi::class)
    val messages: StateFlow<List<MessageEntity>> = _sessionId
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else repo.messages(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val title: StateFlow<String> = combine(_sessionId, repo.sessions) { id, list ->
        list.firstOrNull { it.id == id }?.title ?: DEFAULT_TITLE
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DEFAULT_TITLE)

    init {
        // Picks the chat to show: the one from the route, else the latest, else a new one.
        // Also recovers when the open chat is deleted from the Sessions screen.
        viewModelScope.launch {
            repo.sessions.collect { list ->
                val id = _sessionId.value
                if (id == null || list.none { it.id == id }) {
                    job?.cancel()
                    _ui.value = ChatUi()
                    _sessionId.value = (list.firstOrNull() ?: repo.createSession()).id
                }
            }
        }
    }

    fun send() {
        val id = _sessionId.value ?: return
        val text = draft.trim()
        if (text.isEmpty() || _ui.value.running) return
        start(id, text)
    }

    /** Retry after an error: continues the stored conversation, nothing is stored twice. */
    fun retry() {
        val id = _sessionId.value ?: return
        if (_ui.value.running) return
        start(id, null)
    }

    /** The Stop button: cancels the model call; the loop saves what was already streamed. */
    fun stop() {
        job?.cancel()
    }

    /** Id of a chat to open for "New chat"; reuses the current one when it is still empty. */
    suspend fun newChatId(): String {
        val current = _sessionId.value
        if (current != null && !_ui.value.running && repo.history(current).isEmpty()) return current
        return repo.createSession().id
    }

    fun currentSessionId(): String? = _sessionId.value

    private fun start(id: String, text: String?) {
        if (job?.isActive == true) return
        _ui.value = ChatUi(running = true, status = "Thinking\u2026")
        job = viewModelScope.launch {
            try {
                graph.agent.run(id, text).collect { handle(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update {
                    it.copy(error = ChatError(ErrorKind.UNKNOWN, "Something went wrong. Try again.", true))
                }
            } finally {
                _ui.update { it.copy(running = false, status = "", streaming = "") }
            }
        }
    }

    private fun handle(event: AgentEvent) {
        when (event) {
            AgentEvent.UserMessageSaved -> draft = ""
            is AgentEvent.ModelCall -> _ui.update { it.copy(status = "Thinking\u2026", streaming = "") }
            is AgentEvent.Delta -> _ui.update { it.copy(status = "", streaming = it.streaming + event.text) }
            is AgentEvent.ToolStarted -> _ui.update { it.copy(status = "Using ${event.name}\u2026") }
            is AgentEvent.ToolFinished -> Unit
            is AgentEvent.ContextCompacted -> _ui.update { it.copy(notice = event.note) }
            AgentEvent.TurnSaved -> _ui.update { it.copy(streaming = "") }
            is AgentEvent.Completed -> _ui.update { it.copy(status = "") }
            is AgentEvent.Failed -> _ui.update {
                it.copy(error = ChatError(event.kind, event.message, event.retryable), status = "")
            }
        }
    }

    private companion object {
        const val DEFAULT_TITLE = "Hermes Clone"
    }
}
