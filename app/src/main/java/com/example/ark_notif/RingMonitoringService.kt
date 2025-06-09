package com.example.ark_notif

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
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
import android.provider.Settings
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

class RingMonitoringService : Service(), SharedPreferences.OnSharedPreferenceChangeListener {
    private val serviceScope = CoroutineScope(Dispatchers.IO)
    private var monitoringJob: Job? = null
    private var vibrator: Vibrator? = null
    private var silentMediaPlayer: MediaPlayer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var isRinging = false
    private var isMonitoring = false
    private var isServiceActive = true
    private var ringtoneJob: Job? = null
    private var silentPlayerJob: Job? = null
    private var currentRingtone: Ringtone? = null
    private var deviceId: String = "unknown-device"
    private lateinit var sharedPreferences: SharedPreferences

    companion object {
        private const val CHANNEL_ID = "RingMonitoringChannel"
        private const val NOTIFICATION_ID = 1234
        private const val MONITORING_INTERVAL = 5000L
        const val ACTION_START_MONITORING = "START_MONITORING"
        const val ACTION_STOP_MONITORING = "STOP_MONITORING"
        const val ACTION_TOGGLE_MONITORING = "TOGGLE_MONITORING"
        const val ACTION_RESTART_SERVICE = "RESTART_SERVICE"

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

        fun restartService(context: Context) {
            val intent = Intent(context, RingMonitoringService::class.java).apply {
                action = ACTION_RESTART_SERVICE
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    @SuppressLint("HardwareIds")
    private fun retrieveDeviceId(): String {
        return try {
            Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown-device"
        } catch (e: Exception) {
            Log.e("RingMonitoringService", "Error getting device identifier: ${e.message}", e)
            "unknown-device"
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("ForegroundServiceType")
    override fun onCreate() {
        super.onCreate()
        Log.d("RingMonitoringService", "Service created")
        deviceId = retrieveDeviceId()
        Log.d("RingMonitoringService", "Device ID: $deviceId")

        // Initialize shared preferences and register listener
        sharedPreferences = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        sharedPreferences.registerOnSharedPreferenceChangeListener(this)

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
            ACTION_RESTART_SERVICE -> {
                Log.d("RingMonitoringService", "Received restart command")
                stopMonitoring()
                startMonitoring()
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

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if (key == "phorjp") {
            Log.d("RingMonitoringService", "phorjp preference changed, restarting service")
            restartService(this)
        }
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
                // Get the preference value
                val prefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
                val phorjp = prefs.getString("phorjp", null)

                while (isActive) {  // Use isActive instead of manual flags
                    try {
                        val response = withContext(Dispatchers.IO) {
                            if (phorjp == "jp") {
                                RetrofitClientJP.instance.getRingStatus(deviceId).execute()
                            } else {
                                // Default to PH client
                                RetrofitClient.instance.getRingStatus(deviceId).execute()
                            }
                        }

                        if (response.isSuccessful) {
                            response.body()?.shouldRing?.let { shouldRing ->
                                if (shouldRing && !isRinging) {
                                    Log.d("RingMonitoringService", "Starting ring")
                                    startRinging()
                                } else if (!shouldRing && isRinging) {
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

    private fun startRinging() {
        if (isRinging) return

        isRinging = true
        val alarmUri: Uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)

        ringtoneJob?.cancel()

        ringtoneJob = serviceScope.launch {
            try {
                stopSilentAudio()
                val pattern = longArrayOf(0, 1000, 1000)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator?.vibrate(pattern, 0)
                }

                while (isActive && isRinging) {
                    try {
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

                        currentRingtone?.play()

                        try {
                            while (isActive && isRinging && currentRingtone?.isPlaying == true) {
                                delay(500)
                            }
                        } catch (e: CancellationException) {
                            currentRingtone?.stop()
                            throw e
                        }

                        currentRingtone?.stop()
                        currentRingtone = null

                        if (isActive && isRinging) {
                            delay(200)
                        }

                    } catch (e: Exception) {
                        if (e !is CancellationException) {
                            Log.e("RingMonitoringService", "Error in ringtone loop", e)
                            delay(1000)
                        }
                    }
                }
            } catch (e: CancellationException) {
                // Clean up on cancellation
                currentRingtone?.stop()
                vibrator?.cancel()
                currentRingtone = null
                startSilentAudio()
                throw e
            } catch (e: Exception) {
                Log.e("RingMonitoringService", "Ringtone error", e)
            } finally {
                withContext(NonCancellable) {
                    currentRingtone?.stop()
                    vibrator?.cancel()
                    currentRingtone = null
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
        // Get current preference
        val phorjp = sharedPreferences.getString("phorjp", null)
        val isJapanese = phorjp == "jp"

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

        val otherAppIntent = packageManager.getLaunchIntentForPackage("com.example.ng_notification")
        val fallbackIntent = Intent(this, MainActivity::class.java)
        val contentIntent = otherAppIntent ?: fallbackIntent
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            0,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Determine notification content based on language preference
        val (title, statusText, toggleText) = if (isJapanese) {
            Triple(
                "NG着信監視サービス",
                when {
                    isRinging -> "🔊 鳴っています - タップして表示"
                    isMonitoring -> "📡 アクティブ - 監視中"
                    else -> "⏸️ 非アクティブ - タップして開始"
                },
                if (isMonitoring) "監視を停止" else "監視を開始"
            )
        } else {
            Triple(
                "NG Ring Monitoring Service",
                when {
                    isRinging -> "🔊 RINGING - Tap to view"
                    isMonitoring -> "📡 Active - Monitoring for NG"
                    else -> "⏸️ Inactive - Tap to start"
                },
                if (isMonitoring) "Stop Monitoring" else "Start Monitoring"
            )
        }

        // Set appropriate icon based on country
        val flagIcon = if (isJapanese) R.drawable.japan else R.drawable.philippinesflag

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_ring_active)
            .setLargeIcon(android.graphics.BitmapFactory.decodeResource(resources, flagIcon))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentPendingIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(
                if (isMonitoring) R.drawable.stop_icon else R.drawable.start_icon,
                toggleText,
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

        // Unregister preference listener
        sharedPreferences.unregisterOnSharedPreferenceChangeListener(this)

        // Release wake lock
        wakeLock?.let { wl ->
            if (wl.isHeld) {
                wl.release()
            }
        }

        super.onDestroy()
    }
}