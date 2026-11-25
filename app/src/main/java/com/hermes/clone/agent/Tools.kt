package com.hermes.clone.agent

import com.hermes.clone.data.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.DateTimeException
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** Default cap for one tool result sent back to the model. */
const val MAX_TOOL_RESULT_CHARS = 8_000

/** What a tool handler gets besides its arguments. */
data class ToolContext(
    val sessionId: String,
    val settings: AppSettings,
)

/** One local tool: its OpenAI function schema plus the code that runs it. */
interface Tool {
    val spec: ToolSpec

    /** Results longer than this are cut before they reach the model. */
    val maxResultChars: Int get() = MAX_TOOL_RESULT_CHARS

    /** Returns the text the model will see. Throw [ToolException] for a problem the model can fix. */
    suspend fun execute(args: JsonObject, ctx: ToolContext): String
}

val Tool.name: String get() = spec.function.name

/** A problem with a tool call that is safe to show to the model (bad arguments, missing item). */
class ToolException(message: String) : Exception(message)

/** The outcome of one tool call. [isError] is only used for the UI. */
data class ToolOutcome(val content: String, val isError: Boolean)

/**
 * Holds the tools of one agent and runs them. [execute] never throws, except for coroutine
 * cancellation: every failure becomes an error text the model can read.
 */
class ToolRegistry(tools: List<Tool>) {
    private val byName: Map<String, Tool> = tools.associateBy { it.name }

    val specs: List<ToolSpec> = tools.map { it.spec }
    val names: Set<String> get() = byName.keys

    suspend fun execute(name: String, argumentsJson: String, ctx: ToolContext): ToolOutcome {
        val tool = byName[name.trim()]
            ?: return ToolOutcome(
                "Error: unknown tool \"$name\". Available tools: ${byName.keys.joinToString(", ")}.",
                true,
            )
        val args = try {
            parseArguments(argumentsJson)
        } catch (e: ToolException) {
            return ToolOutcome("Error: invalid arguments for ${tool.name}: ${e.message}.", true)
        }
        return try {
            ToolOutcome(clip(tool.execute(args, ctx), tool.maxResultChars), false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ToolException) {
            ToolOutcome("Error: ${e.message}", true)
        } catch (e: Exception) {
            ToolOutcome("Error: ${tool.name} failed unexpectedly (${e.javaClass.simpleName}).", true)
        }
    }
}

// ---- argument parsing --------------------------------------------------------------------------

internal fun parseArguments(raw: String): JsonObject {
    val text = raw.trim().ifEmpty { "{}" }
    val element = try {
        HermesJson.parseToJsonElement(text)
    } catch (e: IllegalArgumentException) {
        throw ToolException("the arguments were not valid JSON")
    }
    return when (element) {
        is JsonObject -> element
        is JsonNull -> JsonObject(emptyMap())
        else -> throw ToolException("the arguments must be a JSON object")
    }
}

internal fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

internal fun JsonObject.long(key: String): Long? {
    val text = string(key)?.trim() ?: return null
    return text.toLongOrNull() ?: text.toDoubleOrNull()?.toLong()
}

internal fun JsonObject.int(key: String): Int? = long(key)?.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())?.toInt()

internal fun JsonObject.bool(key: String): Boolean? {
    val primitive = this[key] as? JsonPrimitive ?: return null
    primitive.booleanOrNull?.let { return it }
    return when (primitive.contentOrNull?.trim()?.lowercase()) {
        "true" -> true
        "false" -> false
        else -> null
    }
}

internal fun clip(text: String, max: Int): String =
    if (text.length <= max) text else text.take(max) + "\n...[cut: ${text.length - max} more characters]"

internal fun formatTime(millis: Long): String =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

// ---- JSON-schema helpers -----------------------------------------------------------------------

internal fun stringProp(description: String, values: List<String>? = null): JsonObject = buildJsonObject {
    put("type", "string")
    put("description", description)
    if (values != null) putJsonArray("enum") { values.forEach { add(it) } }
}

internal fun intProp(description: String): JsonObject = buildJsonObject {
    put("type", "integer")
    put("description", description)
}

internal fun boolProp(description: String): JsonObject = buildJsonObject {
    put("type", "boolean")
    put("description", description)
}

internal fun arrayProp(description: String, items: JsonObject): JsonObject = buildJsonObject {
    put("type", "array")
    put("description", description)
    put("items", items)
}

internal fun objectSchema(
    properties: Map<String, JsonObject>,
    required: List<String> = emptyList(),
): JsonObject = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") { properties.forEach { (key, value) -> put(key, value) } }
    if (required.isNotEmpty()) putJsonArray("required") { required.forEach { add(it) } }
}

internal fun toolSpec(name: String, description: String, parameters: JsonObject): ToolSpec =
    ToolSpec(function = ToolFunction(name = name, description = description, parameters = parameters))

// ---- current_time ------------------------------------------------------------------------------

/** The agent has no clock of its own (the system prompt must stay byte-stable), so it asks this tool. */
class CurrentTimeTool : Tool {
    override val spec: ToolSpec = toolSpec(
        name = "current_time",
        description = "Returns the current date and time on the user's phone. Optionally pass an IANA time zone such as Asia/Kolkata or America/New_York.",
        parameters = objectSchema(
            mapOf("timezone" to stringProp("Optional IANA time zone id. Defaults to the phone's time zone.")),
        ),
    )

    override suspend fun execute(args: JsonObject, ctx: ToolContext): String {
        val requested = args.string("timezone")?.trim()?.takeIf { it.isNotEmpty() }
        val zone = if (requested == null) {
            ZoneId.systemDefault()
        } else {
            try {
                ZoneId.of(requested)
            } catch (e: DateTimeException) {
                throw ToolException("unknown time zone \"$requested\". Use an IANA id such as Asia/Kolkata.")
            }
        }
        val now = ZonedDateTime.now(zone)
        return buildJsonObject {
            put("iso", now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
            put("date", now.toLocalDate().toString())
            put("time", now.format(DateTimeFormatter.ofPattern("HH:mm:ss")))
            put("weekday", now.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH))
            put("timezone", zone.id)
        }.toString()
    }
}
