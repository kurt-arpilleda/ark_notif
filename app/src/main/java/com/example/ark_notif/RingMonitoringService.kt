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
    private val serviceScope = CoroutineScope(Dispatchers.Default) // Changed to Default dispatcher
    private var monitoringJob: Job? = null
    private var periodicRestartJob: Job? = null
    private var vibrator: Vibrator? = null
    private var silentMediaPlayer: MediaPlayer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var isRinging = false
    private var isMonitoring = false
    private var currentRingtone: Ringtone? = null
    private var deviceId: String = "unknown-device"
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var alarmManager: AlarmManager
    private var alarmPendingIntent: PendingIntent? = null
    private var workManagerInitialized = false

    companion object {
        private const val CHANNEL_ID = "RingMonitoringChannel"
        private const val NOTIFICATION_ID = 1234
        private const val MONITORING_INTERVAL = 10_000L // Increased from 5s to 15s
        private const val RESTART_INTERVAL = 600_000L // 10m
        private const val ALARM_INTERVAL = 900_000L // 15m
        private const val ALARM_REQUEST_CODE = 9876
        const val ACTION_START_MONITORING = "START_MONITORING"
        const val ACTION_STOP_MONITORING = "STOP_MONITORING"
        const val ACTION_TOGGLE_MONITORING = "TOGGLE_MONITORING"
        const val ACTION_RESTART_SERVICE = "RESTART_SERVICE"
        const val ACTION_ALARM_TRIGGER = "ALARM_TRIGGER"

        fun startService(context: Context) {
            val intent = Intent(context, RingMonitoringService::class.java).apply {
                action = ACTION_START_MONITORING
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
            Log.e("RingMonitoringService", "Error getting device identifier", e)
            "unknown-device"
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @RequiresApi(Build.VERSION_CODES.O)
    @SuppressLint("ForegroundServiceType", "WakelockTimeout")
    override fun onCreate() {
        super.onCreate()
        deviceId = retrieveDeviceId()
        Log.d("RingMonitoringService", "Service created with device ID: $deviceId")

        sharedPreferences = getSharedPreferences("AppPrefs", MODE_PRIVATE)
        sharedPreferences.registerOnSharedPreferenceChangeListener(this)

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())

        vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
        alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager

        // Use a more efficient wake lock strategy
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "RingMonitoringService::WakeLock"
        ).apply {
            setReferenceCounted(false)
        }

        createSilentAudioFile()
        registerAlarmReceiver()
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerAlarmReceiver() {
        val filter = IntentFilter().apply {
            addAction(ACTION_ALARM_TRIGGER)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(alarmReceiver, filter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(alarmReceiver, filter)
        }
    }

    private val alarmReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_ALARM_TRIGGER -> {
                    Log.d("RingMonitoringService", "Alarm triggered, keeping service alive")
                    if (!isMonitoring) {
                        startMonitoring()
                    }
                    scheduleNextAlarm()
                }
            }
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerTime,
                    alarmPendingIntent!!
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
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
        } catch (e: Exception) {
            Log.e("RingMonitoringService", "Failed to schedule alarm", e)
        }
    }

    private fun cancelAlarms() {
        alarmPendingIntent?.let {
            alarmManager.cancel(it)
            alarmPendingIntent = null
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.action?.let { action ->
            when (action) {
                ACTION_START_MONITORING -> {
                    Log.d("RingMonitoringService", "Received start command")
                    if (!isMonitoring) {
                        startMonitoring()
                        scheduleNextAlarm()
                    }
                }
                ACTION_STOP_MONITORING -> {
                    Log.d("RingMonitoringService", "Received stop command")
                    stopMonitoring()
                    stopPeriodicRestart()
                    cancelAlarms()
                    stopSelf()
                }
                ACTION_TOGGLE_MONITORING -> {
                    Log.d("RingMonitoringService", "Received toggle command")
                    if (isMonitoring) {
                        stopMonitoring()
                        stopPeriodicRestart()
                        cancelAlarms()
                    } else {
                        startMonitoring()
                        scheduleNextAlarm()
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
                        startMonitoring()
                    }
                    scheduleNextAlarm()
                }
            }
        } ?: run {
            if (!isMonitoring) {
                startMonitoring()
                scheduleNextAlarm()
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

    private fun startPeriodicRestart() {
        periodicRestartJob?.cancel()
        periodicRestartJob = serviceScope.launch {
            try {
                while (isActive) {
                    delay(RESTART_INTERVAL)
                    if (isActive && isMonitoring) {
                        Log.d("RingMonitoringService", "Performing periodic monitoring restart")
                        stopMonitoring()
                        startMonitoring()
                    }
                }
            } catch (e: CancellationException) {
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
        stopSilentAudio()

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
            }
        } catch (e: Exception) {
            Log.e("RingMonitoringService", "Failed to start silent audio", e)
        }
    }

    private fun stopSilentAudio() {
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
        monitoringJob?.cancel()

        // Only acquire wake lock when actually needed
        wakeLock?.acquire(60_000L) // 1 minute

        monitoringJob = serviceScope.launch {
            try {
                val prefs = getSharedPreferences("AppPrefs", MODE_PRIVATE)
                val phorjp = prefs.getString("phorjp", null)

                while (isActive) {
                    try {
                        // Only acquire wake lock during network operations
                        wakeLock?.acquire(30_000L) // 30 seconds for network ops

                        val ringResponse = withContext(Dispatchers.IO) {
                            if (phorjp == "jp") {
                                RetrofitClientJP.instance.getRingStatus(deviceId).execute()
                            } else {
                                RetrofitClient.instance.getRingStatus(deviceId).execute()
                            }
                        }

                        val pagingResponse = withContext(Dispatchers.IO) {
                            if (phorjp == "jp") {
                                RetrofitClientJP.instance.getPagingStatus(deviceId).execute()
                            } else {
                                RetrofitClient.instance.getPagingStatus(deviceId).execute()
                            }
                        }

                        // Release wake lock after network operations
                        wakeLock?.let { if (it.isHeld) it.release() }

                        val ringStatus = ringResponse.body()
                        val pagingStatus = pagingResponse.body()

                        val shouldRing = (ringStatus?.shouldRing == true) || (pagingStatus?.shouldRing == true)
                        val notificationType = when {
                            pagingStatus?.shouldRing == true -> pagingStatus.type
                            ringStatus?.shouldRing == true -> ringStatus.type
                            else -> null
                        }

                        if (notificationType != null) {
                            sharedPreferences.edit().putString("current_notification_type", notificationType).apply()
                        } else {
                            sharedPreferences.edit().remove("current_notification_type").apply()
                        }

                        withContext(Dispatchers.Main) {
                            updateNotification()
                        }

                        if (shouldRing && !isRinging) {
                            Log.d("RingMonitoringService", "Starting ring")
                            startRinging(notificationType)
                        } else if (!shouldRing && isRinging) {
                            Log.d("RingMonitoringService", "Stopping ring")
                            stopRinging()
                        }
                    } catch (e: Exception) {
                        Log.e("RingMonitoringService", "Monitoring error", e)
                        // Ensure wake lock is released on error
                        wakeLock?.let { if (it.isHeld) it.release() }
                        delay(MONITORING_INTERVAL)
                    }
                    delay(MONITORING_INTERVAL)
                }
            } finally {
                withContext(NonCancellable) {
                    stopRinging()
                    isMonitoring = false
                    wakeLock?.let { if (it.isHeld) it.release() }
                }
            }
        }

        startPeriodicRestart()
        updateNotification()
    }

    private fun stopMonitoring() {
        isMonitoring = false
        monitoringJob?.cancel()
        monitoringJob = null
        stopPeriodicRestart()
        stopRinging()
        wakeLock?.let { if (it.isHeld) it.release() }
        updateNotification()
    }

    private fun openAppAutomatically(notificationType: String?) {
        try {
            val phorjp = sharedPreferences.getString("phorjp", null)
            val intent = when (notificationType) {
                "NG" -> {
                    packageManager.getLaunchIntentForPackage("com.example.ng_notification")?.apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                        putExtra("phorjp", phorjp)
                    }
                }
                "PAGING" -> {
                    if (phorjp == "jp") {
                        Intent(this, PagingActivityJP::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                        }
                    } else {
                        Intent(this, PagingActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                        }
                    }
                }
                else -> null
            }

            intent?.let {
                startActivity(it)
            }
        } catch (e: Exception) {
            Log.e("RingMonitoringService", "Failed to auto-open app", e)
        }
    }

    private fun startRinging(notificationType: String?) {
        if (isRinging) return

        isRinging = true
        sharedPreferences.edit().putString("current_notification_type", notificationType).apply()

        openAppAutomatically(notificationType)

        serviceScope.launch {
            try {
                stopSilentAudio()

                // Vibrate pattern
                val pattern = longArrayOf(0, 1000, 1000)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator?.vibrate(pattern, 0)
                }

                // Get ringtone
                val ringtoneUri = try {
                    sharedPreferences.getString("selected_ringtone_uri", null)?.let { uriString ->
                        Uri.parse(uriString)
                    }?.takeIf { uri ->
                        val ringtone = RingtoneManager.getRingtone(this@RingMonitoringService, uri)
                        ringtone != null
                    } ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                } catch (e: Exception) {
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                }

                // Play ringtone
                currentRingtone = withContext(Dispatchers.IO) {
                    RingtoneManager.getRingtone(this@RingMonitoringService, ringtoneUri)?.apply {
                        setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ALARM)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                .build()
                        )
                    }
                }

                currentRingtone?.play()
            } catch (e: Exception) {
                Log.e("RingMonitoringService", "Error starting ringtone", e)
                stopRinging()
            }
        }

        updateNotification()
    }

    private fun stopRinging() {
        if (!isRinging) return

        isRinging = false
        sharedPreferences.edit().remove("current_notification_type").apply()

        try {
            currentRingtone?.stop()
            vibrator?.cancel()
            currentRingtone = null
            startSilentAudio()
        } catch (e: Exception) {
            Log.e("RingMonitoringService", "Error stopping ringtone", e)
        }

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
        val phorjp = sharedPreferences.getString("phorjp", null)
        val isJapanese = phorjp == "jp"
        val notificationType = sharedPreferences.getString("current_notification_type", null)

        val toggleIntent = Intent(this, RingMonitoringService::class.java).apply {
            action = ACTION_TOGGLE_MONITORING
        }
        val togglePendingIntent = PendingIntent.getService(
            this,
            0,
            toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val contentIntent = when (notificationType) {
            "NG" -> packageManager.getLaunchIntentForPackage("com.example.ng_notification")?.apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("phorjp", phorjp)
            }
            "PAGING" -> {
                if (phorjp == "jp") {
                    Intent(this, PagingActivityJP::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    }
                } else {
                    Intent(this, PagingActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    }
                }
            }
            else -> {
                Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
            }
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            0,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val (title, statusText, toggleText) = if (isJapanese) {
            when (notificationType) {
                "NG" -> Triple(
                    "NGレポート",
                    if (isRinging) "🔊 鳴っています - タップして表示" else "📡 監視中",
                    if (isMonitoring) "監視を停止" else "監視を開始"
                )
                "PAGING" -> Triple(
                    "注意：呼び出されています！",
                    if (isRinging) "🔊 鳴っています - タップして表示" else "📡 監視中",
                    if (isMonitoring) "監視を停止" else "監視を開始"
                )
                else -> Triple(
                    "リング監視サービス",
                    when {
                        isRinging -> "🔊 鳴っています - タップして表示"
                        isMonitoring -> "📡 アクティブ - モニタリング"
                        else -> "⏸️ 非アクティブ - タップして開始"
                    },
                    if (isMonitoring) "監視を停止" else "監視を開始"
                )
            }
        } else {
            when (notificationType) {
                "NG" -> Triple(
                    "NG Report",
                    if (isRinging) "🔊 RINGING - Tap to view" else "📡 Monitoring",
                    if (isMonitoring) "Stop Monitoring" else "Start Monitoring"
                )
                "PAGING" -> Triple(
                    "Attention: You're being paged!",
                    if (isRinging) "🔊 RINGING - Tap to view" else "📡 Monitoring",
                    if (isMonitoring) "Stop Monitoring" else "Start Monitoring"
                )
                else -> Triple(
                    "Ring Monitoring Service",
                    when {
                        isRinging -> "🔊 RINGING - Tap to view"
                        isMonitoring -> "📡 Active - Monitoring"
                        else -> "⏸️ Inactive - Tap to start"
                    },
                    if (isMonitoring) "Stop Monitoring" else "Start Monitoring"
                )
            }
        }

        val flagIcon = if (isJapanese) R.drawable.japan else R.drawable.philippinesflag

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_ring_active)
            .setLargeIcon(android.graphics.BitmapFactory.decodeResource(resources, flagIcon))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
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
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        Log.d("RingMonitoringService", "Service destroyed")
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
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }
}