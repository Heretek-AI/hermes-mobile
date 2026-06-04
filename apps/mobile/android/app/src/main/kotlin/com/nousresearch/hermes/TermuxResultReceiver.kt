package com.nousresearch.hermes

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume

/**
 * Receives the result broadcast Termux's RunCommandService sends back
 * to our process via the PendingIntent attached to RUN_COMMAND with the
 * `com.termux.RUN_COMMAND_PENDING_INTENT` extra.
 *
 * Termux puts the result in an outer Bundle keyed `"result"` on the
 * fired Intent's extras. Inside that Bundle: `stdout`, `stderr`,
 * `stdout_original_length`, `stderr_original_length`, `exitCode`,
 * `err`, `errmsg` — exact key names verified from
 * `termux-shared/.../TermuxConstants.java`.
 *
 * The receiver is registered dynamically by [TermuxResultRegistry] for
 * exactly one action per pending command (action =
 * `com.nousresearch.hermes.TERMUX_RESULT.<sessionId>`). It looks up the
 * waiting `Continuation` and resumes it with a
 * [BundledPythonRunner.PythonResult] carrying the real exit code +
 * truncated stdout/stderr.
 */
internal class TermuxResultReceiver(private val sessionId: Int) : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Per TermuxConstants: outer Bundle extra is "result".
        val bundle = intent.getBundleExtra(KEY_RESULT_BUNDLE)
        val result = if (bundle != null) {
            BundledPythonRunner.PythonResult(
                exitCode = bundle.getInt(KEY_EXIT_CODE, -1),
                stdout = bundle.getString(KEY_STDOUT) ?: "",
                stderr = bundle.getString(KEY_STDERR) ?: "",
                process = null,
            )
        } else {
            // No "result" bundle — something dispatched the broadcast
            // without going through RunCommandService. Treat as
            // dispatch failure.
            BundledPythonRunner.PythonResult(
                exitCode = -1,
                stdout = "",
                stderr = "TermuxResultReceiver: no result bundle in broadcast",
                process = null,
            )
        }
        // `err` (Termux internal error code; RESULT_OK == -1 == no error)
        // surfaces dispatch-level failures like missing RUN_COMMAND
        // permission. Promote those to a non-zero exitCode with a
        // diagnostic stderr so the install UI's existing failure path
        // surfaces them verbatim.
        val termuxErr = bundle?.getInt(KEY_ERR, RESULT_OK) ?: RESULT_OK
        val finalResult = if (termuxErr != RESULT_OK && result.exitCode == 0) {
            val errmsg = bundle?.getString(KEY_ERRMSG) ?: "Termux returned err=$termuxErr"
            result.copy(
                exitCode = if (termuxErr != 0) termuxErr else -1,
                stderr = if (result.stderr.isEmpty()) errmsg else "${result.stderr}\n$errmsg",
            )
        } else {
            result
        }
        Log.d(TAG, "result sessionId=$sessionId exitCode=${finalResult.exitCode} err=$termuxErr stdoutBytes=${finalResult.stdout.length} stderrBytes=${finalResult.stderr.length}")
        TermuxResultRegistry.deliver(sessionId, finalResult)
    }

    companion object {
        private const val TAG = "TermuxResultReceiver"

        // Verified against termux-shared TermuxConstants.java
        // (https://raw.githubusercontent.com/termux/termux-app/master/termux-shared/src/main/java/com/termux/shared/termux/TermuxConstants.java)
        const val KEY_RESULT_BUNDLE = "result"
        const val KEY_STDOUT = "stdout"
        const val KEY_STDERR = "stderr"
        const val KEY_STDOUT_ORIG_LEN = "stdout_original_length"
        const val KEY_STDERR_ORIG_LEN = "stderr_original_length"
        const val KEY_EXIT_CODE = "exitCode"
        const val KEY_ERR = "err"
        const val KEY_ERRMSG = "errmsg"

        // Termux uses Activity.RESULT_OK (== -1) to mean "no internal error".
        // Anything else (e.g. 1 for "permission denied") is a Termux-side
        // dispatch failure, distinct from the command's own exit code.
        const val RESULT_OK = -1

        /** Action prefix; each pending command gets a unique suffix
         *  (the session ID) so concurrent installs don't race. */
        const val ACTION_PREFIX = "com.nousresearch.hermes.TERMUX_RESULT"
    }
}

/**
 * Process-singleton bridge between [TermuxRunner.runAndWait] (caller
 * side, holds a [Continuation]) and [TermuxResultReceiver] (receiver
 * side, gets the broadcast back from Termux).
 *
 * Lifecycle per command:
 *  1. caller calls [register] → gets a unique session ID, the registry
 *     stashes the continuation, allocates a [TermuxResultReceiver] for
 *     that session ID, registers it with [Context.registerReceiver] on
 *     a unique action filter, and constructs a `PendingIntent.getBroadcast`
 *     the caller hands to Termux's RunCommandService via the
 *     `com.termux.RUN_COMMAND_PENDING_INTENT` Intent extra.
 *  2. Termux runs the command, then fires our PendingIntent with the
 *     result Bundle. Our [TermuxResultReceiver.onReceive] calls
 *     [deliver].
 *  3. [deliver] looks up the continuation, calls `cont.resume(result)`,
 *     unregisters the receiver, removes the map entry.
 *  4. If the caller cancels first (coroutine cancellation), [cancel]
 *     is invoked from `cont.invokeOnCancellation` and we unregister
 *     without resuming.
 *
 * Thread-safe: backing map is a [ConcurrentHashMap]; session ID is an
 * [AtomicInteger]. `remove` returns null on multi-resolve so we won't
 * double-resume a continuation.
 */
internal object TermuxResultRegistry {
    private const val TAG = "TermuxResultRegistry"

    private val nextSessionId = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, Entry>()

    private data class Entry(
        val cont: Continuation<BundledPythonRunner.PythonResult>,
        val receiver: BroadcastReceiver,
        val context: Context,
    )

    /**
     * Register a continuation and a per-session receiver. Returns the
     * session ID; the caller embeds it in the result PendingIntent's
     * action string so the receiver knows which entry to resume.
     */
    fun register(
        context: Context,
        cont: Continuation<BundledPythonRunner.PythonResult>,
    ): Int {
        val sessionId = nextSessionId.getAndIncrement()
        val action = "${TermuxResultReceiver.ACTION_PREFIX}.$sessionId"
        val receiver = TermuxResultReceiver(sessionId)
        val filter = IntentFilter(action)
        // Use the application context to outlive any single activity
        // and to keep the receiver scope bounded to our process.
        // RECEIVER_NOT_EXPORTED on API 33+ (handled by ContextCompat
        // for older API levels).
        ContextCompat.registerReceiver(
            context.applicationContext,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        pending[sessionId] = Entry(cont, receiver, context.applicationContext)
        Log.d(TAG, "register sessionId=$sessionId action=$action (pending=${pending.size})")
        return sessionId
    }

    /**
     * Build the action string for a session ID — used by [TermuxRunner]
     * when constructing the result PendingIntent.
     */
    fun actionForSession(sessionId: Int): String =
        "${TermuxResultReceiver.ACTION_PREFIX}.$sessionId"

    /**
     * Called by [TermuxResultReceiver.onReceive] when Termux fires the
     * PendingIntent. Idempotent — a duplicate broadcast finds no entry
     * and is a no-op.
     */
    fun deliver(sessionId: Int, result: BundledPythonRunner.PythonResult) {
        val entry = pending.remove(sessionId) ?: run {
            Log.w(TAG, "deliver sessionId=$sessionId: no pending continuation (cancelled or duplicate)")
            return
        }
        try {
            entry.context.unregisterReceiver(entry.receiver)
        } catch (e: IllegalArgumentException) {
            // Receiver already unregistered (e.g. cancel race). Safe to ignore.
        }
        entry.cont.resume(result)
    }

    /**
     * Called from `cont.invokeOnCancellation`. Removes the entry and
     * unregisters the receiver WITHOUT resuming (the continuation is
     * already cancelled). Safe to call after `deliver` already removed
     * the entry — `remove` returns null and we no-op.
     */
    fun cancel(sessionId: Int) {
        val entry = pending.remove(sessionId) ?: return
        try {
            entry.context.unregisterReceiver(entry.receiver)
        } catch (e: IllegalArgumentException) {
            // Already unregistered.
        }
        Log.d(TAG, "cancel sessionId=$sessionId (pending=${pending.size})")
    }

    /**
     * Caller-side: synthesize a failure result without going through
     * the broadcast pipeline (e.g. when `startForegroundService` throws
     * synchronously before Termux ever sees the intent). Removes the
     * entry, unregisters the receiver, resumes the continuation.
     */
    fun resolveSynthetic(sessionId: Int, result: BundledPythonRunner.PythonResult) {
        deliver(sessionId, result)
    }

    /**
     * Construct the PendingIntent the caller attaches to the
     * RUN_COMMAND intent as
     * `com.termux.RUN_COMMAND_PENDING_INTENT`. Uses
     * [PendingIntent.FLAG_MUTABLE] because Termux's RunCommandService
     * mutates the intent extras (it adds the result Bundle before
     * firing); [PendingIntent.FLAG_IMMUTABLE] throws
     * IllegalArgumentException at startForegroundService time on
     * API 31+.
     *
     * The result Intent is scoped to our own package via
     * `setPackage` so only our process receives the broadcast.
     */
    fun pendingIntentFor(context: Context, sessionId: Int): PendingIntent {
        val resultIntent = Intent(actionForSession(sessionId))
            .setPackage(context.packageName)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getBroadcast(
            context.applicationContext,
            sessionId,
            resultIntent,
            flags,
        )
    }
}
