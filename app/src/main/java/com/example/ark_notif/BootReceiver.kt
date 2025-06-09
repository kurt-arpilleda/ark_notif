package com.example.ark_notif

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON" -> {
                Log.d("BootReceiver", "Boot completed, starting service")
                RingMonitoringService.startService(context)
                val manager = RingMonitoringManager.getInstance(context)
                manager.startMonitoring()
            }
        }
    }
}