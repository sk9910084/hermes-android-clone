package com.hermes.clone.agent

import com.hermes.clone.data.AppSettings
import com.hermes.clone.data.Provider
import com.hermes.clone.data.Role
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Talks to any OpenAI-compatible /chat/completions endpoint directly from the phone.
 * No proxy, no backend. The API key is only ever placed in the Authorization header of
 * requests to the user's configured base URL, and is never logged.
 */
class ProviderClient(
    private val http: OkHttpClient = defaultHttpClient(),
    private val json: Json = HermesJson,
) {

    /**
     * Runs one model call. With [stream] true the reply is read as server-sent events and every
     * text fragment is passed to [onDelta] as it arrives. Throws [ProviderException] on any failure.
     */
    suspend fun chat(
        settings: AppSettings,
        messages: List<ChatMessage>,
        tools: List<ToolSpec>?,
        toolChoice: String? = null,
        stream: Boolean = settings.streaming,
        maxTokens: Int? = null,
        onDelta: suspend (String) -> Unit = {},
    ): AssistantTurn {
        if (settings.model.isBlank()) {
            throw ProviderException(ErrorKind.NO_MODEL, "Choose a model in Settings first.", retryable = false)
        }
        val toolList = tools?.takeIf { it.isNotEmpty() }
        fun body(streaming: Boolean) = ChatRequest(
            model = settings.model.trim(),
            messages = messages,
            tools = toolList,
            toolChoice = if (toolList != null) toolChoice else null,
            stream = streaming,
            maxTokens = maxTokens,
        )

        if (!stream) return blockingChat(chatRequest(settings, body(false)))

        var delivered = false
        return try {
            streamChat(chatRequest(settings, body(true))) { fragment ->
                delivered = true
                onDelta(fragment)
            }
        } catch (e: ProviderException) {
            // Some endpoints reject stream=true; fall back to a plain request once.
            if (e.kind == ErrorKind.BAD_REQUEST && !delivered) {
                blockingChat(chatRequest(settings, body(false)))
            } else {
                throw e
            }
        }
    }

    /** GET {baseUrl}/models. Needs a key, except for the Custom provider. */
    suspend fun listModels(settings: AppSettings): List<String> {
        if (settings.apiKey.isBlank() && settings.provider != Provider.CUSTOM) {
            throw ProviderException(
                ErrorKind.NO_API_KEY,
                "Add your API key in Settings before fetching models.",
                retryable = false,
            )
        }
        val request = newRequest(settings, "/models").get().build()
        val raw = execute(request) { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw mapHttpError(response.code, text)
            text
        }
        return parseModelIds(raw, settings.provider)
    }

    /** Tiny connectivity check for the Settings "Test" button. Returns a success message. */
    suspend fun testConnection(settings: AppSettings): String {
        try {
            val models = listModels(settings)
            return "Connected. The provider lists ${models.size} models."
        } catch (e: ProviderException) {
            if (e.kind != ErrorKind.NOT_FOUND) throw e
        }
        // /models is not offered everywhere; try a one-token chat call instead.
        chat(
            settings = settings,
            messages = listOf(ChatMessage(Role.USER, "ping")),
            tools = null,
            stream = false,
            maxTokens = 1,
        )
        return "Connected. The chat endpoint answered."
    }

    // ---- request building ------------------------------------------------------------------

    private fun newRequest(settings: AppSettings, path: String): Request.Builder {
        val base = settings.baseUrl.trim().trimEnd('/')
        val url = (base + path).toHttpUrlOrNull()
            ?: throw ProviderException(
                ErrorKind.BAD_URL,
                "The base URL is not valid. Enter a full https:// address in Settings.",
                retryable = false,
            )
        val builder = Request.Builder().url(url)
        // The header is omitted entirely when no key is set (some local endpoints need none).
        val key = settings.apiKey.trim()
        if (key.isNotEmpty()) builder.header("Authorization", "$AUTH_SCHEME $key")
        return builder
    }

    private fun chatRequest(settings: AppSettings, body: ChatRequest): Request {
        val payload = json.encodeToString(ChatRequest.serializer(), body)
        return newRequest(settings, "/chat/completions")
            .header("Accept", if (body.stream) "text/event-stream" else "application/json")
            .post(payload.toRequestBody(JSON_MEDIA))
            .build()
    }

    // ---- execution -------------------------------------------------------------------------

    /**
     * Runs a blocking OkHttp call off the main thread. If the coroutine is cancelled (the Stop
     * button), the HTTP call is cancelled immediately so the blocking read ends.
     */
    private suspend fun <T> execute(request: Request, handler: suspend (Response) -> T): T =
        withContext(Dispatchers.IO) {
            val call = http.newCall(request)
            coroutineScope {
                val watcher = launch {
                    try {
                        awaitCancellation()
                    } finally {
                        call.cancel()
                    }
                }
                try {
                    call.execute().use { response -> handler(response) }
                } catch (e: IOException) {
                    if (!isActive) throw CancellationException("Request cancelled")
                    throw mapIoError(e)
                } finally {
                    watcher.cancel()
                }
            }
        }

    private suspend fun blockingChat(request: Request): AssistantTurn =
        execute(request) { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw mapHttpError(response.code, raw)
            val parsed = try {
                json.decodeFromString(ChatResponse.serializer(), raw)
            } catch (e: IllegalArgumentException) {
                throw ProviderException(ErrorKind.PARSE, "The provider sent a reply the app couldn't read.")
            }
            parsed.error?.let { throw ProviderException(ErrorKind.SERVER, errorText(it)) }
            val message = parsed.choices.firstOrNull()?.message
            val calls = message?.toolCalls.orEmpty()
                .filter { it.function.name.isNotBlank() }
                .mapIndexed { i, c -> if (c.id.isBlank()) c.copy(id = "call_${System.nanoTime()}_$i") else c }
            AssistantTurn(message?.content.orEmpty(), calls)
        }

    private suspend fun streamChat(request: Request, onDelta: suspend (String) -> Unit): AssistantTurn =
        execute(request) { response ->
            if (!response.isSuccessful) {
                throw mapHttpError(response.code, response.body?.string().orEmpty())
            }
            val source = response.body?.source()
                ?: throw ProviderException(ErrorKind.PARSE, "The provider sent an empty reply.")
            val text = StringBuilder()
            val calls = ToolCallAssembler()
            while (true) {
                currentCoroutineContext().ensureActive()
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue // blank keep-alives and ": comment" lines
                val payload = line.removePrefix("data:").trim()
                if (payload.isEmpty()) continue
                if (payload == "[DONE]") break
                val chunk = try {
                    json.decodeFromString(StreamChunk.serializer(), payload)
                } catch (e: IllegalArgumentException) {
                    continue // skip a fragment we cannot read rather than abort the whole reply
                }
                chunk.error?.let { throw ProviderException(ErrorKind.SERVER, errorText(it)) }
                for (choice in chunk.choices) {
                    val delta = choice.delta ?: continue
                    val piece = delta.content
                    if (!piece.isNullOrEmpty()) {
                        text.append(piece)
                        onDelta(piece)
                    }
                    delta.toolCalls?.forEach { calls.accept(it) }
                }
            }
            AssistantTurn(text.toString(), calls.build())
        }

    // ---- parsing ---------------------------------------------------------------------------

    private fun parseModelIds(raw: String, provider: Provider): List<String> {
        val root: JsonElement = try {
            json.parseToJsonElement(raw)
        } catch (e: IllegalArgumentException) {
            throw ProviderException(ErrorKind.PARSE, "The provider's model list could not be read.")
        }
        val items: List<JsonElement> = when (root) {
            is JsonArray -> root
            is JsonObject -> ((root["data"] ?: root["models"]) as? JsonArray) ?: emptyList()
            else -> emptyList()
        }
        val ids = items
            .mapNotNull { element ->
                val obj = element as? JsonObject
                ((obj?.get("id") ?: obj?.get("name")) as? JsonPrimitive)?.contentOrNull
            }
            .map { it.removePrefix("models/") }
            .distinct()
            .sorted()
        return if (provider == Provider.OPENROUTER) {
            ids.sortedBy { if (it.endsWith(":free")) 0 else 1 }
        } else {
            ids
        }
    }

    private companion object {
        const val AUTH_SCHEME = "Bearer"
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        fun defaultHttpClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
    }
}
