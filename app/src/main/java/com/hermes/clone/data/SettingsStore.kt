package com.hermes.clone.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

const val DEFAULT_MAX_ITERATIONS = 25
const val DEFAULT_CONTEXT_LIMIT = 32_000

/** Supported providers. All three speak OpenAI-compatible Chat Completions. */
enum class Provider(
    val id: String,
    val label: String,
    val defaultBaseUrl: String,
    val suggestedModel: String,
    val helperText: String,
) {
    OPENROUTER(
        id = "openrouter",
        label = "OpenRouter",
        defaultBaseUrl = "https://openrouter.ai/api/v1",
        suggestedModel = "meta-llama/llama-3.3-70b-instruct:free",
        helperText = "Free models on OpenRouter have ids ending in :free. " +
            "Tap \"Fetch models\" to see what is available right now.",
    ),
    GEMINI(
        id = "gemini",
        label = "Gemini",
        defaultBaseUrl = "https://generativelanguage.googleapis.com/v1beta/openai",
        suggestedModel = "gemini-2.0-flash",
        helperText = "Use a key from Google AI Studio; the free tier has rate limits. " +
            "Tap \"Fetch models\" to see the current model ids.",
    ),
    CUSTOM(
        id = "custom",
        label = "Custom (OpenAI-compatible)",
        defaultBaseUrl = "",
        suggestedModel = "",
        helperText = "Any OpenAI-compatible HTTPS endpoint, for example a server you run. " +
            "Enter its base URL (ending in /v1) and a model id. The key can stay empty if the endpoint needs none.",
    ),
    ;

    companion object {
        fun fromId(id: String?): Provider = values().firstOrNull { it.id == id } ?: OPENROUTER
    }
}

data class AppSettings(
    val provider: Provider = Provider.OPENROUTER,
    val baseUrl: String = Provider.OPENROUTER.defaultBaseUrl,
    val model: String = Provider.OPENROUTER.suggestedModel,
    val apiKey: String = "",
    val maxIterations: Int = DEFAULT_MAX_ITERATIONS,
    val streaming: Boolean = true,
    val contextLimitTokens: Int = DEFAULT_CONTEXT_LIMIT,
) {
    val hasApiKey: Boolean get() = apiKey.isNotBlank()

    // Never print the key, even by accident.
    override fun toString(): String =
        "AppSettings(provider=$provider, baseUrl=$baseUrl, model=$model, apiKey=<redacted>, " +
            "maxIterations=$maxIterations, streaming=$streaming, contextLimitTokens=$contextLimitTokens)"
}

/**
 * Stores all settings in EncryptedSharedPreferences. The API key is never written anywhere else.
 * If the Android keystore is unusable, the key is kept in memory only for this process
 * (it is NOT written to plain storage).
 */
class SettingsStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs: SharedPreferences? = openPrefs()
    private var memoryOnlyKey: String = ""

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    fun current(): AppSettings = _settings.value

    fun update(transform: (AppSettings) -> AppSettings) {
        synchronized(this) {
            val next = normalize(transform(_settings.value))
            persist(next)
            _settings.value = next
        }
    }

    /** Switches provider and resets base URL and model to that provider's defaults. */
    fun selectProvider(provider: Provider) {
        update { it.copy(provider = provider, baseUrl = provider.defaultBaseUrl, model = provider.suggestedModel) }
    }

    fun clearApiKey() {
        update { it.copy(apiKey = "") }
    }

    /** Resets everything, including the key, to defaults. */
    fun wipe() {
        synchronized(this) {
            memoryOnlyKey = ""
            prefs?.edit()?.clear()?.apply()
            _settings.value = AppSettings()
        }
    }

    private fun normalize(s: AppSettings): AppSettings = s.copy(
        baseUrl = s.baseUrl.trim().trimEnd('/'),
        model = s.model.trim(),
        apiKey = s.apiKey.trim(),
        maxIterations = s.maxIterations.coerceIn(1, 100),
        contextLimitTokens = s.contextLimitTokens.coerceIn(4_000, 1_000_000),
    )

    private fun persist(s: AppSettings) {
        val p = prefs
        if (p == null) {
            memoryOnlyKey = s.apiKey
            return
        }
        val editor = p.edit()
            .putString(K_PROVIDER, s.provider.id)
            .putString(K_BASE_URL, s.baseUrl)
            .putString(K_MODEL, s.model)
            .putInt(K_MAX_ITER, s.maxIterations)
            .putBoolean(K_STREAMING, s.streaming)
            .putInt(K_CONTEXT, s.contextLimitTokens)
        if (s.apiKey.isBlank()) editor.remove(K_API_KEY) else editor.putString(K_API_KEY, s.apiKey)
        editor.apply()
    }

    private fun load(): AppSettings {
        val p = prefs
        val provider = Provider.fromId(p?.getString(K_PROVIDER, null))
        return AppSettings(
            provider = provider,
            baseUrl = p?.getString(K_BASE_URL, null) ?: provider.defaultBaseUrl,
            model = p?.getString(K_MODEL, null) ?: provider.suggestedModel,
            apiKey = p?.getString(K_API_KEY, null) ?: memoryOnlyKey,
            maxIterations = p?.getInt(K_MAX_ITER, DEFAULT_MAX_ITERATIONS) ?: DEFAULT_MAX_ITERATIONS,
            streaming = p?.getBoolean(K_STREAMING, true) ?: true,
            contextLimitTokens = p?.getInt(K_CONTEXT, DEFAULT_CONTEXT_LIMIT) ?: DEFAULT_CONTEXT_LIMIT,
        )
    }

    private fun openPrefs(): SharedPreferences? {
        return try {
            createEncrypted()
        } catch (e: Exception) {
            // A corrupted keystore entry is the usual cause: start fresh once.
            try {
                appContext.deleteSharedPreferences(FILE_NAME)
                createEncrypted()
            } catch (e2: Exception) {
                null
            }
        }
    }

    private fun createEncrypted(): SharedPreferences {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            appContext,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private companion object {
        const val FILE_NAME = "hermes_secure_prefs"
        const val K_PROVIDER = "provider"
        const val K_BASE_URL = "base_url"
        const val K_MODEL = "model"
        const val K_API_KEY = "api_key"
        const val K_MAX_ITER = "max_iterations"
        const val K_STREAMING = "streaming"
        const val K_CONTEXT = "context_limit"
    }
}
