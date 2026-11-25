package com.hermes.clone

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.hermes.clone.ui.chat.ChatScreen
import com.hermes.clone.ui.memory.MemoriesScreen
import com.hermes.clone.ui.sessions.SessionsScreen
import com.hermes.clone.ui.settings.SettingsScreen
import com.hermes.clone.ui.skills.SkillDetailScreen
import com.hermes.clone.ui.skills.SkillsScreen
import com.hermes.clone.ui.theme.HermesCloneTheme
import com.hermes.clone.ui.todos.TodosScreen

object Routes {
    const val CHAT_PATTERN = "chat?sessionId={sessionId}"
    const val SESSIONS = "sessions"
    const val SKILLS = "skills"
    const val SKILL_PATTERN = "skill/{name}"
    const val MEMORIES = "memories"
    const val TODOS = "todos"
    const val SETTINGS = "settings"

    fun chat(sessionId: String?): String = if (sessionId == null) "chat" else "chat?sessionId=$sessionId"
    fun skill(name: String): String = "skill/${Uri.encode(name)}"
}

/** Single activity. Rotation is handled by the manifest (configChanges), so nothing restarts. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            HermesCloneTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    HermesApp()
                }
            }
        }
    }
}

@Composable
fun HermesApp() {
    val nav = rememberNavController()

    // Opening a chat replaces the chat/sessions entries, so Back from a chat leaves the app.
    fun openChat(sessionId: String) {
        nav.navigate(Routes.chat(sessionId)) {
            popUpTo(Routes.CHAT_PATTERN) { inclusive = true }
            launchSingleTop = true
        }
    }

    NavHost(navController = nav, startDestination = Routes.CHAT_PATTERN) {
        composable(
            route = Routes.CHAT_PATTERN,
            arguments = listOf(
                navArgument("sessionId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { entry ->
            ChatScreen(
                sessionId = entry.arguments?.getString("sessionId"),
                onOpenChat = { openChat(it) },
                onOpenSessions = { nav.navigate(Routes.SESSIONS) },
                onOpenSkills = { nav.navigate(Routes.SKILLS) },
                onOpenMemories = { nav.navigate(Routes.MEMORIES) },
                onOpenTodos = { nav.navigate(Routes.TODOS) },
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
            )
        }
        composable(Routes.SESSIONS) {
            SessionsScreen(onBack = { nav.popBackStack() }, onOpenChat = { openChat(it) })
        }
        composable(Routes.SKILLS) {
            SkillsScreen(
                onBack = { nav.popBackStack() },
                onOpenSkill = { nav.navigate(Routes.skill(it)) },
            )
        }
        composable(
            route = Routes.SKILL_PATTERN,
            arguments = listOf(navArgument("name") { type = NavType.StringType }),
        ) { entry ->
            SkillDetailScreen(
                name = entry.arguments?.getString("name").orEmpty(),
                onBack = { nav.popBackStack() },
            )
        }
        composable(Routes.MEMORIES) { MemoriesScreen(onBack = { nav.popBackStack() }) }
        composable(Routes.TODOS) { TodosScreen(onBack = { nav.popBackStack() }) }
        composable(Routes.SETTINGS) { SettingsScreen(onBack = { nav.popBackStack() }) }
    }
}
