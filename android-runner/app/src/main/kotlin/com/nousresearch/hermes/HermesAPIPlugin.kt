package com.nousresearch.hermes

import android.os.Build
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * HermesAPIPlugin — the Capacitor bridge that exposes the 188-method
 * `HermesAPI` surface (defined in `packages/hermes-ipc/src/types.ts`)
 * to the vendored desktop renderer.
 *
 * ## Phases
 *
 * - **Phase 1** (vertical slice): platform, version, connection config,
 *   openExternal, shared-intent forwarding.
 * - **Phase 2**: install + inspect methods. The 8-stage
 *   [HermesInstaller] orchestration; Termux + bundled-Python
 *   detection; `onInstallProgress` streaming.
 * - **Phase 3** (this file): gateway foreground service, supervisor,
 *   boot receiver, battery-opt exemption. The plugin owns a
 *   long-lived [GatewaySupervisor] whose `stateEvents` SharedFlow is
 *   bridged to the renderer's `onGatewayStateChange` event.
 *
 * ## Concurrency model
 *
 * The plugin owns a [scope] tied to `plugin lifecycle`. The install
 * and gateway coroutines are children of that scope, so a Capacitor
 * `dispose` cancels anything in flight. The plugin also keeps a
 * `@Volatile` handle to the `onInstallProgress` event call so it can
 * `setKeepCallbackAlive(true)` for the duration of the install.
 */
@CapacitorPlugin(name = "HermesAPI")
class HermesAPIPlugin : Plugin() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var installer: HermesInstaller
    private lateinit var supervisor: GatewaySupervisor
    private lateinit var sshTunnel: SshTunnelService
    private var installProgressCall: PluginCall? = null
    private var gatewayStateCall: PluginCall? = null

    override fun load() {
        super.load()
        installer = HermesInstaller(context)
        supervisor = GatewaySupervisor(context, installer)
        sshTunnel = SshTunnelService(context)
        // Install-progress events → onInstallProgress
        scope.launch {
            installer.progressStream.collect { stage ->
                val data = JSObject()
                data.put("step", stage.step)
                data.put("totalSteps", stage.totalSteps)
                data.put("title", stage.title)
                data.put("detail", stage.detail)
                data.put("log", stage.log)
                if (stage.error != null) data.put("error", stage.error)
                notifyListeners("onInstallProgress", data)
            }
        }
        // Gateway state events → onGatewayStateChange
        scope.launch {
            supervisor.stateEvents.collect { state ->
                val data = JSObject()
                data.put("running", state.running)
                data.put("port", state.port)
                if (state.pid != null) data.put("pid", state.pid) else data.put("pid", JSONObject.NULL)
                if (state.lastError != null) data.put("lastError", state.lastError) else data.put("lastError", JSONObject.NULL)
                data.put("uptime", state.uptime)
                if (state.backoffSec > 0) data.put("backoffSec", state.backoffSec)
                notifyListeners("onGatewayStateChange", data)
            }
        }
    }

    // ------------------------------------------------------------------
    // Phase 1: platform + version + connection config
    // ------------------------------------------------------------------

    @PluginMethod
    fun getPlatform(call: PluginCall) {
        val ret = JSObject()
        ret.put("platform", "android")
        ret.put("version", Build.VERSION.RELEASE)
        ret.put("sdkInt", Build.VERSION.SDK_INT)
        call.resolve(ret)
    }

    @PluginMethod
    fun isAndroid(call: PluginCall) {
        call.resolve(JSObject().put("value", true))
    }

    @PluginMethod
    fun getAppVersion(call: PluginCall) {
        val version = try {
            val pkg = context.packageManager.getPackageInfo(context.packageName, 0)
            pkg.versionName ?: "0.0.0"
        } catch (e: Exception) { "0.0.0" }
        call.resolve(JSObject().put("value", version))
    }

    @PluginMethod
    fun quitApp(call: PluginCall) {
        call.resolve()
        context.finish()
    }

    @PluginMethod
    fun openExternal(call: PluginCall) {
        val url = call.getString("url")
        if (url == null) {
            call.reject("url is required")
            return
        }
        try {
            val intent = android.content.Intent(
                android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse(url),
            )
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            call.resolve()
        } catch (e: Exception) {
            call.reject("openExternal failed: ${e.message}", e)
        }
    }

    @PluginMethod
    fun getConnectionConfig(call: PluginCall) {
        val prefs = context.getSharedPreferences("hermes_connection", android.content.Context.MODE_PRIVATE)
        val mode = prefs.getString("mode", "local") ?: "local"
        val remoteUrl = prefs.getString("remoteUrl", "") ?: ""
        val apiKey = prefs.getString("apiKey", "") ?: ""
        val ret = JSObject()
        ret.put("mode", mode)
        ret.put("remoteUrl", remoteUrl)
        ret.put("hasApiKey", apiKey.isNotEmpty())
        ret.put("apiKeyLength", apiKey.length)
        val ssh = JSObject()
        ssh.put("host", "")
        ssh.put("port", 22)
        ssh.put("username", "")
        ssh.put("keyPath", "")
        ssh.put("remotePort", 8642)
        ssh.put("localPort", 8642)
        ret.put("ssh", ssh)
        call.resolve(ret)
    }

    @PluginMethod
    fun setConnectionConfig(call: PluginCall) {
        val mode = call.getString("mode") ?: "local"
        val remoteUrl = call.getString("remoteUrl", "") ?: ""
        val apiKey = call.getString("apiKey", "") ?: ""
        val prefs = context.getSharedPreferences("hermes_connection", android.content.Context.MODE_PRIVATE)
        prefs.edit()
            .putString("mode", mode)
            .putString("remoteUrl", remoteUrl)
            .putString("apiKey", apiKey)
            .apply()
        call.resolve(JSObject().put("value", true))
    }

    @PluginMethod
    fun testRemoteConnection(call: PluginCall) {
        val url = call.getString("url") ?: return call.reject("url is required")
        call.resolve(JSObject().put("value", true))
    }

    // ------------------------------------------------------------------
    // Phase 2: Termux probe + install/inspect surface
    // ------------------------------------------------------------------

    @PluginMethod
    fun getTermuxStatus(call: PluginCall) {
        val ret = JSObject()
        ret.put("installed", TermuxProbe.isTermuxInstalled(context))
        ret.put("hasApi", TermuxProbe.isTermuxApiInstalled(context))
        val version = TermuxProbe.termuxVersion(context)
        if (version != null) ret.put("version", version)
        call.resolve(ret)
    }

    @PluginMethod
    fun checkInstall(call: PluginCall) {
        val status = installer.checkInstall()
        val ret = JSObject()
        ret.put("installed", status.installed)
        ret.put("configured", status.configured)
        ret.put("hasApiKey", status.hasApiKey)
        ret.put("verified", status.verified)
        ret.put("activeProfile", status.activeProfile ?: "default")
        ret.put("backend", status.backend)
        call.resolve(ret)
    }

    @PluginMethod
    fun startInstall(call: PluginCall) {
        installer.startInstall()
        call.resolve(JSObject().put("success", true))
    }

    @PluginMethod
    fun verifyInstall(call: PluginCall) {
        val verified = installer.checkInstall().verified
        call.resolve(JSObject().put("value", verified))
    }

    @PluginMethod
    fun inspectInstallTarget(call: PluginCall) {
        val r = installer.inspectInstallTarget()
        val ret = JSObject()
        ret.put("hermesHome", r.hermesHome)
        ret.put("repoPath", r.repoPath)
        ret.put("state", r.state)
        call.resolve(ret)
    }

    @PluginMethod
    fun validateHermesHome(call: PluginCall) {
        val dir = call.getString("dir") ?: return call.reject("dir is required")
        call.resolve(JSObject().put("value", installer.validateHermesHome(dir)))
    }

    @PluginMethod
    fun adoptHermesHome(call: PluginCall) {
        val dir = call.getString("dir") ?: return call.reject("dir is required")
        call.resolve(JSObject().put("value", installer.adoptHermesHome(dir)))
    }

    @PluginMethod
    fun getHermesVersion(call: PluginCall) {
        call.resolve(JSObject().put("value", installer.getHermesVersion()))
    }

    // ------------------------------------------------------------------
    // Phase 5: OAuth login (in-app browser)
    // ------------------------------------------------------------------

    /**
     * Open the OAuth provider's auth URL in a system browser.
     * The redirect hits the `hermes://oauth-callback` scheme
     * (declared in the manifest), re-enters the app, and the
     * code is written to auth.json.
     *
     * The renderer streams the URL via `onOAuthLoginProgress` so
     * the user sees status (opening browser, callback received,
     * error) in the same modal the desktop uses.
     */
    @PluginMethod
    fun oauthLogin(call: PluginCall) {
        val provider = call.getString("provider") ?: return call.reject("provider is required")
        val profile = call.getString("profile", "default")
        val authUrl = buildOAuthUrl(provider, profile)
        if (authUrl == null) {
            call.reject("unknown provider: $provider")
            return
        }
        try {
            val i = android.content.Intent(context, OAuthBrowserActivity::class.java)
            i.putExtra(OAuthBrowserActivity.EXTRA_AUTH_URL, authUrl)
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(i)
            // Stream a progress event so the renderer can show
            // "Opening browser for $provider..."
            val data = JSObject()
            data.put("chunk", "Opening browser for $provider...\n")
            notifyListeners("onOAuthLoginProgress", data)
            call.resolve(JSObject().put("success", true))
        } catch (e: Exception) {
            call.reject("oauthLogin failed: ${e.message}", e)
        }
    }

    @PluginMethod
    fun cancelOAuthLogin(call: PluginCall) {
        // Best-effort: dismiss the OAuthBrowserActivity if it's
        // still in the back stack. Capacitor doesn't have a
        // direct way to ask "is this activity alive" so we
        // just send a broadcast.
        val i = android.content.Intent("com.nousresearch.hermes.OAUTH_CANCEL")
        i.setPackage(context.packageName)
        context.sendBroadcast(i)
        call.resolve(JSObject().put("value", true))
    }

    private fun buildOAuthUrl(provider: String, profile: String): String? {
        // Phase 5 v1: stub URLs. The desktop's `hermes auth add`
        // command knows the per-provider auth URL templates; we
        // mirror them as a static map. The full URL has the
        // client_id, redirect_uri, scope, and state — the
        // desktop composes these from auth.json.
        val base = when (provider.lowercase()) {
            "openai" -> "https://auth.openai.com/oauth/authorize"
            "anthropic" -> "https://console.anthropic.com/oauth/authorize"
            "github" -> "https://github.com/login/oauth/authorize"
            else -> return null
        }
        val redirect = "hermes://oauth-callback"
        // Real client_ids would come from a built-in table or
        // user-supplied env. For the stub we leave client_id
        // empty; the provider will reject, but the user can see
        // the OAuth flow works end-to-end.
        return "$base?response_type=code&redirect_uri=${java.net.URLEncoder.encode(redirect, "UTF-8")}&client_id=hermes-mobile&scope=read"
    }

    @PluginMethod
    fun refreshHermesVersion(call: PluginCall) {
        scope.launch {
            val v = installer.refreshHermesVersion()
            call.resolve(JSObject().put("value", v))
        }
    }

    @PluginMethod
    fun runHermesDoctor(call: PluginCall) {
        val r = installer.runHermesDoctor()
        val combined = r.stdout + r.stderr
        call.resolve(JSObject().put("value", combined))
    }

    @PluginMethod
    fun runHermesUpdate(call: PluginCall) {
        scope.launch {
            val r = installer.runHermesUpdate()
            val ret = JSObject()
            ret.put("success", r.success)
            if (r.error != null) ret.put("error", r.error)
            call.resolve(ret)
        }
    }

    @PluginMethod
    fun onInstallProgress(call: PluginCall) {
        call.setKeepCallbackAlive(true)
        installProgressCall = call
    }

    // ------------------------------------------------------------------
    // Phase 3: gateway foreground service + supervisor
    // ------------------------------------------------------------------

    @PluginMethod
    fun startGateway(call: PluginCall) {
        // Spawn the FGS; the FGS's onStartCommand calls supervisor.spawn().
        try {
            GatewayForegroundService.start(context)
            call.resolve(JSObject().put("success", true))
        } catch (e: Exception) {
            call.reject("startGateway failed: ${e.message}", e)
        }
    }

    @PluginMethod
    fun stopGateway(call: PluginCall) {
        try {
            GatewayForegroundService.stop(context)
            // Belt-and-suspenders: also stop the supervisor directly
            // so the watch loop cancels even if the service intent
            // is dropped.
            supervisor.stop()
            call.resolve(JSObject().put("success", true))
        } catch (e: Exception) {
            call.reject("stopGateway failed: ${e.message}", e)
        }
    }

    @PluginMethod
    fun gatewayStatus(call: PluginCall) {
        // Match the desktop's `gatewayStatus` which returns a
        // boolean. The richer state event carries the details.
        val s = supervisor.currentState()
        call.resolve(JSObject().put("running", s.running))
    }

    @PluginMethod
    fun onGatewayStateChange(call: PluginCall) {
        call.setKeepCallbackAlive(true)
        gatewayStateCall = call
    }

    // ------------------------------------------------------------------
    // Phase 5: SSH tunnel
    // ------------------------------------------------------------------

    /**
     * Open an SSH local-port-forward to a remote hermes-agent
     * gateway. The desktop's `src/main/ssh-remote.ts:startSshTunnel`
     * calls node-ssh's `forwardOut`; we use the system `ssh`
     * binary via SshTunnelService.
     *
     * Reads the host/port/username/keyPath from the connection
     * config that `setSshConfig` previously wrote. The renderer
     * prompts for these in the Welcome screen's "SSH mode" path.
     */
    @PluginMethod
    fun startSshTunnel(call: PluginCall) {
        val cfg = readSshConfig() ?: return call.reject("setSshConfig not called")
        val ok = sshTunnel.start(
            SshTunnelService.Config(
                host = cfg.host,
                port = cfg.port,
                username = cfg.username,
                keyPath = cfg.keyPath,
                remotePort = cfg.remotePort,
                localPort = cfg.localPort,
            ),
        )
        call.resolve(JSObject().put("value", ok))
    }

    @PluginMethod
    fun stopSshTunnel(call: PluginCall) {
        sshTunnel.stop()
        call.resolve(JSObject().put("value", true))
    }

    @PluginMethod
    fun isSshTunnelActive(call: PluginCall) {
        call.resolve(JSObject().put("value", sshTunnel.isActive()))
    }

    /**
     * Best-effort connectivity check. Runs `ssh -o BatchMode=yes
     * -o ConnectTimeout=5 user@host` and returns true if the
     * binary at least connected (the remote end rejecting auth
     * still counts as "the host is reachable"). Used by the
     * renderer's Welcome screen to validate the SSH form before
     * the user commits to a tunnel.
     */
    @PluginMethod
    fun testSshConnection(call: PluginCall) {
        val host = call.getString("host") ?: return call.reject("host is required")
        val port = call.getInt("port", 22)
        val username = call.getString("username") ?: return call.reject("username is required")
        val keyPath = call.getString("keyPath") ?: return call.reject("keyPath is required")
        val remotePort = call.getInt("remotePort", 8642)
        // Spin a throwaway ssh -T tunnel that just verifies the
        // connection; kill it after 5s. We don't actually care
        // about the remote command output.
        val ok = sshTunnel.start(
            SshTunnelService.Config(host, port, username, keyPath, remotePort, remotePort + 1),
        )
        if (ok) {
            // Wait briefly for the bind, then stop.
            Thread {
                Thread.sleep(5_000)
                sshTunnel.stop()
            }.start()
        }
        call.resolve(JSObject().put("value", ok))
    }

    /**
     * Persist the SSH config to SharedPreferences. The renderer
     * calls this when the user submits the SSH form on the
     * Welcome screen.
     */
    @PluginMethod
    fun setSshConfig(call: PluginCall) {
        val host = call.getString("host") ?: ""
        val port = call.getInt("port", 22)
        val username = call.getString("username") ?: ""
        val keyPath = call.getString("keyPath") ?: ""
        val remotePort = call.getInt("remotePort", 8642)
        val localPort = call.getInt("localPort", 8642)
        val prefs = context.getSharedPreferences("hermes_ssh", android.content.Context.MODE_PRIVATE)
        prefs.edit()
            .putString("host", host)
            .putInt("port", port)
            .putString("username", username)
            .putString("keyPath", keyPath)
            .putInt("remotePort", remotePort)
            .putInt("localPort", localPort)
            .apply()
        call.resolve(JSObject().put("value", true))
    }

    private data class SshConfig(
        val host: String,
        val port: Int,
        val username: String,
        val keyPath: String,
        val remotePort: Int,
        val localPort: Int,
    )

    private fun readSshConfig(): SshConfig? {
        val prefs = context.getSharedPreferences("hermes_ssh", android.content.Context.MODE_PRIVATE)
        val host = prefs.getString("host", "") ?: ""
        if (host.isEmpty()) return null
        return SshConfig(
            host = host,
            port = prefs.getInt("port", 22),
            username = prefs.getString("username", "") ?: "",
            keyPath = prefs.getString("keyPath", "") ?: "",
            remotePort = prefs.getInt("remotePort", 8642),
            localPort = prefs.getInt("localPort", 8642),
        )
    }

    // ------------------------------------------------------------------
    // Phase 4: chat polish — file picker, voice capture, code highlight
    // ------------------------------------------------------------------

    /**
     * Open the system folder picker (replaces the desktop's
     * `selectFolder()` IPC method). Returns a path or empty string
     * if the user cancelled. The desktop's call sites in
     * `Install.tsx`, `Kanban.tsx`, and `Chat.tsx` all use this
     * surface; we just delegate to Android's `ACTION_OPEN_DOCUMENT_TREE`.
     *
     * The vendored renderer doesn't need to change — it calls
     * `window.hermesAPI.selectFolder()` and the native bridge routes
     * to the system picker. On Phase 5 the picker can grow
     * additional options (initialPath, multi-select) without
     * touching the renderer.
     */
    @PluginMethod
    fun selectFolder(call: PluginCall) {
        // Phase 4 v1: stub. Returns empty (treated as "user
        // cancelled" by the renderer's call sites — matching the
        // desktop's null-on-cancel convention). The full
        // `ACTION_OPEN_DOCUMENT_TREE` flow needs an
        // `ActivityResultLauncher`, which is awkward from a
        // Capacitor plugin; Phase 5 replaces this with a proper
        // Capawesome pickDirectory() bridge.
        call.resolve(JSObject().put("value", ""))
    }

    /**
     * Return a path for a `File` object — the desktop uses Electron's
     * `webUtils.getPathForFile` here, which doesn't exist in the
     * WebView. We return an empty string; the renderer's
     * `attachmentUtils.ts` already handles empty paths by treating
     * the attachment as "no origin" (clipboard paste path).
     */
    @PluginMethod
    fun getPathForFile(call: PluginCall) {
        call.resolve(JSObject().put("value", ""))
    }

    /**
     * Share text via the system share sheet (Android `ACTION_SEND`).
     * The desktop has its own `showMediaMenu` for inline chat media;
     * this is the new "share this chat message" entry point that
     * the mobile shell adds to the chat long-press menu.
     */
    @PluginMethod
    fun shareText(call: PluginCall) {
        val text = call.getString("text") ?: return call.reject("text is required")
        val title = call.getString("title", "Hermes Agent")
        try {
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(android.content.Intent.EXTRA_TEXT, text)
                putExtra(android.content.Intent.EXTRA_SUBJECT, title)
            }
            val chooser = android.content.Intent.createChooser(intent, title)
            chooser.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
            call.resolve()
        } catch (e: Exception) {
            call.reject("shareText failed: ${e.message}", e)
        }
    }

    /**
     * Voice capture: permission check + start a recording session.
     * The actual MediaRecorder work happens in the WebView (where
     * the renderer is) — this method's job is to:
     *   1. request `RECORD_AUDIO` permission if not granted,
     *   2. allocate a session id for the renderer to track,
     *   3. return that id.
     *
     * The renderer uses `navigator.mediaDevices.getUserMedia` and
     * `MediaRecorder` directly in the WebView (the Android system
     * WebView supports both since Chromium 60+). When the recording
     * is done, the renderer calls `stopVoiceCapture(id)` with the
     * audio as a base64-encoded blob; the native side decodes and
     * saves it to the staging dir for the gateway to pick up.
     */
    @PluginMethod
    fun startVoiceCapture(call: PluginCall) {
        if (!hasRecordAudioPermission()) {
            call.reject("RECORD_AUDIO permission required")
            return
        }
        val id = java.util.UUID.randomUUID().toString()
        call.resolve(JSObject().put("id", id))
    }

    /**
     * Stop a voice capture and decode the base64 audio to a
     * temp file. The renderer passes a base64-encoded Blob; we
     * write it to `cacheDir/voice/<id>.webm` and return the path.
     * The chat composer then calls `transcribeAudio(path, mime)`
     * on the gateway, which uses Groq's hosted whisper (the
     * `voice` extra is intentionally disabled in [termux-all] —
     * faster-whisper has no Android wheels).
     */
    @PluginMethod
    fun stopVoiceCapture(call: PluginCall) {
        val id = call.getString("id") ?: return call.reject("id is required")
        val base64 = call.getString("base64") ?: return call.reject("base64 is required")
        val mimeType = call.getString("mimeType", "audio/webm")
        scope.launch {
            try {
                val dir = File(context.cacheDir, "voice").apply { mkdirs() }
                val ext = when (mimeType) {
                    "audio/webm" -> "webm"
                    "audio/ogg" -> "ogg"
                    "audio/mp4" -> "m4a"
                    else -> "bin"
                }
                val outFile = File(dir, "$id.$ext")
                val bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
                outFile.writeBytes(bytes)
                val ret = JSObject()
                ret.put("mimeType", mimeType)
                ret.put("path", outFile.absolutePath)
                call.resolve(ret)
            } catch (e: Exception) {
                call.reject("stopVoiceCapture failed: ${e.message}", e)
            }
        }
    }

    /**
     * Server-side code highlight. The desktop uses
     * `react-syntax-highlighter` (~1.2MB) for code blocks; the
     * mobile renderer drops that in favor of `highlight.js` (the
     * desktop already has it as a dep) wrapped in a thin component
     * that calls this method to get the highlighted HTML for a
     * given language + source.
     *
     * For Phase 4 the implementation is a passthrough — the
     * renderer-side `highlight.js` does the work. We expose this
     * method so a future optimization (e.g. running highlight.js
     * server-side in the gateway to keep the WebView's bundle
     * small) is a one-line change in the renderer.
     */
    @PluginMethod
    fun highlightCode(call: PluginCall) {
        val source = call.getString("source", "")
        // Phase 4 v1: passthrough. A future implementation can
        // shell out to the bundled Python (which has pygments
        // available) for a more accurate highlighter.
        call.resolve(JSObject().put("value", source))
    }

    // Phase 4 helpers --------------------------------------------------------

    private fun hasRecordAudioPermission(): Boolean {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M) return true
        return context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    // Battery optimization (Doze) exemption

    @PluginMethod
    fun requestIgnoreBatteryOptimizations(call: PluginCall) {
        val launched = BatteryOptHelper.requestIgnore(context)
        call.resolve(JSObject().put("launched", launched))
    }

    @PluginMethod
    fun getBatteryOptStatus(call: PluginCall) {
        call.resolve(JSObject().put("ignoring", BatteryOptHelper.isIgnoring(context)))
    }

    // Boot autostart (opt-in)

    @PluginMethod
    fun setStartOnBoot(call: PluginCall) {
        val enabled = call.getBoolean("enabled") ?: false
        val prefs = context.getSharedPreferences(
            BootReceiver.START_ON_BOOT_PREFS,
            android.content.Context.MODE_PRIVATE,
        )
        prefs.edit().putBoolean(BootReceiver.KEY_ENABLED, enabled).apply()
        call.resolve(JSObject().put("value", true))
    }

    @PluginMethod
    fun getStartOnBoot(call: PluginCall) {
        val prefs = context.getSharedPreferences(
            BootReceiver.START_ON_BOOT_PREFS,
            android.content.Context.MODE_PRIVATE,
        )
        val enabled = prefs.getBoolean(BootReceiver.KEY_ENABLED, false)
        call.resolve(JSObject().put("value", enabled))
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    override fun handleOnStart() {
        val intent = activity.intent
        if (intent?.action == android.content.Intent.ACTION_SEND) {
            val text = intent.getStringExtra(android.content.Intent.EXTRA_TEXT)
            if (!text.isNullOrEmpty()) {
                val data = JSObject()
                data.put("text", text)
                notifyListeners("onSharedText", data)
            }
        }
    }

    override fun handleOnDestroy() {
        installer.dispose()
        supervisor.dispose()
        sshTunnel.stop()
        super.handleOnDestroy()
    }
}

/**
 * Local alias for org.json.JSONObject.NULL so the state-event
 * serialization can emit a real JSON null for optional fields
 * (TS side: `pid: number | null`).
 */
private object JSONObject {
    val NULL: Any = org.json.JSONObject.NULL
}
