package com.nousresearch.hermes

import android.content.Context
import android.content.pm.PackageManager

/**
 * Probes the device for Termux and the Termux:API add-on.
 *
 * The hybrid Python strategy uses Termux when present (smaller APK,
 * reuses the user's existing Python install + apt dependencies), and
 * falls back to a bundled CPython runtime (extracted from APK assets
 * by [BundledPythonRunner]) when Termux isn't installed.
 *
 * The probe is purely a `PackageManager.getPackageInfo` check — no
 * intents fired, no Termux API called. Cheap and safe to call on
 * every app launch.
 */
object TermuxProbe {

    const val TERMUX_PACKAGE = "com.termux"
    const val TERMUX_API_PACKAGE = "com.termux.api"

    /** Returns true if both Termux and Termux:API are installed. */
    fun isInstalled(context: Context): Boolean {
        return isTermuxInstalled(context) && isTermuxApiInstalled(context)
    }

    fun isTermuxInstalled(context: Context): Boolean {
        return isPackageInstalled(context, TERMUX_PACKAGE)
    }

    fun isTermuxApiInstalled(context: Context): Boolean {
        return isPackageInstalled(context, TERMUX_API_PACKAGE)
    }

    /** Version name of the Termux package, or null if not installed. */
    fun termuxVersion(context: Context): String? {
        return packageVersion(context, TERMUX_PACKAGE)
    }

    /**
     * Workstream C: Termux >= 0.109 is required for the
     * `RUN_COMMAND_PENDING_INTENT` mechanism that `TermuxRunner.runAndWait`
     * depends on (older Termux silently drops the extra and runs
     * fire-and-forget, leaving our continuation hanging forever).
     *
     * Current F-Droid Termux is 0.119+, so this should always pass for
     * users who installed via the Workstream A F-Droid deep-link.
     * Side-loaders running ancient builds get a graceful diagnostic
     * via [InstallScreen]'s permission-needed surface.
     *
     * Returns true if Termux is installed AND the parsed version is
     * at or above the floor. Returns false if not installed, version
     * unparseable, or below the floor.
     */
    fun isRunCommandResultSupported(context: Context): Boolean {
        if (!isTermuxInstalled(context)) return false
        val raw = termuxVersion(context) ?: return false
        return isVersionAtLeast(raw, RUN_COMMAND_PENDING_INTENT_MIN_VERSION)
    }

    /** Returns the absolute path Termux uses for its $PREFIX. */
    fun termuxHome(context: Context): String? {
        // Termux stores its root at /data/data/com.termux/files/ — we
        // can't read it directly (per-user data), but we know the
        // layout. The hermes-agent install lives at
        // /data/data/com.termux/files/home/.hermes/hermes-agent/.
        return if (isTermuxInstalled(context)) "/data/data/$TERMUX_PACKAGE/files" else null
    }

    private fun isPackageInstalled(context: Context, pkg: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(pkg, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        } catch (e: Exception) {
            // Some OEMs (Xiaomi, Huawei) throw generic exceptions
            // instead of NameNotFoundException. Treat as not installed.
            false
        }
    }

    private fun packageVersion(context: Context, pkg: String): String? {
        return try {
            val info = context.packageManager.getPackageInfo(pkg, 0)
            info.versionName
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Parses a dotted version string (e.g. "0.119.1", "0.109") into a
     * list of ints and compares lexicographically against `floor`.
     * Tolerant of trailing non-numeric junk (e.g. "0.119.1-debug" →
     * [0, 119, 1]) — the dash-suffix is dropped on parsing the last
     * segment.
     */
    internal fun isVersionAtLeast(raw: String, floor: List<Int>): Boolean {
        val actual = raw.split(".").map { seg ->
            seg.takeWhile { it.isDigit() }.toIntOrNull() ?: return false
        }
        val len = maxOf(actual.size, floor.size)
        for (i in 0 until len) {
            val a = actual.getOrElse(i) { 0 }
            val f = floor.getOrElse(i) { 0 }
            if (a > f) return true
            if (a < f) return false
        }
        return true   // exactly equal
    }

    /** Floor for `RUN_COMMAND_PENDING_INTENT` (Termux >= 0.109). */
    private val RUN_COMMAND_PENDING_INTENT_MIN_VERSION = listOf(0, 109)
}
