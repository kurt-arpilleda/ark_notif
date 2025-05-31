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
import kotlinx.coroutines.withContext

class RingMonitoringService : Service() {
    private val serviceScope = CoroutineScope(Dispatchers.IO)
    private var monitoringJob: Job? = null
    private var ringtone: android.media.Ringtone? = null
    private var vibrator: Vibrator? = null
    private var isRinging = false
    private var isMonitoring = false

    companion object {
        private const val CHANNEL_ID = "RingMonitoringChannel"
        private const val NOTIFICATION_ID = 1234
        private const val MONITORING_INTERVAL = 2000L // 2 seconds

        fun startService(context: Context) {
            val intent = Intent(context, RingMonitoringService::class.java).apply {
                action = "START_MONITORING"
                putExtra("timestamp", System.currentTimeMillis())
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, RingMonitoringService::class.java).apply {
                action = "STOP_MONITORING"
            }
            context.stopService(intent)
        }
    }

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
        when (intent?.action) {
            "START_MONITORING" -> {
                Log.d("RingMonitoringService", "Received start command")
                if (!isMonitoring) {
                    startMonitoring()
                } else {
                    Log.d("RingMonitoringService", "Service already monitoring, ignoring duplicate start")
                }
            }
            "STOP_MONITORING" -> {
                Log.d("RingMonitoringService", "Received stop command")
                stopMonitoring()
                stopSelf()
            }
            else -> {
                // Default behavior if no action specified
                if (!isMonitoring) {
                    startMonitoring()
                }
            }
        }
        return START_STICKY
    }

    private fun startMonitoring() {
        if (isMonitoring) return

        isMonitoring = true
        monitoringJob?.cancel() // Cancel any existing job

        monitoringJob = serviceScope.launch {
            while (isMonitoring) {
                try {
                    val response = withContext(Dispatchers.IO) {
                        RetrofitClient.instance.getRingStatus().execute()
                    }

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
                    // Add delay before retry to prevent tight loop on error
                    delay(5000)
                }
                delay(MONITORING_INTERVAL)
            }
        }
    }

    private fun stopMonitoring() {
        isMonitoring = false
        monitoringJob?.cancel()
        monitoringJob = null
        stopRinging()
    }

    private fun startRinging() {
        if (isRinging) return

        isRinging = true
        val alarmUri: Uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        serviceScope.launch {
            while (isRinging) {
                val currentRingtone = RingtoneManager.getRingtone(
                    this@RingMonitoringService,
                    alarmUri
                ).apply {
                    setAudioAttributes(audioAttributes)
                    play()
                }

                while (currentRingtone.isPlaying && isRinging) {
                    delay(100)
                }

                // Proper cleanup of the Ringtone
                currentRingtone.stop()
                // No release() method exists - just let it be garbage collected
            }
        }

        // Start vibration pattern
        val pattern = longArrayOf(0, 1000, 1000)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(pattern, 0)
        }
    }

    private fun stopRinging() {
        if (!isRinging) return

        isRinging = false
        ringtone?.stop()
        ringtone = null
        vibrator?.cancel()
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
        Log.d("RingMonitoringService", "Service destroyed")
        stopMonitoring()
        super.onDestroy()
    }
}