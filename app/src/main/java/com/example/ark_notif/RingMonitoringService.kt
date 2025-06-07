package com.example.ark_notif

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException
import java.io.File
import java.io.FileOutputStream

class RingMonitoringService : Service() {
    private val serviceScope = CoroutineScope(Dispatchers.IO)
    private var monitoringJob: Job? = null
    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null
    private var silentMediaPlayer: MediaPlayer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var isRinging = false
    private var isMonitoring = false
    private var isServiceActive = true
    private var ringtoneJob: Job? = null
    private var silentPlayerJob: Job? = null

    companion object {
        private const val CHANNEL_ID = "RingMonitoringChannel"
        private const val NOTIFICATION_ID = 1234
        private const val MONITORING_INTERVAL = 2000L // 2 seconds
        const val ACTION_START_MONITORING = "START_MONITORING"
        const val ACTION_STOP_MONITORING = "STOP_MONITORING"
        const val ACTION_TOGGLE_MONITORING = "TOGGLE_MONITORING"

        fun startService(context: Context) {
            val intent = Intent(context, RingMonitoringService::class.java).apply {
                action = ACTION_START_MONITORING
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
                action = ACTION_STOP_MONITORING
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

        // Acquire wake lock to keep service running
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "RingMonitoringService::WakeLock"
        )
        wakeLock?.acquire(10*60*1000L /*10 minutes*/)

        startForeground(NOTIFICATION_ID, createNotification())
        createSilentAudioFile()
        startSilentAudio()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_MONITORING -> {
                Log.d("RingMonitoringService", "Received start command")
                if (!isMonitoring) {
                    startMonitoring()
                } else {
                    Log.d("RingMonitoringService", "Service already monitoring, ignoring duplicate start")
                }
            }
            ACTION_STOP_MONITORING -> {
                Log.d("RingMonitoringService", "Received stop command")
                stopMonitoring()
                stopSelf()
            }
            ACTION_TOGGLE_MONITORING -> {
                Log.d("RingMonitoringService", "Received toggle command")
                if (isMonitoring) {
                    stopMonitoring()
                } else {
                    startMonitoring()
                }
                updateNotification()
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

    private fun createSilentAudioFile() {
        try {
            // Create a very short silent audio file
            val silentFile = File(filesDir, "silent.wav")
            if (!silentFile.exists()) {
                val silentData = byteArrayOf(
                    0x52, 0x49, 0x46, 0x46, // "RIFF"
                    0x24, 0x00, 0x00, 0x00, // File size - 8
                    0x57, 0x41, 0x56, 0x45, // "WAVE"
                    0x66, 0x6D, 0x74, 0x20, // "fmt "
                    0x10, 0x00, 0x00, 0x00, // Subchunk1Size
                    0x01, 0x00, 0x01, 0x00, // AudioFormat, NumChannels
                    0x44, 0x11, 0x00, 0x00, // SampleRate
                    0x44, 0x11, 0x00, 0x00, // ByteRate
                    0x01, 0x00, 0x08, 0x00, // BlockAlign, BitsPerSample
                    0x64, 0x61, 0x74, 0x61, // "data"
                    0x00, 0x00, 0x00, 0x00  // Subchunk2Size (0 = silent)
                )

                FileOutputStream(silentFile).use { fos ->
                    fos.write(silentData)
                }
            }
        } catch (e: Exception) {
            Log.e("RingMonitoringService", "Failed to create silent audio file", e)
        }
    }

    private fun startSilentAudio() {
        silentPlayerJob = serviceScope.launch {
            try {
                val silentFile = File(filesDir, "silent.wav")
                if (silentFile.exists()) {
                    silentMediaPlayer = MediaPlayer().apply {
                        setDataSource(silentFile.absolutePath)
                        setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                .build()
                        )
                        setVolume(0.0f, 0.0f) // Silent
                        isLooping = true
                        prepare()
                        start()
                    }
                    Log.d("RingMonitoringService", "Silent audio started to maintain service priority")
                }
            } catch (e: Exception) {
                Log.e("RingMonitoringService", "Failed to start silent audio", e)
            }
        }
    }

    private fun stopSilentAudio() {
        silentPlayerJob?.cancel()
        silentMediaPlayer?.let { player ->
            try {
                if (player.isPlaying) {
                    player.stop()
                }
                player.release()
            } catch (e: Exception) {
                Log.e("RingMonitoringService", "Error stopping silent audio", e)
            }
        }
        silentMediaPlayer = null
    }

    private fun startMonitoring() {
        if (isMonitoring) return

        isMonitoring = true
        monitoringJob?.cancel() // Cancel any existing job

        monitoringJob = serviceScope.launch {
            try {
                while (isActive) {  // Use isActive instead of manual flags
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
                        } else {
                            Log.w("RingMonitoringService", "API response not successful: ${response.code()}")
                        }
                    } catch (e: Exception) {
                        if (isActive) {  // Only log if we're still active
                            Log.e("RingMonitoringService", "Monitoring error", e)
                        }
                        // Add delay before retry to prevent tight loop on error
                        delay(5000)
                    }
                    delay(MONITORING_INTERVAL)
                }
            } finally {
                // Clean up when coroutine ends
                withContext(NonCancellable) {
                    stopRinging()
                    isMonitoring = false
                }
            }
        }

        updateNotification()
    }

    private fun stopMonitoring() {
        isMonitoring = false
        monitoringJob?.cancel()
        monitoringJob = null
        stopRinging()
        updateNotification()
    }

    private var currentRingtone: Ringtone? = null  // Make this a class-level variable

    private fun startRinging() {
        if (isRinging) return

        isRinging = true
        val alarmUri: Uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)

        // Cancel any existing ringtone job
        ringtoneJob?.cancel()

        ringtoneJob = serviceScope.launch {
            try {
                // Stop silent audio while ringing to avoid conflicts
                stopSilentAudio()

                // Create and configure the ringtone once
                currentRingtone = withContext(Dispatchers.IO) {
                    RingtoneManager.getRingtone(
                        this@RingMonitoringService,
                        alarmUri
                    ).apply {
                        setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ALARM)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                .build()
                        )
                    }
                }

                // Start vibration pattern (will loop automatically)
                val pattern = longArrayOf(0, 1000, 1000)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator?.vibrate(pattern, 0)
                }

                // Start playing (will loop automatically)
                currentRingtone?.play()

                // Keep alive while we should be ringing
                while (isActive && isRinging) {
                    delay(1000) // Just keep checking the flag
                }
            } catch (e: CancellationException) {
                // Normal cancellation
            } catch (e: Exception) {
                Log.e("RingMonitoringService", "Ringtone error", e)
            } finally {
                withContext(NonCancellable) {
                    currentRingtone?.stop()
                    vibrator?.cancel()
                    currentRingtone = null
                    // Restart silent audio after ringing stops
                    startSilentAudio()
                }
            }
        }

        updateNotification()
    }

    private fun stopRinging() {
        if (!isRinging) return

        isRinging = false
        ringtoneJob?.cancel()
        updateNotification()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Ring Monitoring Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notification channel for ring monitoring service"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(): Notification {
        // PendingIntent for toggle action
        val toggleIntent = Intent(this, RingMonitoringService::class.java).apply {
            action = ACTION_TOGGLE_MONITORING
        }
        val togglePendingIntent = PendingIntent.getService(
            this,
            0,
            toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // PendingIntent for main activity
        val contentIntent = Intent(this, MainActivity::class.java)
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            0,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Build the notification with status indicators
        val statusText = when {
            isRinging -> "🔊 RINGING - Tap to view"
            isMonitoring -> "📡 Active - Monitoring for rings"
            else -> "⏸️ Inactive - Tap to start"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Ring Monitoring Service")
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_ring_active)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentPendingIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(
                if (isMonitoring) R.drawable.stop_icon else R.drawable.start_icon,
                if (isMonitoring) "Stop Monitoring" else "Start Monitoring",
                togglePendingIntent
            )
            .setStyle(androidx.media.app.NotificationCompat.MediaStyle()
                .setShowActionsInCompactView(0)
            )
            .build()
    }

    private fun updateNotification() {
        val notification = createNotification()
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        Log.d("RingMonitoringService", "Service destroyed")
        isServiceActive = false
        stopMonitoring()
        stopSilentAudio()

        // Release wake lock
        wakeLock?.let { wl ->
            if (wl.isHeld) {
                wl.release()
            }
        }

        super.onDestroy()
    }
}