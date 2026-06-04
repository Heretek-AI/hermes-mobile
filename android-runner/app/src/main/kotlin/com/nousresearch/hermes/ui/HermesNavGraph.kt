package com.nousresearch.hermes.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.nousresearch.hermes.HermesApi
import com.nousresearch.hermes.ui.chat.ChatScreen
import com.nousresearch.hermes.ui.components.TopBarWithBack
import com.nousresearch.hermes.ui.memory.MemoryScreen
import com.nousresearch.hermes.ui.more.MoreSheet
import com.nousresearch.hermes.ui.placeholder.PlaceholderScreen
import com.nousresearch.hermes.ui.sessions.SessionsScreen

/**
 * HermesNavGraph — Phase 3: 5 bottom-nav tabs + 12 overflow
 * destinations = 14 routes (matches the desktop's 14-view
 * sidebar).
 *
 * Layout:
 * - **Bottom nav (5):** Chat, Sessions, Skills, Memory, Settings
 * - **6th tab "More":** opens a [MoreSheet] with the 12 secondary
 *   destinations. Tapping one navigates to it; the screen shows
 *   a back arrow in its top app bar that returns to the
 *   previously-selected bottom-nav tab.
 *
 * Phase 4 will replace the [PlaceholderScreen] composables
 * with real screens. The 4 bottom-nav items that already have
 * real screens (Chat, Sessions, Memory) wire up to them here.
 */
@Composable
fun HermesNavGraph(hermes: HermesApi) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    var showMore by rememberSaveable { mutableStateOf(false) }

    // True if the current route is a More destination (we hide
    // the bottom nav on those screens so the back arrow is the
    // only way back to a bottom tab).
    val isMoreRoute = currentRoute in Destination.MoreEntry.all.map { it.route }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            if (isMoreRoute) {
                val dest = Destination.MoreEntry.all.firstOrNull { it.route == currentRoute }
                TopBarWithBack(title = dest?.label ?: "") {
                    navController.popBackStack()
                }
            }
        },
        bottomBar = {
            if (!isMoreRoute) {
                NavigationBar {
                    Destination.BottomTab.all.forEach { tab ->
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
                    // The 6th "More" tab
                    NavigationBarItem(
                        selected = false,
                        onClick = { showMore = true },
                        icon = {
                            Icon(
                                Icons.Filled.MoreHoriz,
                                contentDescription = "More",
                            )
                        },
                        label = { Text("More") },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Destination.BottomTab.Chat.route,
            modifier = Modifier.padding(padding),
        ) {
            // ── Bottom-nav destinations (5) ────────────────────
            composable(Destination.BottomTab.Chat.route) { ChatScreen(hermes) }
            composable(Destination.BottomTab.Sessions.route) { SessionsScreen(hermes) }
            composable(Destination.BottomTab.Skills.route) {
                PlaceholderScreen("Skills", "Manage installed and bundled skills")
            }
            composable(Destination.BottomTab.Memory.route) { MemoryScreen(hermes) }
            composable(Destination.BottomTab.Settings.route) {
                PlaceholderScreen("Settings", "Configure the Hermes agent")
            }

            // ── Overflow destinations (12) ────────────────────
            Destination.MoreEntry.all.forEach { entry ->
                composable(entry.route) {
                    PlaceholderScreen(entry.label, "Coming in Phase 4")
                }
            }
        }

        if (showMore) {
            MoreSheet(
                onDismiss = { showMore = false },
                onPick = { entry ->
                    showMore = false
                    navController.navigate(entry.route)
                },
            )
        }
    }
}
