package com.nousresearch.hermes.ui.onboarding

import androidx.compose.runtime.Composable
import com.nousresearch.hermes.HermesApi
import com.nousresearch.hermes.ui.HermesNavGraph

/**
 * MainScreen — the post-onboarding shell.
 *
 * Wraps the 5-tab [HermesNavGraph] (or, after Phase 3, the 13-
 * destination nav graph). When [HermesApi.appState] is `Main`,
 * `MainActivity` renders this composable.
 */
@Composable
fun MainScreen(hermes: HermesApi) {
    HermesNavGraph(hermes)
}
