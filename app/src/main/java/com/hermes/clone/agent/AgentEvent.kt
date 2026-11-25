package com.hermes.clone.agent

/**
 * Everything the chat screen needs to know while a turn runs. AgentLoop.run() emits these;
 * the ChatViewModel turns them into UI state. Messages themselves are read from Room.
 */
sealed interface AgentEvent {

    /** The user's message is now stored (the UI may clear the input field). */
    data object UserMessageSaved : AgentEvent

    /** A model call is starting. [iteration] counts from 1. Good moment to show "Thinking...". */
    data class ModelCall(val iteration: Int) : AgentEvent

    /** A fragment of the assistant's reply, in arrival order (streaming only). */
    data class Delta(val text: String) : AgentEvent

    /** A local tool is about to run. [summary] is a short one-line preview of its arguments. */
    data class ToolStarted(val callId: String, val name: String, val summary: String) : AgentEvent

    data class ToolFinished(val callId: String, val name: String, val isError: Boolean) : AgentEvent

    /** Context was trimmed or summarized to stay below the model's limit. */
    data class ContextCompacted(val note: String) : AgentEvent

    /** The assistant message of the current model call is stored in Room; clear any live text. */
    data object TurnSaved : AgentEvent

    /** The turn is finished. [text] is the final answer. */
    data class Completed(val text: String) : AgentEvent

    /**
     * The turn stopped with an error. Everything stored so far stays valid, so a retry simply calls
     * run(sessionId, null). When [userMessageSaved] is false the message was NOT stored (for example
     * no API key or no network), so the UI should keep the text in the input field.
     */
    data class Failed(
        val kind: ErrorKind,
        val message: String,
        val retryable: Boolean,
        val userMessageSaved: Boolean,
    ) : AgentEvent
}
