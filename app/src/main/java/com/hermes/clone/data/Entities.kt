package com.hermes.clone.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey

const val DEFAULT_SESSION_TITLE = "New chat"

/** Message roles in OpenAI chat-completions format. */
object Role {
    const val SYSTEM = "system"
    const val USER = "user"
    const val ASSISTANT = "assistant"
    const val TOOL = "tool"
}

object TodoStatus {
    const val PENDING = "pending"
    const val IN_PROGRESS = "in_progress"
    const val COMPLETED = "completed"
    const val CANCELLED = "cancelled"
    val all: List<String> = listOf(PENDING, IN_PROGRESS, COMPLETED, CANCELLED)
}

/**
 * One conversation.
 *
 * [systemPrompt] is frozen when the session is created, so it stays byte-identical for the
 * whole conversation (prompt-cache friendly, as in the original Hermes design).
 * [summary] / [summaryUptoId] hold the result of context compression: messages with
 * id <= summaryUptoId are folded into [summary]. [injectedSkills] is a comma-separated list
 * of skill names whose instructions were already sent in this conversation.
 */
@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val systemPrompt: String,
    val summary: String = "",
    val summaryUptoId: Long = 0L,
    val injectedSkills: String = "",
)

/**
 * One message. [content] is exactly what is sent to the model; [display] (when not null) is
 * what the chat UI shows instead (used when skill instructions were prepended to a user turn).
 */
@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sessionId")],
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val role: String,
    val content: String = "",
    val display: String? = null,
    val toolCallsJson: String? = null,
    val toolCallId: String? = null,
    val toolName: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

/** Full-text index over message content, used by the session_search tool. */
@Fts4(contentEntity = MessageEntity::class)
@Entity(tableName = "messages_fts")
data class MessageFts(
    val content: String,
)

@Entity(tableName = "memories")
data class MemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val content: String,
    /** "user" (added by hand) or "agent" (saved through the memory tool). */
    val source: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(tableName = "todos")
data class TodoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val content: String,
    val status: String = TodoStatus.PENDING,
    val createdAt: Long,
    val updatedAt: Long,
)
