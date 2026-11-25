package com.hermes.clone

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.hermes.clone.agent.AgentLoop
import com.hermes.clone.agent.Compression
import com.hermes.clone.agent.CurrentTimeTool
import com.hermes.clone.agent.DelegateTaskTool
import com.hermes.clone.agent.MemoryTool
import com.hermes.clone.agent.ProviderClient
import com.hermes.clone.agent.SessionSearchTool
import com.hermes.clone.agent.SkillViewTool
import com.hermes.clone.agent.SkillsListTool
import com.hermes.clone.agent.Subagent
import com.hermes.clone.agent.TodoTool
import com.hermes.clone.agent.ToolRegistry
import com.hermes.clone.data.AppDatabase
import com.hermes.clone.data.HermesRepository
import com.hermes.clone.data.SettingsStore
import com.hermes.clone.data.SkillsStore

/**
 * Hand-wired dependency graph (no DI framework, no Application subclass needed).
 * Screens get it with AppGraph.get(context); it lives as long as the process.
 */
class AppGraph private constructor(context: Context) {
    private val appContext: Context = context.applicationContext

    val settings = SettingsStore(appContext)
    val skills = SkillsStore(appContext)
    val repository = HermesRepository(AppDatabase.create(appContext))
    val provider = ProviderClient()

    /** Tools of the main agent. */
    val tools: ToolRegistry

    val agent: AgentLoop

    init {
        // The sub-agent gets every tool except delegate_task, so it cannot start sub-agents itself.
        val baseTools = listOf(
            CurrentTimeTool(),
            TodoTool(repository),
            MemoryTool(repository),
            SessionSearchTool(repository),
            SkillsListTool(skills),
            SkillViewTool(skills),
        )
        val subagent = Subagent(provider, ToolRegistry(baseTools))
        tools = ToolRegistry(baseTools + DelegateTaskTool(subagent))
        agent = AgentLoop(
            repo = repository,
            provider = provider,
            registry = tools,
            settingsStore = settings,
            skills = skills,
            compression = Compression(repository, provider),
            isOnline = { isOnline(appContext) },
        )
    }

    /** Settings > "Clear all data": chats, memories, todos, skill switches, and the API key. */
    suspend fun clearAllData() {
        repository.deleteAllSessions() // messages go with them (cascade)
        repository.deleteAllMemories()
        repository.deleteAllTodos()
        skills.clearFlags()
        settings.wipe()
    }

    companion object {
        @Volatile
        private var instance: AppGraph? = null

        fun get(context: Context): AppGraph =
            instance ?: synchronized(this) {
                instance ?: AppGraph(context).also { instance = it }
            }

        /** True when the phone has a network that claims internet access. Needs ACCESS_NETWORK_STATE. */
        private fun isOnline(context: Context): Boolean =
            try {
                val manager = context.getSystemService(ConnectivityManager::class.java)
                val network = manager?.activeNetwork
                val caps = if (manager != null && network != null) manager.getNetworkCapabilities(network) else null
                when {
                    manager == null -> true // cannot tell: let the request itself decide
                    caps == null -> false
                    else -> caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                }
            } catch (e: SecurityException) {
                true
            }
    }
}
