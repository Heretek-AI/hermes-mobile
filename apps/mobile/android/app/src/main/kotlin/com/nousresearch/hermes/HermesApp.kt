package com.nousresearch.hermes

import android.app.Application
import android.util.Log

/**
 * HermesApp — the Application subclass for the native-Kotlin build.
 *
 * Replaces Capacitor's BridgeActivity as the singleton root. The
 * renderer is gone; the Compose UI in `MainActivity` reads
 * `(application as HermesApp)` and picks up a `hermes: HermesApi`
 * field.
 *
 * Lifetime: created by the system before any Activity. The long-lived
 * state inside HermesApi (chat event SharedFlows, install supervisor,
 * SSH tunnel handle, gateway FGS client) does not need to be
 * re-created on rotation.
 *
 * Phase 1 wiring:
 *  1. Phase 0 registers this class in the manifest via
 *     `android:name=".HermesApp"`.
 *  2. Phase 1 adds `hermes = HermesApi(this)` here.
 *  3. HermesAPIPlugin's `load()` resolves `(application as HermesApp).hermes`
 *     and routes every IPC call to the singleton. The plugin stays
 *     around as a thin bridge for the WebView (which Phase 5 will
 *     remove).
 *  4. Phase 3's `MainActivity` does `setContent { HermesAppTheme {
 *     HermesNavGraph((application as HermesApp).hermes) } }` and
 *     the singleton is consumed by the Compose tree directly.
 */
class HermesApp : Application() {

    lateinit var hermes: HermesApi
        private set

    override fun onCreate() {
        super.onCreate()
        hermes = HermesApi(this)
        Log.i(TAG, "HermesApp.onCreate — HermesApi singleton initialised")
    }

    companion object {
        private const val TAG = "HermesApp"
    }
}
