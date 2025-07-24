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
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

class RingMonitoringService : Service(), SharedPreferences.OnSharedPreferenceChangeListener {
    private val serviceScope = CoroutineScope(Dispatchers.IO + kotlinx.coroutines.SupervisorJob())
    private var monitoringJob: Job? = null
    private var periodicRestartJob: Job? = null
    private var keepAliveJob: Job? = null
    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var audioManager: AudioManager? = null

    @Volatile private var isRinging = false
    @Volatile private var isMonitoring = false
    @Volatile private var shouldStopRinging = false

    // Use MediaPlayer instead of Ringtone for better control
    private var mediaPlayer: MediaPlayer? = null
    private val deviceId: String by lazy { retrieveDeviceId() }
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var alarmManager: AlarmManager
    private var alarmPendingIntent: PendingIntent? = null
    private var keepAlivePendingIntent: PendingIntent? = null

    private var cachedNotification: Notification? = null
    private var lastNotificationHash: Int = 0
    private var lastNetworkCallTime = 0L

    companion object {
        private const val CHANNEL_ID = "RingMonitoringChannel"
        private const val NOTIFICATION_ID = 1234
        private const val MONITORING_INTERVAL = 8_000L
        private const val RESTART_INTERVAL = 300_000L // 5m instead of 10m
        private const val ALARM_INTERVAL = 600_000L // 10m instead of 15m
        private const val KEEP_ALIVE_INTERVAL = 120_000L // 2m keep alive
        private const val ALARM_REQUEST_CODE = 9876
        private const val KEEP_ALIVE_REQUEST_CODE = 9877
        private const val WAKELOCK_TIMEOUT = 15_000L // Reduced to 15 seconds
        private const val MIN_NETWORK_CALL_INTERVAL = 5_000L // Minimum 5s between API calls

        const val ACTION_START_MONITORING = "START_MONITORING"
        const val ACTION_STOP_MONITORING = "STOP_MONITORING"
        const val ACTION_TOGGLE_MONITORING = "TOGGLE_MONITORING"
        const val ACTION_RESTART_SERVICE = "RESTART_SERVICE"
        const val ACTION_ALARM_TRIGGER = "ALARM_TRIGGER"
        const val ACTION_KEEP_ALIVE = "KEEP_ALIVE"

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
        Log.d("RingMonitoringService", "Service created with device ID: $deviceId")

        sharedPreferences = getSharedPreferences("AppPrefs", MODE_PRIVATE)
        sharedPreferences.registerOnSharedPreferenceChangeListener(this)

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())

        vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
        alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager

        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "RingMonitoringService::WakeLock"
        ).apply {
            setReferenceCounted(false)
        }

        registerAlarmReceiver()
        startKeepAliveService()
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerAlarmReceiver() {
        val filter = IntentFilter().apply {
            addAction(ACTION_ALARM_TRIGGER)
            addAction(ACTION_KEEP_ALIVE)
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
                ACTION_KEEP_ALIVE -> {
                    Log.d("RingMonitoringService", "Keep alive triggered")
                    // Just update notification to show we're alive
                    updateNotification()
                    scheduleKeepAlive()
                }
            }
        }
    }

    private fun startKeepAliveService() {
        keepAliveJob = serviceScope.launch {
            while (isActive) {
                try {
                    // Periodic notification update to prevent Android from killing the service
                    if (isMonitoring) {
                        withContext(Dispatchers.Main) {
                            updateNotification()
                        }
                    }
                    delay(KEEP_ALIVE_INTERVAL)
                } catch (e: CancellationException) {
                    break
                } catch (e: Exception) {
                    Log.e("RingMonitoringService", "Keep alive error", e)
                }
            }
        }
        scheduleKeepAlive()
    }

    private fun scheduleKeepAlive() {
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
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> {
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerTime,
                        keepAlivePendingIntent!!
                    )
                }
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerTime,
                        keepAlivePendingIntent!!
                    )
                }
                else -> {
                    alarmManager.setExact(
                        AlarmManager.RTC_WAKEUP,
                        triggerTime,
                        keepAlivePendingIntent!!
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("RingMonitoringService", "Failed to schedule keep alive", e)
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
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> {
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerTime,
                        alarmPendingIntent!!
                    )
                }
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerTime,
                        alarmPendingIntent!!
                    )
                }
                else -> {
                    alarmManager.setExact(
                        AlarmManager.RTC_WAKEUP,
                        triggerTime,
                        alarmPendingIntent!!
                    )
                }
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
        keepAlivePendingIntent?.let {
            alarmManager.cancel(it)
            keepAlivePendingIntent = null
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
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
            ACTION_KEEP_ALIVE -> {
                Log.d("RingMonitoringService", "Received keep alive")
                updateNotification()
                scheduleKeepAlive()
            }
            null -> {
                if (!isMonitoring) {
                    startMonitoring()
                    scheduleNextAlarm()
                }
            }
        }
        return START_STICKY
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        when (key) {
            "phorjp", "languageFlag", "languageFlagJP" -> {
                Log.d("RingMonitoringService", "Language preference changed, updating notification")
                cachedNotification = null
                lastNotificationHash = 0
                updateNotification()
            }
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
                        // Don't stop/start if currently ringing
                        if (!isRinging) {
                            stopMonitoring()
                            startMonitoring()
                        }
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

    private fun startMonitoring() {
        if (isMonitoring) return

        isMonitoring = true
        monitoringJob?.cancel()

        monitoringJob = serviceScope.launch {
            try {
                val prefs = getSharedPreferences("AppPrefs", MODE_PRIVATE)
                val phorjp = prefs.getString("phorjp", null)

                while (isActive) {
                    try {
                        // Rate limit API calls to prevent excessive battery drain
                        val currentTime = System.currentTimeMillis()
                        if (currentTime - lastNetworkCallTime < MIN_NETWORK_CALL_INTERVAL) {
                            delay(MIN_NETWORK_CALL_INTERVAL - (currentTime - lastNetworkCallTime))
                        }

                        // Acquire wake lock only during network operations
                        wakeLock?.acquire(WAKELOCK_TIMEOUT)

                        // Use async for concurrent API calls - major optimization!
                        val ringResponseDeferred = async {
                            if (phorjp == "jp") {
                                RetrofitClientJP.instance.getRingStatus(deviceId).execute()
                            } else {
                                RetrofitClient.instance.getRingStatus(deviceId).execute()
                            }
                        }

                        val pagingResponseDeferred = async {
                            if (phorjp == "jp") {
                                RetrofitClientJP.instance.getPagingStatus(deviceId).execute()
                            } else {
                                RetrofitClient.instance.getPagingStatus(deviceId).execute()
                            }
                        }

                        val ringResponse = ringResponseDeferred.await()
                        val pagingResponse = pagingResponseDeferred.await()

                        lastNetworkCallTime = System.currentTimeMillis()
                        wakeLock?.let { if (it.isHeld) it.release() }

                        val ringStatus = ringResponse.body()
                        val pagingStatus = pagingResponse.body()

                        val shouldRing = (ringStatus?.shouldRing == true) || (pagingStatus?.shouldRing == true)
                        val notificationType = when {
                            pagingStatus?.shouldRing == true -> pagingStatus.type
                            ringStatus?.shouldRing == true -> ringStatus.type
                            else -> null
                        }

                        val currentType = sharedPreferences.getString("current_notification_type", null)
                        if (currentType != notificationType) {
                            if (notificationType != null) {
                                sharedPreferences.edit().putString("current_notification_type", notificationType).apply()
                            } else {
                                sharedPreferences.edit().remove("current_notification_type").apply()
                            }

                            withContext(Dispatchers.Main) {
                                updateNotification()
                            }
                        }

                        when {
                            shouldRing && !isRinging -> {
                                Log.d("RingMonitoringService", "Starting ring")
                                startRinging(notificationType)
                            }
                            !shouldRing && isRinging -> {
                                Log.d("RingMonitoringService", "Stopping ring")
                                stopRinging()
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("RingMonitoringService", "Monitoring error", e)
                        wakeLock?.let { if (it.isHeld) it.release() }
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
        shouldStopRinging = false
        sharedPreferences.edit().putString("current_notification_type", notificationType).apply()

        openAppAutomatically(notificationType)

        serviceScope.launch {
            try {
                // Start vibration pattern - continuous
                val pattern = longArrayOf(0, 1000, 1000)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator?.vibrate(pattern, 0)
                }

                // Get ringtone URI
                val ringtoneUri = try {
                    sharedPreferences.getString("selected_ringtone_uri", null)?.let { uriString ->
                        Uri.parse(uriString)
                    }?.takeIf { uri ->
                        try {
                            val ringtone = RingtoneManager.getRingtone(this@RingMonitoringService, uri)
                            ringtone != null
                        } catch (e: Exception) {
                            false
                        }
                    } ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                } catch (e: Exception) {
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                }

                // Use MediaPlayer for better control and to prevent double ringing
                startMediaPlayerLoop(ringtoneUri)

            } catch (e: Exception) {
                Log.e("RingMonitoringService", "Error starting ringtone", e)
                stopRinging()
            }
        }

        updateNotification()
    }

    private fun stopRinging() {
        if (!isRinging) return

        shouldStopRinging = true
        isRinging = false
        sharedPreferences.edit().remove("current_notification_type").apply()

        try {
            // Stop media player
            mediaPlayer?.let { player ->
                if (player.isPlaying) {
                    player.stop()
                }
                player.release()
            }
            mediaPlayer = null

            // Stop vibration
            vibrator?.cancel()
        } catch (e: Exception) {
            Log.e("RingMonitoringService", "Error stopping ringtone", e)
        }

        updateNotification()
    }

    private fun startMediaPlayerLoop(ringtoneUri: Uri) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                mediaPlayer?.release() // Release any existing player

                mediaPlayer = MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )

                    // Set volume to maximum for alarm
                    setVolume(1.0f, 1.0f)
                    isLooping = true // Enable built-in looping

                    setDataSource(this@RingMonitoringService, ringtoneUri)

                    setOnPreparedListener { player ->
                        if (!shouldStopRinging && isRinging) {
                            player.start()
                            Log.d("RingMonitoringService", "MediaPlayer started successfully")
                        }
                    }

                    setOnErrorListener { _, what, extra ->
                        Log.e("RingMonitoringService", "MediaPlayer error: what=$what, extra=$extra")
                        // Try to fallback to system alarm sound
                        try {
                            reset()
                            setDataSource(this@RingMonitoringService, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
                            prepareAsync()
                        } catch (e: Exception) {
                            Log.e("RingMonitoringService", "Failed to fallback to default alarm", e)
                        }
                        true
                    }

                    setOnCompletionListener { player ->
                        // This shouldn't be called due to looping, but just in case
                        if (!shouldStopRinging && isRinging) {
                            try {
                                player.start()
                            } catch (e: Exception) {
                                Log.e("RingMonitoringService", "Error restarting MediaPlayer", e)
                            }
                        }
                    }

                    prepareAsync()
                }

            } catch (e: Exception) {
                Log.e("RingMonitoringService", "Error setting up MediaPlayer", e)
                // Fallback to Ringtone if MediaPlayer fails
                startRingtoneFallback(ringtoneUri)
            }
        }
    }

    private fun startRingtoneFallback(ringtoneUri: Uri) {
        serviceScope.launch {
            try {
                val ringtone = RingtoneManager.getRingtone(this@RingMonitoringService, ringtoneUri)?.apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                }

                // Manual loop for Ringtone since it doesn't have built-in looping
                while (!shouldStopRinging && isRinging && isActive) {
                    try {
                        if (ringtone != null && !ringtone.isPlaying) {
                            withContext(Dispatchers.Main) {
                                ringtone.play()
                            }
                        }
                        delay(100) // Check every 100ms
                    } catch (e: Exception) {
                        Log.e("RingMonitoringService", "Error in ringtone fallback loop", e)
                        break
                    }
                }

                ringtone?.stop()
            } catch (e: Exception) {
                Log.e("RingMonitoringService", "Error in ringtone fallback", e)
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Ring Monitoring Service",
                NotificationManager.IMPORTANCE_HIGH // Changed to HIGH to prevent killing
            ).apply {
                description = "Notification channel for ring monitoring service"
                setShowBadge(false)
                setBypassDnd(true) // Allow notifications even in DND mode
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(): Notification {
        val phorjp = sharedPreferences.getString("phorjp", null)
        val languageFlag = if (phorjp == "jp") {
            sharedPreferences.getString("languageFlagJP", "ja") ?: "ja"
        } else {
            sharedPreferences.getString("languageFlag", "en") ?: "en"
        }
        val isJapanese = languageFlag == "ja"
        val notificationType = sharedPreferences.getString("current_notification_type", null)

        val notificationHash = listOf(phorjp, languageFlag, notificationType, isRinging, isMonitoring, System.currentTimeMillis() / 60000).hashCode()

        if (notificationHash == lastNotificationHash && cachedNotification != null) {
            return cachedNotification!!
        }

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

        val flagIcon = if (phorjp == "jp") R.drawable.japan else R.drawable.philippinesflag

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_ring_active)
            .setLargeIcon(android.graphics.BitmapFactory.decodeResource(resources, flagIcon))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentPendingIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(
                if (isMonitoring) R.drawable.stop_icon else R.drawable.start_icon,
                toggleText,
                togglePendingIntent
            )
            .setStyle(androidx.media.app.NotificationCompat.MediaStyle()
                .setShowActionsInCompactView(0)
            )
            .build()

        cachedNotification = notification
        lastNotificationHash = notificationHash

        return notification
    }

    private fun updateNotification() {
        try {
            val notification = createNotification()
            val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.e("RingMonitoringService", "Error updating notification", e)
        }
    }

    override fun onDestroy() {
        Log.d("RingMonitoringService", "Service destroyed")
        stopMonitoring()
        stopPeriodicRestart()
        cancelAlarms()

        // Stop keep alive
        keepAliveJob?.cancel()
        keepAliveJob = null

        try {
            unregisterReceiver(alarmReceiver)
        } catch (e: IllegalArgumentException) {
            Log.w("RingMonitoringService", "Receiver not registered", e)
        }

        sharedPreferences.unregisterOnSharedPreferenceChangeListener(this)
        wakeLock?.let { if (it.isHeld) it.release() }

        mediaPlayer?.let { player ->
            try {
                if (player.isPlaying) {
                    player.stop()
                }
                player.release()
            } catch (e: Exception) {
                Log.e("RingMonitoringService", "Error releasing MediaPlayer", e)
            }
        }
        mediaPlayer = null

        cachedNotification = null

        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.d("RingMonitoringService", "Task removed, rescheduling service")
        val restartServiceIntent = Intent(applicationContext, RingMonitoringService::class.java).apply {
            action = ACTION_RESTART_SERVICE
        }
        val restartServicePendingIntent = PendingIntent.getService(
            this,
            1,
            restartServiceIntent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )

        val alarmService = getSystemService(ALARM_SERVICE) as AlarmManager
        alarmService.set(
            AlarmManager.ELAPSED_REALTIME,
            android.os.SystemClock.elapsedRealtime() + 1000,
            restartServicePendingIntent
        )

        super.onTaskRemoved(rootIntent)
    }
}