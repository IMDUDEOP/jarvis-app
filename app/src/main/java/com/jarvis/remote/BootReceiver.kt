package com.jarvis.remote

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/**
 * Without this, "pair once, forever" would break the moment the phone
 * itself reboots — the foreground service (and its socket) doesn't survive
 * a reboot on its own, only across the app being merely backgrounded. This
 * only restarts the connection; it never re-pairs, since the permanent
 * secret in PairingRepository already survived the reboot on disk.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val repo = PairingRepository(context)
        if (!repo.isPaired) return
        val serviceIntent = Intent(context, JarvisConnectionService::class.java)
        ContextCompat.startForegroundService(context, serviceIntent)
    }
}
