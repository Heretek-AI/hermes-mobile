package com.nousresearch.hermes

import android.content.Context
import android.os.Build
import android.util.Base64
import com.nousresearch.hermes.chat.ChatUsage
import com.nousresearch.hermes.chat.MessageEntity
import com.nousresearch.hermes.chat.MessageKind
import com.nousresearch.hermes.chat.SessionSummary
import com.nousresearch.hermes.chat.SseEvent
import com.nousresearch.hermes.db.HermesDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
        val i = android.content.Intent(context, OAuthBrowserActivity::class.java)
        i.putExtra(OAuthBrowserActivity.EXTRA_AUTH_URL, authUrl)
        i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(i)
        return true
    }

    fun cancelOAuthLogin() {
        val i = android.content.Intent("com.nousresearch.hermes.OAUTH_CANCEL")
        i.setPackage(context.packageName)
        context.sendBroadcast(i)
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

    data class VoiceCaptureResult(val mimeType: String, val path: String)

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
        return VoiceCaptureResult(mimeType = mimeType, path = outFile.absolutePath)
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

    fun requestIgnoreBatteryOptimizations(): Boolean = BatteryOptHelper.requestIgnore(context)

    fun getBatteryOptStatus(): Boolean = BatteryOptHelper.isIgnoring(context)

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

    fun getStartOnBoot(): Boolean {
        val prefs = context.getSharedPreferences(
            BootReceiver.START_ON_BOOT_PREFS,
            android.content.Context.MODE_PRIVATE,
        )
        return prefs.getBoolean(BootReceiver.KEY_ENABLED, false)
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
    }
}
