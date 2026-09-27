package com.jarvis.remote

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.BatteryManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import org.json.JSONObject

/**
 * Executes the small, fixed set of actions remote_app_control_phone (on the
 * JARVIS/server side) can ask this phone to perform. Deliberately NOT a
 * general "run arbitrary code on my phone" surface — each action here is
 * one specific, narrow capability. Add new ones deliberately, one at a
 * time, matching a new case in the "action" enum on the Python tool too.
 */
object PhoneControlExecutor {

    fun execute(context: Context, action: String, params: JSONObject) {
        when (action) {
            "vibrate" -> vibrate(context)
            "flashlight_on" -> setTorch(context, true)
            "flashlight_off" -> setTorch(context, false)
            "battery_status" -> {
                // Nothing to *do* here beyond reporting — the reply already
                // goes back over the same socket via JarvisConnectionService,
                // this just exists as a named branch for clarity/extension.
            }
            "open_url" -> openUrl(context, params.optString("url", ""))
            "ring" -> {
                // Handled by JarvisConnectionService directly (it launches
                // IncomingCallActivity), not here — ringing needs a full-
                // screen Activity + ringtone, not a fire-and-forget action.
            }
            else -> { /* unknown action — ignore rather than crash */ }
        }
    }

    fun batteryPercent(context: Context): Int {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    private fun vibrate(context: Context) {
        val vibrator: Vibrator = if (android.os.Build.VERSION.SDK_INT >= 31) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        vibrator.vibrate(VibrationEffect.createOneShot(400, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private fun setTorch(context: Context, on: Boolean) {
        try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val backCameraId = cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id)
                    .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return
            cameraManager.setTorchMode(backCameraId, on)
        } catch (_: Exception) {
            // No flash unit, or camera busy (e.g. actively scanning a QR
            // code) — fail silently, this is a best-effort convenience.
        }
    }

    private fun openUrl(context: Context, url: String) {
        if (url.isBlank()) return
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (_: Exception) {
        }
    }
}
