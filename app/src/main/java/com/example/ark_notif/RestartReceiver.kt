package com.example.ark_notif

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val currentTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            .format(Date())

        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                Log.d("RestartReceiver", "📱 Device reboot detected at $currentTime")
                Log.d("RestartReceiver", "🔄 Rescheduling hourly restarts...")
                ScheduleManager.scheduleHourlyRestarts(context)
                Log.d("RestartReceiver", "🚀 Starting service immediately after reboot")
                RingMonitoringService.startService(context)
            }
            else -> {
                val scheduledHour = intent.getIntExtra("scheduled_hour", -1)
                val hourLabel = if (scheduledHour != -1) String.format("%02d:00", scheduledHour) else "unknown time"

                Log.d("RestartReceiver", "⏰ Hourly restart triggered at $currentTime (scheduled for $hourLabel)")
                Log.d("RestartReceiver", "🔄 Restarting monitoring service...")
                RingMonitoringService.restartService(context)
                Log.d("RestartReceiver", "✅ Service restart initiated")
            }
        }
    }
}