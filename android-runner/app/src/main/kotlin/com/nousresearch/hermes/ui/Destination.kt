package com.nousresearch.hermes.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apartment
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.ViewKanban
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Destination — sealed class covering the 14 desktop views
 * (Phase 3 expansion). The 5 [BottomTab] entries are the
 * bottom-nav items; the 9 [MoreEntry] entries live in the
 * "More" overflow sheet.
 *
 * Phase 4 will replace the [PlaceholderScreen] composables
 * with real screens (Skills, Settings, Soul, Models, …).
 */
sealed class Destination(val route: String) {

    /** Bottom-nav items (5 of them). */
    sealed class BottomTab(route: String, val label: String, val icon: ImageVector) :
        Destination(route) {
        data object Chat : BottomTab("chat", "Chat", Icons.Filled.Chat)
        data object Sessions : BottomTab("sessions", "Sessions", Icons.Filled.Storage)
        data object Skills : BottomTab("skills", "Skills", Icons.Filled.SmartToy)
        data object Memory : BottomTab("memory", "Memory", Icons.Filled.Memory)
        data object Settings : BottomTab("settings", "Settings", Icons.Filled.Settings)

        companion object {
            val all: List<BottomTab> = listOf(Chat, Sessions, Skills, Memory, Settings)
        }
    }

    /** Overflow items shown in the "More" bottom sheet (9 of them). */
    sealed class MoreEntry(route: String, val label: String, val icon: ImageVector) :
        Destination(route) {
        data object Discover : MoreEntry("discover", "Discover", Icons.Filled.Explore)
        data object Agents : MoreEntry("agents", "Agents", Icons.Filled.People)
        data object Office : MoreEntry("office", "Office", Icons.Filled.Apps)
        data object Kanban : MoreEntry("kanban", "Kanban", Icons.Filled.ViewKanban)
        data object Models : MoreEntry("models", "Models", Icons.Filled.AutoAwesome)
        data object Providers : MoreEntry("providers", "Providers", Icons.Filled.Cloud)
        data object Tools : MoreEntry("tools", "Tools", Icons.Filled.Build)
        data object Schedules : MoreEntry("schedules", "Schedules", Icons.Filled.Schedule)
        data object Gateway : MoreEntry("gateway", "Gateway", Icons.Filled.Bolt)
        data object Soul : MoreEntry("soul", "Soul", Icons.Filled.Person)
        data object Persona : MoreEntry("persona", "Persona", Icons.Filled.Apartment)
        data object Dashboard : MoreEntry("dashboard", "Dashboard", Icons.Filled.Dashboard)

        companion object {
            val all: List<MoreEntry> = listOf(
                Discover, Agents, Office, Kanban, Models,
                Providers, Tools, Schedules, Gateway, Soul, Persona, Dashboard,
            )
        }
    }
}
