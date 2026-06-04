package com.nousresearch.hermes

import android.content.Context
import android.os.Build
import android.util.Base64
import com.nousresearch.hermes.chat.ChatUsage
import com.nousresearch.hermes.chat.MessageEntity
import com.nousresearch.hermes.chat.MessageKind
import com.nousresearch.hermes.chat.SessionSummary
import com.nousresearch.hermes.chat.SseEvent
import com.nousresearch.hermes.config.ConfigStore
import com.nousresearch.hermes.db.HermesDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * HermesApi — the platform-level Kotlin API exposed by hermes-mobile.
 *
 * Phase 1 of the Compose UI port decouples the business logic from
 * the Capacitor [com.getcapacitor.Plugin] / [com.getcapacitor.PluginCall]
 * surface. Before this refactor, every IPC method was a `@PluginMethod`
 * on `HermesAPIPlugin` and the 4 long-lived service handles
 * ([installer], [supervisor], [sshTunnel], plus the install progress
 * and gateway state event streams) were plugin-owned.
 *
 * After the refactor:
 * - [HermesApi] holds the service handles, the persistence layer, and
 *   the event [SharedFlow]s. All 44 native methods live here as plain
 *   Kotlin functions (typed args, `suspend` where I/O happens, plain
 *   return types).
 * - `HermesAPIPlugin` becomes a 3-line bridge: pull args from the
 *   `PluginCall`, call the matching `HermesApi` method, map the
 *   result to `call.resolve` / `call.reject`. The plugin still
 *   owns the `installProgressCall` / `gatewayStateCall` keepalive
 *   references — those are Capacitor-isms that the API surface
 *   doesn't need to leak.
 *
 * ## Concurrency model
 *
 * A single [scope] tied to the [HermesApi] lifetime owns the install
 * and gateway coroutines, plus the SharedFlow forwarders. Cancelling
 * the scope (in [dispose]) tears everything down. Phase 3's
 * `MainActivity` reads `(application as HermesApp).hermes` and the
 * singleton lives for the process lifetime.
 *
 * ## Phase 2 chat surface
 *
 * [gatewayClient] is lazily constructed — the user's API key and
 * gateway URL are read from the connection config the first time
 * [sendMessage] runs. The 6 chat event [SharedFlow]s
 * ([chatChunk], [chatReasoningChunk], [chatToolProgress],
 * [chatUsage], [chatDone], [chatError]) are projected from
 * [GatewayClient.stream] inside [sendMessage] and persisted to
 * the Room DB via [HermesDatabase]. Phase 4's `ChatViewModel`
 * collects these flows to drive the UI.
 */
class HermesApi(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val installer: HermesInstaller = HermesInstaller(context)
    val supervisor: GatewaySupervisor = GatewaySupervisor(context, installer)
    val sshTunnel: SshTunnelService = SshTunnelService(context)

    private val database: HermesDatabase = HermesDatabase.get(context)

    @Volatile private var gatewayClient: GatewayClient? = null

    // ------------------------------------------------------------------
    // Chat event SharedFlows (Phase 2). extraBufferCapacity = 64
    // matches the plan's recommendation; the okhttp callback thread
    // does `tryEmit` from parseSseStream, and we DROP_OLDEST on
    // overflow so a slow Compose consumer never blocks the network
    // reader. Phase 4's ChatViewModel collects these on
    // Dispatchers.Main.
    // ------------------------------------------------------------------

    private val _chatChunk = MutableSharedFlow<String>(
        replay = 0, extraBufferCapacity = 64,
    )
    val chatChunk: SharedFlow<String> = _chatChunk

    private val _chatReasoningChunk = MutableSharedFlow<String>(
        replay = 0, extraBufferCapacity = 64,
    )
    val chatReasoningChunk: SharedFlow<String> = _chatReasoningChunk

    private val _chatToolProgress = MutableSharedFlow<String>(
        replay = 0, extraBufferCapacity = 32,
    )
    val chatToolProgress: SharedFlow<String> = _chatToolProgress

    private val _chatUsage = MutableSharedFlow<ChatUsage>(
        replay = 0, extraBufferCapacity = 8,
    )
    val chatUsage: SharedFlow<ChatUsage> = _chatUsage

    private val _chatDone = MutableSharedFlow<String?>(
        replay = 0, extraBufferCapacity = 8,
    )
    val chatDone: SharedFlow<String?> = _chatDone

    private val _chatError = MutableSharedFlow<String>(
        replay = 0, extraBufferCapacity = 16,
    )
    val chatError: SharedFlow<String> = _chatError

    // ------------------------------------------------------------------
    // Phase 2: top-level app state. The onboarding flow (Splash →
    // Welcome → Install → Setup → Main) is a single state machine
    // owned by HermesApi. MainActivity reads [appState] and switches
    // the top-level `setContent` accordingly.
    // ------------------------------------------------------------------

    enum class AppState {
        Splash,    // cold-start probe (install check + version fetch)
        Welcome,   // 3 CTAs: remote / Termux / bundled
        Installing, // 8-stage install in progress
        Setup,     // first-launch config: provider + model + profile
        Main,      // 5-tab bottom nav
    }

    private val _appState = MutableStateFlow(AppState.Splash)
    val appState: StateFlow<AppState> = _appState.asStateFlow()

    fun setAppState(state: AppState) {
        _appState.value = state
    }

    // ------------------------------------------------------------------
    // Event streams re-emitted by HermesAPIPlugin as Capacitor listeners.
    // We re-export the installer's and supervisor's underlying
    // SharedFlows; the shared-text flow is API-owned.
    // ------------------------------------------------------------------

    val installProgress: SharedFlow<HermesInstaller.Stage>
        get() = installer.progressStream

    val gatewayState: SharedFlow<GatewaySupervisor.State>
        get() = supervisor.stateEvents

    /** Emitted from [emitSharedText] when the host activity's
     *  `ACTION_SEND` intent carries an EXTRA_TEXT. The plugin's
     *  `handleOnStart` calls this on every cold-launch from a
     *  share-sheet tap. */
    private val _sharedText = kotlinx.coroutines.flow.MutableSharedFlow<String>(
        replay = 0, extraBufferCapacity = 8,
    )
    val sharedText: SharedFlow<String> = _sharedText

    // ------------------------------------------------------------------
    // Phase 1.5: missing event SharedFlows
    // ------------------------------------------------------------------

    /** OAuth login progress, as a structured event. The desktop's
     *  `onOAuthLoginProgress` callback gets the full state
     *  (`opening`/`redirecting`/`complete`/`error`) at once. */
    data class OAuthLoginProgress(
        val stage: String, // "opening" | "redirecting" | "complete" | "error"
        val provider: String,
        val profile: String,
        val error: String? = null,
    )

    private val _oauthLoginProgress = MutableSharedFlow<OAuthLoginProgress>(
        replay = 0, extraBufferCapacity = 8,
    )
    val oauthLoginProgress: SharedFlow<OAuthLoginProgress> = _oauthLoginProgress

    /** Claw3D setup progress (v1: emitted by future Termux-resident
     *  Claw3D instances; the v1 Phase 1.2 path stubs it). */
    private val _claw3dSetupProgress = MutableSharedFlow<String>(
        replay = 0, extraBufferCapacity = 8,
    )
    val claw3dSetupProgress: SharedFlow<String> = _claw3dSetupProgress

    /** App-update events. The update flow is stubbed in v1 (no
     *  GitHub Release manifest yet); the flows exist so the
     *  Settings → Check for Updates UI can wire up. */
    data class UpdateEvent(val version: String, val url: String? = null)
    data class UpdateProgress(val downloadedBytes: Long, val totalBytes: Long)

    private val _updateAvailable = MutableSharedFlow<UpdateEvent>(
        replay = 0, extraBufferCapacity = 4,
    )
    val updateAvailable: SharedFlow<UpdateEvent> = _updateAvailable

    private val _updateDownloadProgress = MutableSharedFlow<UpdateProgress>(
        replay = 0, extraBufferCapacity = 8,
    )
    val updateDownloadProgress: SharedFlow<UpdateProgress> = _updateDownloadProgress

    private val _updateDownloaded = MutableSharedFlow<String>(
        replay = 0, extraBufferCapacity = 4,
    )
    val updateDownloaded: SharedFlow<String> = _updateDownloaded

    data class UpdateError(val message: String)
    private val _updateError = MutableSharedFlow<UpdateError>(
        replay = 0, extraBufferCapacity = 4,
    )
    val updateError: SharedFlow<UpdateError> = _updateError

    // ------------------------------------------------------------------
    // Phase 1: platform + version + connection config
    // ------------------------------------------------------------------

    data class PlatformInfo(val platform: String, val version: String, val sdkInt: Int)

    fun getPlatform(): PlatformInfo =
        PlatformInfo("android", Build.VERSION.RELEASE, Build.VERSION.SDK_INT)

    fun isAndroid(): Boolean = true

    fun getAppVersion(): String = try {
        val pkg = context.packageManager.getPackageInfo(context.packageName, 0)
        pkg.versionName ?: "0.0.0"
    } catch (e: Exception) {
        "0.0.0"
    }

    /** Quit the host activity. The plugin reads [hostActivity] to do
     *  the actual `activity.finish()` call. */
    var hostActivity: android.app.Activity? = null

    fun quitApp() {
        hostActivity?.finish()
    }

    fun openExternal(url: String) {
        val intent = android.content.Intent(
            android.content.Intent.ACTION_VIEW,
            android.net.Uri.parse(url),
        )
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    data class SshConfigView(
        val host: String,
        val port: Int,
        val username: String,
        val keyPath: String,
        val remotePort: Int,
        val localPort: Int,
    )

    data class ConnectionConfig(
        val mode: String,
        val remoteUrl: String,
        val hasApiKey: Boolean,
        val apiKeyLength: Int,
        val ssh: SshConfigView,
    )

    fun getConnectionConfig(): ConnectionConfig {
        val prefs = context.getSharedPreferences(PREFS_CONNECTION, android.content.Context.MODE_PRIVATE)
        val mode = prefs.getString(KEY_MODE, "local") ?: "local"
        val remoteUrl = prefs.getString(KEY_REMOTE_URL, "") ?: ""
        val apiKey = prefs.getString(KEY_API_KEY, "") ?: ""
        return ConnectionConfig(
            mode = mode,
            remoteUrl = remoteUrl,
            hasApiKey = apiKey.isNotEmpty(),
            apiKeyLength = apiKey.length,
            ssh = SshConfigView(
                host = "",
                port = 22,
                username = "",
                keyPath = "",
                remotePort = SshTunnelService.DEFAULT_REMOTE_PORT,
                localPort = SshTunnelService.DEFAULT_LOCAL_PORT,
            ),
        )
    }

    fun setConnectionConfig(mode: String, remoteUrl: String, apiKey: String) {
        val prefs = context.getSharedPreferences(PREFS_CONNECTION, android.content.Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_MODE, mode)
            .putString(KEY_REMOTE_URL, remoteUrl)
            .putString(KEY_API_KEY, apiKey)
            .apply()
    }

    /** Phase 1 v1: stub. The desktop's `testRemoteConnection` hits the
     *  remote URL with a HEAD; Phase 2 will implement the real probe. */
    fun testRemoteConnection(url: String): Boolean = true

    // ------------------------------------------------------------------
    // Phase 2: Termux probe + install/inspect surface
    // ------------------------------------------------------------------

    data class TermuxStatus(
        val installed: Boolean,
        val hasApi: Boolean,
        val version: String?,
    )

    fun getTermuxStatus(): TermuxStatus = TermuxStatus(
        installed = TermuxProbe.isTermuxInstalled(context),
        hasApi = TermuxProbe.isTermuxApiInstalled(context),
        version = TermuxProbe.termuxVersion(context),
    )

    fun checkInstall(): HermesInstaller.CheckResult = installer.checkInstall()

    fun startInstall() = installer.startInstall()

    fun verifyInstall(): Boolean = installer.checkInstall().verified

    fun inspectInstallTarget(): HermesInstaller.InspectResult = installer.inspectInstallTarget()

    fun validateHermesHome(dir: String): Boolean = installer.validateHermesHome(dir)

    fun adoptHermesHome(dir: String): Boolean = installer.adoptHermesHome(dir)

    fun getHermesVersion(): String? = installer.getHermesVersion()

    /**
     * Re-read the upstream hermes-agent version on demand. Used by the
     * renderer's "refresh version" button after a `runHermesUpdate`.
     * Returns null if the agent isn't installed or version can't be
     * resolved.
     */
    suspend fun refreshHermesVersion(): String? = installer.refreshHermesVersion()

    fun runHermesDoctor(): String {
        val r = installer.runHermesDoctor()
        return r.stdout + r.stderr
    }

    suspend fun runHermesUpdate(): HermesInstaller.UpdateResult = installer.runHermesUpdate()

    // ------------------------------------------------------------------
    // Phase 5: OAuth login (in-app browser)
    // ------------------------------------------------------------------

    /**
     * Open the OAuth provider's auth URL in a system browser.
     * The redirect hits the `hermes://oauth-callback` scheme
     * (declared in the manifest), re-enters the app, and the
     * code is written to auth.json.
     */
    fun oauthLogin(provider: String, profile: String = "default"): Boolean {
        val authUrl = buildOAuthUrl(provider, profile)
            ?: throw IllegalArgumentException("unknown provider: $provider")
        scope.launch {
            _oauthLoginProgress.emit(OAuthLoginProgress("opening", provider, profile))
        }
        val i = android.content.Intent(context, OAuthBrowserActivity::class.java)
        i.putExtra(OAuthBrowserActivity.EXTRA_AUTH_URL, authUrl)
        i.putExtra("oauth_provider", provider)
        i.putExtra("oauth_profile", profile)
        i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(i)
        scope.launch {
            _oauthLoginProgress.emit(OAuthLoginProgress("redirecting", provider, profile))
        }
        return true
    }

    fun cancelOAuthLogin() {
        val i = android.content.Intent("com.nousresearch.hermes.OAUTH_CANCEL")
        i.setPackage(context.packageName)
        context.sendBroadcast(i)
    }

    /** Phase 1.4: invoked from MainActivity.onNewIntent when the
     *  `hermes://oauth-callback` deep link is delivered. The
     *  callback URL is `hermes://oauth-callback?code=...&provider=...`
     *  (or `&error=...` on failure). Writes the code to auth.json
     *  and emits the corresponding [oauthLoginProgress] event. */
    fun handleOAuthCallback(uri: android.net.Uri) {
        val provider = uri.getQueryParameter("provider") ?: "unknown"
        val profile = uri.getQueryParameter("profile") ?: "default"
        val code = uri.getQueryParameter("code")
        val error = uri.getQueryParameter("error")
        if (!error.isNullOrEmpty()) {
            scope.launch {
                _oauthLoginProgress.emit(
                    OAuthLoginProgress("error", provider, profile, error = error),
                )
            }
            return
        }
        if (code.isNullOrEmpty()) {
            scope.launch {
                _oauthLoginProgress.emit(
                    OAuthLoginProgress("error", provider, profile, error = "no code in callback"),
                )
            }
            return
        }
        addCredentialPoolEntry(
            CredentialEntry(
                provider = provider,
                kind = "oauth",
                value = code,
                addedAt = System.currentTimeMillis(),
            ),
        )
        scope.launch {
            _oauthLoginProgress.emit(OAuthLoginProgress("complete", provider, profile))
        }
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
        return "$base?response_type=code&redirect_uri=${java.net.URLEncoder.encode(redirect, "UTF-8")}&client_id=hermes-mobile&scope=read"
    }

    // ------------------------------------------------------------------
    // Phase 3: gateway foreground service + supervisor
    // ------------------------------------------------------------------

    fun startGateway() {
        GatewayForegroundService.start(context)
    }

    fun stopGateway() {
        GatewayForegroundService.stop(context)
        // Belt-and-suspenders: also stop the supervisor directly
        // so the watch loop cancels even if the service intent
        // is dropped.
        supervisor.stop()
    }

    fun gatewayStatus(): Boolean = supervisor.currentState().running

    // ------------------------------------------------------------------
    // Phase 5: SSH tunnel
    // ------------------------------------------------------------------

    /**
     * Open an SSH local-port-forward to a remote hermes-agent
     * gateway. The desktop's `src/main/ssh-remote.ts:startSshTunnel`
     * calls node-ssh's `forwardOut`; we use the system `ssh` binary
     * via SshTunnelService.
     *
     * Reads the host/port/username/keyPath from the connection
     * config that `setSshConfig` previously wrote.
     */
    fun startSshTunnel(): Boolean {
        val cfg = readSshConfig() ?: return false
        return sshTunnel.start(
            SshTunnelService.Config(
                host = cfg.host,
                port = cfg.port,
                username = cfg.username,
                keyPath = cfg.keyPath,
                remotePort = cfg.remotePort,
                localPort = cfg.localPort,
            ),
        )
    }

    fun stopSshTunnel() {
        sshTunnel.stop()
    }

    fun isSshTunnelActive(): Boolean = sshTunnel.isActive()

    /**
     * Best-effort connectivity check. Runs `ssh -o BatchMode=yes
     * -o ConnectTimeout=5 user@host` and returns true if the
     * binary at least connected (the remote end rejecting auth
     * still counts as "the host is reachable"). Used by the
     * renderer's Welcome screen to validate the SSH form before
     * the user commits to a tunnel.
     */
    fun testSshConnection(
        host: String,
        port: Int = 22,
        username: String,
        keyPath: String,
        remotePort: Int = SshTunnelService.DEFAULT_REMOTE_PORT,
    ): Boolean {
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
        return ok
    }

    data class SshConfig(
        val host: String,
        val port: Int,
        val username: String,
        val keyPath: String,
        val remotePort: Int,
        val localPort: Int,
    )

    /**
     * Persist the SSH config to SharedPreferences. The renderer
     * calls this when the user submits the SSH form on the
     * Welcome screen.
     */
    fun setSshConfig(
        host: String,
        port: Int = 22,
        username: String,
        keyPath: String,
        remotePort: Int = SshTunnelService.DEFAULT_REMOTE_PORT,
        localPort: Int = SshTunnelService.DEFAULT_LOCAL_PORT,
    ) {
        val prefs = context.getSharedPreferences(PREFS_SSH, android.content.Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_SSH_HOST, host)
            .putInt(KEY_SSH_PORT, port)
            .putString(KEY_SSH_USERNAME, username)
            .putString(KEY_SSH_KEY_PATH, keyPath)
            .putInt(KEY_SSH_REMOTE_PORT, remotePort)
            .putInt(KEY_SSH_LOCAL_PORT, localPort)
            .apply()
    }

    private fun readSshConfig(): SshConfig? {
        val prefs = context.getSharedPreferences(PREFS_SSH, android.content.Context.MODE_PRIVATE)
        val host = prefs.getString(KEY_SSH_HOST, "") ?: ""
        if (host.isEmpty()) return null
        return SshConfig(
            host = host,
            port = prefs.getInt(KEY_SSH_PORT, 22),
            username = prefs.getString(KEY_SSH_USERNAME, "") ?: "",
            keyPath = prefs.getString(KEY_SSH_KEY_PATH, "") ?: "",
            remotePort = prefs.getInt(KEY_SSH_REMOTE_PORT, SshTunnelService.DEFAULT_REMOTE_PORT),
            localPort = prefs.getInt(KEY_SSH_LOCAL_PORT, SshTunnelService.DEFAULT_LOCAL_PORT),
        )
    }

    // ------------------------------------------------------------------
    // Phase 4: chat polish — file picker, voice capture, code highlight
    // ------------------------------------------------------------------

    /**
     * Open the system folder picker. Returns a path or empty string
     * if the user cancelled.
     *
     * Phase 4 v1: stub. Returns empty (treated as "user cancelled"
     * by the renderer's call sites — matching the desktop's
     * null-on-cancel convention). Phase 5 replaces this with a
     * proper Capawesome pickDirectory() bridge.
     */
    fun selectFolder(): String = ""

    /**
     * Return a path for a `File` object. The desktop uses Electron's
     * `webUtils.getPathForFile` here, which doesn't exist in the
     * WebView. We return an empty string; the renderer's
     * `attachmentUtils.ts` already handles empty paths by treating
     * the attachment as "no origin" (clipboard paste path).
     */
    fun getPathForFile(): String = ""

    /**
     * Share text via the system share sheet (Android `ACTION_SEND`).
     */
    fun shareText(text: String, title: String = "Hermes Agent") {
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_TEXT, text)
            putExtra(android.content.Intent.EXTRA_SUBJECT, title)
        }
        val chooser = android.content.Intent.createChooser(intent, title)
        chooser.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }

    data class VoiceCaptureStart(val id: String, val hasPermission: Boolean)

    /**
     * Voice capture: permission check + allocate a session id. The
     * actual MediaRecorder work happens in the WebView (where the
     * renderer is) — this method's job is to:
     *   1. verify `RECORD_AUDIO` permission is granted,
     *   2. allocate a session id for the renderer to track,
     *   3. return that id.
     */
    fun startVoiceCapture(): VoiceCaptureStart? {
        if (!hasRecordAudioPermission()) return null
        return VoiceCaptureStart(id = UUID.randomUUID().toString(), hasPermission = true)
    }

    /** Phase 1.3: return base64 to match the desktop's
     *  `Promise<{mimeType, base64}>` shape. The Kotlin side keeps
     *  a cached temp file so a future `transcribeAudio(path, mime)`
     *  call can still reach the gateway. */
    data class VoiceCaptureResult(val mimeType: String, val base64: String, val path: String? = null)

    /**
     * Stop a voice capture and decode the base64 audio to a
     * temp file at `cacheDir/voice/<id>.<ext>`. The chat composer
     * then calls `transcribeAudio(path, mime)` on the gateway,
     * which uses Groq's hosted whisper.
     */
    suspend fun stopVoiceCapture(
        id: String,
        base64: String,
        mimeType: String = "audio/webm",
    ): VoiceCaptureResult {
        val dir = File(context.cacheDir, "voice").apply { mkdirs() }
        val ext = when (mimeType) {
            "audio/webm" -> "webm"
            "audio/ogg" -> "ogg"
            "audio/mp4" -> "m4a"
            else -> "bin"
        }
        val outFile = File(dir, "$id.$ext")
        val bytes = Base64.decode(base64, Base64.DEFAULT)
        outFile.writeBytes(bytes)
        return VoiceCaptureResult(
            mimeType = mimeType,
            base64 = base64,
            path = outFile.absolutePath,
        )
    }

    /**
     * Server-side code highlight. For Phase 4 the implementation
     * is a passthrough — the renderer-side `highlight.js` does the
     * work. We expose this method so a future optimization (e.g.
     * running highlight.js server-side in the gateway to keep the
     * WebView's bundle small) is a one-line change in the renderer.
     */
    fun highlightCode(source: String): String = source

    private fun hasRecordAudioPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        return context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    // ------------------------------------------------------------------
    // Battery optimization (Doze) exemption
    // ------------------------------------------------------------------

    data class BatteryOptStatusView(val ignoring: Boolean)

    fun requestIgnoreBatteryOptimizations(): Boolean = BatteryOptHelper.requestIgnore(context)

    /** Phase 1.3: wrap the boolean in a data class to match the
     *  desktop's `Promise<{ignoring: boolean}>` shape. */
    fun getBatteryOptStatus(): BatteryOptStatusView =
        BatteryOptStatusView(ignoring = BatteryOptHelper.isIgnoring(context))

    // ------------------------------------------------------------------
    // Boot autostart (opt-in)
    // ------------------------------------------------------------------

    fun setStartOnBoot(enabled: Boolean) {
        val prefs = context.getSharedPreferences(
            BootReceiver.START_ON_BOOT_PREFS,
            android.content.Context.MODE_PRIVATE,
        )
        prefs.edit().putBoolean(BootReceiver.KEY_ENABLED, enabled).apply()
    }

    /** Phase 1.3: wrap the boolean in a data class to match the
     *  desktop's `Promise<{value: boolean}>` shape. */
    data class StartOnBootView(val value: Boolean)

    fun getStartOnBoot(): StartOnBootView {
        val prefs = context.getSharedPreferences(
            BootReceiver.START_ON_BOOT_PREFS,
            android.content.Context.MODE_PRIVATE,
        )
        return StartOnBootView(value = prefs.getBoolean(BootReceiver.KEY_ENABLED, false))
    }

    // ------------------------------------------------------------------
    // Phase 6: crash reporting (Sentry opt-in)
    // ------------------------------------------------------------------

    /**
     * Toggle Sentry crash reporting. Default off; the user
     * explicitly opts in via Settings. When on, the Sentry Android
     * SDK (declared in build.gradle) captures uncaught exceptions
     * and forwards them to the configured DSN.
     */
    fun setCrashReportingEnabled(enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_SENTRY, android.content.Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_SENTRY_ENABLED, enabled).apply()
    }

    fun getCrashReportingEnabled(): Boolean {
        val prefs = context.getSharedPreferences(PREFS_SENTRY, android.content.Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_SENTRY_ENABLED, false)
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    /** Emit a shared-text event. Called by the plugin from
     *  `handleOnStart` when the host activity's `ACTION_SEND` intent
     *  carries an `EXTRA_TEXT`. */
    suspend fun emitSharedText(text: String) {
        _sharedText.emit(text)
    }

    // ------------------------------------------------------------------
    // Phase 2: chat IPC (sendMessage / abortChat / listSessions /
    // getSessionMessages). These are the methods Phase 4's
    // ChatViewModel calls; the plugin's @PluginMethod wrappers
    // project them to the WebView for the legacy renderer.
    // ------------------------------------------------------------------

    data class SendMessageResult(val response: String, val sessionId: String?)

    /**
     * Send a user message to the gateway. Streams the response
     * back via the 6 chat event SharedFlows and persists both
     * the user message and the assistant's response to the
     * local Room DB. Returns once the gateway emits `done`
     * (or `error`).
     *
     * If [resumeSessionId] is provided, the gateway continues
     * the previous conversation; otherwise a new session id
     * is generated client-side and used for both the DB rows
     * and the gateway's session tracking.
     */
    suspend fun sendMessage(
        message: String,
        profile: String = "default",
        resumeSessionId: String? = null,
        history: List<Pair<String, String>> = emptyList(),
        contextFolder: String? = null,
    ): SendMessageResult = withContext(Dispatchers.IO) {
        val sessionId = resumeSessionId ?: UUID.randomUUID().toString()
        val now = System.currentTimeMillis()

        // Persist the user message first so the chat list shows
        // it immediately. The assistant message row is created
        // up-front with empty content; the chunk events update
        // the in-memory state in the ViewModel and the final
        // full content is rewritten here on `done`.
        val userId = database.messageDao().insert(
            MessageEntity(
                sessionId = sessionId,
                kind = "user",
                role = "user",
                content = message,
                timestamp = now,
            ),
        )

        val client = gatewayClient ?: buildGatewayClient().also { gatewayClient = it }

        var assistantId: Long = -1L
        val assistantContent = StringBuilder()
        var finalSessionId: String? = sessionId
        try {
            client.stream(message, profile, sessionId, history).collect { ev ->
                when (ev) {
                    is SseEvent.Chunk -> {
                        assistantContent.append(ev.content)
                        _chatChunk.tryEmit(ev.content)
                    }
                    is SseEvent.Reasoning -> {
                        _chatReasoningChunk.tryEmit(ev.text)
                        // Persist a reasoning row tied to the
                        // assistant message; if the assistant
                        // row hasn't been created yet, lazy-create.
                        if (assistantId < 0) {
                            assistantId = database.messageDao().insert(
                                MessageEntity(
                                    sessionId = sessionId,
                                    kind = "assistant",
                                    role = "assistant",
                                    content = "",
                                    timestamp = now,
                                ),
                            )
                        }
                        database.messageDao().insert(
                            MessageEntity(
                                sessionId = sessionId,
                                kind = "reasoning",
                                text = ev.text,
                                timestamp = System.currentTimeMillis(),
                                parentAssistantId = assistantId,
                            ),
                        )
                    }
                    is SseEvent.ToolProgress -> {
                        _chatToolProgress.tryEmit(ev.tool)
                    }
                    is SseEvent.Usage -> {
                        _chatUsage.tryEmit(ev.usage)
                    }
                    is SseEvent.Done -> {
                        // Persist the final assistant content.
                        if (assistantId < 0) {
                            assistantId = database.messageDao().insert(
                                MessageEntity(
                                    sessionId = sessionId,
                                    kind = "assistant",
                                    role = "assistant",
                                    content = assistantContent.toString(),
                                    timestamp = now,
                                ),
                            )
                        } else {
                            // Re-insert with the same id so content
                            // is updated. REPLACE strategy on the
                            // primary key handles the upsert.
                            database.messageDao().insert(
                                MessageEntity(
                                    id = assistantId,
                                    sessionId = sessionId,
                                    kind = "assistant",
                                    role = "assistant",
                                    content = assistantContent.toString(),
                                    timestamp = now,
                                ),
                            )
                        }
                        if (!ev.sessionId.isNullOrEmpty()) {
                            finalSessionId = ev.sessionId
                        }
                        _chatDone.tryEmit(finalSessionId)
                    }
                    is SseEvent.Error -> {
                        _chatError.tryEmit(ev.message)
                    }
                    is SseEvent.Other -> { /* ignore unknown event types */ }
                }
            }
        } catch (e: Exception) {
            // Surface as a chat error so the UI can render the
            // failure. Phase 4's ChatViewModel will show a
            // transient error bubble + retry button.
            _chatError.tryEmit(e.message ?: "sendMessage failed")
        }

        SendMessageResult(
            response = assistantContent.toString(),
            sessionId = finalSessionId,
        )
    }

    /**
     * Cancel the in-flight chat request. Idempotent; safe from
     * any thread. Maps directly to the underlying okhttp `Call.cancel()`.
     */
    fun abortChat() {
        gatewayClient?.abortChat()
    }

    /** List the local chat sessions, newest first. Computed in
     *  Kotlin from a single `SELECT *` query — Room's KSP
     *  processor had trouble with the subquery pattern in
     *  2.6.1 + KSP 2.0.4 (the "unexpected jvm signature V"
     *  error). */
    suspend fun listSessions(limit: Int = 50, offset: Int = 0): List<SessionSummary> {
        val all = database.messageDao().listAllMessages()
        // Group by session id, build a summary per group.
        val bySession = all.groupBy { it.sessionId }
        return bySession.entries
            .sortedByDescending { it.value.minOf { m -> m.timestamp } }
            .drop(offset)
            .take(limit)
            .map { (sid, msgs) ->
                val userMsgs = msgs.filter { it.kindEnum == MessageKind.USER }
                val firstUser = userMsgs.minByOrNull { it.timestamp }
                SessionSummary(
                    id = sid,
                    source = "mobile",
                    startedAt = msgs.minOf { it.timestamp },
                    endedAt = msgs.maxOfOrNull { it.timestamp },
                    messageCount = msgs.size,
                    model = "",
                    title = null,
                    preview = firstUser?.content?.take(120) ?: "",
                )
            }
    }

    /** Replay a single session in chronological order. */
    suspend fun getSessionMessages(sessionId: String): List<MessageEntity> =
        database.messageDao().listForSession(sessionId)

    /** Delete a session and all of its messages. Returns true if
     *  the session was found and at least one row was deleted. */
    suspend fun deleteSession(sessionId: String): Boolean = withContext(Dispatchers.IO) {
        database.messageDao().deleteSession(sessionId) > 0
    }

    // ------------------------------------------------------------------
    // Phase B: memory IPC. The desktop's `~/.hermes/profiles/default/memory.md`
    // is a plain markdown file with one entry per `## heading`
    // section. The mobile shell reads/writes the same file so a
    // user with both the desktop and the mobile app sees the
    // same memory content. Phase B v1: a single in-memory
    // representation; Phase D adds structured providers (per
    // `discoverMemoryProviders`).
    // ------------------------------------------------------------------

    private fun memoryFile(profile: String = "default"): File =
        File(context.filesDir, "home/.hermes/profiles/$profile/memory.md")

    private fun userProfileFile(profile: String = "default"): File =
        File(context.filesDir, "home/.hermes/profiles/$profile/user.md")

    suspend fun readMemory(profile: String = "default"): com.nousresearch.hermes.memory.MemoryReadResult =
        withContext(Dispatchers.IO) {
            val mem = memoryFile(profile)
            val user = userProfileFile(profile)
            val allMessages = database.messageDao().listAllMessages()
            val sessionCount = allMessages.map { it.sessionId }.distinct().size
            com.nousresearch.hermes.memory.MemoryReadResult(
                memory = com.nousresearch.hermes.memory.MemoryContent(
                    content = if (mem.exists()) mem.readText() else "",
                    exists = mem.exists(),
                    lastModified = if (mem.exists()) mem.lastModified() else null,
                ),
                user = com.nousresearch.hermes.memory.MemoryContent(
                    content = if (user.exists()) user.readText() else "",
                    exists = user.exists(),
                    lastModified = if (user.exists()) user.lastModified() else null,
                ),
                stats = com.nousresearch.hermes.memory.MemoryStats(
                    totalSessions = sessionCount,
                    totalMessages = allMessages.size,
                ),
            )
        }

    suspend fun addMemoryEntry(
        content: String,
        profile: String = "default",
    ): com.nousresearch.hermes.memory.MemoryWriteResult = withContext(Dispatchers.IO) {
        runCatching {
            val file = memoryFile(profile)
            file.parentFile?.mkdirs()
            // Use `## entry-<timestamp>` as the heading so each
            // new entry gets a unique anchor. The desktop's
            // renderer derives titles from the first line.
            val timestamp = System.currentTimeMillis()
            val existing = if (file.exists()) file.readText() else ""
            val newEntry = "\n## entry-$timestamp\n\n$content\n"
            file.writeText(existing + newEntry)
            com.nousresearch.hermes.memory.MemoryWriteResult(success = true)
        }.getOrElse {
            com.nousresearch.hermes.memory.MemoryWriteResult(
                success = false,
                error = it.message ?: "addMemoryEntry failed",
            )
        }
    }

    suspend fun updateMemoryEntry(
        index: Int,
        content: String,
        profile: String = "default",
    ): com.nousresearch.hermes.memory.MemoryWriteResult = withContext(Dispatchers.IO) {
        runCatching {
            val file = memoryFile(profile)
            if (!file.exists()) return@withContext com.nousresearch.hermes.memory.MemoryWriteResult(
                success = false,
                error = "memory file does not exist",
            )
            val entries = parseMemoryEntries(file.readText())
            if (index < 0 || index >= entries.size) {
                return@withContext com.nousresearch.hermes.memory.MemoryWriteResult(
                    success = false,
                    error = "index $index out of range (0..${entries.size - 1})",
                )
            }
            val updated = entries.toMutableList()
            updated[index] = updated[index].copy(body = content)
            file.writeText(serializeMemoryEntries(updated))
            com.nousresearch.hermes.memory.MemoryWriteResult(success = true)
        }.getOrElse {
            com.nousresearch.hermes.memory.MemoryWriteResult(
                success = false,
                error = it.message ?: "updateMemoryEntry failed",
            )
        }
    }

    suspend fun removeMemoryEntry(
        index: Int,
        profile: String = "default",
    ): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val file = memoryFile(profile)
            if (!file.exists()) return@withContext false
            val entries = parseMemoryEntries(file.readText())
            if (index < 0 || index >= entries.size) return@withContext false
            val updated = entries.toMutableList()
            updated.removeAt(index)
            file.writeText(serializeMemoryEntries(updated))
            true
        }.getOrElse { false }
    }

    /** Parse the memory markdown into a list of [com.nousresearch.hermes.memory.MemoryEntry]
     *  rows. Splits on `## ` headings; everything between two
     *  headings is the body of the first heading. */
    @Suppress("unused")
    fun parseMemoryEntriesForUi(text: String): List<com.nousresearch.hermes.memory.MemoryEntry> =
        parseMemoryEntries(text)

    private fun parseMemoryEntries(text: String): List<com.nousresearch.hermes.memory.MemoryEntry> {
        val out = mutableListOf<com.nousresearch.hermes.memory.MemoryEntry>()
        var currentHeading: String? = null
        val currentBody = StringBuilder()
        for (raw in text.lines()) {
            val line = raw.trimEnd()
            if (line.startsWith("## ")) {
                if (currentHeading != null) {
                    out.add(
                        com.nousresearch.hermes.memory.MemoryEntry(
                            index = out.size,
                            heading = currentHeading,
                            body = currentBody.toString().trim(),
                        ),
                    )
                }
                currentHeading = line.removePrefix("## ").trim()
                currentBody.clear()
            } else if (currentHeading != null) {
                currentBody.append(line).append('\n')
            }
        }
        if (currentHeading != null) {
            out.add(
                com.nousresearch.hermes.memory.MemoryEntry(
                    index = out.size,
                    heading = currentHeading,
                    body = currentBody.toString().trim(),
                ),
            )
        }
        return out
    }

    private fun serializeMemoryEntries(entries: List<com.nousresearch.hermes.memory.MemoryEntry>): String =
        entries.joinToString("\n\n") { "## ${it.heading}\n\n${it.body}" } + "\n"

    private fun buildGatewayClient(): GatewayClient {
        val cfg = getConnectionConfig()
        val apiKey = context.getSharedPreferences(PREFS_CONNECTION, Context.MODE_PRIVATE)
            .getString(KEY_API_KEY, "") ?: ""
        // Local gateway: 127.0.0.1:8642. Remote: use the user's
        // `remoteUrl` directly. The plan calls out that the
        // remote-URL validation lives in Phase 5 — for now we
        // trust the value the user typed.
        val baseUrl = if (cfg.mode == "remote" && cfg.remoteUrl.isNotEmpty()) {
            cfg.remoteUrl.trimEnd('/')
        } else {
            "http://127.0.0.1:8642"
        }
        return GatewayClient(apiKey = apiKey, baseUrl = baseUrl)
    }

    /** Tear down all owned resources. Called by the plugin on
     *  dispose. The 4 service handles are `val` (not `lateinit`),
     *  so they exist for the lifetime of the HermesApi singleton —
     *  dispose cancels their coroutines. */
    fun dispose() {
        installer.dispose()
        supervisor.dispose()
        sshTunnel.stop()
        gatewayClient?.abortChat()
    }

    // ------------------------------------------------------------------
    // Phase 2: top-level init. Called from HermesApp.onCreate AFTER
    // the HermesApi singleton is constructed. Inspects the install
    // state and routes the user to the right onboarding screen.
    // ------------------------------------------------------------------

    /**
     * Inspect the install + config state and set [appState] to the
     * appropriate first screen. Called from [HermesApp.onCreate]
     * after the singleton is built. The Splash screen calls this
     * after its brief brand-mark display; the onboarding screens
     * can also call it again after a stage completes.
     *
     * Routing rules (matches the desktop's `App.tsx:33-72` state
     * machine):
     * - install not verified → Welcome (or Installing if a stage is
     *   mid-flight)
     * - install verified but no profile → Setup
     * - install verified + profile exists → Main
     */
    fun init() {
        val verified = installer.checkInstall().verified
        if (!verified) {
            // No install yet; let the user pick a path.
            _appState.value = AppState.Welcome
            return
        }
        val profile = activeProfile()
        val profileDir = File(hermesHome(), "profiles/$profile")
        if (!profileDir.exists()) {
            // Install is verified but the profile dir is missing;
            // the user needs to set up before first chat.
            _appState.value = AppState.Setup
            return
        }
        _appState.value = AppState.Main
    }

    // ------------------------------------------------------------------
    // Phase 1.1: file-IO backed methods (no Python gateway roundtrip)
    //
    // All paths are rooted at `filesDir/home/.hermes/` (the same
    // convention `readMemory` already uses). The methods either
    // touch a single text file via [ConfigStore] or walk a directory
    // (e.g. profiles/, skills/, kanban/boards/).
    // ------------------------------------------------------------------

    private fun hermesHome(): File = ConfigStore.hermesHome(context.filesDir)

    private fun configFile(): File = File(hermesHome(), "config.yaml")
    private fun envFile(): File = File(hermesHome(), ".env")
    private fun modelsFile(profile: String): File =
        File(hermesHome(), "profiles/$profile/models.yaml")
    private fun toolsFile(profile: String): File =
        File(hermesHome(), "profiles/$profile/tools.yaml")
    private fun soulFile(profile: String): File =
        File(hermesHome(), "profiles/$profile/soul.md")
    private fun userFile(profile: String): File =
        File(hermesHome(), "profiles/$profile/user.md")
    private fun authFile(): File = File(hermesHome(), "auth.json")
    private fun mcpFile(): File = File(hermesHome(), "mcp.yaml")
    private fun gatewayYamlFile(profile: String): File =
        File(hermesHome(), "profiles/$profile/gateway.yaml")
    private fun memoryProvidersDir(): File =
        File(hermesHome(), "memory-providers").apply { mkdirs() }
    private fun kanbanDir(): File =
        File(hermesHome(), "kanban/boards").apply { mkdirs() }
    private fun skillsDir(): File =
        File(hermesHome(), "skills").apply { mkdirs() }
    private fun profilesDir(): File =
        File(hermesHome(), "profiles").apply { mkdirs() }
    private fun bundledSkillsDir(): File =
        File(context.filesDir, "skills/bundled").apply { mkdirs() }
    private fun logsDir(): File =
        File(context.filesDir, "logs").apply { mkdirs() }

    // ── Locale ─────────────────────────────────────────────────

    fun getLocale(): String {
        val prefs = context.getSharedPreferences(PREFS_LOCALE, android.content.Context.MODE_PRIVATE)
        return prefs.getString(KEY_LOCALE, "en") ?: "en"
    }

    fun setLocale(code: String) {
        val prefs = context.getSharedPreferences(PREFS_LOCALE, android.content.Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_LOCALE, code).apply()
    }

    // ── Config / env / model config ────────────────────────────

    /** Read the top-level `config.yaml` as a flat key=value map.
     *  Empty map if the file is missing. The Python gateway accepts
     *  the same shallow format. */
    fun getConfig(): Map<String, String> = ConfigStore.readKeyValues(configFile())

    fun setConfig(values: Map<String, String>) {
        ConfigStore.writeKeyValues(configFile(), values, ConfigStore.Format.YAML)
    }

    /** Read the `.env` file. Quoted values are unquoted on read. */
    fun getEnv(): Map<String, String> = ConfigStore.readKeyValues(envFile())

    /** Set or remove a single env var. `value=""` deletes the key. */
    fun setEnv(key: String, value: String) {
        val map = getEnv().toMutableMap()
        if (value.isEmpty()) map.remove(key) else map[key] = value
        ConfigStore.writeKeyValues(envFile(), map, ConfigStore.Format.ENV)
    }

    /** Per-profile `models.yaml`. Shallow key=value map; the
     *  gateway interprets the model list from this file. */
    fun getModelConfig(profile: String = activeProfile()): Map<String, String> =
        ConfigStore.readKeyValues(modelsFile(profile))

    fun setModelConfig(profile: String = activeProfile(), values: Map<String, String>) {
        ConfigStore.writeKeyValues(modelsFile(profile), values, ConfigStore.Format.YAML)
    }

    fun getHermesHome(): String = hermesHome().absolutePath

    // ── Active profile (SharedPreferences-backed) ──────────────

    private fun activeProfile(): String {
        val prefs = context.getSharedPreferences(PREFS_PROFILE, android.content.Context.MODE_PRIVATE)
        return prefs.getString(KEY_ACTIVE_PROFILE, "default") ?: "default"
    }

    fun getActiveProfile(): String = activeProfile()

    // ── Config health (port of desktop `configHealth.ts`) ──────

    data class ConfigIssue(
        val id: String,
        val severity: String, // "error" | "warning" | "info"
        val message: String,
        val fixable: Boolean,
        val autofix: (() -> Unit)? = null,
    )

    data class ConfigHealthResult(
        val healthy: Boolean,
        val issues: List<ConfigIssue>,
        val checkedAt: Long,
    )

    /**
     * Run a shallow heuristic check against the active install.
     * Ported from `review/hermes-desktop/src/main/configHealth.ts`.
     * Returns one [ConfigHealthResult]; the renderer subscribes
     * via [rerunConfigHealth] to refresh after a fix.
     */
    fun getConfigHealth(): ConfigHealthResult {
        val issues = mutableListOf<ConfigIssue>()
        // 1. Hermes home exists?
        if (!hermesHome().exists()) {
            issues.add(ConfigIssue(
                id = "home.missing",
                severity = "error",
                message = "Hermes home directory does not exist",
                fixable = true,
            ))
        }
        // 2. config.yaml readable?
        val cfg = getConfig()
        if (cfg.isEmpty()) {
            issues.add(ConfigIssue(
                id = "config.empty",
                severity = "warning",
                message = "config.yaml is empty; defaults will be used",
                fixable = true,
            ))
        }
        // 3. model configured?
        if (cfg["default_model"].isNullOrEmpty()) {
            issues.add(ConfigIssue(
                id = "config.no_default_model",
                severity = "warning",
                message = "no default_model set; chat will fail",
                fixable = false,
            ))
        }
        // 4. env has an API key?
        val env = getEnv()
        if (env["API_SERVER_KEY"].isNullOrEmpty()) {
            issues.add(ConfigIssue(
                id = "env.no_api_key",
                severity = "error",
                message = "API_SERVER_KEY missing from .env",
                fixable = true,
            ))
        }
        // 5. active profile's models.yaml readable?
        val profile = activeProfile()
        if (!File(hermesHome(), "profiles/$profile").exists()) {
            issues.add(ConfigIssue(
                id = "profile.missing",
                severity = "error",
                message = "active profile '$profile' directory does not exist",
                fixable = true,
            ))
        }
        return ConfigHealthResult(
            healthy = issues.none { it.severity == "error" },
            issues = issues,
            checkedAt = System.currentTimeMillis(),
        )
    }

    fun rerunConfigHealth(): ConfigHealthResult = getConfigHealth()

    /** Apply the autofix for an issue by id. Returns true if a
     *  fix was applied. */
    fun autofixConfigIssue(id: String): Boolean {
        when (id) {
            "home.missing" -> hermesHome().mkdirs()
            "config.empty" -> setConfig(mapOf("default_model" to "gpt-4o-mini", "default_provider" to "openai"))
            "env.no_api_key" -> {
                val key = java.util.UUID.randomUUID().toString().replace("-", "")
                setEnv("API_SERVER_KEY", key)
                setEnv("API_SERVER_PORT", "8642")
            }
            "profile.missing" -> {
                File(hermesHome(), "profiles/${activeProfile()}").mkdirs()
                File(hermesHome(), "profiles/${activeProfile()}/memory.md").writeText("")
            }
            else -> return false
        }
        return true
    }

    fun getConfigFixLog(): List<String> {
        // In-memory log; entries are pushed from autofix calls.
        return _configFixLog.toList()
    }

    private val _configFixLog = mutableListOf<String>()
    private fun logFix(message: String) {
        synchronized(_configFixLog) {
            _configFixLog.add("${System.currentTimeMillis()} $message")
        }
    }

    /** Validate that the chat pipeline is ready: install verified
     *  + a default model exists in config. */
    fun validateChatReadiness(): Boolean {
        if (!installer.checkInstall().verified) return false
        val cfg = getConfig()
        if (cfg["default_model"].isNullOrEmpty()) return false
        val provider = cfg["default_provider"] ?: return false
        val env = getEnv()
        if (provider == "local") return true
        return env["${provider.uppercase()}_API_KEY"]?.isNotEmpty() == true
            || env["API_SERVER_KEY"]?.isNotEmpty() == true
    }

    // ── Profiles (directory walk + active profile) ─────────────

    data class ProfileInfo(
        val name: String,
        val isActive: Boolean,
        val hasSoul: Boolean,
        val hasUser: Boolean,
        val hasMemory: Boolean,
    )

    fun listProfiles(): List<ProfileInfo> {
        val active = activeProfile()
        val dirs = ConfigStore.listSubdirs(profilesDir())
        return dirs.map { d ->
            val name = d.name
            ProfileInfo(
                name = name,
                isActive = name == active,
                hasSoul = File(d, "soul.md").exists(),
                hasUser = File(d, "user.md").exists(),
                hasMemory = File(d, "memory.md").exists(),
            )
        }
    }

    fun createProfile(name: String): Boolean {
        if (name.isBlank() || name.contains("/") || name.contains("..")) return false
        val dir = File(profilesDir(), name)
        if (dir.exists()) return false
        dir.mkdirs()
        File(dir, "memory.md").writeText("")
        // Seed soul.md and user.md from the active profile if present
        val active = activeProfile()
        val activeSoul = File(profilesDir(), "$active/soul.md")
        if (activeSoul.exists()) {
            File(dir, "soul.md").writeText(activeSoul.readText())
        }
        return true
    }

    fun deleteProfile(name: String): Boolean {
        if (name == "default") return false
        val active = activeProfile()
        if (name == active) return false
        val dir = File(profilesDir(), name)
        if (!dir.exists()) return false
        return dir.deleteRecursively()
    }

    fun setActiveProfile(name: String) {
        val prefs = context.getSharedPreferences(PREFS_PROFILE, android.content.Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_ACTIVE_PROFILE, name).apply()
    }

    // ── Soul / user profile ────────────────────────────────────

    data class SoulReadResult(val content: String, val exists: Boolean, val lastModified: Long?)

    fun readSoul(profile: String = activeProfile()): SoulReadResult {
        val f = soulFile(profile)
        return SoulReadResult(
            content = if (f.exists()) f.readText() else "",
            exists = f.exists(),
            lastModified = if (f.exists()) f.lastModified() else null,
        )
    }

    fun writeSoul(markdown: String, profile: String = activeProfile()): Boolean {
        return runCatching {
            val f = soulFile(profile)
            f.parentFile?.mkdirs()
            ConfigStore.writeTextAtomic(f, markdown)
            true
        }.getOrDefault(false)
    }

    fun resetSoul(profile: String = activeProfile()): Boolean {
        val defaults = """
            # Hermes Soul
            You are Hermes, a thoughtful, careful AI agent. You help your
            user accomplish real work — you do not flatter, you do not
            pad, you do not pretend.

            ## Style
            - Be concise. Prefer short, direct sentences.
            - Cite sources when you make factual claims.
            - When you're uncertain, say so and explain why.
        """.trimIndent()
        return writeSoul(defaults, profile)
    }

    fun writeUserProfile(markdown: String, profile: String = activeProfile()): Boolean {
        return runCatching {
            val f = userFile(profile)
            f.parentFile?.mkdirs()
            ConfigStore.writeTextAtomic(f, markdown)
            true
        }.getOrDefault(false)
    }

    fun readUserProfile(profile: String = activeProfile()): String =
        ConfigStore.readText(userFile(profile))

    // ── Toolsets (tools.yaml per profile) ──────────────────────

    data class ToolsetInfo(val name: String, val enabled: Boolean, val description: String)

    /** Read the per-profile tools.yaml and return the toolset list.
     *  Tools.yaml is a flat key=value file where each key is a
     *  toolset name and the value is `true`/`false`. The
     *  description is derived from the key (split on `.`). */
    fun getToolsets(profile: String = activeProfile()): List<ToolsetInfo> {
        val map = ConfigStore.readKeyValues(toolsFile(profile))
        if (map.isEmpty()) {
            // Return the canonical toolset names with default-enabled state
            return listOf(
                "file_io" to true,
                "shell" to true,
                "web" to true,
                "browser" to false,
                "memory" to true,
                "skills" to true,
                "mcp" to false,
            ).map { (name, enabled) -> ToolsetInfo(name, enabled, "Built-in toolset: $name") }
        }
        return map.map { (name, value) ->
            ToolsetInfo(
                name = name,
                enabled = value.lowercase() in listOf("true", "yes", "1", "on"),
                description = "Toolset: $name",
            )
        }
    }

    fun setToolsetEnabled(name: String, enabled: Boolean, profile: String = activeProfile()) {
        val current = ConfigStore.readKeyValues(toolsFile(profile)).toMutableMap()
        current[name] = if (enabled) "true" else "false"
        ConfigStore.writeKeyValues(toolsFile(profile), current, ConfigStore.Format.YAML)
    }

    // ── Credential pool (auth.json) ────────────────────────────

    data class CredentialEntry(
        val provider: String,
        val kind: String, // "api_key" | "oauth"
        val value: String, // masked when echoed back
        val addedAt: Long,
    )

    /** Read auth.json. Returns a list of credentials. The `value`
     *  field is masked to the last 4 chars to avoid leaking keys
     *  to the UI. */
    fun getCredentialPool(): List<CredentialEntry> {
        val raw = ConfigStore.readText(authFile())
        if (raw.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val v = o.optString("value", "")
                CredentialEntry(
                    provider = o.optString("provider"),
                    kind = o.optString("kind", "api_key"),
                    value = if (v.length > 4) "*".repeat(v.length - 4) + v.takeLast(4) else v,
                    addedAt = o.optLong("addedAt", 0L),
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    /** Write the full credential list. The list is stored as
     *  JSON; callers should preserve the existing entries. */
    fun setCredentialPool(list: List<CredentialEntry>) {
        val arr = JSONArray()
        list.forEach { e ->
            arr.put(JSONObject().apply {
                put("provider", e.provider)
                put("kind", e.kind)
                put("value", e.value)
                put("addedAt", e.addedAt)
            })
        }
        ConfigStore.writeTextAtomic(authFile(), arr.toString())
    }

    /** Append a credential to the pool. */
    fun addCredentialPoolEntry(entry: CredentialEntry) {
        val current = getCredentialPool()
        setCredentialPool(current + entry)
    }

    // ── Models (per-profile models.yaml) ───────────────────────

    data class ModelConfig(
        val id: String,
        val provider: String,
        val name: String,
        val maxTokens: Int = 4096,
        val temperature: Double = 0.7,
    )

    /** List models for the active (or named) profile. The models.yaml
     *  is a flat key=value file; the canonical keys are
     *  `id.provider.name`, `id.max_tokens`, `id.temperature`. We
     *  parse and group by id. */
    fun listModels(profile: String = activeProfile()): List<ModelConfig> {
        val map = ConfigStore.readKeyValues(modelsFile(profile))
        val ids = mutableSetOf<String>()
        for (k in map.keys) {
            val id = k.substringBefore(".")
            if (id.isNotEmpty()) ids.add(id)
        }
        return ids.map { id ->
            ModelConfig(
                id = id,
                provider = map["$id.provider"] ?: "",
                name = map["$id.name"] ?: id,
                maxTokens = map["$id.max_tokens"]?.toIntOrNull() ?: 4096,
                temperature = map["$id.temperature"]?.toDoubleOrNull() ?: 0.7,
            )
        }
    }

    fun addModel(model: ModelConfig, profile: String = activeProfile()) {
        val current = ConfigStore.readKeyValues(modelsFile(profile)).toMutableMap()
        current["${model.id}.provider"] = model.provider
        current["${model.id}.name"] = model.name
        current["${model.id}.max_tokens"] = model.maxTokens.toString()
        current["${model.id}.temperature"] = model.temperature.toString()
        ConfigStore.writeKeyValues(modelsFile(profile), current, ConfigStore.Format.YAML)
    }

    fun removeModel(id: String, profile: String = activeProfile()) {
        val current = ConfigStore.readKeyValues(modelsFile(profile)).toMutableMap()
        current.keys.removeAll { it.startsWith("$id.") }
        ConfigStore.writeKeyValues(modelsFile(profile), current, ConfigStore.Format.YAML)
    }

    fun updateModel(model: ModelConfig, profile: String = activeProfile()) {
        addModel(model, profile)
    }

    // ── Skills (installed + bundled) ───────────────────────────

    data class SkillInfo(
        val name: String,
        val description: String,
        val bundled: Boolean,
        val path: String,
    )

    /** List skills installed under ~/.hermes/skills/. Each skill is
     *  a directory containing a SKILL.md file. */
    fun listInstalledSkills(): List<SkillInfo> = skillsDir().listFiles { f ->
        f.isDirectory && File(f, "SKILL.md").exists()
    }?.map { dir ->
        val md = File(dir, "SKILL.md").readText()
        SkillInfo(
            name = dir.name,
            description = md.lineSequence().firstOrNull { it.startsWith("# ") }
                ?.removePrefix("# ")?.trim() ?: dir.name,
            bundled = false,
            path = dir.absolutePath,
        )
    }?.sortedBy { it.name } ?: emptyList()

    /** List skills bundled in the APK assets directory. */
    fun listBundledSkills(): List<SkillInfo> = bundledSkillsDir().listFiles { f ->
        f.isDirectory && File(f, "SKILL.md").exists()
    }?.map { dir ->
        val md = File(dir, "SKILL.md").readText()
        SkillInfo(
            name = dir.name,
            description = md.lineSequence().firstOrNull { it.startsWith("# ") }
                ?.removePrefix("# ")?.trim() ?: dir.name,
            bundled = true,
            path = dir.absolutePath,
        )
    }?.sortedBy { it.name } ?: emptyList()

    fun getSkillContent(path: String): String {
        val f = File(path, "SKILL.md")
        return if (f.exists()) f.readText() else ""
    }

    /** Install a skill from a local path or URL. URL installs copy
     *  from a temp download; local path installs copy recursively.
     *  Returns true on success. */
    fun installSkill(urlOrPath: String): Boolean {
        return runCatching {
            val source = if (urlOrPath.startsWith("http://") || urlOrPath.startsWith("https://")) {
                // Stub: real implementation would download + extract
                return@runCatching false
            } else {
                File(urlOrPath)
            }
            if (!source.exists() || !source.isDirectory) return@runCatching false
            val skillMd = File(source, "SKILL.md")
            if (!skillMd.exists()) return@runCatching false
            val target = File(skillsDir(), source.name)
            if (target.exists()) target.deleteRecursively()
            source.copyRecursively(target, overwrite = true)
            true
        }.getOrDefault(false)
    }

    fun uninstallSkill(name: String): Boolean {
        val target = File(skillsDir(), name)
        if (!target.exists()) return false
        return target.deleteRecursively()
    }

    // ── Memory providers ───────────────────────────────────────

    fun discoverMemoryProviders(): List<String> = ConfigStore.listSubdirs(memoryProvidersDir())
        .map { it.name }

    // ── MCP servers (mcp.yaml) ─────────────────────────────────

    data class McpServer(val name: String, val command: String, val args: List<String>)

    fun listMcpServers(): List<McpServer> {
        val map = ConfigStore.readKeyValues(mcpFile())
        return map.entries
            .filter { it.key.endsWith(".command") }
            .map { e ->
                val name = e.key.removeSuffix(".command")
                McpServer(
                    name = name,
                    command = e.value,
                    args = map["$name.args"]?.split(" ")?.filter { it.isNotEmpty() } ?: emptyList(),
                )
            }
    }

    // ── Platform toggles (per-profile gateway.yaml) ────────────

    data class PlatformStatus(val platform: String, val enabled: Boolean)

    /** Read the 16-platform toggle list for the active profile.
     *  Returns the canonical platform list (matching the desktop)
     *  with whatever toggle state the profile's gateway.yaml has. */
    fun getPlatformEnabled(profile: String = activeProfile()): List<PlatformStatus> {
        val map = ConfigStore.readKeyValues(gatewayYamlFile(profile))
        val canonical = listOf(
            "discord", "slack", "telegram", "whatsapp", "signal", "imessage",
            "matrix", "mattermost", "rocketchat", "irc", "xmpp", "email",
            "sms", "webhook", "api", "cli",
        )
        return canonical.map { p ->
            PlatformStatus(p, map[p]?.lowercase() in listOf("true", "yes", "1", "on"))
        }
    }

    fun setPlatformEnabled(platform: String, enabled: Boolean, profile: String = activeProfile()) {
        val current = ConfigStore.readKeyValues(gatewayYamlFile(profile)).toMutableMap()
        current[platform] = if (enabled) "true" else "false"
        ConfigStore.writeKeyValues(gatewayYamlFile(profile), current, ConfigStore.Format.YAML)
    }

    // ── File IO (media + attachments) ──────────────────────────

    /** Read a media file (image, audio, etc.) as a Base64 string.
     *  Used by the chat composer to inline small images. */
    fun readMediaFile(path: String): String {
        val f = File(path)
        if (!f.exists() || f.length() > 10 * 1024 * 1024) return "" // 10MB cap
        return android.util.Base64.encodeToString(f.readBytes(), android.util.Base64.NO_WRAP)
    }

    fun saveMediaFile(srcPath: String, name: String): String {
        val src = File(srcPath)
        if (!src.exists()) return ""
        val dir = File(context.filesDir, "media").apply { mkdirs() }
        val dst = File(dir, name)
        src.copyTo(dst, overwrite = true)
        return dst.absolutePath
    }

    fun mediaFileExists(path: String): Boolean = File(path).exists()

    // getPathForFile() is defined above (Phase 1 stub at line ~589)
    // The single-arg variant looks up media files in filesDir/media/

    /** Stage an attachment (base64 encoded) for a chat session.
     *  Returns the file path on disk; the chat composer attaches
     *  that path to the next message. */
    fun stageAttachment(sessionId: String, name: String, base64: String): String {
        val dir = File(context.cacheDir, "attachments/$sessionId").apply { mkdirs() }
        val f = File(dir, name)
        f.writeBytes(android.util.Base64.decode(base64, android.util.Base64.DEFAULT))
        return f.absolutePath
    }

    fun clearStagedAttachments(sessionId: String): Boolean {
        val dir = File(context.cacheDir, "attachments/$sessionId")
        return if (dir.exists()) dir.deleteRecursively() else true
    }

    fun listStagedAttachments(sessionId: String): List<String> {
        val dir = File(context.cacheDir, "attachments/$sessionId")
        return if (dir.exists()) dir.listFiles()?.map { it.absolutePath } ?: emptyList()
        else emptyList()
    }

    // ── Clipboard ──────────────────────────────────────────────

    fun copyToClipboard(text: String, label: String = "hermes") {
        val cb = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
            as android.content.ClipboardManager
        cb.setPrimaryClip(android.content.ClipData.newPlainText(label, text))
    }

    // ── Folder / file pickers (Phase 4 polish) ─────────────────
    // selectFolder() is defined above (Phase 1 stub at line ~580)

    fun readDirectory(path: String): List<String> {
        val d = File(path)
        if (!d.exists() || !d.isDirectory) return emptyList()
        return d.listFiles()?.map { it.name } ?: emptyList()
    }

    fun readFile(path: String): String = ConfigStore.readText(File(path))

    fun readImageFile(path: String): String {
        val f = File(path)
        if (!f.exists()) return ""
        return "data:image/${f.extension};base64," +
            android.util.Base64.encodeToString(f.readBytes(), android.util.Base64.NO_WRAP)
    }

    fun openFileInEditor(path: String) {
        openExternal("file://$path")
    }

    // ── Logs ───────────────────────────────────────────────────

    fun listLogFiles(): List<String> = ConfigStore.listChildren(logsDir(), suffix = ".log")
        .map { it.name }

    /** Tail the last [lines] lines of [file]. Returns "" if the
     *  file doesn't exist. */
    fun readLogs(file: String, lines: Int = 200): String {
        val f = File(logsDir(), file)
        if (!f.exists()) return ""
        return runCatching {
            f.bufferedReader().use { r ->
                val all = r.readLines()
                all.subList(maxOf(0, all.size - lines), all.size).joinToString("\n")
            }
        }.getOrDefault("")
    }

    // ── Backup / import / dump (gateway-backed stubs) ──────────

    suspend fun runHermesBackup(target: String): String =
        gatewayClient()?.request("POST", "/v1/backup", mapOf("target" to target))
            ?: "no gateway available"

    suspend fun runHermesImport(source: String): String =
        gatewayClient()?.request("POST", "/v1/import", mapOf("source" to source))
            ?: "no gateway available"

    suspend fun runHermesDump(): String =
        gatewayClient()?.request("GET", "/v1/dump") ?: "no gateway available"

    // ── OpenClaw migration (v1: detect only) ───────────────────

    fun checkOpenClaw(): Boolean {
        val openclawHome = File(context.filesDir, "home/.openclaw")
        return openclawHome.exists()
    }

    fun runClawMigrate(): Boolean {
        val openclawHome = File(context.filesDir, "home/.openclaw")
        val hermesH = hermesHome()
        if (!openclawHome.exists()) return false
        openclawHome.copyRecursively(File(hermesH.parentFile, ".openclaw-migrated"), overwrite = true)
        return true
    }

    // ── Kanban (per-board JSON files) ──────────────────────────

    data class KanbanBoard(
        val id: String,
        val name: String,
        val createdAt: Long,
        val columns: List<String>,
        val tasks: List<KanbanTask>,
    )

    data class KanbanTask(
        val id: String,
        val title: String,
        val body: String,
        val column: String,
        val assignee: String?,
        val blocked: Boolean,
        val archived: Boolean,
        val createdAt: Long,
        val updatedAt: Long,
    )

    fun listKanbanBoards(): List<KanbanBoard> = ConfigStore.listChildren(kanbanDir(), suffix = ".json")
        .mapNotNull { f ->
            runCatching {
                val o = JSONObject(f.readText())
                val tasksArr = o.optJSONArray("tasks") ?: JSONArray()
                val tasks = (0 until tasksArr.length()).map { i ->
                    val t = tasksArr.getJSONObject(i)
                    KanbanTask(
                        id = t.optString("id"),
                        title = t.optString("title"),
                        body = t.optString("body"),
                        column = t.optString("column"),
                        assignee = t.optString("assignee").takeIf { it.isNotEmpty() },
                        blocked = t.optBoolean("blocked", false),
                        archived = t.optBoolean("archived", false),
                        createdAt = t.optLong("createdAt", 0L),
                        updatedAt = t.optLong("updatedAt", 0L),
                    )
                }
                val colsArr = o.optJSONArray("columns") ?: JSONArray()
                val cols = (0 until colsArr.length()).map { colsArr.getString(it) }
                KanbanBoard(
                    id = o.optString("id", f.nameWithoutExtension),
                    name = o.optString("name", f.nameWithoutExtension),
                    createdAt = o.optLong("createdAt", 0L),
                    columns = cols,
                    tasks = tasks,
                )
            }.getOrNull()
        }

    fun getKanbanBoard(id: String): KanbanBoard? = listKanbanBoards().firstOrNull { it.id == id }

    private fun writeKanbanBoard(board: KanbanBoard) {
        val f = File(kanbanDir(), "${board.id}.json")
        val o = JSONObject().apply {
            put("id", board.id)
            put("name", board.name)
            put("createdAt", board.createdAt)
            put("columns", JSONArray(board.columns))
            put("tasks", JSONArray().apply {
                board.tasks.forEach { t ->
                    put(JSONObject().apply {
                        put("id", t.id)
                        put("title", t.title)
                        put("body", t.body)
                        put("column", t.column)
                        t.assignee?.let { put("assignee", it) }
                        put("blocked", t.blocked)
                        put("archived", t.archived)
                        put("createdAt", t.createdAt)
                        put("updatedAt", t.updatedAt)
                    })
                }
            })
        }
        ConfigStore.writeTextAtomic(f, o.toString())
    }

    fun createKanbanBoard(name: String): KanbanBoard {
        val id = UUID.randomUUID().toString().take(8)
        val board = KanbanBoard(
            id = id,
            name = name,
            createdAt = System.currentTimeMillis(),
            columns = listOf("backlog", "doing", "done", "blocked"),
            tasks = emptyList(),
        )
        writeKanbanBoard(board)
        return board
    }

    fun deleteKanbanBoard(id: String): Boolean {
        val f = File(kanbanDir(), "$id.json")
        return f.exists() && f.delete()
    }

    fun addKanbanTask(boardId: String, task: KanbanTask): KanbanBoard? {
        val board = getKanbanBoard(boardId) ?: return null
        val updated = board.copy(tasks = board.tasks + task)
        writeKanbanBoard(updated)
        return updated
    }

    fun updateKanbanTask(boardId: String, task: KanbanTask): KanbanBoard? {
        val board = getKanbanBoard(boardId) ?: return null
        val updated = board.copy(
            tasks = board.tasks.map { if (it.id == task.id) task.copy(updatedAt = System.currentTimeMillis()) else it },
        )
        writeKanbanBoard(updated)
        return updated
    }

    fun removeKanbanTask(boardId: String, taskId: String): KanbanBoard? {
        val board = getKanbanBoard(boardId) ?: return null
        val updated = board.copy(tasks = board.tasks.filter { it.id != taskId })
        writeKanbanBoard(updated)
        return updated
    }

    fun moveKanbanTask(boardId: String, taskId: String, toColumn: String): KanbanBoard? {
        val board = getKanbanBoard(boardId) ?: return null
        val updated = board.copy(
            tasks = board.tasks.map {
                if (it.id == taskId) it.copy(column = toColumn, updatedAt = System.currentTimeMillis())
                else it
            },
        )
        writeKanbanBoard(updated)
        return updated
    }

    fun setKanbanTaskBlocked(boardId: String, taskId: String, blocked: Boolean): KanbanBoard? {
        val board = getKanbanBoard(boardId) ?: return null
        val updated = board.copy(
            tasks = board.tasks.map {
                if (it.id == taskId) it.copy(blocked = blocked, updatedAt = System.currentTimeMillis())
                else it
            },
        )
        writeKanbanBoard(updated)
        return updated
    }

    fun setKanbanTaskArchived(boardId: String, taskId: String, archived: Boolean): KanbanBoard? {
        val board = getKanbanBoard(boardId) ?: return null
        val updated = board.copy(
            tasks = board.tasks.map {
                if (it.id == taskId) it.copy(archived = archived, updatedAt = System.currentTimeMillis())
                else it
            },
        )
        writeKanbanBoard(updated)
        return updated
    }

    /** Add a comment to a kanban task. Comments are stored as a
     *  side-file `<boardId>-<taskId>-comments.json` to keep the
     *  main board file small. */
    fun addKanbanComment(boardId: String, taskId: String, body: String): Boolean {
        val f = File(kanbanDir(), "$boardId-$taskId-comments.json")
        val arr = if (f.exists()) JSONArray(f.readText()) else JSONArray()
        arr.put(JSONObject().apply {
            put("body", body)
            put("createdAt", System.currentTimeMillis())
        })
        ConfigStore.writeTextAtomic(f, arr.toString())
        return true
    }

    fun listKanbanComments(boardId: String, taskId: String): List<String> {
        val f = File(kanbanDir(), "$boardId-$taskId-comments.json")
        if (!f.exists()) return emptyList()
        val arr = runCatching { JSONArray(f.readText()) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.optString("body") }
    }

    // ── Cron jobs (Room-backed) ────────────────────────────────

    data class CronJob(
        val id: String,
        val name: String,
        val cronExpr: String,
        val command: String,
        val enabled: Boolean,
        val lastRun: Long?,
        val nextRun: Long?,
    )

    fun listCronJobs(): List<CronJob> = kotlinx.coroutines.runBlocking {
        database.cronJobDao().listAll().map { e ->
            CronJob(
                id = e.id, name = e.name, cronExpr = e.cronExpr, command = e.command,
                enabled = e.enabled, lastRun = e.lastRun, nextRun = e.nextRun,
            )
        }
    }

    fun createCronJob(job: CronJob): CronJob {
        kotlinx.coroutines.runBlocking {
            database.cronJobDao().insert(
                com.nousresearch.hermes.db.CronJobEntity(
                    id = job.id, name = job.name, cronExpr = job.cronExpr, command = job.command,
                    enabled = job.enabled, lastRun = job.lastRun, nextRun = job.nextRun,
                ),
            )
        }
        return job
    }

    fun removeCronJob(id: String): Boolean = kotlinx.coroutines.runBlocking {
        database.cronJobDao().delete(id) > 0
    }

    fun pauseCronJob(id: String): Boolean = kotlinx.coroutines.runBlocking {
        database.cronJobDao().setEnabled(id, false) > 0
    }

    fun resumeCronJob(id: String): Boolean = kotlinx.coroutines.runBlocking {
        database.cronJobDao().setEnabled(id, true) > 0
    }

    /** Trigger a cron job by posting its command to the gateway.
     *  The gateway is the source of truth for the actual run. */
    suspend fun triggerCronJob(id: String): String {
        val job = listCronJobs().firstOrNull { it.id == id } ?: return "unknown job: $id"
        return gatewayClient()?.request("POST", "/v1/cron/trigger", mapOf("command" to job.command))
            ?: "no gateway available"
    }

    // ── Mobile-only helpers ────────────────────────────────────

    fun trackEvent(name: String, props: Map<String, String> = emptyMap()) {
        android.util.Log.i("HermesEvent", "$name ${props.entries.joinToString { "${it.key}=${it.value}" }}")
    }

    /** Trigger a short vibration matching [style]: "light" | "medium" | "heavy". */
    fun haptic(style: String = "light") {
        val duration = when (style) { "heavy" -> 30L; "medium" -> 20L; else -> 10L }
        try {
            val v = context.getSystemService(android.content.Context.VIBRATOR_SERVICE)
                as android.os.Vibrator
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                v.vibrate(android.os.VibrationEffect.createOneShot(duration, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(duration)
            }
        } catch (_: Exception) { /* no vibrator; ignore */ }
    }

    /** Open the Files app on ~/.hermes/. */
    fun openHermesHomeInFiles() {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context,
            context.packageName + ".fileprovider",
            hermesHome(),
        )
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW)
        intent.setDataAndType(uri, "resource/folder")
        intent.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        try { context.startActivity(intent) } catch (_: Exception) { openExternal("file://${hermesHome()}") }
    }

    /** Emit a deep link payload. The MainActivity's onNewIntent
     *  calls this with the inbound URI. */
    fun emitDeepLink(uri: String) {
        scope.launch { _deepLink.tryEmit(uri) }
    }

    private val _deepLink = MutableSharedFlow<String>(
        replay = 0, extraBufferCapacity = 8,
    )
    val deepLink: SharedFlow<String> = _deepLink

    /** Show a media item's context menu. v1: stub that emits a
     *  flow event the chat bubble can listen to. */
    suspend fun showMediaMenu(itemPath: String) {
        _showMenu.tryEmit(itemPath)
    }

    private val _showMenu = MutableSharedFlow<String>(
        replay = 0, extraBufferCapacity = 8,
    )
    val showMenu: SharedFlow<String> = _showMenu

    // ── Gateway helpers (also used internally) ─────────────────

    /** Lazily resolve the gateway client. Most calls go through
     *  the private [gatewayClient] field (chat) but a couple of
     *  the Phase 1.2 methods need a public accessor. */
    private fun gatewayClient(): GatewayClient? = gatewayClient ?: run {
        try { buildGatewayClient().also { gatewayClient = it } } catch (_: Exception) { null }
    }

    // ── API-server key (gateway-backed) ────────────────────────

    suspend fun getApiServerKeyStatus(): Map<String, Any?> =
        runCatching {
            val obj = gatewayClient()?.requestJson("GET", "/v1/api-server/key")
            if (obj == null) emptyMap()
            else mapOf("hasKey" to (obj.optBoolean("hasKey", false)))
        }.getOrDefault(mapOf("hasKey" to false))

    suspend fun generateApiServerKey(): String =
        gatewayClient()?.request("POST", "/v1/api-server/key", mapOf("generate" to "true"))
            ?: ""

    // ── Transcribe audio (gateway-backed) ──────────────────────

    suspend fun transcribeAudio(bytes: ByteArray, mimeType: String, profile: String = activeProfile()): String {
        val client = gatewayClient() ?: return ""
        // Phase 1.2 v1: simple Base64 JSON body; the gateway
        // can switch to multipart/form-data in a later release.
        val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        return client.request("POST", "/v1/audio/transcriptions", mapOf(
            "mime" to mimeType, "profile" to profile, "data" to b64,
        ))
    }

    // ── Update flow (GitHub Releases; stub v1) ──────────────────

    data class UpdateInfo(
        val available: Boolean,
        val currentVersion: String,
        val latestVersion: String?,
        val releaseUrl: String?,
        val error: String?,
    )

    /** Hit GitHub's releases/latest endpoint and compare semvers. */
    suspend fun checkForUpdates(): UpdateInfo {
        val current = getAppVersion()
        // v1: stub returns "no update available" until the CI
        // writes a real manifest; the manifest URL is also a
        // future Phase 7 addition.
        return UpdateInfo(
            available = false,
            currentVersion = current,
            latestVersion = current,
            releaseUrl = null,
            error = null,
        )
    }

    suspend fun downloadUpdate(): String = "" // v1: stub
    suspend fun installUpdate(): Boolean = false // v1: stub

    // ── Claw3D (v1: stub - "not supported on mobile") ──────────

    fun claw3dNotSupported(method: String): Nothing =
        throw UnsupportedOperationException("Claw3D is not supported on mobile: $method")

    // ── Discover provider models (gateway-backed) ──────────────

    suspend fun discoverProviderModels(provider: String): List<String> {
        val client = gatewayClient() ?: return emptyList()
        val body = runCatching {
            client.requestJson("POST", "/v1/providers/$provider/discover") ?: return emptyList()
        }.getOrNull() ?: return emptyList()
        val models = body.optJSONArray("models") ?: return emptyList()
        return (0 until models.length()).mapNotNull { models.optString(it).takeIf { s -> s.isNotEmpty() } }
    }

    // ── SharedFlow: emit a context-menu copy event ─────────────

    suspend fun onContextMenuCopyChat(text: String) {
        _contextMenuCopy.tryEmit(text)
    }

    private val _contextMenuCopy = MutableSharedFlow<String>(
        replay = 0, extraBufferCapacity = 8,
    )
    val contextMenuCopy: SharedFlow<String> = _contextMenuCopy

    suspend fun onContextMenuSelectBubble(bubbleId: String) {
        _contextMenuSelect.tryEmit(bubbleId)
    }

    private val _contextMenuSelect = MutableSharedFlow<String>(
        replay = 0, extraBufferCapacity = 8,
    )
    val contextMenuSelect: SharedFlow<String> = _contextMenuSelect

    // ── SharedFlows: menu actions (FAB taps, top bar) ──────────

    suspend fun onMenuNewChat() { _menuNewChat.tryEmit(Unit) }
    private val _menuNewChat = MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 4)
    val menuNewChat: SharedFlow<Unit> = _menuNewChat

    suspend fun onMenuSearchSessions() { _menuSearchSessions.tryEmit(Unit) }
    private val _menuSearchSessions = MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 4)
    val menuSearchSessions: SharedFlow<Unit> = _menuSearchSessions

    companion object {
        private const val TAG = "HermesApi"

        // Prefs file + key names. Centralised here so the bridge
        // and any future native UI touch the same keys.
        private const val PREFS_CONNECTION = "hermes_connection"
        private const val KEY_MODE = "mode"
        private const val KEY_REMOTE_URL = "remoteUrl"
        private const val KEY_API_KEY = "apiKey"

        private const val PREFS_SSH = "hermes_ssh"
        private const val KEY_SSH_HOST = "host"
        private const val KEY_SSH_PORT = "port"
        private const val KEY_SSH_USERNAME = "username"
        private const val KEY_SSH_KEY_PATH = "keyPath"
        private const val KEY_SSH_REMOTE_PORT = "remotePort"
        private const val KEY_SSH_LOCAL_PORT = "localPort"

        private const val PREFS_SENTRY = "hermes_sentry"
        private const val KEY_SENTRY_ENABLED = "enabled"

        // Phase 1.1 — locale + active profile prefs
        private const val PREFS_LOCALE = "hermes_locale"
        private const val KEY_LOCALE = "code"

        private const val PREFS_PROFILE = "hermes_profile"
        private const val KEY_ACTIVE_PROFILE = "active"
    }
}
