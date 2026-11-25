package com.hermes.clone.agent

import com.hermes.clone.data.HermesRepository
import com.hermes.clone.data.MemoryEntity
import com.hermes.clone.data.TodoEntity
import com.hermes.clone.data.TodoInput
import com.hermes.clone.data.TodoStatus
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

// ---- todo_list ---------------------------------------------------------------------------------

/** Upstream's core "todo" tool: read the list, or write it (replace or merge). */
class TodoTool(private val repo: HermesRepository) : Tool {
    override val spec: ToolSpec = toolSpec(
        name = "todo_list",
        description = "Reads or writes the user's to-do list. Call with no arguments to read it. " +
            "To write, pass todos. With merge=false (default) the whole list is replaced; with merge=true " +
            "items are updated by id and unknown ids or items without an id are added. " +
            "Keep at most one item in_progress. Returns the full list afterwards.",
        parameters = objectSchema(
            mapOf(
                "todos" to arrayProp(
                    "Items to write. Omit to just read the list.",
                    objectSchema(
                        mapOf(
                            "id" to intProp("Existing item id (needed to update an item when merge=true)."),
                            "content" to stringProp("What needs to be done."),
                            "status" to stringProp("Item status.", TodoStatus.all),
                        ),
                    ),
                ),
                "merge" to boolProp("true: update/add items and keep the rest. false: replace the whole list."),
            ),
        ),
    )

    override suspend fun execute(args: JsonObject, ctx: ToolContext): String {
        val raw = args["todos"]
        if (raw != null && raw !is JsonNull) {
            val array = raw as? JsonArray ?: throw ToolException("\"todos\" must be an array of items")
            if (array.size > MAX_TODOS) throw ToolException("too many items; at most $MAX_TODOS are allowed")
            val inputs = array.mapNotNull { element ->
                (element as? JsonObject)?.let { item ->
                    TodoInput(id = item.long("id"), content = item.string("content"), status = item.string("status"))
                }
            }
            repo.applyTodos(inputs, merge = args.bool("merge") ?: false)
        }
        return render(repo.todoList())
    }

    private fun render(items: List<TodoEntity>): String = buildJsonObject {
        putJsonArray("todos") {
            items.forEach { item ->
                add(
                    buildJsonObject {
                        put("id", item.id)
                        put("content", item.content)
                        put("status", item.status)
                    },
                )
            }
        }
        putJsonObject("summary") {
            put("total", items.size)
            TodoStatus.all.forEach { status -> put(status, items.count { it.status == status }) }
        }
    }.toString()

    private companion object {
        const val MAX_TODOS = 50
    }
}

// ---- memory ------------------------------------------------------------------------------------

/** Long-term memory in Room: save, search, list and remove facts. */
class MemoryTool(private val repo: HermesRepository) : Tool {
    override val spec: ToolSpec = toolSpec(
        name = "memory",
        description = "Long-term memory that survives between chats. " +
            "action=save stores one short fact (needs content). action=search finds saved facts (needs query). " +
            "action=list shows the most recent facts. action=remove deletes a fact by id; use it only when the " +
            "user asks or the fact is wrong. Never store secrets such as passwords or API keys.",
        parameters = objectSchema(
            mapOf(
                "action" to stringProp("What to do.", listOf("save", "search", "list", "remove")),
                "content" to stringProp("The fact to save, written as one short self-contained sentence."),
                "query" to stringProp("Words to look for (action=search)."),
                "id" to intProp("Memory id (action=remove)."),
                "limit" to intProp("Maximum number of results for search/list (default 10, max 25)."),
            ),
            required = listOf("action"),
        ),
    )

    override suspend fun execute(args: JsonObject, ctx: ToolContext): String {
        val limit = (args.int("limit") ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
        return when (val action = args.string("action")?.trim()?.lowercase()) {
            "save" -> {
                val content = args.string("content")?.trim().orEmpty()
                if (content.isEmpty()) throw ToolException("action=save needs \"content\"")
                val id = repo.saveMemory(content.take(MAX_CONTENT_CHARS), source = "agent")
                if (id == null) "That fact is already saved." else "Saved as memory #$id."
            }
            "search" -> {
                val query = args.string("query")?.trim().orEmpty()
                if (query.isEmpty()) throw ToolException("action=search needs \"query\"")
                renderList(repo.searchMemories(query, limit), "No saved memory matches \"$query\".")
            }
            "list" -> renderList(repo.recentMemories(limit), "There are no saved memories yet.")
            "remove" -> {
                val id = args.long("id") ?: throw ToolException("action=remove needs \"id\"")
                repo.deleteMemory(id)
                "Removed memory #$id (if it existed)."
            }
            null, "" -> throw ToolException("\"action\" is required: save, search, list or remove")
            else -> throw ToolException("unknown action \"$action\"; use save, search, list or remove")
        }
    }

    private fun renderList(items: List<MemoryEntity>, emptyText: String): String {
        if (items.isEmpty()) return emptyText
        return buildJsonObject {
            put("count", items.size)
            putJsonArray("memories") {
                items.forEach { memory ->
                    add(
                        buildJsonObject {
                            put("id", memory.id)
                            put("content", memory.content)
                            put("source", memory.source)
                            put("updated", formatTime(memory.updatedAt))
                        },
                    )
                }
            }
        }.toString()
    }

    private companion object {
        const val DEFAULT_LIMIT = 10
        const val MAX_LIMIT = 25
        const val MAX_CONTENT_CHARS = 500
    }
}

// ---- session_search ----------------------------------------------------------------------------

/** Full-text search over the user's other chats. */
class SessionSearchTool(private val repo: HermesRepository) : Tool {
    override val spec: ToolSpec = toolSpec(
        name = "session_search",
        description = "Searches the user's past chats (not the current one) for the given words and returns " +
            "matching snippets with the chat title and date. Use it when the user refers to something discussed earlier.",
        parameters = objectSchema(
            mapOf(
                "query" to stringProp("Words to look for."),
                "limit" to intProp("Maximum number of snippets (default 5, max 10)."),
            ),
            required = listOf("query"),
        ),
    )

    override suspend fun execute(args: JsonObject, ctx: ToolContext): String {
        val query = args.string("query")?.trim().orEmpty()
        if (query.isEmpty()) throw ToolException("\"query\" is required")
        val limit = (args.int("limit") ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
        val hits = repo.searchSessions(query, excludeSessionId = ctx.sessionId, limit = limit)
        if (hits.isEmpty()) return "No past chat matches \"$query\"."
        return buildJsonObject {
            put("count", hits.size)
            putJsonArray("results") {
                hits.forEach { hit ->
                    add(
                        buildJsonObject {
                            put("chat", hit.sessionTitle)
                            put("role", hit.role)
                            put("date", formatTime(hit.createdAt))
                            put("snippet", hit.snippet)
                        },
                    )
                }
            }
        }.toString()
    }

    private companion object {
        const val DEFAULT_LIMIT = 5
        const val MAX_LIMIT = 10
    }
}
