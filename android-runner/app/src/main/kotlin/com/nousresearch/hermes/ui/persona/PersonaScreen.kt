package com.nousresearch.hermes.ui.persona

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nousresearch.hermes.HermesApi

/**
 * PersonaScreen — tabbed view of the active profile's
 * persona config (soul, user profile, memory).
 *
 * Phase 4.13: composes existing HermesApi methods
 * (readSoul, readUserProfile, readMemory) into a single
 * tabbed view. The "Edit" affordance is on the dedicated
 * Soul screen (so the markdown editor there can be reused
 * for the user profile).
 */
@Composable
fun PersonaScreen(hermes: HermesApi) {
    var tab by remember { mutableStateOf(0) }
    val soul = remember { hermes.readSoul() }
    val user = remember { hermes.readUserProfile() }
    val memory = remember { kotlinx.coroutines.runBlocking { hermes.readMemory() } }

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Soul") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("User") })
            Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("Memory") })
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val text = when (tab) {
                0 -> soul.content
                1 -> user
                else -> memory.memory.content
            }
            Card {
                Text(
                    text.ifEmpty { "(empty)" },
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}
