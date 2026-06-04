package com.nousresearch.hermes.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.nousresearch.hermes.HermesApi
import com.nousresearch.hermes.ui.chat.ChatScreen
import com.nousresearch.hermes.ui.memory.MemoryScreen
import com.nousresearch.hermes.ui.placeholder.PlaceholderScreen
import com.nousresearch.hermes.ui.sessions.SessionsScreen

/**
 * HermesNavGraph — the 5-tab bottom nav. Phase A: only Chat is
 * functional; Sessions / Skills / Memory / Settings show "Coming
 * in Phase B/C/D" placeholders.
 *
 * The 5-tab bottom nav is the only nav in Phase A — no sidebar.
 * The desktop's 14-view sidebar is gone (and stays gone on
 * mobile — phones don't have sidebars).
 *
 * Phase B adds the Sessions + Memory screens. Phase C adds
 * Settings + Profiles. Phase D adds Skills + the rest.
 */
sealed class Tab(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    data object Chat : Tab("chat", "Chat", Icons.Filled.Chat)
    data object Sessions : Tab("sessions", "Sessions", Icons.Filled.Storage)
    data object Skills : Tab("skills", "Skills", Icons.Filled.SmartToy)
    data object Memory : Tab("memory", "Memory", Icons.Filled.Memory)
    data object Settings : Tab("settings", "Settings", Icons.Filled.Settings)

    companion object {
        val all: List<Tab> = listOf(Chat, Sessions, Skills, Memory, Settings)
    }
}

@Composable
fun HermesNavGraph(hermes: HermesApi) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            NavigationBar {
                Tab.all.forEach { tab ->
                    val selected = currentRoute == tab.route
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            if (currentRoute != tab.route) {
                                navController.navigate(tab.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Tab.Chat.route,
            modifier = Modifier.padding(padding),
        ) {
            composable(Tab.Chat.route) { ChatScreen(hermes) }
            composable(Tab.Sessions.route) { SessionsScreen(hermes) }
            composable(Tab.Skills.route) {
                PlaceholderScreen("Skills", "Coming in Phase D")
            }
            composable(Tab.Memory.route) { MemoryScreen(hermes) }
            composable(Tab.Settings.route) {
                PlaceholderScreen("Settings", "Coming in Phase C")
            }
        }
    }
}
