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
 * ## Backend dispatch (Workstream B + C, atomic-wondering-sunrise plan)
 *
 * [runShell], [runHermesDoctor] and [runPipInstall] each branch on
 * [currentBackend] and dispatch to either [TermuxRunner.runAndWait]
 * (Termux) or [BundledPythonRunner] (bundled CPython). Pre-Workstream-B,
 * every shell-out went through `bundled.runPython` even on Termux,
 * which made the Termux install path immediately fail at stage 3 with
 * `"Bundled Python not available"`. Workstream C finished the wiring
 * by switching from fire-and-forget `termux.run` to
 * `termux.runAndWait` (which uses `RUN_COMMAND_PENDING_INTENT` + a
 * BroadcastReceiver) so the Termux backend now reports real exit
 * codes and truncated stdout/stderr (~100KB cap imposed by Termux).
 *
 * Per Workstream C, [checkInstall] no longer runs the doctor inline
 * (avoids forcing the entire IPC surface suspend); stage 8 writes a
 * [hermesVerifiedMarker] file after the doctor passes, and
 * [checkInstall] reads that marker. Explicit re-verification is
 * `runHermesDoctor()` from a suspend caller.
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

    /**
     * Where HermesInstaller stores install-state sentinels we need to
     * read back from our app's process.
     *
     * Workstream C followup (B1–B4): on the Termux backend,
     * [hermesHome] resolves to `/data/data/com.termux/files/home/.hermes`,
     * which our app's process can neither read nor write (Android
     * cross-app sandbox). We can still ASK Termux (via [TermuxRunner])
     * to create files under that path — but we can't directly check
     * them later. So install-state sentinels (verified marker, clone
     * marker, venv marker) get relocated to OUR app's `filesDir` on
     * the Termux backend, while real install artifacts (the venv, the
     * repo) stay in Termux's `$PREFIX` where Termux's pip/git wrote
     * them. On the Bundled backend, everything lives co-located with
     * the install (no sandbox crossing).
     */
    private val markerDir: File
        get() = when (currentBackend()) {
            Backend.TERMUX -> File(context.filesDir, "hermes-markers").also { it.mkdirs() }
            Backend.BUNDLED -> hermesHome.also { it.mkdirs() }
            Backend.NONE -> File(context.filesDir, "hermes-markers")
        }

    /**
     * Marker file written by stage 8 ([runStages]) after `hermes doctor`
     * exits 0. [checkInstall] reads this to populate `verified` without
     * having to re-run the doctor on every call (the doctor spawns a
     * Python subprocess and is expensive). Manually delete to force a
     * re-verification on next install. Lives under [markerDir] so the
     * Termux backend can read its own write back.
     */
    val hermesVerifiedMarker: File get() = File(markerDir, ".verified")

    /** Workstream C B3 followup: written after stage 3 (clone) succeeds.
     *  Stage 3 uses this for skip-on-rerun detection on Termux backend
     *  (we can't check `hermesRepo.exists()` from our sandbox there). */
    private val hermesRepoClonedMarker: File get() = File(markerDir, ".repo-cloned")

    /** Workstream C B3 followup: written after stage 4 (venv) succeeds.
     *  Same skip-on-rerun semantics as [hermesRepoClonedMarker]. */
    private val hermesVenvCreatedMarker: File get() = File(markerDir, ".venv-created")

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
     *  `checkInstall` IPC method delegates here.
     *
     *  Workstream C: `verified` no longer runs `hermes doctor`
     *  inline (which would force this method to suspend and cascade
     *  through HermesApi.init/validateChatReadiness). Instead the
     *  install flow's stage 8 writes [hermesVerifiedMarker] after
     *  the doctor passes, and we read that file here. Manual re-
     *  verification is a `runHermesDoctor()` call from a suspend
     *  caller.
     *
     *  Workstream C B1/B2 followup: on the Termux backend we cannot
     *  probe Termux's `$PREFIX` from our sandbox, so `installed`
     *  collapses to the verified marker's existence. We can't
     *  distinguish "files exist but doctor hasn't run yet" from
     *  "nothing's there" — but the marker IS the success signal of a
     *  completed install flow, so this is the right semantics. On
     *  Bundled we keep the file-existence probe (the venv lives in
     *  our own filesDir on that path). */
    fun checkInstall(): CheckResult {
        val backend = currentBackend()
        val installed = when (backend) {
            Backend.TERMUX -> hermesVerifiedMarker.exists()
            Backend.BUNDLED -> hermesPython.exists() && hermesPython.canExecute()
            Backend.NONE -> false
        }
        val configured = when (backend) {
            // Same sandbox reason as `installed`: on Termux we can't see
            // /data/data/com.termux/.../.hermes/config.yaml from our
            // process. Treat the verified marker as a proxy — if the
            // install completed, config + env were generated by stage 6.
            Backend.TERMUX -> hermesVerifiedMarker.exists()
            Backend.BUNDLED -> hermesConfig.exists() && hermesEnv.exists()
            Backend.NONE -> false
        }
        val verified = installed && hermesVerifiedMarker.exists()
        val hasApiKey = when (backend) {
            Backend.TERMUX -> verified  // can't read Termux's .env; trust the marker
            else -> hermesEnv.exists() && hermesEnv.readLines().any { it.startsWith("API_SERVER_KEY=") && it.length > "API_SERVER_KEY=".length + 8 }
        }
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

    /** Run `hermes doctor` and return its combined stdout+stderr.
     *  Backend-aware: on Termux, dispatches via
     *  [TermuxRunner.runAndWait] (real exit code + truncated
     *  stdout/stderr via `RUN_COMMAND_PENDING_INTENT`). Suspend
     *  per Workstream C — callers must be in a coroutine context. */
    suspend fun runHermesDoctor(): BundledPythonRunner.PythonResult = when (currentBackend()) {
        Backend.TERMUX -> termux.runAndWait(
            command = "./venv/bin/hermes doctor",
            cwd = hermesRepo.absolutePath,
        )
        Backend.BUNDLED -> bundled.runPython(
            argv = listOf("-m", "hermes_cli.main", "doctor"),
            cwd = hermesRepo,
        )
        Backend.NONE -> BundledPythonRunner.PythonResult(
            exitCode = -1,
            stdout = "",
            stderr = "No Python backend available (neither Termux nor bundled).",
            process = null,
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
        // B3 followup: on Termux we can't probe hermesRepo from our
        // sandbox, so use the markerDir-resident `hermesRepoClonedMarker`
        // as the skip signal. On Bundled, fall back to the original
        // directory probe.
        val alreadyCloned = when (backend) {
            Backend.TERMUX -> hermesRepoClonedMarker.exists()
            Backend.BUNDLED -> hermesRepo.exists() && File(hermesRepo, ".git").exists()
            Backend.NONE -> false
        }
        if (!alreadyCloned) {
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
            // Record success so we skip this stage on re-runs.
            try { hermesRepoClonedMarker.writeText("cloned-at=${System.currentTimeMillis()}\n") }
            catch (e: Exception) { Log.w(TAG, "failed to write repo-cloned marker: ${e.message}") }
            applyPatches()
        } else {
            emit(3, 8, "hermes-agent already cloned", hermesRepo.absolutePath, "")
        }

        // ---- Stage 4: Create venv ----
        // B3 followup: same Termux-sandbox-can't-probe-Termux pattern
        // as stage 3. Also switch the interpreter name from `python3.11`
        // to `python` — Termux's `pkg install python` ships the binary
        // as `python` (symlinked to the current 3.x), while the
        // Bundled tarball still provides `python3.11` at a known path.
        val alreadyVenv = when (backend) {
            Backend.TERMUX -> hermesVenvCreatedMarker.exists()
            Backend.BUNDLED -> hermesVenv.exists()
            Backend.NONE -> false
        }
        val pythonBin = when (backend) {
            Backend.TERMUX -> "python"
            Backend.BUNDLED -> "python3.11"
            Backend.NONE -> "python"
        }
        if (!alreadyVenv) {
            emit(4, 8, "Creating Python venv", "$pythonBin -m venv venv", "")
            val venv = runShell(
                listOf(pythonBin, "-m", "venv", "venv"),
                cwd = hermesRepo,
            )
            if (venv.exitCode != 0) {
                emit(4, 8, "venv creation failed", venv.stderr.take(500), "", error = venv.stderr)
                return
            }
            try { hermesVenvCreatedMarker.writeText("created-at=${System.currentTimeMillis()}\n") }
            catch (e: Exception) { Log.w(TAG, "failed to write venv-created marker: ${e.message}") }
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
        // Workstream C: persist a marker so subsequent checkInstall()
        // calls don't need to re-run the doctor on every invocation.
        try {
            hermesVerifiedMarker.parentFile?.mkdirs()
            hermesVerifiedMarker.writeText("verified-at=${System.currentTimeMillis()}\nbackend=${backend.name}\n")
        } catch (e: Exception) {
            // Non-fatal: the install succeeded; just means the next
            // checkInstall() will report verified=false until the next
            // explicit doctor call. Log and continue.
            Log.w(TAG, "failed to write verified marker: ${e.message}")
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

    private suspend fun runPipInstall(): BundledPythonRunner.PythonResult = when (currentBackend()) {
        Backend.TERMUX -> termux.runAndWait(
            command = "./venv/bin/pip install -e .[termux-all] -c constraints-termux.txt --disable-pip-version-check --no-cache-dir",
            cwd = hermesRepo.absolutePath,
        )
        Backend.BUNDLED -> bundled.runPython(
            argv = listOf(
                "-m", "pip", "install",
                "-e", ".[termux-all]",
                "-c", "constraints-termux.txt",
                "--disable-pip-version-check",
                "--no-cache-dir",
            ),
            cwd = hermesRepo,
        )
        Backend.NONE -> BundledPythonRunner.PythonResult(
            exitCode = -1,
            stdout = "",
            stderr = "No Python backend available (neither Termux nor bundled).",
            process = null,
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

    private suspend fun applyPatches() {
        // Patches ship as a read-only asset directory
        // "hermes-agent-patches/". For each .patch file, dispatch
        // `git apply --check` then `git apply` via TermuxRunner
        // (Workstream C). Failures are logged but non-fatal — the
        // user can intervene.
        //
        // Mechanic: our app's process cannot write into Termux's
        // $PREFIX (cross-app sandboxing), but Termux can write to its
        // own /data/data/com.termux/files/usr/tmp. So we ship the
        // patch content INLINE via a bash heredoc instead of
        // round-tripping through a shared file. Termux runs the
        // heredoc, gets the patch in $PREFIX/tmp, then applies it.
        val patchDir = "hermes-agent-patches"
        try {
            val files = context.assets.list(patchDir) ?: return
            for (name in files.sorted()) {
                if (!name.endsWith(".patch")) continue
                val content = context.assets.open("$patchDir/$name").bufferedReader().readText()
                val termuxTmp = "/data/data/${TermuxProbe.TERMUX_PACKAGE}/files/usr/tmp"
                val tmpPatch = "$termuxTmp/hermes-patch-$name"
                // Heredoc with a sentinel that's vanishingly unlikely
                // to appear in any real patch text.
                val sentinel = "HERMES_PATCH_EOF_${System.nanoTime()}"
                val writeCmd = "cat > '$tmpPatch' <<'$sentinel'\n$content\n$sentinel\n"
                val write = runShell(listOf("bash", "-c", writeCmd), cwd = hermesRepo)
                if (write.exitCode != 0) {
                    Log.w(TAG, "patch $name: write to Termux tmp failed: ${write.stderr.take(200)}")
                    continue
                }
                val check = runShell(listOf("git", "apply", "--check", tmpPatch), cwd = hermesRepo)
                if (check.exitCode != 0) {
                    Log.w(TAG, "patch $name: git apply --check failed; skipping: ${check.stderr.take(200)}")
                    continue
                }
                val apply = runShell(listOf("git", "apply", tmpPatch), cwd = hermesRepo)
                if (apply.exitCode != 0) {
                    Log.w(TAG, "patch $name: git apply failed: ${apply.stderr.take(200)}")
                    continue
                }
                Log.i(TAG, "patch $name: applied")
            }
        } catch (e: Exception) {
            Log.w(TAG, "applyPatches failed: ${e.message}")
        }
    }

    /**
     * Run an arbitrary shell command. Backend-aware:
     *
     * - **TERMUX**: dispatches via [TermuxRunner.runAndWait] (real exit
     *   code + truncated stdout/stderr via `RUN_COMMAND_PENDING_INTENT`).
     * - **BUNDLED**: shells through the bundled CPython's
     *   `subprocess.run` (the legacy escape hatch — preserves the
     *   pre-Workstream-B behavior for the bundled path).
     * - **NONE**: returns an error result; the caller should have
     *   bailed at the Stage 1 probe.
     */
    private suspend fun runShell(
        argv: List<String>,
        cwd: File? = null,
    ): BundledPythonRunner.PythonResult = when (currentBackend()) {
        Backend.TERMUX -> runShellViaTermux(argv, cwd)
        Backend.BUNDLED -> bundled.runPython(
            argv = listOf(
                "-c",
                "import subprocess; print(subprocess.run(${argvToPyList(argv)}, cwd='${cwd?.absolutePath ?: ""}').returncode)",
            ),
            cwd = cwd,
        )
        Backend.NONE -> BundledPythonRunner.PythonResult(
            exitCode = -1,
            stdout = "",
            stderr = "No Python backend available (neither Termux nor bundled).",
            process = null,
        )
    }

    /**
     * Dispatch a shell command to Termux's RunCommandService and await
     * the result via the `RUN_COMMAND_PENDING_INTENT` mechanism
     * (Workstream C). Returns a [BundledPythonRunner.PythonResult] with
     * the real exit code + truncated stdout/stderr (~100KB combined cap
     * imposed by Termux; original lengths available via
     * `*_ORIGINAL_LENGTH` bundle keys, not currently surfaced).
     *
     * If Termux is not installed or `com.termux.permission.RUN_COMMAND`
     * has not been granted, [TermuxRunner.runAndWait] returns a
     * `PythonResult` with `exitCode = -1` and a diagnostic stderr that
     * the install UI surfaces verbatim (see
     * [com.nousresearch.hermes.ui.onboarding.InstallScreen]'s
     * permission-needed guidance card).
     */
    private suspend fun runShellViaTermux(
        argv: List<String>,
        cwd: File? = null,
    ): BundledPythonRunner.PythonResult {
        val command = argv.joinToString(" ") { shellQuote(it) }
        return termux.runAndWait(command, cwd = cwd?.absolutePath)
    }

    /** POSIX-safe single-quote escaping for a single shell argument. */
    private fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

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
     *  string from `hermes_agent/__init__.py`).
     *
     *  Workstream C B5 followup: backend-aware. On Termux, the venv
     *  lives in `/data/data/com.termux/files/...` which our app can't
     *  probe directly; we dispatch through [TermuxRunner.runAndWait]
     *  to ask Termux to run the importlib.metadata one-liner inside
     *  its own venv. On Bundled we keep the direct
     *  `bundled.runPython` invocation. Suspend because the Termux
     *  path is async. */
    suspend fun getHermesVersion(): String? = when (currentBackend()) {
        Backend.TERMUX -> {
            val r = termux.runAndWait(
                command = "./venv/bin/python -c " +
                    "'import importlib.metadata; print(importlib.metadata.version(\"hermes-agent\"))'",
                cwd = hermesRepo.absolutePath,
            )
            if (r.exitCode == 0) r.stdout.trim().takeIf { it.isNotEmpty() } else null
        }
        Backend.BUNDLED -> {
            if (!hermesPython.exists()) {
                null
            } else {
                val r = bundled.runPython(
                    argv = listOf("-c", "import importlib.metadata; print(importlib.metadata.version('hermes-agent'))"),
                    cwd = hermesRepo,
                )
                if (r.exitCode == 0) r.stdout.trim() else null
            }
        }
        Backend.NONE -> null
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
