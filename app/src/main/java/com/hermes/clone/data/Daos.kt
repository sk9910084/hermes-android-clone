package com.hermes.clone.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {
    @Query("SELECT * FROM sessions ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun get(id: String): SessionEntity?

    @Query("SELECT * FROM sessions ORDER BY updatedAt DESC LIMIT 1")
    suspend fun latest(): SessionEntity?

    // Plain insert/update (never REPLACE): REPLACE deletes the row first, which would
    // cascade-delete every message of the session.
    @Insert
    suspend fun insert(session: SessionEntity)

    @Update
    suspend fun update(session: SessionEntity)

    @Query("UPDATE sessions SET title = :title WHERE id = :id")
    suspend fun rename(id: String, title: String)

    @Query("UPDATE sessions SET updatedAt = :time WHERE id = :id")
    suspend fun touch(id: String, time: Long)

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM sessions")
    suspend fun deleteAll()
}

@Dao
interface MessageDao {
    @Insert
    suspend fun insert(message: MessageEntity): Long

    @Insert
    suspend fun insertAll(messages: List<MessageEntity>): List<Long>

    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY id ASC")
    fun observe(sessionId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY id ASC")
    suspend fun list(sessionId: String): List<MessageEntity>

    @Query("UPDATE messages SET content = :content WHERE id = :id")
    suspend fun setContent(id: Long, content: String)

    @Query(
        """
        SELECT * FROM messages
        WHERE id IN (SELECT docid FROM messages_fts WHERE messages_fts MATCH :match)
          AND sessionId != :excludeSessionId
          AND role IN ('user', 'assistant')
          AND content != ''
        ORDER BY id DESC
        LIMIT :limit
        """,
    )
    suspend fun search(match: String, excludeSessionId: String, limit: Int): List<MessageEntity>
}

@Dao
interface MemoryDao {
    @Query("SELECT * FROM memories ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memories ORDER BY updatedAt DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<MemoryEntity>

    @Query("SELECT * FROM memories WHERE content LIKE '%' || :query || '%' ORDER BY updatedAt DESC LIMIT :limit")
    suspend fun search(query: String, limit: Int): List<MemoryEntity>

    @Query("SELECT * FROM memories WHERE lower(content) = lower(:content) LIMIT 1")
    suspend fun findExact(content: String): MemoryEntity?

    @Query("SELECT * FROM memories WHERE id = :id")
    suspend fun get(id: Long): MemoryEntity?

    @Insert
    suspend fun insert(memory: MemoryEntity): Long

    @Update
    suspend fun update(memory: MemoryEntity)

    @Query("DELETE FROM memories WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM memories")
    suspend fun deleteAll()
}

@Dao
interface TodoDao {
    @Query("SELECT * FROM todos ORDER BY id ASC")
    fun observeAll(): Flow<List<TodoEntity>>

    @Query("SELECT * FROM todos ORDER BY id ASC")
    suspend fun all(): List<TodoEntity>

    @Query("SELECT * FROM todos WHERE id = :id")
    suspend fun get(id: Long): TodoEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(todo: TodoEntity): Long

    @Update
    suspend fun update(todo: TodoEntity)

    @Query("DELETE FROM todos WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM todos")
    suspend fun deleteAll()
}
