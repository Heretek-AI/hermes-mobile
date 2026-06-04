package com.nousresearch.hermes

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.nousresearch.hermes.ui.HermesNavGraph
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
 */
class MainActivity : ComponentActivity() {

    private val hermes: HermesApi by lazy { (application as HermesApp).hermes }
    private val scope = MainScope()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            HermesAppTheme {
                HermesNavGraph(hermes)
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
}
