package com.nousresearch.hermes

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Long-running foreground service that owns the [GatewaySupervisor].
 *
 * Android 14+ requires every foreground service to declare a
 * `foregroundServiceType` and pass the matching `ServiceInfo.FOREGROUND_SERVICE_TYPE_*`
 * constant to `startForeground`. We use `specialUse` — the only type
 * that fits a long-running AI server. The justification string is
 * declared in the manifest as a `<property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE" />`
 * entry; Play Console review looks at this string to confirm the
 * declaration is honest.
 *
 * ## Why specialUse
 *
 * We considered the other types and rejected them:
 * - `dataSync` — Google Play restricts to <6h/day; not for AI.
 * - `syncManager` — only for system sync adapters; rejected by Play.
 * - `mediaPlayback` / `location` / `phoneCall` — wrong type.
 *
 * The justification (also in AndroidManifest.xml):
 * "Long-running AI agent gateway with HTTP/SSE chat, scheduled cron
 * tasks, and 16 messaging platform integrations. Killing the process
 * terminates the user's assistant and breaks the user-facing chat UI."
 *
 * ## Lifecycle
 *
 * | Trigger                | Action                                            |
 * |------------------------|---------------------------------------------------|
 * | startService           | startForeground + supervisor.spawn                |
 * | START_STICKY           | re-arms service after Android kills for memory    |
 * | User taps Stop action  | supervisor.stop + stopSelf                         |
 * | User taps Open action  | PendingIntent → MainActivity                      |
 * | bootCompleted          | BootReceiver starts this service (opt-in)         |
 */
class GatewayForegroundService : Service() {

    companion object {
        const val ACTION_START = "com.nousresearch.hermes.START"
        const val ACTION_STOP = "com.nousresearch.hermes.STOP"
        const val EXTRA_FROM_BOOT = "from_boot"
        private const val NOTIF_ID = 1
        private const val TAG = "GatewayFGS"

        fun start(context: Context) {
            val i = Intent(context, GatewayForegroundService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, GatewayForegroundService::class.java))
        }
    }

    private lateinit var supervisor: GatewaySupervisor

    override fun onCreate() {
        super.onCreate()
        NotifChannels.ensureChannels(this)
        supervisor = GatewaySupervisor(this, HermesInstaller(this))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Log.i(TAG, "stop action received; tearing down")
                supervisor.stop()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                // START or null (re-arming from START_STICKY)
                startInForeground()
                if (intent?.getBooleanExtra(EXTRA_FROM_BOOT, false) == true) {
                    Log.i(TAG, "started from boot")
                }
                supervisor.spawn()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        supervisor.dispose()
        super.onDestroy()
    }

    /**
     * Promote the service to foreground status with the persistent
     * gateway notification. Must be called within 5s of
     * `startForegroundService` (Android's ANR window) — failures
     * here will throw `ForegroundServiceDidNotStartInTimeException`
     * which we swallow and log.
     */
    private fun startInForeground() {
        val notif = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // Android 14+ — explicit FGS type
                startForeground(
                    NOTIF_ID,
                    notif,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(NOTIF_ID, notif)
            }
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed: ${e.message}", e)
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = packageManager.getLaunchIntentForPackage(packageName)
        val openPi = openIntent?.let {
            PendingIntent.getActivity(
                this, 0, it,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
        val stopPi = PendingIntent.getService(
            this, 1,
            Intent(this, GatewayForegroundService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val state = supervisor.currentState()
        val text = if (state.running) {
            "Gateway running on localhost:${state.port}" +
                if (state.pid != null) " · pid ${state.pid}" else ""
        } else if (state.lastError != null) {
            "Gateway stopped: ${state.lastError}"
        } else {
            "Gateway starting…"
        }

        return NotificationCompat.Builder(this, NotifChannels.GATEWAY_CHANNEL)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openPi)
            .addAction(0, getString(R.string.gateway_notif_action_open), openPi)
            .addAction(0, getString(R.string.gateway_notif_action_stop), stopPi)
            .build()
    }
}
