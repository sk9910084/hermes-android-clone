package com.hermes.clone.agent

import com.hermes.clone.data.Role
import kotlinx.serialization.json.JsonObject

/**
 * delegate_task: a same-model, same-provider sub-agent with a fresh, short context. It runs
 * sequentially, uses a registry WITHOUT delegate_task (so it cannot recurse), keeps nothing in the
 * database and returns a short report as the tool result.
 */
class Subagent(
    private val provider: ProviderClient,
    private val registry: ToolRegistry,
) {

    suspend fun run(goal: String, context: String?, ctx: ToolContext): String {
        val task = buildString {
            append("Task:\n").append(goal.trim())
            if (!context.isNullOrBlank()) append("\n\nContext:\n").append(context.trim())
        }
        val messages = ArrayList<ChatMessage>()
        messages += ChatMessage(Role.SYSTEM, SystemPrompt.subagent())
        messages += ChatMessage(Role.USER, task)

        var calls = 0
        while (true) {
            val budgetUsed = calls >= MAX_ITERATIONS
            val request = if (budgetUsed) ContextBuilder.appendBudgetNotice(messages) else messages
            val turn = try {
                provider.chat(
                    settings = ctx.settings,
                    messages = request,
                    tools = registry.specs,
                    toolChoice = if (budgetUsed) "none" else null,
                    stream = false,
                )
            } catch (e: ProviderException) {
                throw ToolException("the sub-agent could not finish: ${e.message}")
            }

            if (turn.toolCalls.isEmpty() || budgetUsed) {
                val text = turn.content.trim()
                return when {
                    text.isNotEmpty() -> text.take(MAX_REPORT_CHARS)
                    budgetUsed -> "The sub-agent used all of its steps without writing a report."
                    else -> "The sub-agent finished but returned no text."
                }
            }

            val used = turn.toolCalls.take(MAX_TOOL_CALLS_PER_STEP)
            messages += ChatMessage(Role.ASSISTANT, turn.content.ifBlank { null }, toolCalls = used)
            for (call in used) {
                val outcome = registry.execute(call.function.name, call.function.arguments, ctx)
                messages += ChatMessage(
                    role = Role.TOOL,
                    content = clip(outcome.content, MAX_TOOL_CHARS),
                    toolCallId = call.id,
                    name = call.function.name,
                )
            }
            calls++
        }
    }

    private companion object {
        const val MAX_ITERATIONS = 8
        const val MAX_TOOL_CALLS_PER_STEP = 6
        const val MAX_TOOL_CHARS = 4_000
        const val MAX_REPORT_CHARS = 6_000
    }
}

/** The tool schema and handler the main agent sees. */
class DelegateTaskTool(private val subagent: Subagent) : Tool {
    override val spec: ToolSpec = toolSpec(
        name = "delegate_task",
        description = "Hands one self-contained task to a focused sub-agent that works on its own with the same " +
            "tools (except delegate_task) and returns a short report. It knows nothing about this chat, so put " +
            "everything it needs in goal and context. Use it sparingly, for multi-step work.",
        parameters = objectSchema(
            mapOf(
                "goal" to stringProp("What the sub-agent must achieve, stated completely."),
                "context" to stringProp("Background facts the sub-agent needs (optional)."),
            ),
            required = listOf("goal"),
        ),
    )

    override suspend fun execute(args: JsonObject, ctx: ToolContext): String {
        val goal = args.string("goal")?.trim().orEmpty()
        if (goal.isEmpty()) throw ToolException("\"goal\" is required")
        return subagent.run(goal, args.string("context"), ctx)
    }
}
