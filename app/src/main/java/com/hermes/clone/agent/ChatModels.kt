package com.hermes.clone.agent

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Shared JSON configuration for every request and response. */
@OptIn(ExperimentalSerializationApi::class)
val HermesJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
    isLenient = true
}

// ---- messages (OpenAI chat-completions format) ---------------------------------------------

@Serializable
data class FunctionCall(
    val name: String = "",
    val arguments: String = "{}",
)

@Serializable
data class ToolCall(
    val id: String = "",
    val type: String = "function",
    val function: FunctionCall = FunctionCall(),
)

@Serializable
data class ChatMessage(
    val role: String,
    val content: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ToolCall>? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
    val name: String? = null,
)

// ---- request -------------------------------------------------------------------------------

@Serializable
data class ToolFunction(
    val name: String,
    val description: String,
    val parameters: JsonObject,
)

@Serializable
data class ToolSpec(
    val type: String = "function",
    val function: ToolFunction,
)

@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val tools: List<ToolSpec>? = null,
    @SerialName("tool_choice") val toolChoice: String? = null,
    val stream: Boolean = false,
    @SerialName("max_tokens") val maxTokens: Int? = null,
)

// ---- non-streaming response ----------------------------------------------------------------

@Serializable
data class ResponseChoice(
    val message: ChatMessage? = null,
)

@Serializable
data class ChatResponse(
    val choices: List<ResponseChoice> = emptyList(),
    val error: JsonElement? = null,
)

// ---- streaming (SSE) chunks ----------------------------------------------------------------

@Serializable
data class DeltaFunction(
    val name: String? = null,
    val arguments: String? = null,
)

@Serializable
data class DeltaToolCall(
    val index: Int = 0,
    val id: String? = null,
    val function: DeltaFunction? = null,
)

@Serializable
data class StreamDelta(
    val content: String? = null,
    @SerialName("tool_calls") val toolCalls: List<DeltaToolCall>? = null,
)

@Serializable
data class StreamChoice(
    val delta: StreamDelta? = null,
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
data class StreamChunk(
    val choices: List<StreamChoice> = emptyList(),
    val error: JsonElement? = null,
)

/** The finished result of one model call. */
data class AssistantTurn(
    val content: String,
    val toolCalls: List<ToolCall>,
)
