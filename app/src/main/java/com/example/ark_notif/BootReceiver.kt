package com.example.ark_notif

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.annotation.RequiresApi

class BootReceiver : BroadcastReceiver() {

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onReceive(context: Context, intent: Intent) {
        if (Intent.ACTION_BOOT_COMPLETED == intent.action) {
            Log.d("BootReceiver", "BOOT_COMPLETED received")

            // Start the service directly if we have overlay permission (or don't need it)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)) {
                Log.d("BootReceiver", "Starting RingMonitoringService directly")
                RingMonitoringService.startService(context)
            } else {
                Log.d("BootReceiver", "Overlay permission needed, requesting...")
                requestOverlayPermission(context)
            }
        }
    }

    private fun requestOverlayPermission(context: Context) {
        try {
            val overlayIntent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            ).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
            context.startActivity(overlayIntent)
            Log.d("BootReceiver", "Overlay permission intent started")
        } catch (e: Exception) {
            Log.e("BootReceiver", "Failed to start overlay permission activity", e)
            // Try to start service anyway as a fallback
            RingMonitoringService.startService(context)
        }
    }
}