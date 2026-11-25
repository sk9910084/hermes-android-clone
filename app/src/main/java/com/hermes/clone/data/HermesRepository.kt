package com.hermes.clone.data

import android.database.sqlite.SQLiteException
import androidx.room.withTransaction
import com.hermes.clone.agent.SystemPrompt
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/** A todo item as sent by the model (id and status are optional). */
data class TodoInput(val id: Long?, val content: String?, val status: String?)

/** One hit from the session search. */
data class SearchHit(
    val sessionId: String,
    val sessionTitle: String,
    val role: String,
    val snippet: String,
    val createdAt: Long,
)

class HermesRepository(private val db: AppDatabase) {
    private val sessionDao = db.sessionDao()
    private val messageDao = db.messageDao()
    private val memoryDao = db.memoryDao()
    private val todoDao = db.todoDao()

    val sessions: Flow<List<SessionEntity>> = sessionDao.observeAll()
    val memories: Flow<List<MemoryEntity>> = memoryDao.observeAll()
    val todos: Flow<List<TodoEntity>> = todoDao.observeAll()

    // ---- sessions -------------------------------------------------------------------------

    suspend fun session(id: String): SessionEntity? = sessionDao.get(id)

    suspend fun latestSession(): SessionEntity? = sessionDao.latest()

    suspend fun createSession(): SessionEntity {
        val now = System.currentTimeMillis()
        val session = SessionEntity(
            id = UUID.randomUUID().toString(),
            title = DEFAULT_SESSION_TITLE,
            createdAt = now,
            updatedAt = now,
            systemPrompt = SystemPrompt.build(),
        )
        sessionDao.insert(session)
        return session
    }

    suspend fun updateSession(session: SessionEntity) = sessionDao.update(session)

    suspend fun renameSession(id: String, title: String) =
        sessionDao.rename(id, title.trim().ifBlank { DEFAULT_SESSION_TITLE })

    suspend fun deleteSession(id: String) = sessionDao.delete(id)

    suspend fun deleteAllSessions() = sessionDao.deleteAll()

    // ---- messages -------------------------------------------------------------------------

    fun messages(sessionId: String): Flow<List<MessageEntity>> = messageDao.observe(sessionId)

    suspend fun history(sessionId: String): List<MessageEntity> = messageDao.list(sessionId)

    suspend fun addMessage(message: MessageEntity): Long = db.withTransaction {
        val id = messageDao.insert(message)
        sessionDao.touch(message.sessionId, System.currentTimeMillis())
        id
    }

    /** Saves an assistant tool-call message together with all of its tool results, atomically. */
    suspend fun addRound(assistant: MessageEntity, results: List<MessageEntity>) {
        db.withTransaction {
            messageDao.insert(assistant)
            messageDao.insertAll(results)
            sessionDao.touch(assistant.sessionId, System.currentTimeMillis())
        }
    }

    suspend fun setMessageContent(id: Long, content: String) = messageDao.setContent(id, content)

    suspend fun searchSessions(query: String, excludeSessionId: String, limit: Int): List<SearchHit> {
        val match = buildFtsQuery(query) ?: return emptyList()
        val rows = try {
            messageDao.search(match, excludeSessionId, limit)
        } catch (e: SQLiteException) {
            emptyList()
        }
        val titles = rows.map { it.sessionId }.distinct()
            .associateWith { sessionDao.get(it)?.title ?: "(deleted chat)" }
        return rows.map {
            SearchHit(
                sessionId = it.sessionId,
                sessionTitle = titles[it.sessionId].orEmpty(),
                role = it.role,
                snippet = (it.display ?: it.content).replace('\n', ' ').trim().take(SNIPPET_CHARS),
                createdAt = it.createdAt,
            )
        }
    }

    /** Turns free text into a safe FTS4 prefix query: "tok1* tok2*". Null when nothing usable. */
    private fun buildFtsQuery(text: String): String? {
        val tokens = TOKEN_REGEX.findAll(text).map { it.value.lowercase() }
            .filter { it.length >= 2 }.take(8).toList()
        if (tokens.isEmpty()) return null
        return tokens.joinToString(" ") { "$it*" }
    }

    // ---- memories -------------------------------------------------------------------------

    /** Saves a memory. Returns null when the same text is already stored. */
    suspend fun saveMemory(content: String, source: String): Long? {
        val text = content.trim()
        if (text.isEmpty() || memoryDao.findExact(text) != null) return null
        val now = System.currentTimeMillis()
        return memoryDao.insert(MemoryEntity(content = text, source = source, createdAt = now, updatedAt = now))
    }

    suspend fun updateMemory(id: Long, content: String) {
        val existing = memoryDao.get(id) ?: return
        val text = content.trim()
        if (text.isEmpty()) return
        memoryDao.update(existing.copy(content = text, updatedAt = System.currentTimeMillis()))
    }

    suspend fun deleteMemory(id: Long) = memoryDao.delete(id)

    suspend fun deleteAllMemories() = memoryDao.deleteAll()

    suspend fun searchMemories(query: String, limit: Int): List<MemoryEntity> =
        memoryDao.search(query.trim(), limit)

    suspend fun recentMemories(limit: Int): List<MemoryEntity> = memoryDao.recent(limit)

    // ---- todos ----------------------------------------------------------------------------

    suspend fun todoList(): List<TodoEntity> = todoDao.all()

    suspend fun addTodo(content: String): Long? {
        val text = content.trim()
        if (text.isEmpty()) return null
        val now = System.currentTimeMillis()
        return todoDao.insert(TodoEntity(content = text.take(MAX_TODO_CHARS), createdAt = now, updatedAt = now))
    }

    suspend fun setTodoStatus(id: Long, status: String) {
        val existing = todoDao.get(id) ?: return
        if (status !in TodoStatus.all) return
        todoDao.update(existing.copy(status = status, updatedAt = System.currentTimeMillis()))
    }

    suspend fun renameTodo(id: Long, content: String) {
        val existing = todoDao.get(id) ?: return
        val text = content.trim()
        if (text.isEmpty()) return
        todoDao.update(existing.copy(content = text.take(MAX_TODO_CHARS), updatedAt = System.currentTimeMillis()))
    }

    suspend fun deleteTodo(id: Long) = todoDao.delete(id)

    suspend fun deleteAllTodos() = todoDao.deleteAll()

    /**
     * Applies a todo write from the model. merge=false replaces the whole list;
     * merge=true updates items by id and appends the ones it does not know.
     */
    suspend fun applyTodos(inputs: List<TodoInput>, merge: Boolean) {
        db.withTransaction {
            val now = System.currentTimeMillis()
            if (!merge) todoDao.deleteAll()
            for (input in inputs) {
                val status = input.status?.takeIf { it in TodoStatus.all }
                val existing = if (merge) input.id?.let { todoDao.get(it) } else null
                if (existing != null) {
                    val newContent = input.content?.trim()?.takeIf { it.isNotEmpty() } ?: existing.content
                    todoDao.update(
                        existing.copy(
                            content = newContent.take(MAX_TODO_CHARS),
                            status = status ?: existing.status,
                            updatedAt = now,
                        ),
                    )
                } else {
                    val content = input.content?.trim().orEmpty()
                    if (content.isEmpty()) continue
                    todoDao.insert(
                        TodoEntity(
                            id = input.id ?: 0L,
                            content = content.take(MAX_TODO_CHARS),
                            status = status ?: TodoStatus.PENDING,
                            createdAt = now,
                            updatedAt = now,
                        ),
                    )
                }
            }
        }
    }

    private companion object {
        val TOKEN_REGEX = Regex("[\\p{L}\\p{N}]+")
        const val SNIPPET_CHARS = 240
        const val MAX_TODO_CHARS = 300
    }
}
