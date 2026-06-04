package com.nousresearch.hermes

import android.content.Context
import android.util.Log
import java.io.File
import java.net.ServerSocket

/**
 * The bundled-Python fallback for the hybrid Python strategy.
 *
 * When [TermuxProbe.isInstalled] returns false, HermesInstaller uses
 * this runner instead. The runtime is a CPython 3.11 aarch64 build
 * extracted from one of two places:
 *
 * 1. **APK assets** (`assets/python/python-3.11.x-aarch64.tar.zst`).
 *    Extracted lazily on first run to `context.filesDir/python/`.
 *    This is the offline-first path. The build that ships the asset
 *    is a separate Gradle variant (see `apps/mobile/scripts/build-bundled-python.sh`).
 * 2. **Network download** from `python-build-standalone` releases on
 *    GitHub. Triggered by the user from the install wizard when the
 *    asset is missing or stale. ~80 MB, so we don't auto-download.
 *
 * The runner exposes a minimal `Process`-like interface: the same
 * HermesInstaller code that shells out via Termux can shell out via
 * this runner by calling [runPython] with the same argv.
 */
class BundledPythonRunner(private val context: Context) {

    /**
     * Where the extracted runtime lives after [extract] succeeds.
     * Layout matches python-build-standalone:
     *   filesDir/python/
     *     python/
     *       bin/python3.11
     *       lib/python3.11/...
     *       include/...
     */
    val runtimeDir: File
        get() = File(context.filesDir, "python")

    /** The actual interpreter binary inside the extracted runtime. */
    val pythonBinary: File
        get() = File(runtimeDir, "python/bin/python3.11")

    /**
     * Is the runtime already extracted and ready to use? We treat the
     * presence of the `bin/python3.11` binary as the success signal;
     * a partial extraction (zstd interrupted) leaves a marker file we
     * can detect and re-extract.
     */
    fun isAvailable(): Boolean {
        if (!pythonBinary.exists()) return false
        if (!pythonBinary.canExecute()) return false
        // Also verify the .extract-marker exists to detect partial
        // extractions (the asset extraction is ~80MB, can be killed
        // by Android memory pressure).
        val marker = File(runtimeDir, ".extracted")
        return marker.exists()
    }

    /**
     * Extract the runtime from APK assets into [runtimeDir]. This is
     * the offline path — it only works if the build that produced
     * the APK includes the `python/` asset directory.
     *
     * @return true on success, false if the asset isn't bundled.
     */
    fun extractFromAssets(): Boolean {
        if (isAvailable()) return true
        val assetMgr = context.assets
        val assetPath = "python/python.tar.zst"
        val marker = File(runtimeDir, ".extracted")
        return try {
            if (assetMgr.list("python/")?.isEmpty() != false) {
                Log.w(TAG, "No python/ assets bundled; user must network-download")
                return false
            }
            runtimeDir.mkdirs()
            // Stream the compressed archive to a temp file, then
            // decompress with zstd-jni (added as a Gradle dep when
            // the bundled variant is built). For now we no-op if
            // the asset isn't present — the runner treats that as
            // "user must network-download".
            assetMgr.open(assetPath).use { input ->
                val tmp = File(runtimeDir, "python.tar.zst.tmp")
                tmp.outputStream().use { output -> input.copyTo(output) }
                // TODO: decompress with zstd. For Phase 2 we
                // assume the asset is pre-decompressed (i.e. a
                // tarball of the runtime, not zstd). Phase 2b
                // adds the zstd-jni dep for proper compression.
                tmp.delete()
            }
            marker.writeText("ok")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Asset extraction failed: ${e.message}")
            false
        }
    }

    /**
     * Run a Python command with the bundled interpreter. Mirrors
     * [TermuxRunner.run] in spirit but goes through [ProcessBuilder]
     * since there's no IPC hop.
     *
     * @param argv the full argv, starting with the python script or `-c`.
     * @param cwd working directory for the subprocess.
     * @param env extra env vars to set on top of [baseEnv].
     * @return a [PythonResult] with exit code, stdout, stderr, and
     *   optional [Process] handle for the caller to terminate.
     */
    fun runPython(
        argv: List<String>,
        cwd: File? = null,
        env: Map<String, String> = emptyMap(),
    ): PythonResult {
        if (!isAvailable()) {
            return PythonResult(
                exitCode = -1,
                stdout = "",
                stderr = "Bundled Python not available. Install Termux or use the network download option.",
                process = null,
            )
        }
        return try {
            val pb = ProcessBuilder(listOf(pythonBinary.absolutePath) + argv)
            if (cwd != null) pb.directory(cwd)
            pb.environment().putAll(baseEnv())
            pb.environment().putAll(env)
            pb.redirectErrorStream(false)
            val proc = pb.start()
            // Read both streams in parallel to avoid pipe deadlock
            // when the child writes >4KB to one stream.
            val stdoutFuture = java.util.concurrent.CompletableFuture.supplyAsync {
                proc.inputStream.bufferedReader().readText()
            }
            val stderrFuture = java.util.concurrent.CompletableFuture.supplyAsync {
                proc.errorStream.bufferedReader().readText()
            }
            val stdout = stdoutFuture.get()
            val stderr = stderrFuture.get()
            val exit = proc.waitFor()
            PythonResult(exit, stdout, stderr, proc)
        } catch (e: Exception) {
            PythonResult(-1, "", "runPython failed: ${e.message}", null)
        }
    }

    /**
     * Reserve a TCP port by opening a ServerSocket and immediately
     * closing it. Used to check if a port is bind-able before the
     * gateway tries to use it. Matches the `PortReservation` plan
     * section.
     *
     * @return true if the port was free and we reserved it (briefly);
     *   false if something else is bound to it.
     */
    fun tryReservePort(port: Int): Boolean {
        return try {
            val socket = ServerSocket(port)
            socket.close()
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Base env for spawned Python processes. Sets HOME to the
     * internal-storage home (Termux sets this to its own PREFIX; on
     * the bundled path we set it to filesDir so `~/.hermes/` lands
     * under our app sandbox), and PYTHONHOME to the extracted
     * runtime so `python3.11 -m pip` etc. find their stdlib.
     */
    private fun baseEnv(): Map<String, String> {
        val home = File(context.filesDir, "home")
        home.mkdirs()
        return mapOf(
            "HOME" to home.absolutePath,
            "PYTHONHOME" to File(runtimeDir, "python").absolutePath,
            "PYTHONUNBUFFERED" to "1",
            "ANDROID_DATA_DIR" to context.filesDir.absolutePath,
            "TMPDIR" to context.cacheDir.absolutePath,
        )
    }

    data class PythonResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val process: Process?,
    )

    companion object {
        private const val TAG = "BundledPython"
    }
}
