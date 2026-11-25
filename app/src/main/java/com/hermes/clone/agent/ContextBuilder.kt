package com.hermes.clone.agent

import com.hermes.clone.data.MemoryEntity
import com.hermes.clone.data.MessageEntity
import com.hermes.clone.data.Role
import com.hermes.clone.data.SessionEntity
import com.hermes.clone.data.Skill
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer

/**
 * Turns stored rows into the exact message list sent to the model.
 *
 * Rules this file enforces (from the upstream design):
 *  - the system prompt is the one stored on the session, never rebuilt;
 *  - memories and skill instructions are prepended to a USER message, never to the system prompt;
 *  - roles strictly alternate (no two user messages in a row) and every tool call has a result;
 *  - the only mid-conversation change is context compression (see Compression).
 */
object ContextBuilder {
    const val SKILL_CHARS_EACH = 6_000
    const val SKILL_CHARS_TOTAL = 14_000
    const val MEMORY_COUNT = 20
    const val MEMORY_CHARS = 2_000

    /** Replaces an old tool result when context is trimmed. */
    const val TRIMMED_TOOL_RESULT = "[Older tool result removed to save space.]"

    private const val INTERRUPTED_RESULT = "(No result was recorded; this step was interrupted.)"
    private const val BUDGET_NOTICE =
        "\n\n[Notice from the app: the tool-call budget for this turn is used up. " +
            "Do not call more tools. Write your final answer now using what you have.]"

    private val callListSerializer = ListSerializer(ToolCall.serializer())

    /** A user turn ready to store: [content] goes to the model, [display] (if any) goes to the UI. */
    data class UserTurn(
        val content: String,
        val display: String?,
        val injectedSkillNames: List<String>,
    )

    /** Skill names are stored comma-separated on the session; keep them comma-free. */
    fun skillKey(name: String): String = name.replace(',', ' ').trim()

    /**
     * Prepends automatic context (saved memories, newly enabled skills) to the user's text.
     * Pass memories only for the first turn of a session so earlier context never changes.
     */
    fun buildUserTurn(text: String, skills: List<Skill>, memories: List<MemoryEntity>): UserTurn {
        val memoryLines = ArrayList<String>()
        var memoryChars = 0
        for (memory in memories) {
            val line = "- " + memory.content.replace('\n', ' ').trim()
            if (memoryChars + line.length > MEMORY_CHARS) break
            memoryLines += line
            memoryChars += line.length
        }

        val skillBlocks = ArrayList<String>()
        val skillNames = ArrayList<String>()
        var skillChars = 0
        for (skill in skills) {
            val body = skill.body.trim().let {
                if (it.length > SKILL_CHARS_EACH) {
                    it.take(SKILL_CHARS_EACH) + "\n[...shortened; call skill_view for the full text]"
                } else {
                    it
                }
            }
            val block = "### Skill: ${skill.name}\n${skill.description}\n\n$body"
            // A skill that does not fit is left for a later turn (it is not marked as injected).
            if (skillBlocks.isNotEmpty() && skillChars + block.length > SKILL_CHARS_TOTAL) continue
            skillBlocks += block
            skillChars += block.length
            skillNames += skillKey(skill.name)
        }

        if (memoryLines.isEmpty() && skillBlocks.isEmpty()) return UserTurn(text, null, emptyList())

        val sb = StringBuilder()
        sb.append("[Context supplied automatically by the app. The user did not write this block.]\n\n")
        if (memoryLines.isNotEmpty()) {
            sb.append("Saved memories (facts remembered from earlier chats):\n")
                .append(memoryLines.joinToString("\n"))
                .append("\n\n")
        }
        if (skillBlocks.isNotEmpty()) {
            sb.append("Enabled skills (read-only instructions; use them when relevant):\n\n")
                .append(skillBlocks.joinToString("\n\n"))
                .append("\n\n")
        }
        sb.append("[End of automatic context. The user's message follows.]\n\n").append(text)
        return UserTurn(sb.toString(), text, skillNames)
    }

    /** The full request body messages for one model call. */
    fun toChatMessages(session: SessionEntity, history: List<MessageEntity>): List<ChatMessage> {
        val converted = history
            .filter { it.id > session.summaryUptoId }
            .mapNotNull { it.toChatMessage() }
        val body = sanitize(converted).toMutableList()

        if (session.summary.isNotBlank()) {
            val prefix = "[Summary of the earlier part of this conversation, written by the app to save space]\n" +
                session.summary.trim() +
                "\n[End of summary. The conversation continues below.]\n\n"
            val first = body.firstOrNull()
            if (first != null && first.role == Role.USER) {
                body[0] = first.copy(content = prefix + first.content.orEmpty())
            } else {
                body.add(0, ChatMessage(Role.USER, prefix.trimEnd()))
            }
        }
        return listOf(ChatMessage(Role.SYSTEM, session.systemPrompt)) + body
    }

    /** Rough size of a request: about four characters per token. */
    fun estimateTokens(messages: List<ChatMessage>, toolsChars: Int = 0): Int {
        var chars = toolsChars
        for (message in messages) {
            chars += (message.content?.length ?: 0) + 16
            message.toolCalls?.forEach { chars += it.function.name.length + it.function.arguments.length + 24 }
        }
        return chars / 4 + 1
    }

    /** Size of the tool schemas in characters (they are part of every request). */
    fun toolsChars(specs: List<ToolSpec>): Int =
        try {
            HermesJson.encodeToString(ListSerializer(ToolSpec.serializer()), specs).length
        } catch (e: SerializationException) {
            specs.size * 400
        }

    /**
     * For the final "no more tools" call: the notice rides on the last tool result, in memory only
     * (never a synthetic user message and never stored).
     */
    fun appendBudgetNotice(messages: List<ChatMessage>): List<ChatMessage> {
        val last = messages.lastOrNull() ?: return messages
        if (last.role != Role.TOOL) return messages
        return messages.dropLast(1) + last.copy(content = last.content.orEmpty() + BUDGET_NOTICE)
    }

    fun decodeToolCalls(json: String?): List<ToolCall> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            HermesJson.decodeFromString(callListSerializer, json)
        } catch (e: SerializationException) {
            emptyList()
        } catch (e: IllegalArgumentException) {
            emptyList()
        }
    }

    fun encodeToolCalls(calls: List<ToolCall>): String =
        HermesJson.encodeToString(callListSerializer, calls)

    // ---- internals -------------------------------------------------------------------------

    private fun MessageEntity.toChatMessage(): ChatMessage? =
        when (role) {
            Role.USER -> ChatMessage(Role.USER, content)
            Role.ASSISTANT -> {
                val calls = decodeToolCalls(toolCallsJson)
                if (calls.isEmpty()) {
                    if (content.isBlank()) null else ChatMessage(Role.ASSISTANT, content)
                } else {
                    ChatMessage(Role.ASSISTANT, content.ifBlank { null }, toolCalls = calls)
                }
            }
            Role.TOOL -> ChatMessage(Role.TOOL, content, toolCallId = toolCallId, name = toolName)
            else -> null
        }

    /**
     * Makes a history safe to send: drops tool results without a matching call, adds a placeholder
     * result for a call that never got one, and merges consecutive user messages.
     */
    internal fun sanitize(input: List<ChatMessage>): List<ChatMessage> {
        val out = ArrayList<ChatMessage>()
        val pending = LinkedHashMap<String, String>() // tool_call_id -> tool name

        fun flushPending() {
            for ((id, name) in pending) {
                out += ChatMessage(Role.TOOL, INTERRUPTED_RESULT, toolCallId = id, name = name.ifBlank { null })
            }
            pending.clear()
        }

        for (message in input) {
            if (message.role == Role.TOOL) {
                val id = message.toolCallId
                if (id != null && pending.remove(id) != null) out += message
                continue
            }
            flushPending()
            val last = out.lastOrNull()
            if (message.role == Role.USER && last != null && last.role == Role.USER) {
                out[out.lastIndex] = last.copy(content = last.content.orEmpty() + "\n\n" + message.content.orEmpty())
                continue
            }
            out += message
            message.toolCalls?.forEach { pending[it.id] = it.function.name }
        }
        flushPending()
        return out
    }
}
