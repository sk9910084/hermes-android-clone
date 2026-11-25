package com.hermes.clone.agent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

enum class ErrorKind {
    NO_API_KEY,
    NO_MODEL,
    BAD_URL,
    OFFLINE,
    TIMEOUT,
    UNAUTHORIZED,
    RATE_LIMITED,
    NOT_FOUND,
    CONTEXT_OVERFLOW,
    BAD_REQUEST,
    SERVER,
    NETWORK,
    PARSE,
    EMPTY_REPLY,
    UNKNOWN,
}

/** Every provider problem becomes one of these, with a message that is safe to show on screen. */
class ProviderException(
    val kind: ErrorKind,
    override val message: String,
    val retryable: Boolean = true,
) : Exception(message)

private val OVERFLOW_MARKERS = listOf(
    "context length",
    "context_length",
    "maximum context",
    "too many tokens",
    "exceeds the context",
    "token limit",
    "reduce the length",
    "prompt is too long",
)

/** Pulls a short human-readable message out of a provider error body, if there is one. */
internal fun extractErrorMessage(body: String): String? {
    if (body.isBlank()) return null
    return try {
        val root = HermesJson.parseToJsonElement(body)
        val obj = (root as? JsonObject) ?: ((root as? JsonArray)?.firstOrNull() as? JsonObject)
        val err = obj?.get("error") ?: obj?.get("message")
        if (err == null) null else errorText(err).takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        null
    }
}

internal fun errorText(element: JsonElement): String {
    val text = when (element) {
        is JsonPrimitive -> element.contentOrNull.orEmpty()
        is JsonObject -> (element["message"] as? JsonPrimitive)?.contentOrNull ?: element.toString()
        else -> element.toString()
    }
    return text.take(300)
}

private fun withDetail(base: String, detail: String?): String =
    if (detail.isNullOrBlank()) base else "$base Provider said: $detail"

internal fun mapHttpError(code: Int, body: String): ProviderException {
    val detail = extractErrorMessage(body)
    val lower = (detail ?: body).lowercase()
    val overflow = OVERFLOW_MARKERS.any { it in lower }
    return when {
        overflow && (code == 400 || code == 413 || code == 422) ->
            ProviderException(
                ErrorKind.CONTEXT_OVERFLOW,
                "The conversation is too long for this model's context window.",
                retryable = true,
            )
        code == 401 || code == 403 ->
            ProviderException(
                ErrorKind.UNAUTHORIZED,
                "The provider rejected your API key (HTTP $code). Check the key and base URL in Settings.",
                retryable = false,
            )
        code == 402 ->
            ProviderException(
                ErrorKind.BAD_REQUEST,
                withDetail("This model needs credits or a paid plan (HTTP 402). Try a free model.", detail),
                retryable = false,
            )
        code == 404 ->
            ProviderException(
                ErrorKind.NOT_FOUND,
                withDetail("Endpoint or model not found (HTTP 404). Check the base URL and model name in Settings.", detail),
                retryable = false,
            )
        code == 408 ->
            ProviderException(ErrorKind.TIMEOUT, "The provider took too long to answer (HTTP 408). Try again.")
        code == 429 ->
            ProviderException(
                ErrorKind.RATE_LIMITED,
                withDetail("Rate limit reached (HTTP 429). Free models allow few requests; wait a moment, then retry.", detail),
            )
        code in 500..599 ->
            ProviderException(
                ErrorKind.SERVER,
                withDetail("The provider had a server error (HTTP $code). Try again in a moment.", detail),
            )
        else ->
            ProviderException(
                ErrorKind.BAD_REQUEST,
                withDetail("The provider rejected the request (HTTP $code).", detail),
                retryable = false,
            )
    }
}

internal fun mapIoError(e: IOException): ProviderException =
    when (e) {
        is UnknownHostException, is ConnectException ->
            ProviderException(
                ErrorKind.OFFLINE,
                "Couldn't reach the provider. Check your internet connection and the base URL.",
            )
        is SocketTimeoutException ->
            ProviderException(ErrorKind.TIMEOUT, "The provider took too long to answer. Try again.")
        is InterruptedIOException ->
            ProviderException(ErrorKind.TIMEOUT, "The connection timed out. Try again.")
        is SSLException ->
            ProviderException(
                ErrorKind.NETWORK,
                "A secure connection to the provider could not be set up. Check the base URL and your device clock.",
            )
        else ->
            ProviderException(ErrorKind.NETWORK, "A network error interrupted the request. Try again.")
    }
