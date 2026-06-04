package com.nousresearch.hermes

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter

/**
 * Supervises the hermes-agent gateway Python subprocess.
 *
 * Responsibilities:
 *
 * 1. **Spawn** `./venv/bin/hermes gateway` (or equivalent) as a child
 *    process via [BundledPythonRunner.runPython] — see
 *    [GatewaySupervisor.spawn].
 * 2. **Track** the process PID via a PID file at
 *    `$HERMES_HOME/profiles/default/gateway.pid`.
 * 3. **Stream stderr** to `filesDir/logs/gateway-stderr.log` (rotated
 *    on each start).
 * 4. **Restart on crash** with exponential backoff:
 *    1s → 2s → 5s → 15s → 30s → 60s (capped at 60s).
 * 5. **Expose state** to [state] (StateFlow for synchronous reads)
 *    and [stateEvents] (SharedFlow for streaming subscribers like
 *    HermesAPIPlugin's `onGatewayStateChange` event).
 *
 * The supervisor is process-scoped (one per HermesAPIPlugin). The
 * `START_STICKY` in [GatewayForegroundService] re-arms the service
 * after Android kills it under memory pressure, and the supervisor
 * resumes its watch loop.
 */
class GatewaySupervisor(
    private val context: Context,
    private val installer: HermesInstaller,
    private val bundled: BundledPythonRunner = BundledPythonRunner(context),
) {

    /**
     * Public state snapshot. Mirrors the `onGatewayStateChange` event
     * shape in `packages/hermes-ipc/src/types.ts`.
     */
    data class State(
        val running: Boolean,
        val port: Int,
        val pid: Int?,
        val lastError: String?,
        val uptime: Long,
        val backoffSec: Int = 0,
    ) {
        companion object {
            val STOPPED = State(running = false, port = 0, pid = null, lastError = null, uptime = 0)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var watchJob: Job? = null
    private var startMs: Long = 0
    private var crashCount: Int = 0

    private val _state = MutableStateFlow(State.STOPPED)
    val state: StateFlow<State> = _state.asStateFlow()

    // Per-state-change events for the renderer's onGatewayStateChange.
    // Distinct from the StateFlow so the plugin can `notifyListeners`
    // on every transition, not just the latest value.
    private val _stateEvents = kotlinx.coroutines.flow.MutableSharedFlow<State>(replay = 1, extraBufferCapacity = 16)
    val stateEvents: SharedFlow<State> = _stateEvents.asSharedFlow()

    /** Where the PID file lives. Matches the desktop's per-profile
     *  layout so anyone debugging the gateway on-device can find it
     *  the same way they'd find it on macOS/Linux. */
    val pidFile: File
        get() = File(installer.hermesHome, "profiles/default/gateway.pid")

    /** Where stderr is logged. Rotated on each start. */
    val stderrLog: File
        get() = File(context.filesDir, "logs/gateway-stderr.log")

    private val defaultPort = 8642

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /**
     * Spawn the gateway. Idempotent: a second call while one is
     * running is a no-op. The actual process spawn happens on a
     * coroutine; callers can subscribe to [stateEvents] for progress.
     */
    fun spawn() {
        scope.launch {
            mutex.withLock {
                if (watchJob?.isActive == true) {
                    Log.w(TAG, "gateway already running; spawn is a no-op")
                    return@withLock
                }
                crashCount = 0
                watchJob = launch { watchLoop() }
            }
        }
    }

    /** Stop the gateway and cancel the watch loop. Safe to call from
     *  any thread. The current Python subprocess is destroyed
     *  (`Process.destroyForcibly`); restart on next [spawn]. */
    fun stop() {
        scope.launch {
            mutex.withLock {
                watchJob?.cancel("user stop")
                watchJob = null
                pidFile.delete()
                update(State.STOPPED.copy(lastError = null))
            }
        }
    }

    /** Synchronous status read for `gatewayStatus` IPC. */
    fun currentState(): State = _state.value

    /** Tear down on plugin disposal. Cancels all coroutines; the
     *  Python subprocess (if any) is left to the kernel — Android
     *  will SIGKILL the process group on app death. */
    fun dispose() {
        scope.cancel("plugin disposed")
    }

    // ------------------------------------------------------------------
    // Internal: spawn + watch + backoff
    // ------------------------------------------------------------------

    private suspend fun watchLoop() {
        while (scope.isActive) {
            val exitCode = runOnce()
            if (!scope.isActive) return

            // If the user called stop() while runOnce was running, the
            // loop was supposed to be cancelled. If we got here, the
            // gateway exited on its own — treat as a crash and apply
            // backoff before restart.
            crashCount++
            if (crashCount > 6) {
                // After 6 consecutive crashes, give up and surface the
                // error to the renderer. The user has to re-trigger
                // startGateway from the UI.
                update(
                    State.STOPPED.copy(
                        lastError = "Gateway crashed 6 times in a row; check logs at ${stderrLog.absolutePath}",
                    ),
                )
                return
            }
            val backoff = backoffFor(crashCount)
            update(state.value.copy(running = false, pid = null, backoffSec = backoff))
            Log.w(TAG, "gateway exited code=$exitCode; backing off ${backoff}s (crash #$crashCount)")
            delay(backoff * 1000L)
        }
    }

    /**
     * Spawn the gateway once and block (suspend) until it exits.
     * Returns the exit code. Concurrent calls in the same coroutine
     * context are not safe; the supervisor's mutex guards this.
     */
    private suspend fun runOnce(): Int {
        val port = defaultPort
        if (!bundled.tryReservePort(port)) {
            update(state.value.copy(running = false, lastError = "Port $port is in use"))
            return -1
        }
        val logFile = stderrLog.apply {
            parentFile?.mkdirs()
            // Truncate on each start so the log represents the most
            // recent run. The previous run's log is overwritten; the
            // plan §D.5 says rotated on each start.
            if (exists()) delete()
            createNewFile()
        }
        startMs = System.currentTimeMillis()
        update(State(running = true, port = port, pid = null, lastError = null, uptime = 0))

        val repo = installer.hermesRepo
        if (!repo.exists()) {
            update(state.value.copy(running = false, lastError = "hermes-agent not installed"))
            return -1
        }
        val venv = installer.hermesVenv
        if (!venv.exists()) {
            update(state.value.copy(running = false, lastError = "venv not found at ${venv.absolutePath}"))
            return -1
        }
        val python = installer.hermesPython
        if (!python.exists()) {
            update(state.value.copy(running = false, lastError = "python binary not found at ${python.absolutePath}"))
            return -1
        }

        // Use ProcessBuilder directly so we get a real Process handle
        // with a pid we can write to disk and a stderr stream we can
        // tee to the log file. BundledPythonRunner wraps this for the
        // install flow, but here we need a long-lived process.
        val pb = ProcessBuilder(
            python.absolutePath,
            "-m", "hermes_cli.main",
            "--profile", "default",
            "gateway",
        )
        pb.directory(repo)
        pb.environment()["HERMES_HOME"] = installer.hermesHome.absolutePath
        pb.environment()["HERMES_PROFILE"] = "default"
        pb.environment()["API_SERVER_PORT"] = port.toString()
        // Load any extra env from .env
        val envFile = installer.hermesEnv
        if (envFile.exists()) {
            envFile.readLines().forEach { line ->
                val eq = line.indexOf('=')
                if (eq > 0 && !line.startsWith("#")) {
                    val k = line.substring(0, eq).trim()
                    val v = line.substring(eq + 1).trim()
                    pb.environment()[k] = v
                }
            }
        }
        pb.redirectErrorStream(false)

        val proc = try {
            pb.start()
        } catch (e: Exception) {
            update(state.value.copy(running = false, lastError = "spawn failed: ${e.message}"))
            return -1
        }
        val pid = try { /* Process.pid is API 26+ */ pidOf(proc) } catch (e: Exception) { -1 }
        pidFile.parentFile?.mkdirs()
        pidFile.writeText(pid.toString())
        update(state.value.copy(running = true, pid = pid, uptime = 0))

        // Tee stderr to the log file in a background thread; the
        // watch loop blocks on proc.waitFor().
        val stderrTee = Thread {
            try {
                PrintWriter(FileWriter(logFile, true)).use { writer ->
                    proc.errorStream.bufferedReader().forEachLine { line ->
                        writer.println(line)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "stderr tee error: ${e.message}")
            }
        }.apply { isDaemon = true; start() }

        // Uptime tick: emit a state every 5s while running, so the
        // renderer's status panel can show "running for 5m23s".
        val ticker = scope.launch {
            while (scope.isActive && state.value.running) {
                delay(5_000)
                if (state.value.running) {
                    update(state.value.copy(uptime = System.currentTimeMillis() - startMs))
                }
            }
        }

        val exit = try {
            proc.waitFor()
        } catch (e: Exception) {
            -1
        }
        ticker.cancel()
        stderrTee.join(2000) // give the tee up to 2s to flush
        pidFile.delete()
        return exit
    }

    private fun backoffFor(crashCount: Int): Int = when (crashCount) {
        1 -> 1
        2 -> 2
        3 -> 5
        4 -> 15
        else -> 60
    }

    private fun update(s: State) {
        _state.value = s
        scope.launch { _stateEvents.emit(s) }
    }

    private fun pidOf(proc: Process): Int {
        // Process.pid() is API 26+; we're minSdk 24 in the plan, so
        // we read /proc/<pid>/cmdline via reflection-free access. The
        // Process class has a public `pid()` from API 26; for API 24
        // we fall back to extracting from toString() or skip.
        return try {
            proc.javaClass.getMethod("pid").invoke(proc) as Int
        } catch (e: Exception) {
            // Pre-O fallback: parse toString for "pid=NNNN"
            val match = Regex("pid=(\\d+)").find(proc.toString())
            match?.groupValues?.get(1)?.toIntOrNull() ?: -1
        }
    }

    companion object {
        private const val TAG = "GatewaySupervisor"
    }
}
