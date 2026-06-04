package com.nousresearch.hermes

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Starts the gateway foreground service after a phone reboot, but
 * only if the user has opted in via the renderer's Settings screen
 * (`HermesAPI.setStartOnBoot(true)`).
 *
 * ## Manifest
 *
 * Requires:
 * ```xml
 * <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
 *
 * <receiver
 *     android:name=".BootReceiver"
 *     android:enabled="true"
 *     android:exported="true">
 *   <intent-filter android:priority="1000">
 *     <action android:name="android.intent.action.BOOT_COMPLETED" />
 *     <action android:name="android.intent.action.QUICKBOOT_POWERON" />
 *     <action android:name="com.htc.intent.action.QUICKBOOT_POWERON" />
 *   </intent-filter>
 * </receiver>
 * ```
 *
 * `exported="true"` is required for the system to deliver the boot
 * broadcast. The HTC + QUICKBOOT variants cover older HTC devices
 * that emit a different action; modern Android (12+) normalises to
 * `BOOT_COMPLETED` only.
 *
 * ## Why opt-in
 *
 * Auto-starting a long-running service on every boot is a battery
 * and privacy cost; many users reboot their phones daily. We
 * require explicit opt-in via the renderer's `setStartOnBoot` so
 * the default is "the gateway runs only when the user opens the
 * app". Power users can flip it in Settings.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON" &&
            action != "com.htc.intent.action.QUICKBOOT_POWERON"
        ) {
            return
        }
        val prefs = context.getSharedPreferences(START_ON_BOOT_PREFS, Context.MODE_PRIVATE)
        val optIn = prefs.getBoolean(KEY_ENABLED, false)
        if (!optIn) {
            Log.i(TAG, "boot completed but start-on-boot is disabled; skipping")
            return
        }
        Log.i(TAG, "boot completed and start-on-boot is enabled; starting gateway FGS")
        val i = Intent(context, GatewayForegroundService::class.java).apply {
            action = GatewayForegroundService.ACTION_START
            putExtra(GatewayForegroundService.EXTRA_FROM_BOOT, true)
        }
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        } catch (e: Exception) {
            // FGS can fail to start if the app was just installed and
            // the user hasn't opened it once yet (some Android
            // versions require an explicit user-initiated launch
            // before a FGS can run in the background).
            Log.w(TAG, "failed to start gateway FGS at boot: ${e.message}")
        }
    }

    companion object {
        const val START_ON_BOOT_PREFS = "hermes_settings"
        const val KEY_ENABLED = "start_on_boot"
        private const val TAG = "BootReceiver"
    }
}
