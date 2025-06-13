package com.example.ark_notif

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.util.Log
import androidx.annotation.RequiresApi
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
import java.util.concurrent.TimeUnit

class RingMonitoringService : Service(), SharedPreferences.OnSharedPreferenceChangeListener {
    private val serviceScope = CoroutineScope(Dispatchers.IO)
    private var monitoringJob: Job? = null
    private var periodicRestartJob: Job? = null
    private var heartbeatJob: Job? = null // New heartbeat job
    private var keepAliveJob: Job? = null // New keep-alive job
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
    private lateinit var alarmManager: AlarmManager
    private var alarmPendingIntent: PendingIntent? = null
    private var heartbeatPendingIntent: PendingIntent? = null // New heartbeat alarm
    private var keepAlivePendingIntent: PendingIntent? = null // New keep-alive alarm

    companion object {
        private const val CHANNEL_ID = "RingMonitoringChannel"
        private const val NOTIFICATION_ID = 1234
        private const val MONITORING_INTERVAL = 7_000L // 7 seconds
        private const val RESTART_INTERVAL = 180_000L // 3 minutes
        private const val ALARM_INTERVAL = 300_000L // 5 minutes
        private const val HEARTBEAT_INTERVAL = 60_000L // 1 minute
        private const val KEEP_ALIVE_INTERVAL = 30_000L // 30 seconds
        private const val ALARM_REQUEST_CODE = 9876
        private const val HEARTBEAT_REQUEST_CODE = 9877
        private const val KEEP_ALIVE_REQUEST_CODE = 9878
        const val ACTION_START_MONITORING = "START_MONITORING"
        const val ACTION_STOP_MONITORING = "STOP_MONITORING"
        const val ACTION_TOGGLE_MONITORING = "TOGGLE_MONITORING"
        const val ACTION_RESTART_SERVICE = "RESTART_SERVICE"
        const val ACTION_ALARM_TRIGGER = "ALARM_TRIGGER"
        const val ACTION_HEARTBEAT = "HEARTBEAT"
        const val ACTION_KEEP_ALIVE = "KEEP_ALIVE"

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

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    @SuppressLint("ForegroundServiceType")
    override fun onCreate() {
        super.onCreate()
        deviceId = retrieveDeviceId()
        Log.d("RingMonitoringService", "Service created with device ID: $deviceId")

        RingMonitoringManager.getInstance(this)

        sharedPreferences = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        sharedPreferences.registerOnSharedPreferenceChangeListener(this)

        createNotificationChannel()
        vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
        alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager

        // Enhanced wake lock for Android 14
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "RingMonitoringService::WakeLock"
        ).apply {
            // Longer wake lock duration for Android 14
            acquire(60 * 60 * 1000L /*1 hour*/)
        }

        startForeground(NOTIFICATION_ID, createNotification())
        createSilentAudioFile()
        startSilentAudio()

        // Start all monitoring systems
        startPeriodicRestart()
        startHeartbeat() // New heartbeat system
        startKeepAlive() // New keep-alive system

        // Register alarm receiver
        registerAlarmReceiver()
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun registerAlarmReceiver() {
        val filter = IntentFilter().apply {
            addAction(ACTION_ALARM_TRIGGER)
            addAction(ACTION_HEARTBEAT)
            addAction(ACTION_KEEP_ALIVE)
        }
        registerReceiver(alarmReceiver, filter, RECEIVER_EXPORTED)
    }

    private val alarmReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_ALARM_TRIGGER -> {
                    Log.d("RingMonitoringService", "Alarm triggered, keeping service alive")
                    if (!isMonitoring) {
                        startSilentAudio()
                        startMonitoring()
                    }
                    scheduleNextAlarm()
                }
                ACTION_HEARTBEAT -> {
                    Log.d("RingMonitoringService", "Heartbeat triggered")
                    handleHeartbeat()
                    scheduleNextHeartbeat()
                }
                ACTION_KEEP_ALIVE -> {
                    Log.d("RingMonitoringService", "Keep-alive triggered")
                    handleKeepAlive()
                    scheduleNextKeepAlive()
                }
            }
        }
    }

    // New heartbeat system to ensure monitoring continues
    private fun startHeartbeat() {
        heartbeatJob = serviceScope.launch {
            while (isActive && isServiceActive) {
                try {
                    delay(HEARTBEAT_INTERVAL)
                    if (isMonitoring) {
                        Log.d("RingMonitoringService", "Heartbeat: Monitoring is active")
                        // Refresh wake lock
                        refreshWakeLock()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e("RingMonitoringService", "Heartbeat error", e)
                }
            }
        }
    }

    // New keep-alive system for silent audio
    private fun startKeepAlive() {
        keepAliveJob = serviceScope.launch {
            while (isActive && isServiceActive) {
                try {
                    delay(KEEP_ALIVE_INTERVAL)
                    if (!isRinging && (silentMediaPlayer?.isPlaying != true)) {
                        Log.d("RingMonitoringService", "Keep-alive: Restarting silent audio")
                        startSilentAudio()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e("RingMonitoringService", "Keep-alive error", e)
                }
            }
        }
    }

    private fun handleHeartbeat() {
        if (!isMonitoring) {
            Log.w("RingMonitoringService", "Heartbeat detected monitoring stopped, restarting")
            startMonitoring()
        }

        // Ensure silent audio is playing if not ringing
        if (!isRinging) {
            val shouldRestart = try {
                silentMediaPlayer?.isPlaying != true
            } catch (e: IllegalStateException) {
                true // If we can't check state, assume we need to restart
            }
            if (shouldRestart) {
                startSilentAudio()
            }
        }

        refreshWakeLock()
    }
    private fun handleKeepAlive() {
        // Ensure monitoring job is active
        if (isMonitoring && (monitoringJob?.isActive != true)) {
            Log.w("RingMonitoringService", "Keep-alive detected monitoring job stopped, restarting")
            startMonitoring()
        }

        // Ensure silent audio is playing
        if (!isRinging && (silentMediaPlayer?.isPlaying != true)) {
            startSilentAudio()
        }
    }

    private fun refreshWakeLock() {
        try {
            wakeLock?.let { wl ->
                if (wl.isHeld) {
                    wl.release()
                }
                wl.acquire(60 * 60 * 1000L /*1 hour*/)
            }
        } catch (e: Exception) {
            Log.e("RingMonitoringService", "Error refreshing wake lock", e)
        }
    }

    private fun scheduleNextAlarm() {
        val alarmIntent = Intent(this, RingMonitoringService::class.java).apply {
            action = ACTION_ALARM_TRIGGER
        }

        alarmPendingIntent = PendingIntent.getService(
            this,
            ALARM_REQUEST_CODE,
            alarmIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val triggerTime = System.currentTimeMillis() + ALARM_INTERVAL

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // For Android 12+ use setExactAndAllowWhileIdle
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerTime,
                    alarmPendingIntent!!
                )
            } else {
                alarmManager.setExact(
                    AlarmManager.RTC_WAKEUP,
                    triggerTime,
                    alarmPendingIntent!!
                )
            }
            Log.d("RingMonitoringService", "Next alarm scheduled in ${TimeUnit.MILLISECONDS.toMinutes(ALARM_INTERVAL)} minutes")
        } catch (e: Exception) {
            Log.e("RingMonitoringService", "Failed to schedule alarm", e)
        }
    }

    private fun scheduleNextHeartbeat() {
        val heartbeatIntent = Intent(this, RingMonitoringService::class.java).apply {
            action = ACTION_HEARTBEAT
        }

        heartbeatPendingIntent = PendingIntent.getService(
            this,
            HEARTBEAT_REQUEST_CODE,
            heartbeatIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val triggerTime = System.currentTimeMillis() + HEARTBEAT_INTERVAL


        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // For Android 12+ use setExactAndAllowWhileIdle
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerTime,
                    heartbeatPendingIntent!!
                )
            } else {
                alarmManager.setExact(
                    AlarmManager.RTC_WAKEUP,
                    triggerTime,
                    heartbeatPendingIntent!!
                )
            }
        } catch (e: Exception) {
            Log.e("RingMonitoringService", "Failed to schedule heartbeat", e)
        }
    }

    private fun scheduleNextKeepAlive() {
        val keepAliveIntent = Intent(this, RingMonitoringService::class.java).apply {
            action = ACTION_KEEP_ALIVE
        }

        keepAlivePendingIntent = PendingIntent.getService(
            this,
            KEEP_ALIVE_REQUEST_CODE,
            keepAliveIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val triggerTime = System.currentTimeMillis() + KEEP_ALIVE_INTERVAL

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // For Android 12+ use setExactAndAllowWhileIdle
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerTime,
                    keepAlivePendingIntent!!
                )
            } else {
                alarmManager.setExact(
                    AlarmManager.RTC_WAKEUP,
                    triggerTime,
                    keepAlivePendingIntent!!
                )
            }
        }  catch (e: Exception) {
            Log.e("RingMonitoringService", "Failed to schedule keep-alive", e)
        }
    }

    private fun cancelAlarms() {
        alarmPendingIntent?.let {
            alarmManager.cancel(it)
            alarmPendingIntent = null
        }
        heartbeatPendingIntent?.let {
            alarmManager.cancel(it)
            heartbeatPendingIntent = null
        }
        keepAlivePendingIntent?.let {
            alarmManager.cancel(it)
            keepAlivePendingIntent = null
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_MONITORING -> {
                Log.d("RingMonitoringService", "Received start command")
                if (!isMonitoring) {
                    startMonitoring()
                    scheduleNextAlarm()
                    scheduleNextHeartbeat()
                    scheduleNextKeepAlive()
                }
            }
            ACTION_STOP_MONITORING -> {
                Log.d("RingMonitoringService", "Received stop command")
                stopMonitoring()
                stopPeriodicRestart()
                stopHeartbeat()
                stopKeepAlive()
                cancelAlarms()
                stopSelf()
            }
            ACTION_TOGGLE_MONITORING -> {
                Log.d("RingMonitoringService", "Received toggle command")
                if (isMonitoring) {
                    stopMonitoring()
                    stopPeriodicRestart()
                    stopHeartbeat()
                    stopKeepAlive()
                    cancelAlarms()
                } else {
                    startMonitoring()
                    startPeriodicRestart()
                    startHeartbeat()
                    startKeepAlive()
                    scheduleNextAlarm()
                    scheduleNextHeartbeat()
                    scheduleNextKeepAlive()
                }
                updateNotification()
            }
            ACTION_RESTART_SERVICE -> {
                Log.d("RingMonitoringService", "Received restart command")
                stopMonitoring()
                startMonitoring()
                updateNotification()
            }
            ACTION_ALARM_TRIGGER -> {
                Log.d("RingMonitoringService", "Received alarm trigger")
                if (!isMonitoring) {
                    startSilentAudio()
                    startMonitoring()
                }
                scheduleNextAlarm()
            }
            ACTION_HEARTBEAT -> {
                Log.d("RingMonitoringService", "Received heartbeat")
                handleHeartbeat()
                scheduleNextHeartbeat()
            }
            ACTION_KEEP_ALIVE -> {
                Log.d("RingMonitoringService", "Received keep-alive")
                handleKeepAlive()
                scheduleNextKeepAlive()
            }
            else -> {
                if (!isMonitoring) {
                    startSilentAudio()
                    startMonitoring()
                    scheduleNextAlarm()
                    scheduleNextHeartbeat()
                    scheduleNextKeepAlive()
                }
            }
        }
        return START_STICKY
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    private fun stopKeepAlive() {
        keepAliveJob?.cancel()
        keepAliveJob = null
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if (key == "phorjp") {
            Log.d("RingMonitoringService", "phorjp preference changed, restarting service")
            restartService(this)
        }
    }

    private fun startPeriodicRestart() {
        if (periodicRestartJob?.isActive == true) return

        periodicRestartJob = serviceScope.launch {
            try {
                while (isActive && isServiceActive) {
                    delay(RESTART_INTERVAL)

                    if (isActive && isServiceActive && isMonitoring) {
                        Log.d("RingMonitoringService", "Performing periodic monitoring restart")

                        stopSilentAudio()
                        stopMonitoring()

                        startSilentAudio()
                        startMonitoring()

                        Log.d("RingMonitoringService", "Periodic restart completed successfully.")
                    }
                }
            } catch (e: CancellationException) {
                Log.d("RingMonitoringService", "Periodic restart job cancelled")
                throw e
            } catch (e: Exception) {
                Log.e("RingMonitoringService", "Error in periodic restart", e)
            }
        }
    }

    private fun stopPeriodicRestart() {
        periodicRestartJob?.cancel()
        periodicRestartJob = null
    }

    private fun createSilentAudioFile() {
        try {
            val silentFile = File(filesDir, "silent.wav")
            if (!silentFile.exists()) {
                val silentData = byteArrayOf(
                    0x52, 0x49, 0x46, 0x46, 0x24, 0x00, 0x00, 0x00, 0x57, 0x41, 0x56, 0x45,
                    0x66, 0x6D, 0x74, 0x20, 0x10, 0x00, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00,
                    0x44, 0x11, 0x00, 0x00, 0x44, 0x11, 0x00, 0x00, 0x01, 0x00, 0x08, 0x00,
                    0x64, 0x61, 0x74, 0x61, 0x00, 0x00, 0x00, 0x00
                )
                FileOutputStream(silentFile).use { fos -> fos.write(silentData) }
            }
        } catch (e: Exception) {
            Log.e("RingMonitoringService", "Failed to create silent audio file", e)
        }
    }

    private fun startSilentAudio() {
        // Stop existing player first
        stopSilentAudio()

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
                        setVolume(0.0f, 0.0f)
                        isLooping = true
                        setWakeMode(this@RingMonitoringService, PowerManager.PARTIAL_WAKE_LOCK)
                        prepare()
                        start()
                    }
                    Log.d("RingMonitoringService", "Silent audio started with wake mode")
                }
            } catch (e: Exception) {
                Log.e("RingMonitoringService", "Failed to start silent audio", e)
                // Retry after delay
                delay(5000)
                if (isActive && !isRinging) {
                    startSilentAudio()
                }
            }
        }
    }

    private fun stopSilentAudio() {
        silentPlayerJob?.cancel()
        silentMediaPlayer?.let { player ->
            try {
                try {
                    player.stop()
                } catch (e: IllegalStateException) {
                    Log.w("RingMonitoringService", "MediaPlayer already stopped or not prepared", e)
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
        monitoringJob?.cancel()

        monitoringJob = serviceScope.launch {
            try {
                val prefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
                val phorjp = prefs.getString("phorjp", null)

                while (isActive) {
                    try {
                        val response = withContext(Dispatchers.IO) {
                            if (phorjp == "jp") {
                                RetrofitClientJP.instance.getRingStatus(deviceId).execute()
                            } else {
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
                        if (isActive) {
                            Log.e("RingMonitoringService", "Monitoring error", e)
                        }
                        delay(5000)
                    }
                    delay(MONITORING_INTERVAL)
                }
            } finally {
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

                // Continuous vibration pattern (vibrate for 1s, pause for 1s, repeat)
                val pattern = longArrayOf(0, 1000, 1000)

                // Start vibration in repeating mode
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0)) // 0 means repeat indefinitely
                } else {
                    @Suppress("DEPRECATION")
                    vibrator?.vibrate(pattern, 0) // 0 means repeat indefinitely
                }

                // Create and start ringtone once
                currentRingtone = withContext(Dispatchers.IO) {
                    RingtoneManager.getRingtone(this@RingMonitoringService, alarmUri).apply {
                        setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ALARM)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                .build()
                        )
                    }
                }

                // Keep ringtone playing continuously while ringing
                while (isActive && isRinging) {
                    try {
                        if (currentRingtone?.isPlaying != true) {
                            currentRingtone?.play()
                        }
                        delay(500)
                    } catch (e: Exception) {
                        if (e !is CancellationException) {
                            Log.e("RingMonitoringService", "Error maintaining ringtone", e)
                            delay(1000)
                        }
                    }
                }

            } catch (e: CancellationException) {
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

    @SuppressLint("ServiceCast")
    private fun createNotification(): Notification {
        val phorjp = sharedPreferences.getString("phorjp", null)
        val isJapanese = phorjp == "jp"

        val toggleIntent = Intent(this, RingMonitoringService::class.java).apply {
            action = ACTION_TOGGLE_MONITORING
        }
        val togglePendingIntent = PendingIntent.getService(
            this,
            0,
            toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val otherAppIntent = packageManager.getLaunchIntentForPackage("com.example.ng_notification")?.apply {
            putExtra("phorjp", phorjp)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val fallbackIntent = Intent(this, MainActivity::class.java).apply {
            putExtra("phorjp", phorjp)
        }
        val contentIntent = otherAppIntent ?: fallbackIntent
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            0,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

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
        stopPeriodicRestart()
        stopSilentAudio()
        cancelAlarms()
        try {
            unregisterReceiver(alarmReceiver)
        } catch (e: IllegalArgumentException) {
            Log.w("RingMonitoringService", "Receiver not registered", e)
        }
        sharedPreferences.unregisterOnSharedPreferenceChangeListener(this)
        wakeLock?.let { wl ->
            if (wl.isHeld) {
                wl.release()
            }
        }
        super.onDestroy()
    }
}