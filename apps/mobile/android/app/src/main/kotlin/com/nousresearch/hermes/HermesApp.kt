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
 * Phase 7: opt-in Sentry crash reporting. The Sentry SDK is only
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
        if (!hermes.getCrashReportingEnabled()) return
        val dsn = try {
            // BuildConfig.SENTRY_DSN is set in build.gradle from
            // an env var (or the gradle property file). We avoid
            // the network call entirely if the user hasn't opted
            // in. Phase 7.4 will wire the real Sentry SDK call.
            @Suppress("UNUSED_VARIABLE")
            val unused = "Sentry DSN: ${readDsn()}"
            readDsn()
        } catch (e: Exception) {
            Log.w(TAG, "Sentry DSN unavailable; skipping init: ${e.message}")
            return
        }
        if (dsn.isBlank()) {
            Log.i(TAG, "Sentry DSN empty; crash reporting disabled")
            return
        }
        Log.i(TAG, "Sentry would be initialised with DSN (v1 stub)")
        // Phase 7.4 final:
        //   SentryAndroid.init(this) { options ->
        //     options.dsn = dsn
        //     options.environment = if (BuildConfig.DEBUG) "debug" else "release"
        //   }
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
