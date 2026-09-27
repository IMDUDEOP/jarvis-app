package com.jarvis.remote

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

/**
 * Three channels, deliberately different importances:
 *  - SERVICE: the required "foreground service is running" notification.
 *    Low importance, silent — it exists because Android requires a
 *    foreground service to show one, not because the user needs to see it.
 *  - MESSAGE: a JARVIS reply or a pushed notification arriving while the
 *    app isn't in the foreground. Normal importance.
 *  - CALL: the "ring" control command. High importance + full-screen
 *    intent, so it behaves like an actual incoming call even on a locked
 *    screen.
 */
object NotificationHelper {
    const val CHANNEL_SERVICE = "jarvis_service"
    const val CHANNEL_MESSAGE = "jarvis_message"
    const val CHANNEL_CALL = "jarvis_call"

    const val NOTIF_ID_SERVICE = 1001
    const val NOTIF_ID_MESSAGE = 1002
    const val NOTIF_ID_CALL = 1003

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java)

        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_SERVICE, "JARVIS connection", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Shows while JARVIS Remote is connected in the background."
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_MESSAGE, "JARVIS messages", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Replies and notifications from JARVIS."
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_CALL, "JARVIS calls", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Incoming call alerts from JARVIS."
                enableVibration(true)
            }
        )
    }

    fun serviceNotification(context: Context, statusText: String): android.app.Notification {
        val openApp = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, CHANNEL_SERVICE)
            .setContentTitle("JARVIS Remote")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .setContentIntent(openApp)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    fun showMessage(context: Context, title: String, body: String) {
        val openApp = PendingIntent.getActivity(
            context, 1, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notif = NotificationCompat.Builder(context, CHANNEL_MESSAGE)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(true)
            .setContentIntent(openApp)
            .build()
        context.getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID_MESSAGE, notif)
    }
}
