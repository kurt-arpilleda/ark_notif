package com.example.ark_notif

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class RingMonitoringService : Service() {
    private val serviceScope = CoroutineScope(Dispatchers.IO)
    private var monitoringJob: Job? = null
    private var ringtone: android.media.Ringtone? = null
    private var vibrator: Vibrator? = null
    private var isRinging = false

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("ForegroundServiceType")
    override fun onCreate() {
        super.onCreate()
        Log.d("RingMonitoringService", "Service created")
        createNotificationChannel()
        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        startForeground(NOTIFICATION_ID, createNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("RingMonitoringService", "Service started")
        startMonitoring()
        return START_STICKY
    }

    private fun startMonitoring() {
        monitoringJob?.cancel()
        monitoringJob = serviceScope.launch {
            while (true) {
                try {
                    val response = RetrofitClient.instance.getRingStatus().execute()
                    if (response.isSuccessful) {
                        response.body()?.isRing?.let { ringStatus ->
                            if (ringStatus == 1 && !isRinging) {
                                Log.d("RingMonitoringService", "Starting ring")
                                startRinging()
                            } else if (ringStatus == 0 && isRinging) {
                                Log.d("RingMonitoringService", "Stopping ring")
                                stopRinging()
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("RingMonitoringService", "Monitoring error", e)
                }
                delay(2000) // Check every 2 seconds
            }
        }
    }

    private fun startRinging() {
        if (!isRinging) {
            isRinging = true
            val alarmUri: Uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)

            // Configure audio attributes
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            // Start a coroutine to continuously play the ringtone
            serviceScope.launch {
                while (isRinging) {
                    val currentRingtone = RingtoneManager.getRingtone(this@RingMonitoringService, alarmUri).apply {
                        setAudioAttributes(audioAttributes)
                        play()
                    }

                    // Wait for the ringtone to finish playing or until we should stop
                    while (currentRingtone.isPlaying && isRinging) {
                        delay(100)
                    }

                    currentRingtone.stop()
                }
            }

            // Start vibration pattern
            val pattern = longArrayOf(0, 1000, 1000) // wait 0, vibrate 1000, sleep 1000
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(pattern, 0)
            }
        }
    }

    private fun stopRinging() {
        if (isRinging) {
            isRinging = false
            ringtone?.stop()
            ringtone = null
            vibrator?.cancel()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Ring Monitoring Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notification channel for ring monitoring service"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Ring Monitoring Active")
            .setContentText("Monitoring for ring requests")
            .setSmallIcon(R.drawable.ic_ring_active)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("RingMonitoringService", "Service destroyed")
        monitoringJob?.cancel()
        stopRinging()
    }

    companion object {
        private const val CHANNEL_ID = "RingMonitoringChannel"
        private const val NOTIFICATION_ID = 1234

        fun startService(context: Context) {
            Log.d("RingMonitoringService", "Attempting to start service")
            val intent = Intent(context, RingMonitoringService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, RingMonitoringService::class.java)
            context.stopService(intent)
        }
    }
}