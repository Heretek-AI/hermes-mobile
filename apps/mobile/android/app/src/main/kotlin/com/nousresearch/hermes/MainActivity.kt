package com.nousresearch.hermes

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import com.nousresearch.hermes.ui.onboarding.InstallScreen
import com.nousresearch.hermes.ui.onboarding.MainScreen
import com.nousresearch.hermes.ui.onboarding.SetupScreen
import com.nousresearch.hermes.ui.onboarding.SplashScreen
import com.nousresearch.hermes.ui.onboarding.WelcomeScreen
import com.nousresearch.hermes.ui.theme.HermesAppTheme
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

/**
 * MainActivity — the launchable activity for the native-Kotlin
 * Compose build. Replaces the Java `BridgeActivity` from Phase 0.
 *
 * Resolves the [HermesApi] singleton from the
 * [HermesApp.onCreate] hook and passes it to the Compose tree.
 * The Capacitor bridge is no longer instantiated — Phase 5
 * removes the `HermesAPIPlugin` and the 14 autolink lines.
 *
 * ## Phase 2: state-machine root
 *
 * The 5-state onboarding flow (Splash → Welcome → Install →
 * Setup → Main) is read from [HermesApi.appState] inside the
 * Compose tree. `setContent` switches on the current state to
 * render the right screen. Each screen can call
 * [HermesApi.setAppState] to advance; the Compose tree
 * re-composes automatically.
 *
 * ## v0.1.0: deep-link routing
 *
 * The Compose-tree [NavHostController] is created inside
 * [com.nousresearch.hermes.ui.HermesNavGraph] (a `rememberNavController`).
 * To dispatch deep links from [handleIntent] we capture the
 * controller via the `onNavControllerReady` callback on
 * [com.nousresearch.hermes.ui.onboarding.MainScreen]. The
 * controller is nullable here because the user might receive a
 * deep link before the Compose tree has rendered (cold start
 * during onboarding) — in that case the link is just emitted to
 * the `deepLink` SharedFlow and ChatViewModel picks it up.
 */
class MainActivity : ComponentActivity() {

    private val hermes: HermesApi by lazy { (application as HermesApp).hermes }
    private val scope = MainScope()
    private var navController: NavHostController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            HermesAppTheme {
                val state by hermes.appState.collectAsState()
                when (state) {
                    HermesApi.AppState.Splash -> SplashScreen(hermes)
                    HermesApi.AppState.Welcome -> WelcomeScreen(hermes)
                    HermesApi.AppState.Installing -> InstallScreen(hermes)
                    HermesApi.AppState.Setup -> SetupScreen(hermes)
                    HermesApi.AppState.Main -> MainScreen(hermes) { controller ->
                        navController = controller
                    }
                }
            }
        }
        // Phase 1.4: route any inbound deep link from the launch
        // intent (cold start) through the HermesApi handlers.
        handleIntent(intent)
    }

    /** Phase 1.4: Android delivers the deep link via [onNewIntent]
     *  on warm-start (when the activity is already running). The
     *  system passes the same Intent that started the activity
     *  to [setIntent] so a subsequent [getIntent] returns the
     *  new URI. We then dispatch to the [HermesApi] handler. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        val uri: Uri? = intent.data
        if (uri != null && uri.scheme == "hermes") {
            when (uri.host) {
                "oauth-callback" -> hermes.handleOAuthCallback(uri)
                "chat" -> {
                    // hermes://chat/<sessionId> — navigate to the
                    // Chat tab. ChatViewModel.handleDeepLink (via
                    // the deepLink SharedFlow) also picks up the
                    // URI and sets activeSessionId, so the user
                    // lands on the right session. We always emit
                    // so the SharedFlow stays the single source
                    // of truth for deep-link state.
                    hermes.emitDeepLink(uri.toString())
                    navigateToTab("chat")
                }
                "skill" -> {
                    // hermes://skill/<name> — Skill detail screen
                    // is a v2 item (per
                    // groovy-fluttering-island.md §"Open items
                    // deferred to v2"). For v0.1.0 we navigate to
                    // the Skills tab so the user sees the skill
                    // listing; the path segment is preserved on
                    // the URI for a future detail screen.
                    hermes.emitDeepLink(uri.toString())
                    navigateToTab("skills")
                }
                else -> hermes.emitDeepLink(uri.toString())
            }
        }
        // Phase 1.3: ACTION_SEND warm-path (text share sheet)
        if (intent.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (!text.isNullOrEmpty()) {
                scope.launch { hermes.emitSharedText(text) }
            }
        }
    }

    /** Switch to a bottom-nav tab while preserving each tab's
     *  back stack. Mirrors the call site in
     *  [com.nousresearch.hermes.ui.HermesNavGraph] so a deep
     *  link from a cold start (or a notification tap) lands
     *  the user on the same screen as tapping the tab. No-op
     *  if the Compose tree hasn't rendered yet — the SharedFlow
     *  path still surfaces the deep link to the relevant
     *  ViewModel. */
    private fun navigateToTab(route: String) {
        val nc = navController ?: return
        nc.navigate(route) {
            popUpTo(nc.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }
}
