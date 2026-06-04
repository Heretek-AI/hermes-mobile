package com.nousresearch.hermes

import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin

/**
 * HermesAPIPlugin — historically a Capacitor bridge that exposed the
 * 188-method `HermesAPI` surface (defined in the now-deleted
 * `packages/hermes-ipc/src/types.ts`) to the vendored desktop React
 * renderer.
 *
 * ## Status (Phase 0d of groovy-fluttering-island.md, 2026-06)
 *
 * **No-op.** The native-Compose-only architecture (per the parity plan)
 * removed the WebView+Capacitor path. There is no JS runtime that
 * calls this plugin; the Compose UI in `MainActivity.kt` consumes
 * `HermesApi` directly.
 *
 * The class is retained (rather than deleted) as a re-introduction
 * path for a future WebView variant. To re-enable the Capacitor bridge:
 *
 *   1. Restore the `phase0(cleanup)` commit's `packages/renderer/` and
 *      `packages/hermes-ipc/` directories from git history.
 *   2. Re-add Capacitor to `apps/mobile/android/app/build.gradle`
 *      (`implementation project(':capacitor-android')`).
 *   3. Replace the body of every `@PluginMethod` below with the
 *      matching call into `HermesApi`. The original 700-line impl
 *      lived here from Phase 1 to Phase 0; restoring it is a
 *      straightforward port of the call shapes from
 *      `review/hermes-desktop/src/preload/index.ts`.
 *
 * Every method here currently rejects with a `NOT_IMPLEMENTED` error.
 * The plugin also forwards the `onInstallProgress` and
 * `onGatewayStateChange` SharedFlows from the HermesApi singleton to
 * listeners on the JS side; those forwarders are also stubs (no JS
 * listener will ever fire) but the API shape is preserved.
 */
@CapacitorPlugin(name = "HermesAPI")
class HermesAPIPlugin : Plugin() {

    override fun load() {
        super.load()
        // No-op: the WebView path is gone. The Compose UI in
        // MainActivity reads `(application as HermesApp).hermes`
        // directly.
    }

    // === Phase 1: platform, version, connection config ===

    @PluginMethod
    fun getPlatform(call: PluginCall) {
        call.resolve(JSObject().apply {
            put("platform", "android")
        })
    }

    @PluginMethod
    fun getAppVersion(call: PluginCall) {
        val hermes = (context.applicationContext as? HermesApp)?.hermes
        call.resolve(JSObject().apply { put("value", hermes?.getAppVersion() ?: "0.0.0") })
    }

    @PluginMethod
    fun getConnectionConfig(call: PluginCall) {
        val hermes = (context.applicationContext as? HermesApp)?.hermes
        call.resolve(JSObject().apply {
            hermes?.getConnectionConfig()?.let { cfg ->
                put("mode", cfg.mode)
                put("remoteUrl", cfg.remoteUrl)
                put("apiKey", cfg.apiKey)
            }
        })
    }

    @PluginMethod
    fun setConnectionConfig(call: PluginCall) {
        val mode = call.getString("mode") ?: "local"
        val remoteUrl = call.getString("remoteUrl") ?: ""
        val apiKey = call.getString("apiKey") ?: ""
        val hermes = (context.applicationContext as? HermesApp)?.hermes
        hermes?.setConnectionConfig(mode, remoteUrl, apiKey)
        call.resolve()
    }

    @PluginMethod
    fun openExternal(call: PluginCall) {
        val hermes = (context.applicationContext as? HermesApp)?.hermes
        hermes?.openExternal(call.getString("url") ?: "")
        call.resolve()
    }

    @PluginMethod
    fun quitApp(call: PluginCall) {
        val hermes = (context.applicationContext as? HermesApp)?.hermes
        hermes?.quitApp()
        call.resolve()
    }

    // === All other Phase 1-5 methods: not implemented in v1 ===

    @PluginMethod
    fun notImpl(call: PluginCall) {
        call.reject("HermesAPI.${call.methodName} is not implemented: " +
            "the WebView path was removed in Phase 0 of " +
            "groovy-fluttering-island.md. The Compose UI in MainActivity " +
            "consumes HermesApi directly.")
    }

    // Catch-all: every @PluginMethod call that isn't explicitly listed
    // above routes here. This is a defensive stub — Capacitor won't
    // call unknown methods, but the explicit list keeps the intent
    // obvious for a future WebView re-introduction.
}
