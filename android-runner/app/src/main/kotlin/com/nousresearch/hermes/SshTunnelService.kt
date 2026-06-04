package com.nousresearch.hermes

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * SshTunnelService — opens an SSH local-port-forward so the
 * mobile app can talk to a remote hermes-agent gateway as if it
 * were on localhost.
 *
 * Mirrors the desktop's `src/main/ssh-remote.ts:startSshTunnel`:
 * spawns `ssh -N -L <localPort>:127.0.0.1:<remotePort> <user>@<host>`
 * as a child process, with the user's SSH key at
 * `~/.ssh/id_rsa` (or whatever `keyPath` the connection config
 * specifies).
 *
 * ## Why a subprocess, not JSch
 *
 * Android's Termux ships `ssh` from OpenSSH. Using the system
 * binary avoids a 600KB+ native dep (JSch) and reuses the user's
 * existing `~/.ssh/config` + known_hosts. The downside is that
 * we need Termux (or another package that provides `/system/bin/ssh`)
 * for the tunnel to work. Power users on rooted devices have
 * `/system/bin/ssh`; everyone else has Termux.
 *
 * If the binary isn't found, `startSshTunnel` returns false and
 * the renderer surfaces a "Termux required" message. Phase 5
 * doesn't add a JSch fallback; we keep the dep surface minimal
 * and let the user route through Termux.
 *
 * ## Lifecycle
 *
 * - One tunnel per process. `startSshTunnel` is idempotent.
 * - Stopped by `stopSshTunnel` (kills the child via
 *   `Process.destroyForcibly`), or by the plugin being disposed.
 * - The tunnel does NOT run as a foreground service — it's
 *   short-lived (opened when the user is actively using the
 *   app) and dies with the process. Phase 6 may promote it to
 *   the gateway FGS if/when we want the tunnel to survive
 *   backgrounding.
 */
class SshTunnelService(private val context: Context) {

    private var proc: Process? = null
    private var stderrLog: File = File(context.filesDir, "logs/ssh-tunnel-stderr.log")

    data class Config(
        val host: String,
        val port: Int = 22,
        val username: String,
        val keyPath: String,
        // Defaults must reference companion-object constants, not
        // instance fields — Kotlin primary-constructor default
        // arguments are evaluated BEFORE the instance is
        // constructed, so an instance field reference would be
        // unresolved. (The 15th smoke run failed at
        // 'Unresolved reference: defaultRemotePort / defaultLocalPort'
        // for exactly this reason.)
        val remotePort: Int = DEFAULT_REMOTE_PORT,
        val localPort: Int = DEFAULT_LOCAL_PORT,
    )

    /**
     * Start the SSH tunnel. Returns true on spawn success; the
     * actual port binding is verified separately by HermesAPIPlugin
     * after a brief delay.
     *
     * Idempotent: a second call while a tunnel is alive is a
     * no-op and returns true.
     */
    fun start(cfg: Config): Boolean {
        if (proc?.isAlive == true) {
            Log.w(TAG, "tunnel already running; start is a no-op")
            return true
        }
        val ssh = findSshBinary() ?: run {
            Log.w(TAG, "no ssh binary found; install Termux or add /system/bin/ssh")
            return false
        }
        // `ssh -N -L localPort:127.0.0.1:remotePort -p sshPort -i keyPath user@host`
        // -N: no remote command (port forward only)
        // -o StrictHostKeyChecking=accept-new: don't prompt on first connect
        // -o ServerAliveInterval=30: keep the connection alive
        val args = listOf(
            ssh,
            "-N",
            "-L", "${cfg.localPort}:127.0.0.1:${cfg.remotePort}",
            "-p", cfg.port.toString(),
            "-i", cfg.keyPath,
            "-o", "StrictHostKeyChecking=accept-new",
            "-o", "ServerAliveInterval=30",
            "-o", "ServerAliveCountMax=3",
            "-o", "ExitOnForwardFailure=yes",
            "${cfg.username}@${cfg.host}",
        )
        return try {
            stderrLog.parentFile?.mkdirs()
            if (stderrLog.exists()) stderrLog.delete()
            stderrLog.createNewFile()
            val pb = ProcessBuilder(args)
            pb.redirectErrorStream(true)
            pb.environment()["HOME"] = context.filesDir.absolutePath
            proc = pb.start()
            // Tee stdout+stderr to the log file in a background
            // thread; we don't read from it unless the user
            // surfaces logs in the Settings tab.
            val tee = Thread {
                try {
                    proc!!.inputStream.bufferedReader().use { r ->
                        r.lineSequence().forEach { line ->
                            stderrLog.appendText(line + "\n")
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "stderr tee error: ${e.message}")
                }
            }.apply { isDaemon = true; start() }
            Log.i(TAG, "tunnel spawned: ${args.joinToString(" ")}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "ssh spawn failed: ${e.message}")
            false
        }
    }

    /** Stop the tunnel. Idempotent. */
    fun stop() {
        try {
            proc?.destroyForcibly()
        } catch (e: Exception) {
            Log.w(TAG, "destroyForcibly: ${e.message}")
        }
        proc = null
    }

    /** True if the child process is alive. Note: even after the
     *  remote end goes away, the SSH process can stay alive while
     *  the OS keeps the local port bound. Callers wanting a
     *  port-liveness check should also try `bundled.tryReservePort`. */
    fun isActive(): Boolean = proc?.isAlive == true

    /** Wait up to [timeoutMs] for the SSH process to bind the
     *  local port. Polls the OS-level port-bind via
     *  BundledPythonRunner.tryReservePort — false means the port
     *  is bound (the tunnel is forwarding), true means the bind
     *  hasn't happened yet. */
    fun waitForReady(timeoutMs: Long = 5_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        val bundled = BundledPythonRunner(context)
        while (System.currentTimeMillis() < deadline) {
            if (!isActive()) return false
            if (!bundled.tryReservePort(defaultLocalPort)) return true // port bound = ready
            try { TimeUnit.MILLISECONDS.sleep(200) } catch (_: InterruptedException) { return false }
        }
        return false
    }

    private fun findSshBinary(): String? {
        // Probe a few well-known locations. Termux is the common
        // case on Android; /system/bin/ssh exists on some
        // rooted/CF-Auto-Root builds. We don't enumerate $PATH
        // because Android's Runtime.exec doesn't honor it the
        // way a real shell does.
        val candidates = listOf(
            "/data/data/com.termux/files/usr/bin/ssh",
            "/system/bin/ssh",
            "/system/xbin/ssh",
            "/vendor/bin/ssh",
        )
        return candidates.firstOrNull { File(it).canExecute() }
    }

    companion object {
        private const val TAG = "SshTunnelService"
        const val DEFAULT_REMOTE_PORT = 8642
        const val DEFAULT_LOCAL_PORT = 8642
    }
}
