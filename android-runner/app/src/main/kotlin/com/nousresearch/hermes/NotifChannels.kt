package com.nousresearch.hermes

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

/**
 * Notification channel registry. The hermes-mobile app uses three
 * channels, all created on first use (idempotent — registering an
 * existing channel is a no-op on Android 8+).
 *
 * Channel IDs are stable strings; never rename them, since users may
 * have set per-channel importance preferences that we'd silently
 * override on a rename.
 */
object NotifChannels {

    /** Persistent gateway notification (low importance, no sound). */
    const val GATEWAY_CHANNEL = "hermes_gateway"

    /** Higher-importance channel for install errors and crash alerts. */
    const val ALERTS_CHANNEL = "hermes_alerts"

    /** Channel for foreground-service start/stop announcements. */
    const val STATUS_CHANNEL = "hermes_status"

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        nm.createNotificationChannel(
            NotificationChannel(
                GATEWAY_CHANNEL,
                "Gateway status",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Persistent notification while the hermes-agent gateway is running"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
            },
        )

        nm.createNotificationChannel(
            NotificationChannel(
                ALERTS_CHANNEL,
                "Alerts",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Install errors, gateway crashes, and battery-optimization warnings"
                enableVibration(true)
            },
        )

        nm.createNotificationChannel(
            NotificationChannel(
                STATUS_CHANNEL,
                "Service status",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Foreground service start/stop announcements"
                setShowBadge(false)
            },
        )
    }
}
