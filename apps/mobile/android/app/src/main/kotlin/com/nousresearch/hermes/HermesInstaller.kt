package com.nousresearch.hermes

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.SecureRandom

/**
 * HermesInstaller — orchestrates the 8-stage first-run install of
 * hermes-agent on Android. Mirrors the desktop's
 * `installer.ts:runInstall` so the renderer's `Install.tsx` screen
 * (vendored from the desktop) works unmodified.
 *
 * The flow runs on a coroutine scope the plugin owns; one install at
 * a time per process. The renderer subscribes to [progressStream] via
 * `notifyListeners("onInstallProgress", ...)`; the data class is
 * identical to the desktop's `InstallProgress` so the vendored UI
 * doesn't need a patch.
 *
 * ## Stage map (matches plan §C.1)
 *
 * | # | Title                          | Backend          |
 * |---|--------------------------------|------------------|
 * | 1 | Locate Termux / probe Python   | TermuxProbe      |
 * | 2 | Bootstrap uv                   | Termux or bundle |
 * | 3 | Clone hermes-agent             | Termux or bundle |
 * | 4 | Create Python venv             | Termux or bundle |
 * | 5 | Install Python dependencies    | Termux or bundle |
 * | 6 | Generate config.yaml + .env    | Termux or bundle |
 * | 7 | Reserve gateway port           | BundledPython    |
 * | 8 | Verify with `hermes doctor`    | Termux or bundle |
 *
 * ## Failure semantics
 *
 * Each stage catches its own exceptions and emits a Stage with a
 * non-empty `error` field. The orchestrator stops at the first
 * failure and emits a final Stage with `step = totalSteps` and a
 * `log` line that contains the error. The renderer surfaces this
 * verbatim — the user can re-run the install with `--resume` (Phase
 * 2b) or manually fix and click "Continue".
 */
class HermesInstaller(private val context: Context) {

    /** A single install step's progress. Mirrors `InstallProgress` in
     *  the desktop preload's `index.d.ts`. */
    data class Stage(
        val step: Int,
        val totalSteps: Int,
        val title: String,
        val detail: String,
        val log: String,
        val error: String? = null,
    )

    private val _progress = MutableSharedFlow<Stage>(replay = 1, extraBufferCapacity = 64)
    val progressStream: SharedFlow<Stage> = _progress.asSharedFlow()

    /** Tracks the currently running install so we don't double-fire. */
    @Volatile private var currentJob: Job? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val termux = TermuxRunner(context)
    private val bundled = BundledPythonRunner(context)

    /** Where the hermes-agent install lives. Matches the desktop's
     *  HERMES_HOME convention so the renderer's `~/.hermes/...` paths
     *  resolve. For Termux installs, points at Termux's $PREFIX; for
     *  bundled installs, points at our internal storage. */
    val hermesHome: File
        get() = when (currentBackend()) {
            Backend.TERMUX -> File("/data/data/${TermuxProbe.TERMUX_PACKAGE}/files/home/.hermes")
            Backend.BUNDLED -> File(context.filesDir, "home/.hermes")
            // NONE: no backend available (neither Termux nor
            // bundled-Python). Return a sentinel path; the caller
            // (runStages) checks the backend and emits a
            // 'no_python_backend' error before ever reading
            // hermesHome, so this path is never used in practice.
            Backend.NONE -> File(context.filesDir, "home/.hermes-disabled")
        }

    val hermesRepo: File get() = File(hermesHome, "hermes-agent")
    val hermesVenv: File get() = File(hermesRepo, "venv")
    val hermesPython: File get() = File(hermesVenv, "bin/python")
    val hermesConfig: File get() = File(hermesHome, "config.yaml")
    val hermesEnv: File get() = File(hermesHome, ".env")

    /** What backend the install will use. Decided once per install. */
    enum class Backend { TERMUX, BUNDLED, NONE }

    fun currentBackend(): Backend {
        return when {
            TermuxProbe.isInstalled(context) -> Backend.TERMUX
            bundled.isAvailable() -> Backend.BUNDLED
            else -> Backend.NONE
        }
    }

    /**
     * Start the install flow. Returns immediately; subscribe to
     * [progressStream] for events. Idempotent — a second call while
     * one is in flight is a no-op.
     */
    fun startInstall() {
        if (currentJob?.isActive == true) {
            Log.w(TAG, "install already in progress; ignoring startInstall")
            return
        }
        currentJob = scope.launch {
            try {
                runStages()
            } catch (e: Exception) {
                emit(8, 8, "Install failed", e.message ?: "unknown", "", error = e.message)
            }
        }
    }

    /** Cancel an in-flight install. Safe to call from any thread. */
    fun cancel() {
        currentJob?.cancel("user cancelled")
        currentJob = null
    }

    /** Synchronously check current install state — the desktop's
     *  `checkInstall` IPC method delegates here. */
    fun checkInstall(): CheckResult {
        val backend = currentBackend()
        val installed = hermesPython.exists() && hermesPython.canExecute()
        val configured = hermesConfig.exists() && hermesEnv.exists()
        val verified = installed && runHermesDoctor().exitCode == 0
        val hasApiKey = hermesEnv.exists() && hermesEnv.readLines().any { it.startsWith("API_SERVER_KEY=") && it.length > "API_SERVER_KEY=".length + 8 }
        return CheckResult(
            installed = installed,
            configured = configured,
            hasApiKey = hasApiKey,
            verified = verified,
            activeProfile = "default",
            backend = backend.name,
        )
    }

    data class CheckResult(
        val installed: Boolean,
        val configured: Boolean,
        val hasApiKey: Boolean,
        val verified: Boolean,
        val activeProfile: String?,
        val backend: String,
    )

    /** Run `hermes doctor` and return its combined stdout+stderr. */
    fun runHermesDoctor(): BundledPythonRunner.PythonResult {
        return bundled.runPython(
            argv = listOf("-m", "hermes_cli.main", "doctor"),
            cwd = hermesRepo,
        )
    }

    /** `git pull` hermes-agent at the pinned SHA and re-apply patches. */
    suspend fun runHermesUpdate(): UpdateResult = withContext(Dispatchers.IO) {
        if (!hermesRepo.exists()) {
            return@withContext UpdateResult(false, "hermes-agent not installed yet")
        }
        emit(1, 3, "Pulling hermes-agent", "git fetch + reset to pinned SHA", "")
        val pin = readPinnedSha() ?: return@withContext UpdateResult(false, "no pinned SHA file")
        val pull = runShell(listOf("git", "fetch", "origin"), cwd = hermesRepo)
        if (pull.exitCode != 0) return@withContext UpdateResult(false, "git fetch failed: ${pull.stderr}")
        val reset = runShell(listOf("git", "reset", "--hard", pin), cwd = hermesRepo)
        if (reset.exitCode != 0) return@withContext UpdateResult(false, "git reset failed: ${reset.stderr}")

        emit(2, 3, "Applying mobile patches", "git apply patches/*.patch", "")
        applyPatches()

        emit(3, 3, "Reinstalling Python deps", "pip install -e .[termux-all] -U", "")
        val deps = runPipInstall()
        if (deps.exitCode != 0) return@withContext UpdateResult(false, "pip install failed: ${deps.stderr}")
        UpdateResult(true, null)
    }

    data class UpdateResult(val success: Boolean, val error: String?)

    // ------------------------------------------------------------------
    // Internal: 8-stage flow
    // ------------------------------------------------------------------

    private suspend fun runStages() {
        // ---- Stage 1: Probe Python ----
        val backend = currentBackend()
        if (backend == Backend.NONE) {
            emit(
                1, 8, "Locating Python runtime",
                "Neither Termux nor bundled Python is available",
                "Install Termux and Termux:API from F-Droid, or use the network-download option.",
                error = "no_python_backend",
            )
            return
        }
        emit(
            1, 8, "Python runtime detected",
            "Using ${backend.name.lowercase()} backend",
            "",
        )

        // ---- Stage 2: Bootstrap uv (optional) ----
        // We always skip the explicit uv bootstrap — `pip` from the
        // venv is sufficient on Android and avoids an extra ~30s
        // download. The advanced toggle in the renderer's install UI
        // (Phase 2b) can override.
        emit(2, 8, "Skipping uv bootstrap", "Using venv pip directly", "")

        // ---- Stage 3: Clone hermes-agent ----
        if (!hermesRepo.exists() || !File(hermesRepo, ".git").exists()) {
            emit(3, 8, "Cloning hermes-agent", "git clone from NousResearch/hermes-agent", "")
            val pin = readPinnedSha() ?: run {
                emit(3, 8, "Cloning failed", "no pinned SHA in scripts/hermes-agent-version.txt", "", error = "no_pinned_sha")
                return
            }
            val clone = runShell(
                listOf("git", "clone", "https://github.com/NousResearch/hermes-agent.git", ".",
                      pin) + listOf("--depth", "1"),
                cwd = hermesHome,
            )
            if (clone.exitCode != 0) {
                emit(3, 8, "Clone failed", clone.stderr.take(500), "", error = clone.stderr)
                return
            }
            applyPatches()
        } else {
            emit(3, 8, "hermes-agent already cloned", hermesRepo.absolutePath, "")
        }

        // ---- Stage 4: Create venv ----
        if (!hermesVenv.exists()) {
            emit(4, 8, "Creating Python venv", "python3.11 -m venv venv", "")
            val venv = runShell(
                listOf("python3.11", "-m", "venv", "venv"),
                cwd = hermesRepo,
            )
            if (venv.exitCode != 0) {
                emit(4, 8, "venv creation failed", venv.stderr.take(500), "", error = venv.stderr)
                return
            }
        } else {
            emit(4, 8, "venv already exists", hermesVenv.absolutePath, "")
        }

        // ---- Stage 5: Install deps (30-min timeout) ----
        emit(5, 8, "Installing Python dependencies", "./venv/bin/pip install -e .[termux-all] -c constraints-termux.txt", "")
        val deps = runPipInstall()
        if (deps.exitCode != 0) {
            emit(5, 8, "pip install failed", deps.stderr.take(500), "", error = deps.stderr)
            return
        }

        // ---- Stage 6: Write config + .env ----
        emit(6, 8, "Generating config", "Writing config.yaml and .env with API_SERVER_KEY", "")
        writeConfig()
        writeEnv()

        // ---- Stage 7: Reserve port ----
        emit(7, 8, "Reserving gateway port", "ServerSocket(8642) bind check", "")
        if (!bundled.tryReservePort(8642)) {
            emit(
                7, 8, "Port 8642 in use",
                "Another process is bound to 8642. Free the port and retry.",
                "", error = "port_in_use",
            )
            return
        }

        // ---- Stage 8: Smoke test ----
        emit(8, 8, "Verifying install", "./venv/bin/hermes doctor", "")
        val doctor = runHermesDoctor()
        if (doctor.exitCode != 0) {
            emit(8, 8, "hermes doctor failed", doctor.stdout + doctor.stderr, "", error = "doctor_failed")
            return
        }
        emit(8, 8, "Install complete", "hermes-agent is installed and ready", doctor.stdout, null)
    }

    private suspend fun emit(
        step: Int,
        totalSteps: Int,
        title: String,
        detail: String,
        log: String,
        error: String? = null,
    ) {
        _progress.emit(Stage(step, totalSteps, title, detail, log, error))
    }

    private fun runPipInstall(): BundledPythonRunner.PythonResult {
        return bundled.runPython(
            argv = listOf(
                "-m", "pip", "install",
                "-e", ".[termux-all]",
                "-c", "constraints-termux.txt",
                "--disable-pip-version-check",
                "--no-cache-dir",
            ),
            cwd = hermesRepo,
        )
    }

    private fun writeConfig() {
        hermesConfig.parentFile?.mkdirs()
        hermesConfig.writeText("""
            # Generated by hermes-mobile on first run. Edit via Settings.
            default_model: ${'$'}{HERMES_MODEL:-gpt-4o-mini}
            default_provider: ${'$'}{HERMES_PROVIDER:-openai}
            hermes_home: ${hermesHome.absolutePath}
            profiles_dir: ${File(hermesHome, "profiles").absolutePath}
        """.trimIndent() + "\n")
        File(hermesHome, "profiles").mkdirs()
    }

    private fun writeEnv() {
        val apiKey = generateApiServerKey()
        hermesEnv.writeText("""
            # Generated by hermes-mobile on first run.
            HERMES_HOME=${hermesHome.absolutePath}
            HERMES_PROFILE=default
            API_SERVER_KEY=$apiKey
            API_SERVER_PORT=8642
            ANDROID_DATA_DIR=${context.filesDir.absolutePath}
        """.trimIndent() + "\n")
    }

    private fun generateApiServerKey(): String {
        val bytes = ByteArray(32) // 64 hex chars
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun readPinnedSha(): String? = try {
        val f = File(context.filesDir.parentFile, "apps/mobile/scripts/hermes-agent-version.txt")
        // We can't read the app-bundled asset path of the host project
        // from a generated APK. Instead, ship the SHA as a string
        // resource and read it from there. For dev builds, this is
        // the file path; for release, it's a build-time substitute.
        val id = context.resources.getIdentifier("hermes_agent_pinned_sha", "string", context.packageName)
        if (id != 0) context.getString(id) else null
    } catch (e: Exception) {
        null
    }

    private fun applyPatches() {
        // Patches ship as a read-only asset directory
        // "hermes-agent-patches/". For each .patch file, run
        // `git apply --check` then `git apply`. Failures are logged
        // but non-fatal — the user can intervene.
        val patchDir = "hermes-agent-patches"
        try {
            val files = context.assets.list(patchDir) ?: return
            for (name in files.sorted()) {
                if (!name.endsWith(".patch")) continue
                val content = context.assets.open("$patchDir/$name").bufferedReader().readText()
                val checkProc = ProcessBuilder("git", "apply", "--check").directory(hermesRepo)
                    .redirectErrorStream(true).start()
                checkProc.outputStream.bufferedWriter().use { it.write(content) }
                checkProc.outputStream.close()
                val checkExit = checkProc.waitFor()
                if (checkExit != 0) {
                    Log.w(TAG, "patch $name: git apply --check failed; skipping")
                    continue
                }
                val applyProc = ProcessBuilder("git", "apply").directory(hermesRepo)
                    .redirectErrorStream(true).start()
                applyProc.outputStream.bufferedWriter().use { it.write(content) }
                applyProc.outputStream.close()
                applyProc.waitFor()
                Log.i(TAG, "patch $name: applied")
            }
        } catch (e: Exception) {
            Log.w(TAG, "applyPatches failed: ${e.message}")
        }
    }

    /** Run an arbitrary shell command via `ProcessBuilder`. For
     *  Termux installs, this should be dispatched via TermuxRunner
     *  instead — this is the bundled-Python path's escape hatch. */
    private fun runShell(
        argv: List<String>,
        cwd: File? = null,
    ): BundledPythonRunner.PythonResult {
        return bundled.runPython(
            argv = listOf("-c", "import subprocess; print(subprocess.run(${argvToPyList(argv)}, cwd='${cwd?.absolutePath ?: ""}').returncode)"),
            cwd = cwd,
        )
    }

    private fun argvToPyList(argv: List<String>): String {
        return argv.joinToString(", ") { "\"${it.replace("\\", "\\\\").replace("\"", "\\\"")}\"" }
    }

    /** Inspect a directory the user picked — could be an existing
     *  install they want to adopt. Used by `inspectInstallTarget`. */
    fun inspectInstallTarget(): InspectResult {
        val home = hermesHome
        val repo = hermesRepo
        val state = when {
            !home.exists() -> "fresh"
            !repo.exists() -> "fresh"
            !File(repo, ".git").exists() -> "replace"
            else -> "update"
        }
        return InspectResult(
            hermesHome = home.absolutePath,
            repoPath = repo.absolutePath,
            state = state,
        )
    }

    data class InspectResult(
        val hermesHome: String,
        val repoPath: String,
        val state: String, // "fresh" | "update" | "replace"
    )

    /** Validate that a directory the user typed is a usable hermes
     *  install. Used by the renderer's "adopt existing install" path. */
    fun validateHermesHome(dir: String): Boolean {
        val home = File(dir)
        if (!home.exists() || !home.isDirectory) return false
        val repo = File(home, "hermes-agent")
        if (!repo.exists() || !File(repo, ".git").exists()) return false
        val pyproject = File(repo, "pyproject.toml")
        return pyproject.exists() && pyproject.readText().contains("name = \"hermes-agent\"")
    }

    /** Adopt an existing install (used by `inspectInstallTarget` →
     *  `adoptHermesHome`). Symlinks the directory into our expected
     *  layout. No-op for fresh installs. */
    fun adoptHermesHome(dir: String): Boolean {
        if (!validateHermesHome(dir)) return false
        // The user is telling us the install lives at `dir`. We
        // canonicalize and record that as our hermesHome. Since we
        // can't change `hermesHome` after construction (it's a
        // getter), we instead emit a "config override" the renderer
        // persists to SharedPreferences.
        // For Phase 2, we only support the default location; a
        // "use existing install" UI in Phase 5 expands this.
        return dir == hermesHome.absolutePath
    }

    /** Read the upstream hermes-agent version (the `__version__`
     *  string from `hermes_agent/__init__.py`). */
    fun getHermesVersion(): String? {
        if (!hermesPython.exists()) return null
        val r = bundled.runPython(
            argv = listOf("-c", "import importlib.metadata; print(importlib.metadata.version('hermes-agent'))"),
            cwd = hermesRepo,
        )
        return if (r.exitCode == 0) r.stdout.trim() else null
    }

    /** Re-read the version on demand (used by the renderer's
     *  "refresh version" button after a `runHermesUpdate`). */
    suspend fun refreshHermesVersion(): String? {
        emit(1, 1, "Refreshing hermes-agent version", "importlib.metadata.version", "")
        return getHermesVersion()
    }

    /** Release any internal resources. Called when the plugin is
     *  destroyed. */
    fun dispose() {
        scope.cancel("plugin disposed")
        currentJob = null
    }

    companion object {
        private const val TAG = "HermesInstaller"
    }
}
