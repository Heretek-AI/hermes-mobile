package com.nousresearch.hermes

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.nousresearch.hermes.ui.HermesNavGraph
import com.nousresearch.hermes.ui.theme.HermesAppTheme

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val hermes = (application as HermesApp).hermes
        setContent {
            HermesAppTheme {
                HermesNavGraph(hermes)
            }
        }
    }
}
