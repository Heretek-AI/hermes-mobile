package com.nousresearch.hermes

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Runs shell commands inside a Termux environment via the
 * [RUN_COMMAND](https://wiki.termux.com/wiki/Termux:API#Intent_API)
 * intent.
 *
 * Termux's [RunCommandService] is the canonical inter-app bridge: the
 * host app sends a structured Intent, Termux runs the command inside
 * its $PREFIX (so `python`, `pip`, `git` all resolve via the apt-managed
 * PATH), and the output is appended to a log file under
 * `$PREFIX/commands/` plus echoed to logcat.
 *
 * The simplest integration: we send the command, then poll the log
 * file. For Phase 2 we only need fire-and-forget semantics — the
 * renderer watches `onInstallProgress` and we tail the log from inside
 * `HermesInstaller`. Phase 3 (gateway supervision) may want real-time
 * stdout streaming; for now the `logFile` callback gives us a tail to
 * show in the install progress UI.
 */
class TermuxRunner(private val context: Context) {

    /**
     * Result of dispatching a RUN_COMMAND intent. `success` is true if
     * the intent was accepted by Termux; actual command success is
     * reflected in the log file at [logPath].
     */
    data class RunResult(
        val success: Boolean,
        val intentAccepted: Boolean,
        val logPath: String?,
        val error: String? = null,
    )

    /**
     * Dispatch a command to Termux. Runs asynchronously inside Termux
     * — this method returns as soon as the intent is delivered.
     *
     * @param command the shell command to run (passed to `bash -c`).
     * @param cwd the working directory inside Termux (relative to
     *   `$PREFIX`, e.g. `files/home/.hermes/hermes-agent`).
     * @param background if true, Termux runs the command and returns
     *   immediately; if false, Termux blocks the call until exit.
     *   Background is what we want for long-running installs.
     * @param logPath where Termux will append stdout/stderr. The
     *   caller is responsible for tailing this file.
     */
    fun run(
        command: String,
        cwd: String? = null,
        background: Boolean = true,
        logPath: String? = null,
    ): RunResult {
        if (!TermuxProbe.isTermuxInstalled(context)) {
            return RunResult(
                success = false,
                intentAccepted = false,
                logPath = logPath,
                error = "Termux is not installed",
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // We don't actually need to declare the package visibility
            // for Termux specifically (it's explicitly targeted), but
            // Android 11+ restricts implicit cross-app intents. The
            // explicit ComponentName below sidesteps that restriction.
        }

        val intent = Intent().apply {
            component = ComponentName(
                TermuxProbe.TERMUX_PACKAGE,
                "com.termux.app.RunCommandService",
            )
            action = "com.termux.RUN_COMMAND"
            putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/${TermuxProbe.TERMUX_PACKAGE}/files/usr/bin/bash")
            putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", command))
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", background)
            if (cwd != null) {
                putExtra("com.termux.RUN_COMMAND_WORKDIR", cwd)
            }
        }

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            RunResult(success = true, intentAccepted = true, logPath = logPath)
        } catch (e: SecurityException) {
            // Android 12+ blocks background service starts from
            // background contexts. The install UI is in the
            // foreground when this fires, so this should never trip
            // in practice — but log it for diagnostics.
            Log.e(TAG, "SecurityException dispatching RUN_COMMAND: ${e.message}")
            RunResult(false, false, logPath, e.message)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to dispatch RUN_COMMAND: ${e.message}")
            RunResult(false, false, logPath, e.message)
        }
    }

    /**
     * Workstream C variant of [run] that blocks the calling coroutine
     * until Termux's RunCommandService broadcasts back the result via
     * the `com.termux.RUN_COMMAND_PENDING_INTENT` mechanism (requires
     * Termux >= 0.109; current F-Droid Termux 0.119+ is well above the
     * floor).
     *
     * Returns a [BundledPythonRunner.PythonResult] populated with the
     * REAL exit code, REAL stdout, REAL stderr — making every
     * `HermesInstaller` shell stage that gates on `exitCode != 0`
     * work correctly on the Termux backend.
     *
     * Caveats:
     *  - stdout+stderr combined is truncated to ~100KB by Termux
     *    (`stdout_original_length` / `stderr_original_length` carry
     *    the pre-truncation size; we don't currently surface those).
     *  - If the user hasn't granted `com.termux.permission.RUN_COMMAND`
     *    OR Termux's `~/.termux/termux.properties` doesn't have
     *    `allow-external-apps=true`, RunCommandService still fires the
     *    result PendingIntent but with `err != RESULT_OK` and an
     *    `errmsg`; the receiver promotes that to a non-zero exit code
     *    so InstallScreen surfaces the actionable error.
     *  - On Android pre-O (API < 26) [Context.startService] is used
     *    in place of [Context.startForegroundService]; for the same
     *    fire-and-forget reasons as [run].
     *
     * Cancellation: if the coroutine is cancelled before Termux fires
     * the result PendingIntent, the receiver is unregistered via
     * [TermuxResultRegistry.cancel] and the continuation is never
     * resumed (consistent with `suspendCancellableCoroutine` semantics).
     * The dispatched command may still complete inside Termux — there
     * is no way to abort a RunCommand mid-flight from the caller side.
     *
     * Timeouts: callers wrap with `kotlinx.coroutines.withTimeout`
     * when they want bounded waits (e.g. stage 5 pip install allows
     * up to 30 minutes). This method does not impose its own timeout.
     */
    suspend fun runAndWait(
        command: String,
        cwd: String? = null,
    ): BundledPythonRunner.PythonResult = suspendCancellableCoroutine { cont ->
        if (!TermuxProbe.isTermuxInstalled(context)) {
            cont.resume(
                BundledPythonRunner.PythonResult(
                    exitCode = -1,
                    stdout = "",
                    stderr = "Termux is not installed",
                    process = null,
                ),
            )
            return@suspendCancellableCoroutine
        }

        val sessionId = TermuxResultRegistry.register(context, cont)
        val resultPi = TermuxResultRegistry.pendingIntentFor(context, sessionId)
        cont.invokeOnCancellation { TermuxResultRegistry.cancel(sessionId) }

        val intent = Intent().apply {
            component = ComponentName(
                TermuxProbe.TERMUX_PACKAGE,
                "com.termux.app.RunCommandService",
            )
            action = "com.termux.RUN_COMMAND"
            putExtra(
                "com.termux.RUN_COMMAND_PATH",
                "/data/data/${TermuxProbe.TERMUX_PACKAGE}/files/usr/bin/bash",
            )
            putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", command))
            // Always run in background; we don't need an interactive
            // Termux session for these install commands. Foreground
            // mode also makes RUN_COMMAND_PENDING_INTENT misbehave
            // (per the Termux wiki: stderr is null in foreground).
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
            if (cwd != null) {
                putExtra("com.termux.RUN_COMMAND_WORKDIR", cwd)
            }
            // The crucial Workstream C piece — without this extra
            // RunCommandService runs fire-and-forget (the current
            // run() path).
            putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", resultPi)
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (e: SecurityException) {
            // Same Android 12+ background-start guard as run(). We
            // synthesize a failure result so the caller doesn't hang.
            Log.e(TAG, "SecurityException dispatching RUN_COMMAND: ${e.message}")
            TermuxResultRegistry.resolveSynthetic(
                sessionId,
                BundledPythonRunner.PythonResult(
                    exitCode = -1,
                    stdout = "",
                    stderr = "TermuxRunner: SecurityException (${e.message ?: "unknown"})",
                    process = null,
                ),
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to dispatch RUN_COMMAND: ${e.message}")
            TermuxResultRegistry.resolveSynthetic(
                sessionId,
                BundledPythonRunner.PythonResult(
                    exitCode = -1,
                    stdout = "",
                    stderr = "TermuxRunner: dispatch failed (${e.message ?: "unknown"})",
                    process = null,
                ),
            )
        }
    }

    /**
     * Tail the most recent N bytes of a Termux log file. Used by
     * [HermesInstaller] to stream the install output to the renderer's
     * `onInstallProgress` event. The log file is plain UTF-8.
     *
     * @return the tail content, or empty string if the file doesn't
     *   exist yet (Termux hasn't started writing).
     */
    fun tailLog(logPath: String, maxBytes: Int = 4096): String {
        val file = File(logPath)
        if (!file.exists()) return ""
        return try {
            val length = file.length()
            val skip = maxOf(0L, length - maxBytes)
            file.inputStream().use { input ->
                input.skip(skip)
                input.readBytes().toString(Charsets.UTF_8)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to tail $logPath: ${e.message}")
            ""
        }
    }

    /**
     * Best-effort wait for a Termux log file to appear. Useful right
     * after a `run` call so we can confirm Termux has actually
     * picked up the command before tailing.
     */
    fun waitForLog(logPath: String, timeoutMs: Long = 5_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (File(logPath).exists()) return true
            try { TimeUnit.MILLISECONDS.sleep(100) } catch (_: InterruptedException) { return false }
        }
        return false
    }

    companion object {
        private const val TAG = "TermuxRunner"
    }
}
