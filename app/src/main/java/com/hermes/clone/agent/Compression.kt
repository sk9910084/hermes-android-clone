package com.hermes.clone.agent

import com.hermes.clone.data.AppSettings
import com.hermes.clone.data.HermesRepository
import com.hermes.clone.data.MessageEntity
import com.hermes.clone.data.Role
import com.hermes.clone.data.SessionEntity

/**
 * Simplified two-layer context compression (the only sanctioned mid-conversation change):
 *
 *  1. Trim: replace the oldest tool results with a short placeholder, oldest first, until the
 *     request is comfortably below the limit. The two newest tool results are always kept.
 *  2. Summarize: if that is not enough, fold the oldest whole turns into one LLM-written summary
 *     (stored on the session). The cut is always placed right before a user message, so roles
 *     keep alternating. If the summary call fails, a plain digest is used instead.
 *
 * Sizes are estimated at about four characters per token, against AppSettings.contextLimitTokens.
 */
class Compression(
    private val repo: HermesRepository,
    private val provider: ProviderClient,
) {

    /** [session] is the (possibly updated) session row. [note] is a short message for the UI. */
    data class Result(val session: SessionEntity, val changed: Boolean, val note: String?)

    /**
     * Compresses when the request is above 75% of the context limit. With [force] (after the
     * provider reported a context overflow) it compresses regardless of the estimate.
     */
    suspend fun compactIfNeeded(
        session: SessionEntity,
        settings: AppSettings,
        toolsChars: Int,
        force: Boolean = false,
    ): Result {
        val limit = settings.contextLimitTokens
        val trigger = (limit * TRIGGER_RATIO).toInt()
        var current = session
        var history = repo.history(current.id)
        var tokens = estimate(current, history, toolsChars)
        if (!force && tokens <= trigger) return Result(current, false, null)

        val target = if (force) {
            minOf((limit * TARGET_RATIO).toInt(), (tokens * 0.6).toInt())
        } else {
            (limit * TARGET_RATIO).toInt()
        }

        // ---- layer 1: trim old tool results ----
        val candidates = history.filter {
            it.role == Role.TOOL &&
                it.id > current.summaryUptoId &&
                it.content != ContextBuilder.TRIMMED_TOOL_RESULT &&
                it.content.length > MIN_TRIM_CHARS
        }
        val keepIds = candidates.takeLast(KEEP_RECENT_TOOL_RESULTS).map { it.id }.toSet()
        val trimmedIds = HashSet<Long>()
        for (message in candidates) {
            if (tokens <= target) break
            if (message.id in keepIds) continue
            repo.setMessageContent(message.id, ContextBuilder.TRIMMED_TOOL_RESULT)
            tokens -= (message.content.length - ContextBuilder.TRIMMED_TOOL_RESULT.length).coerceAtLeast(0) / 4
            trimmedIds += message.id
        }
        var note: String? = null
        if (trimmedIds.isNotEmpty()) {
            history = history.map {
                if (it.id in trimmedIds) it.copy(content = ContextBuilder.TRIMMED_TOOL_RESULT) else it
            }
            note = "Removed ${trimmedIds.size} older tool result(s) to save space."
        }
        if (tokens <= target) return Result(current, trimmedIds.isNotEmpty(), note)

        // ---- layer 2: one summary of the oldest turns ----
        val active = history.filter { it.id > current.summaryUptoId }
        val base = if (force) minOf(limit, tokens) else limit
        val tailBudget = (base * TAIL_RATIO).toInt()
        val cut = chooseCut(active, tailBudget)
            ?: return Result(current, trimmedIds.isNotEmpty(), note)

        val folded = active.subList(0, cut)
        val summary = summarize(current.summary, folded, settings)
        current = current.copy(
            summary = summary,
            summaryUptoId = folded.last().id,
            // Skill instructions that were folded away are sent again with the next user message.
            injectedSkills = "",
        )
        repo.updateSession(current)
        return Result(current, true, "Summarized ${folded.size} older message(s) to save space.")
    }

    // ---- helpers ---------------------------------------------------------------------------

    private fun estimate(session: SessionEntity, history: List<MessageEntity>, toolsChars: Int): Int =
        ContextBuilder.estimateTokens(ContextBuilder.toChatMessages(session, history), toolsChars)

    private fun entityTokens(message: MessageEntity): Int =
        (message.content.length + (message.toolCallsJson?.length ?: 0)) / 4 + 8

    /**
     * Index in [active] of the first message to KEEP. It is always a user message, so everything
     * before it is a set of complete turns. Returns null when no safe cut exists (one long turn).
     */
    private fun chooseCut(active: List<MessageEntity>, tailBudget: Int): Int? {
        if (active.size < 2) return null
        val suffix = IntArray(active.size + 1)
        for (i in active.indices.reversed()) suffix[i] = suffix[i + 1] + entityTokens(active[i])
        var lastUser = -1
        for (i in 1 until active.size) {
            if (active[i].role != Role.USER) continue
            lastUser = i
            if (suffix[i] <= tailBudget) return i
        }
        return if (lastUser > 0) lastUser else null
    }

    private suspend fun summarize(previous: String, folded: List<MessageEntity>, settings: AppSettings): String {
        val transcript = renderTranscript(folded)
        val request = buildString {
            if (previous.isNotBlank()) append("Existing summary:\n").append(previous.trim()).append("\n\n")
            append("New part of the conversation to fold in:\n").append(transcript)
            append("\n\nWrite the updated summary now.")
        }
        val reply = try {
            provider.chat(
                settings = settings,
                messages = listOf(
                    ChatMessage(Role.SYSTEM, SUMMARY_SYSTEM_PROMPT),
                    ChatMessage(Role.USER, request),
                ),
                tools = null,
                stream = false,
                maxTokens = SUMMARY_MAX_TOKENS,
            ).content.trim()
        } catch (e: ProviderException) {
            ""
        }
        val text = reply.ifEmpty { fallbackDigest(previous, folded) }
        return text.take(MAX_SUMMARY_CHARS)
    }

    private fun renderTranscript(folded: List<MessageEntity>): String {
        val perMessage = (TRANSCRIPT_CHARS / folded.size.coerceAtLeast(1)).coerceIn(200, 1_500)
        val sb = StringBuilder()
        for (message in folded) {
            when (message.role) {
                Role.USER -> sb.append("User: ").append((message.display ?: message.content).take(perMessage))
                Role.ASSISTANT -> {
                    val calls = ContextBuilder.decodeToolCalls(message.toolCallsJson)
                    sb.append("Assistant: ").append(message.content.take(perMessage))
                    if (calls.isNotEmpty()) {
                        sb.append(" [used tools: ").append(calls.joinToString(", ") { it.function.name }).append(']')
                    }
                }
                Role.TOOL -> sb.append("Tool result (").append(message.toolName ?: "tool").append("): ")
                    .append(message.content.take(minOf(perMessage, 500)))
                else -> continue
            }
            sb.append('\n')
        }
        return sb.toString().trim()
    }

    /** Used when the summary call fails: keeps the user's requests so the thread is not lost. */
    private fun fallbackDigest(previous: String, folded: List<MessageEntity>): String {
        val sb = StringBuilder()
        if (previous.isNotBlank()) sb.append(previous.trim()).append("\n")
        sb.append("Earlier messages were shortened. What the user asked about:\n")
        for (message in folded) {
            if (message.role != Role.USER) continue
            val line = (message.display ?: message.content).replace(Regex("\\s+"), " ").trim().take(160)
            if (line.isNotEmpty()) sb.append("- ").append(line).append('\n')
        }
        return sb.toString().trim()
    }

    private companion object {
        const val TRIGGER_RATIO = 0.75
        const val TARGET_RATIO = 0.55
        const val TAIL_RATIO = 0.30
        const val KEEP_RECENT_TOOL_RESULTS = 2
        const val MIN_TRIM_CHARS = 120
        const val TRANSCRIPT_CHARS = 30_000
        const val SUMMARY_MAX_TOKENS = 1_024
        const val MAX_SUMMARY_CHARS = 6_000

        const val SUMMARY_SYSTEM_PROMPT =
            "You compress the history of a chat between a user and an AI assistant so the chat can continue " +
                "in less space. Write a concise summary of at most 400 words. Keep: the user's goals, decisions made, " +
                "facts and numbers that were given, the user's preferences, names, open tasks and anything the " +
                "assistant promised to do. Drop greetings and filler. Write plain text, no preamble, no headings."
    }
}
