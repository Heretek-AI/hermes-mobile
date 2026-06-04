package com.nousresearch.hermes.ui.onboarding

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import com.nousresearch.hermes.HermesApi
import com.nousresearch.hermes.ui.HermesNavGraph

/**
 * MainScreen — the post-onboarding shell.
 *
 * Wraps the 5-tab [HermesNavGraph] (or, after Phase 3, the 13-
 * destination nav graph). When [HermesApi.appState] is `Main`,
 * `MainActivity` renders this composable.
 *
 * v0.1.0: an optional [onNavControllerReady] callback hands the
 * Compose-tree [NavHostController] back to the host activity so
 * deep-link routing in [com.nousresearch.hermes.MainActivity]
 * can navigate to a tab from a cold-start intent or a
 * notification. Defaults to a no-op so callers that don't need
 * the controller (e.g. previews) can omit it.
 */
@Composable
fun MainScreen(
    hermes: HermesApi,
    onNavControllerReady: (NavHostController) -> Unit = {},
) {
    HermesNavGraph(hermes, onNavControllerReady)
}
