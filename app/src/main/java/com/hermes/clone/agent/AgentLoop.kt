package com.hermes.clone.agent

import com.hermes.clone.data.DEFAULT_SESSION_TITLE
import com.hermes.clone.data.HermesRepository
import com.hermes.clone.data.MessageEntity
import com.hermes.clone.data.Provider
import com.hermes.clone.data.Role
import com.hermes.clone.data.SessionEntity
import com.hermes.clone.data.SettingsStore
import com.hermes.clone.data.SkillsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/**
 * The turn loop (spec section 2.1), reimplemented in Kotlin:
 *
 *   while (modelCalls < maxIterations):
 *       response = model(messages, tools)
 *       if response has tool calls: run them, store assistant + results, continue
 *       else: store the answer and finish
 *   (budget used up: one last call with tool_choice "none" so the model must answer)
 *
 * Cancelling the collecting coroutine is the Stop button: the HTTP call is cancelled, nothing
 * half-finished is stored, and any partial answer is saved with an "interrupted" marker.
 * Every failure becomes AgentEvent.Failed; nothing here throws to the UI.
 */
class AgentLoop(
    private val repo: HermesRepository,
    private val provider: ProviderClient,
    private val registry: ToolRegistry,
    private val settingsStore: SettingsStore,
    private val skills: SkillsStore,
    private val compression: Compression,
    private val isOnline: () -> Boolean,
) {

    /**
     * Runs one turn. Pass the user's text to start a new turn, or null to resume the stored
     * conversation after an error (the Retry button). Collect it in a cancellable coroutine.
     */
    fun run(sessionId: String, userText: String?): Flow<AgentEvent> = channelFlow {
        execute(sessionId, userText) { send(it) }
    }

    private suspend fun execute(
        sessionId: String,
        userText: String?,
        emit: suspend (AgentEvent) -> Unit,
    ) {
        val partial = StringBuilder()
        var userSaved = userText == null
        try {
            runTurn(sessionId, userText, emit, partial) { userSaved = true }
        } catch (e: CancellationException) {
            withContext(NonCancellable) { savePartial(sessionId, partial) }
            throw e
        } catch (e: Exception) {
            emit(
                AgentEvent.Failed(
                    kind = ErrorKind.UNKNOWN,
                    message = "Something went wrong while running the agent. Try again.",
                    retryable = true,
                    userMessageSaved = userSaved,
                ),
            )
        }
    }

    private suspend fun runTurn(
        sessionId: String,
        userText: String?,
        emit: suspend (AgentEvent) -> Unit,
        partial: StringBuilder,
        onUserSaved: () -> Unit,
    ) {
        val settings = settingsStore.current()
        val resuming = userText == null

        // ---- preflight: fail before anything is stored, so the draft is kept ----
        val problem = preflight(settings)
        if (problem != null) {
            emit(AgentEvent.Failed(problem.kind, problem.message, problem.retryable, userMessageSaved = resuming))
            return
        }
        if (repo.session(sessionId) == null) {
            emit(AgentEvent.Failed(ErrorKind.UNKNOWN, "This chat no longer exists.", false, userMessageSaved = resuming))
            return
        }

        if (userText != null) {
            val text = userText.trim()
            if (text.isEmpty()) return
            beginUserTurn(sessionId, text)
            onUserSaved()
            emit(AgentEvent.UserMessageSaved)
        } else {
            val last = repo.history(sessionId).lastOrNull()
            if (last == null || (last.role == Role.ASSISTANT && last.toolCallsJson == null)) {
                emit(AgentEvent.Completed(last?.content.orEmpty()))
                return
            }
        }

        val toolsChars = ContextBuilder.toolsChars(registry.specs)
        var iteration = 0
        var overflowRetried = false

        while (true) {
            val budgetUsed = iteration >= settings.maxIterations

            // ---- build the request (compressing first if we are near the limit) ----
            var session = repo.session(sessionId) ?: run {
                emit(AgentEvent.Failed(ErrorKind.UNKNOWN, "This chat no longer exists.", false, true))
                return
            }
            val compacted = compression.compactIfNeeded(session, settings, toolsChars)
            if (compacted.changed) {
                session = compacted.session
                compacted.note?.let { emit(AgentEvent.ContextCompacted(it)) }
            }
            var messages = ContextBuilder.toChatMessages(session, repo.history(sessionId))
            if (budgetUsed) messages = ContextBuilder.appendBudgetNotice(messages)

            // ---- one model call ----
            emit(AgentEvent.ModelCall(iteration + 1))
            partial.clear()
            val turn = try {
                provider.chat(
                    settings = settings,
                    messages = messages,
                    tools = registry.specs,
                    toolChoice = if (budgetUsed) "none" else null,
                    onDelta = { piece ->
                        partial.append(piece)
                        emit(AgentEvent.Delta(piece))
                    },
                )
            } catch (e: ProviderException) {
                partial.clear()
                if (e.kind == ErrorKind.CONTEXT_OVERFLOW && !overflowRetried) {
                    overflowRetried = true
                    val forced = compression.compactIfNeeded(session, settings, toolsChars, force = true)
                    if (forced.changed) {
                        forced.note?.let { emit(AgentEvent.ContextCompacted(it)) }
                        continue
                    }
                }
                emit(AgentEvent.Failed(e.kind, e.message, e.retryable, userMessageSaved = true))
                return
            }
            partial.clear()

            // ---- plain answer: done ----
            if (turn.toolCalls.isEmpty()) {
                if (turn.content.isBlank()) {
                    emit(
                        AgentEvent.Failed(
                            ErrorKind.EMPTY_REPLY,
                            "The model sent an empty reply. Try again, or choose a different model.",
                            retryable = true,
                            userMessageSaved = true,
                        ),
                    )
                    return
                }
                finish(sessionId, turn.content, emit)
                return
            }

            // ---- the model ignored "no more tools" at the end of the budget ----
            if (budgetUsed) {
                val text = turn.content.ifBlank {
                    "I used all ${settings.maxIterations} steps before I could finish. " +
                        "Tell me to continue and I will pick up from here."
                }
                finish(sessionId, text, emit)
                return
            }

            // ---- run the tools, then store the assistant message and all results together ----
            val calls = uniqueIds(turn.toolCalls.take(MAX_TOOL_CALLS_PER_TURN))
            val ctx = ToolContext(sessionId, settings)
            val results = ArrayList<MessageEntity>()
            for (call in calls) {
                val name = call.function.name
                emit(AgentEvent.ToolStarted(call.id, name, previewArguments(call.function.arguments)))
                val outcome = registry.execute(name, call.function.arguments, ctx)
                results += MessageEntity(
                    sessionId = sessionId,
                    role = Role.TOOL,
                    content = outcome.content,
                    toolCallId = call.id,
                    toolName = name,
                )
                emit(AgentEvent.ToolFinished(call.id, name, outcome.isError))
            }
            val assistant = MessageEntity(
                sessionId = sessionId,
                role = Role.ASSISTANT,
                content = turn.content,
                toolCallsJson = ContextBuilder.encodeToolCalls(calls.map { it.withValidArguments() }),
            )
            repo.addRound(assistant, results)
            emit(AgentEvent.TurnSaved)
            iteration++
        }
    }

    // ---- pieces ----------------------------------------------------------------------------

    private class Problem(val kind: ErrorKind, val message: String, val retryable: Boolean)

    private fun preflight(settings: com.hermes.clone.data.AppSettings): Problem? {
        if (!settings.hasApiKey && settings.provider != Provider.CUSTOM) {
            return Problem(
                ErrorKind.NO_API_KEY,
                "Add your API key in Settings, then send your message again.",
                retryable = false,
            )
        }
        if (settings.model.isBlank()) {
            return Problem(ErrorKind.NO_MODEL, "Choose a model in Settings first.", retryable = false)
        }
        if (!settings.baseUrl.startsWith("https://", ignoreCase = true)) {
            return Problem(
                ErrorKind.BAD_URL,
                "The base URL must start with https://. Check it in Settings.",
                retryable = false,
            )
        }
        if (!isOnline()) {
            return Problem(
                ErrorKind.OFFLINE,
                "You're offline. Your message is kept. Reconnect and send it again.",
                retryable = true,
            )
        }
        return null
    }

    /** Stores the user message, with memories (first turn) and newly enabled skills prepended. */
    private suspend fun beginUserTurn(sessionId: String, text: String) {
        val session = repo.session(sessionId) ?: return
        val firstTurn = repo.history(sessionId).isEmpty()
        val alreadyInjected = session.injectedSkills.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val pendingSkills = skills.enabledSkills().filter { ContextBuilder.skillKey(it.name) !in alreadyInjected }
        val memories = if (firstTurn) repo.recentMemories(ContextBuilder.MEMORY_COUNT) else emptyList()
        val turn = ContextBuilder.buildUserTurn(text, pendingSkills, memories)

        repo.addMessage(
            MessageEntity(
                sessionId = sessionId,
                role = Role.USER,
                content = turn.content,
                display = turn.display,
            ),
        )

        var updated: SessionEntity = session
        if (turn.injectedSkillNames.isNotEmpty()) {
            updated = updated.copy(injectedSkills = (alreadyInjected + turn.injectedSkillNames).joinToString(","))
        }
        if (session.title == DEFAULT_SESSION_TITLE) {
            updated = updated.copy(title = titleFrom(text))
        }
        if (updated != session) {
            repo.updateSession(updated.copy(updatedAt = System.currentTimeMillis()))
        }
    }

    private suspend fun finish(sessionId: String, text: String, emit: suspend (AgentEvent) -> Unit) {
        repo.addMessage(MessageEntity(sessionId = sessionId, role = Role.ASSISTANT, content = text))
        emit(AgentEvent.TurnSaved)
        emit(AgentEvent.Completed(text))
    }

    /** Stop pressed while an answer was streaming: keep what the user already saw. */
    private suspend fun savePartial(sessionId: String, partial: StringBuilder) {
        val text = partial.toString().trim()
        partial.clear()
        if (text.isEmpty()) return
        try {
            repo.addMessage(
                MessageEntity(sessionId = sessionId, role = Role.ASSISTANT, content = "$text\n\n[interrupted]"),
            )
        } catch (e: Exception) {
            // The chat may have been deleted meanwhile; nothing to save then.
        }
    }

    private fun titleFrom(text: String): String {
        val oneLine = text.replace(Regex("\\s+"), " ").trim()
        return if (oneLine.length <= TITLE_CHARS) oneLine else oneLine.take(TITLE_CHARS).trimEnd() + "\u2026"
    }

    private fun previewArguments(raw: String): String {
        val oneLine = raw.replace(Regex("\\s+"), " ").trim()
        return if (oneLine.length <= PREVIEW_CHARS) oneLine else oneLine.take(PREVIEW_CHARS) + "\u2026"
    }

    /** Some providers repeat a tool call id; ids must be unique inside one assistant message. */
    private fun uniqueIds(calls: List<ToolCall>): List<ToolCall> {
        val seen = HashSet<String>()
        return calls.mapIndexed { index, call ->
            var id = call.id.ifBlank { "call_$index" }
            var n = 1
            while (!seen.add(id)) id = "${call.id}_${n++}"
            if (id == call.id) call else call.copy(id = id)
        }
    }

    /** Stored arguments must be a JSON object, or the next request could be rejected. */
    private fun ToolCall.withValidArguments(): ToolCall {
        val valid = try {
            HermesJson.parseToJsonElement(function.arguments.ifBlank { "{}" }) is JsonObject
        } catch (e: IllegalArgumentException) {
            false
        }
        return if (valid) this else copy(function = function.copy(arguments = "{}"))
    }

    private companion object {
        const val MAX_TOOL_CALLS_PER_TURN = 8
        const val TITLE_CHARS = 40
        const val PREVIEW_CHARS = 80
    }
}
