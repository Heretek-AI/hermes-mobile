package com.nousresearch.hermes

import android.app.Application
import android.util.Log

/**
 * HermesApp — the Application subclass for the native-Kotlin build.
 *
 * Replaces Capacitor's BridgeActivity as the singleton root. The
 * renderer is gone; the Compose UI in `MainActivity` reads
 * `(application as HermesApp)` and will pick up a `hermes: HermesApi`
 * field once Phase 1 lands.
 *
 * Lifetime: created by the system before any Activity. The long-lived
 * state inside HermesApi (chat event SharedFlows, install supervisor,
 * SSH tunnel handle, gateway FGS client) does not need to be
 * re-created on rotation.
 *
 * Phase A wiring order:
 *  1. Phase 0 registers this class in the manifest via
 *     `android:name=".HermesApp"`. This file is a no-op stub
 *     until Phase 1 introduces HermesApi.
 *  2. Phase 1 adds `hermes = HermesApi(this)` here.
 *  3. Phase 3's `MainActivity` does `setContent { HermesAppTheme {
 *     HermesNavGraph((application as HermesApp).hermes) } }` and
 *     the singleton is consumed by the Compose tree.
 */
class HermesApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "HermesApp.onCreate — phase 0 stub. " +
            "HermesApi will be wired in Phase 1.")
        // Phase 1 will instantiate HermesApi here:
        //   hermes = HermesApi(this)
    }

    companion object {
        private const val TAG = "HermesApp"
    }
}
