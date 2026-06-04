package com.nousresearch.hermes

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log

/**
 * Battery-optimization (a.k.a. Doze / app standby) helper.
 *
 * Android 6+ ships Doze + app standby, both of which kill background
 * processes under memory or battery pressure. For a long-running
 * gateway, the user has to explicitly exempt our app via
 * Settings → Apps → Hermes → Battery → "Unrestricted".
 *
 * This helper wraps the check + the system intent that opens the
 * right Settings page. The renderer's Gateway screen calls
 * `HermesAPI.getBatteryOptStatus()` on every render to decide
 * whether to show a yellow warning banner, and
 * `HermesAPI.requestIgnoreBatteryOptimizations()` when the user
 * taps the "Optimize for background" button.
 *
 * ## Why not REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
 *
 * Google Play restricts `Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
 * — apps that use it without a "core" use case get rejected at
 * review. Sideload and F-Droid have no such restriction, but to
 * keep both distribution paths simple, we open the full Battery
 * settings page (which works on every Android version and never
 * gets a Play rejection) and let the user tap "Unrestricted"
 * themselves. The plan calls this out in §D.4.
 */
object BatteryOptHelper {

    private const val TAG = "BatteryOpt"

    /**
     * True if the app is already exempt from battery optimizations.
     * On pre-M devices, always returns true (no Doze to worry about).
     */
    fun isIgnoring(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Build an Intent that opens the system settings page where the
     * user can grant the exemption. The renderer calls this and
     * launches the activity via the Capacitor Browser plugin or
     * directly via `window.open` (the WebView handles `Intent` URLs
     * that resolve to a Settings activity).
     *
     * @return the intent; caller decides how to launch it
     *   (Capacitor `Browser.open` with the intent's URI, or
     *   `context.startActivity`).
     */
    fun buildRequestIntent(context: Context): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
        return try {
            // The "right" intent on most Android versions. If the OEM
            // has customised the Settings app and this Intent doesn't
            // resolve, the catch block falls back to the app-details
            // page.
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } catch (e: Exception) {
            Log.w(TAG, "ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS unavailable: ${e.message}")
            null
        }
    }

    /**
     * Fallback intent: app-details Battery page. Works on every
     * Android version and is what Play Store's review flow expects
     * for non-`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` exemptions.
     */
    fun buildAppDetailsIntent(context: Context): Intent {
        return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /**
     * Convenience: launch the request flow, falling back to the
     * app-details page if the direct request isn't supported (some
     * OEM ROMs strip the action). Returns true on a successful
     * launch, false if the user has to be routed to the app-details
     * page manually.
     */
    fun requestIgnore(context: Context): Boolean {
        val i = buildRequestIntent(context)
            ?: return runCatching {
                context.startActivity(buildAppDetailsIntent(context))
                true
            }.getOrElse { false }
        return try {
            context.startActivity(i)
            true
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "falling back to app-details: ${e.message}")
            runCatching { context.startActivity(buildAppDetailsIntent(context)) }
                .map { true }
                .getOrElse { false }
        } catch (e: SecurityException) {
            // Pre-Q: REQUEST_IGNORE_BATTERY_OPTIMIZATIONS is allowed
            // without a permission. Post-Q some OEMs gate it; the
            // app-details fallback is the only path.
            Log.w(TAG, "request denied: ${e.message}")
            runCatching { context.startActivity(buildAppDetailsIntent(context)) }
                .map { true }
                .getOrElse { false }
        }
    }
}
