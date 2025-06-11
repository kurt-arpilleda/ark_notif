package com.example.ark_notif

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import java.util.Calendar

object ScheduleManager {
    private const val TAG = "ScheduleManager"
    private const val ACTION_RESTART_SCHEDULED = "com.example.ark_notif.ACTION_RESTART_SCHEDULED"
    private const val BASE_REQUEST_CODE = 1000 // Base for hourly request codes

    fun scheduleHourlyRestarts(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        Log.d(TAG, "⏳ Scheduling hourly restarts...")

        // Cancel any existing alarms first
        cancelScheduledRestarts(context)

        // Schedule for each hour of the day (0-23)
        for (hour in 0..23) {
            val intent = Intent(context, RestartReceiver::class.java).apply {
                action = ACTION_RESTART_SCHEDULED
                putExtra("scheduled_hour", hour)
            }

            val requestCode = BASE_REQUEST_CODE + hour
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val calendar = Calendar.getInstance().apply {
                timeInMillis = System.currentTimeMillis()
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)

                // If it's already past this hour today, schedule for tomorrow
                if (timeInMillis <= System.currentTimeMillis()) {
                    add(Calendar.DAY_OF_YEAR, 1)
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    calendar.timeInMillis,
                    pendingIntent
                )
            } else {
                alarmManager.setExact(
                    AlarmManager.RTC_WAKEUP,
                    calendar.timeInMillis,
                    pendingIntent
                )
            }

            Log.d(TAG, "⏰ Scheduled restart for ${String.format("%02d:00", hour)}")
        }

        Log.d(TAG, "✅ Successfully scheduled hourly restarts")
    }

    fun cancelScheduledRestarts(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        Log.d(TAG, "🛑 Cancelling all scheduled restarts")

        // Cancel all 24 possible hourly alarms
        for (hour in 0..23) {
            val intent = Intent(context, RestartReceiver::class.java).apply {
                action = ACTION_RESTART_SCHEDULED
            }

            val requestCode = BASE_REQUEST_CODE + hour
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
        }

        Log.d(TAG, "✅ All scheduled restarts cancelled")
    }
}