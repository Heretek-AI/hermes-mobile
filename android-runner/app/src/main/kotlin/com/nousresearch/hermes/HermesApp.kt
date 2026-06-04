package com.nousresearch.hermes

import android.app.Application
import android.util.Log
import io.sentry.android.core.SentryAndroid

/**
 * HermesApp — the Application subclass for the native-Kotlin build.
 *
 * Replaces Capacitor's BridgeActivity as the singleton root. The
 * renderer is gone; the Compose UI in `MainActivity` reads
 * `(application as HermesApp)` and picks up a `hermes: HermesApi`
 * field.
 *
 * v0.1.0: opt-in Sentry crash reporting. The Sentry SDK is only
 * initialised when the user has explicitly enabled crash reporting
 * via Settings (see [HermesApi.setCrashReportingEnabled]); we
 * avoid the network round-trip on cold start for users who haven't
 * opted in. The DSN is read from a build-config field (set in
 * the build.gradle's `defaultConfig.buildConfigField` line) — it
 * is *not* hardcoded here so the open-source build doesn't ship
 * the project DSN.
 */
class HermesApp : Application() {

    lateinit var hermes: HermesApi
        private set

    override fun onCreate() {
        super.onCreate()
        hermes = HermesApi(this)
        Log.i(TAG, "HermesApp.onCreate — HermesApi singleton initialised")
        maybeInitSentry()
    }

    private fun maybeInitSentry() {
        if (!hermes.getCrashReportingEnabled()) {
            Log.i(TAG, "Sentry disabled by user preference; skipping init")
            return
        }
        val dsn = readDsn()
        if (dsn.isBlank()) {
            Log.i(TAG, "Sentry DSN empty (open-source build); crash reporting disabled")
            return
        }
        try {
            SentryAndroid.init(this) { options ->
                options.dsn = dsn
                options.isDebug = BuildConfig.DEBUG
                options.environment = if (BuildConfig.DEBUG) "debug" else "release"
            }
            Log.i(TAG, "Sentry initialised (env=${if (BuildConfig.DEBUG) "debug" else "release"})")
        } catch (e: Exception) {
            // Sentry init failure is non-fatal — the app still
            // works without crash reporting. We don't want a
            // bad DSN or a missing class to block startup.
            Log.w(TAG, "Sentry init failed; crash reporting disabled: ${e.message}")
        }
    }

    private fun readDsn(): String = try {
        val clazz = Class.forName("com.nousresearch.hermes.BuildConfig")
        val field = clazz.getField("SENTRY_DSN")
        field.get(null) as? String ?: ""
    } catch (_: Exception) { "" }

    companion object {
        private const val TAG = "HermesApp"
    }
}
