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
import java.util.concurrent.TimeUnit

class RingMonitoringService : Service(), SharedPreferences.OnSharedPreferenceChangeListener {
    private val serviceScope = CoroutineScope(Dispatchers.IO)
    private var monitoringJob: Job? = null
    private var periodicRestartJob: Job? = null
    private var heartbeatJob: Job? = null
    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var isRinging = false
    private var isMonitoring = false
    private var isServiceActive = true
    private var ringtoneJob: Job? = null
    private var currentRingtone: Ringtone? = null
    private var deviceId: String = "unknown-device"
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var alarmManager: AlarmManager
    private var alarmPendingIntent: PendingIntent? = null
    private var heartbeatPendingIntent: PendingIntent? = null
    private var currentNotificationType: String? = null

    companion object {
        private const val CHANNEL_ID = "RingMonitoringChannel"
        private const val NOTIFICATION_ID = 1234
        private const val MONITORING_INTERVAL = 8_000L
        private const val RESTART_INTERVAL = 600_000L
        private const val ALARM_INTERVAL = 900_000L
        private const val HEARTBEAT_INTERVAL = 600_000L
        private const val ALARM_REQUEST_CODE = 9876
        private const val HEARTBEAT_REQUEST_CODE = 9877
        const val ACTION_START_MONITORING = "START_MONITORING"
        const val ACTION_STOP_MONITORING = "STOP_MONITORING"
        const val ACTION_TOGGLE_MONITORING = "TOGGLE_MONITORING"
        const val ACTION_RESTART_SERVICE = "RESTART_SERVICE"
        const val ACTION_ALARM_TRIGGER = "ALARM_TRIGGER"
        const val ACTION_HEARTBEAT = "HEARTBEAT"
        private const val NOTIF_BUTTON_PREF = "notifButton"

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

        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "RingMonitoringService::WakeLock"
        ).apply {
            setReferenceCounted(false)
        }

        val notifButtonValue = sharedPreferences.getInt(NOTIF_BUTTON_PREF, 1)
        if (notifButtonValue == 1) {
            startPeriodicRestart()
            startHeartbeat()
            registerAlarmReceiver()
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerAlarmReceiver() {
        val filter = IntentFilter().apply {
            addAction(ACTION_ALARM_TRIGGER)
            addAction(ACTION_HEARTBEAT)
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
                ACTION_HEARTBEAT -> {
                    Log.d("RingMonitoringService", "Heartbeat triggered")
                    handleHeartbeat()
                    scheduleNextHeartbeat()
                }
            }
        }
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = serviceScope.launch {
            while (isActive && isServiceActive) {
                try {
                    delay(HEARTBEAT_INTERVAL)
                    if (isMonitoring) {
                        Log.d("RingMonitoringService", "Heartbeat: Monitoring is active")
                        if (isRinging) {
                            refreshWakeLock()
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e("RingMonitoringService", "Heartbeat error", e)
                }
            }
        }
        scheduleNextHeartbeat()
    }

    private fun handleHeartbeat() {
        if (!isMonitoring) {
            Log.w("RingMonitoringService", "Heartbeat detected monitoring stopped, restarting")
            startMonitoring()
        }

        if (isRinging) {
            refreshWakeLock()
        }
    }

    private fun refreshWakeLock() {
        try {
            wakeLock?.let { wl ->
                if (!wl.isHeld) {
                    wl.acquire(60 * 1000L)
                    Log.d("RingMonitoringService", "Wake lock acquired for ringing")
                }
            }
        } catch (e: Exception) {
            Log.e("RingMonitoringService", "Error refreshing wake lock", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { wl ->
                if (wl.isHeld) {
                    wl.release()
                    Log.d("RingMonitoringService", "Wake lock released")
                }
            }
        } catch (e: Exception) {
            Log.e("RingMonitoringService", "Error releasing wake lock", e)
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerTime,
                    alarmPendingIntent!!
                )
            } else {
                alarmManager.set(
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerTime,
                    heartbeatPendingIntent!!
                )
            } else {
                alarmManager.set(
                    AlarmManager.RTC_WAKEUP,
                    triggerTime,
                    heartbeatPendingIntent!!
                )
            }
        } catch (e: Exception) {
            Log.e("RingMonitoringService", "Failed to schedule heartbeat", e)
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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.action?.let { action ->
            when (action) {
                ACTION_START_MONITORING -> {
                    Log.d("RingMonitoringService", "Received start command")
                    val notifButtonValue = sharedPreferences.getInt(NOTIF_BUTTON_PREF, 1)
                    if (notifButtonValue == 1 && !isMonitoring) {
                        startMonitoring()
                        scheduleNextAlarm()
                        scheduleNextHeartbeat()
                    }
                }
                ACTION_STOP_MONITORING -> {
                    Log.d("RingMonitoringService", "Received stop command")
                    stopMonitoring()
                    stopPeriodicRestart()
                    stopHeartbeat()
                    cancelAlarms()
                    releaseWakeLock()
                    stopSelf()
                }
                ACTION_TOGGLE_MONITORING -> {
                    Log.d("RingMonitoringService", "Received toggle command")
                    if (isMonitoring) {
                        stopMonitoring()
                        stopPeriodicRestart()
                        stopHeartbeat()
                        cancelAlarms()
                        releaseWakeLock()
                        sharedPreferences.edit().putInt(NOTIF_BUTTON_PREF, 0).apply()
                    } else {
                        startMonitoring()
                        startPeriodicRestart()
                        startHeartbeat()
                        scheduleNextAlarm()
                        scheduleNextHeartbeat()
                        sharedPreferences.edit().putInt(NOTIF_BUTTON_PREF, 1).apply()
                    }
                    updateNotification()
                }
                ACTION_RESTART_SERVICE -> {
                    Log.d("RingMonitoringService", "Received restart command")
                    val notifButtonValue = sharedPreferences.getInt(NOTIF_BUTTON_PREF, 1)
                    stopMonitoring()
                    if (notifButtonValue == 1) {
                        startMonitoring()
                    }
                    updateNotification()
                }
                ACTION_ALARM_TRIGGER -> {
                    Log.d("RingMonitoringService", "Received alarm trigger")
                    val notifButtonValue = sharedPreferences.getInt(NOTIF_BUTTON_PREF, 1)
                    if (notifButtonValue == 1 && !isMonitoring) {
                        startMonitoring()
                    }
                    scheduleNextAlarm()
                }
                ACTION_HEARTBEAT -> {
                    Log.d("RingMonitoringService", "Received heartbeat")
                    val notifButtonValue = sharedPreferences.getInt(NOTIF_BUTTON_PREF, 1)
                    if (notifButtonValue == 1) {
                        handleHeartbeat()
                    }
                    scheduleNextHeartbeat()
                }
            }
        } ?: run {
            val notifButtonValue = sharedPreferences.getInt(NOTIF_BUTTON_PREF, 1)
            if (notifButtonValue == 1 && !isMonitoring) {
                startMonitoring()
                scheduleNextAlarm()
                scheduleNextHeartbeat()
            }
        }
        return START_STICKY
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        when (key) {
            "phorjp" -> {
                Log.d("RingMonitoringService", "phorjp preference changed, restarting monitoring")
                if (isMonitoring) {
                    stopMonitoring()
                    startMonitoring()
                }
                updateNotification()
            }
            "languageFlag", "languageFlagJP" -> {
                Log.d("RingMonitoringService", "Language preference changed, updating notification")
                updateNotification()
            }
            NOTIF_BUTTON_PREF -> {
                Log.d("RingMonitoringService", "Notif button preference changed")
                val notifButtonValue = sharedPreferences?.getInt(NOTIF_BUTTON_PREF, 1) ?: 1
                if (notifButtonValue == 1 && !isMonitoring) {
                    startMonitoring()
                    startPeriodicRestart()
                    startHeartbeat()
                    scheduleNextAlarm()
                    scheduleNextHeartbeat()
                } else if (notifButtonValue == 0 && isMonitoring) {
                    stopMonitoring()
                    stopPeriodicRestart()
                    stopHeartbeat()
                    cancelAlarms()
                    releaseWakeLock()
                }
                updateNotification()
            }
        }
    }

    private fun startPeriodicRestart() {
        val notifButtonValue = sharedPreferences.getInt(NOTIF_BUTTON_PREF, 1)
        if (notifButtonValue == 0) return

        if (periodicRestartJob?.isActive == true) return

        periodicRestartJob = serviceScope.launch {
            try {
                while (isActive && isServiceActive) {
                    delay(RESTART_INTERVAL)

                    if (isActive && isServiceActive && isMonitoring) {
                        Log.d("RingMonitoringService", "Performing periodic monitoring restart")

                        stopMonitoring()
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

    private fun startMonitoring() {
        val notifButtonValue = sharedPreferences.getInt(NOTIF_BUTTON_PREF, 1)
        if (notifButtonValue == 0) return

        if (isMonitoring) return

        isMonitoring = true
        monitoringJob?.cancel()

        monitoringJob = serviceScope.launch {
            try {
                val prefs = getSharedPreferences("AppPrefs", MODE_PRIVATE)
                val phorjp = prefs.getString("phorjp", null)

                while (isActive) {
                    try {
                        val shouldAcquireWakeLock = !isRinging
                        if (shouldAcquireWakeLock) {
                            wakeLock?.acquire(30 * 1000L)
                        }

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

                        val jobOrderResponse = withContext(Dispatchers.IO) {
                            if (phorjp == "jp") {
                                RetrofitClientJP.instance.getJobOrderStatus(deviceId).execute()
                            } else {
                                RetrofitClient.instance.getJobOrderStatus(deviceId).execute()
                            }
                        }

                        if (shouldAcquireWakeLock) {
                            releaseWakeLock()
                        }

                        val ringStatus = ringResponse.body()
                        val pagingStatus = pagingResponse.body()
                        val jobOrderStatus = jobOrderResponse.body()

                        val shouldRing = (pagingStatus?.shouldRing == true) ||
                                (ringStatus?.shouldRing == true) ||
                                (jobOrderStatus?.shouldRing == true)

                        val notificationType = when {
                            pagingStatus?.shouldRing == true -> pagingStatus.type
                            ringStatus?.shouldRing == true -> ringStatus.type
                            jobOrderStatus?.shouldRing == true -> jobOrderStatus.type
                            else -> null
                        }

                        if (shouldRing) {
                            if (!isRinging) {
                                Log.d("RingMonitoringService", "Starting ring")
                                startRinging(notificationType)
                            } else if (currentNotificationType != notificationType) {
                                Log.d("RingMonitoringService", "Notification type changed from $currentNotificationType to $notificationType, restarting ring")
                                stopRinging()
                                startRinging(notificationType)
                            }
                        } else if (isRinging) {
                            Log.d("RingMonitoringService", "Stopping ring")
                            stopRinging()
                        }
                    } catch (e: Exception) {
                        if (isActive) {
                            Log.e("RingMonitoringService", "Monitoring error", e)
                        }
                        releaseWakeLock()
                        delay(10000)
                    }
                    delay(MONITORING_INTERVAL)
                }
            } finally {
                withContext(NonCancellable) {
                    stopRinging()
                    isMonitoring = false
                    releaseWakeLock()
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
        releaseWakeLock()
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
                "JobOrder" -> {
                    packageManager.getLaunchIntentForPackage("com.example.it_job_order_form")?.apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                        putExtra("phorjp", phorjp)
                    }
                }
                else -> null
            }

            intent?.let {
                startActivity(it)
                Log.d("RingMonitoringService", "Auto-opened app for notification type: $notificationType")
            }
        } catch (e: Exception) {
            Log.e("RingMonitoringService", "Failed to auto-open app", e)
        }
    }

    private fun startRinging(notificationType: String?) {
        val notifButtonValue = sharedPreferences.getInt(NOTIF_BUTTON_PREF, 1)
        if (notifButtonValue == 0) return

        if (isRinging && currentNotificationType == notificationType) return

        if (isRinging && currentNotificationType != notificationType) {
            stopRinging()
        }

        isRinging = true
        currentNotificationType = notificationType
        sharedPreferences.edit().putString("current_notification_type", notificationType).apply()

        refreshWakeLock()

        openAppAutomatically(notificationType)

        ringtoneJob?.cancel()

        ringtoneJob = serviceScope.launch {
            try {
                val pattern = longArrayOf(0, 1000, 1000)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator?.vibrate(pattern, 0)
                }

                val ringtoneUri = try {
                    sharedPreferences.getString("selected_ringtone_uri", null)?.let { uriString ->
                        Uri.parse(uriString)
                    }?.takeIf { uri ->
                        val ringtone = RingtoneManager.getRingtone(this@RingMonitoringService, uri)
                        ringtone != null
                    } ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                } catch (e: Exception) {
                    Log.e("RingMonitoringService", "Invalid ringtone URI, using default", e)
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                }

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
                releaseWakeLock()
                throw e
            } catch (e: Exception) {
                Log.e("RingMonitoringService", "Ringtone error", e)
            } finally {
                withContext(NonCancellable) {
                    currentRingtone?.stop()
                    vibrator?.cancel()
                    currentRingtone = null
                    releaseWakeLock()
                }
            }
        }

        updateNotification()
    }

    private fun stopRinging() {
        if (!isRinging) return

        isRinging = false
        currentNotificationType = null
        sharedPreferences.edit().remove("current_notification_type").apply()
        ringtoneJob?.cancel()
        releaseWakeLock()
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
        val isJapanese = when (phorjp) {
            "ph" -> sharedPreferences.getString("languageFlag", "en") == "ja"
            "jp" -> sharedPreferences.getString("languageFlagJP", "ja") == "ja"
            else -> false
        }

        val notificationType = sharedPreferences.getString("current_notification_type", null)
        val notifButtonValue = sharedPreferences.getInt(NOTIF_BUTTON_PREF, 1)

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
            "JobOrder" -> packageManager.getLaunchIntentForPackage("com.example.it_job_order_form")?.apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("phorjp", phorjp)
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
                "PAGING" -> Triple(
                    "注意：呼び出されています！",
                    "🔊 鳴っています - タップして表示",
                    if (notifButtonValue == 1) "監視を停止" else "監視を開始"
                )
                "NG" -> Triple(
                    "NGレポート",
                    "🔊 鳴っています - タップして表示",
                    if (notifButtonValue == 1) "監視を停止" else "監視を開始"
                )
                "JobOrder" -> Triple(
                    "ジョブオーダー通知",
                    "🔊 鳴っています - タップして表示",
                    if (notifButtonValue == 1) "監視を停止" else "監視を開始"
                )
                else -> Triple(
                    "リング監視サービス",
                    when {
                        isRinging -> "🔊 鳴っています - タップして表示"
                        notifButtonValue == 1 -> "📡 アクティブ - モニタリング"
                        else -> "⏸️ 非アクティブ - タップして開始"
                    },
                    if (notifButtonValue == 1) "監視を停止" else "監視を開始"
                )
            }
        } else {
            when (notificationType) {
                "PAGING" -> Triple(
                    "Attention: You're being paged!",
                    "🔊 RINGING - Tap to view",
                    if (notifButtonValue == 1) "Stop Monitoring" else "Start Monitoring"
                )
                "NG" -> Triple(
                    "NG Report",
                    "🔊 RINGING - Tap to view",
                    if (notifButtonValue == 1) "Stop Monitoring" else "Start Monitoring"
                )
                "JobOrder" -> Triple(
                    "Job Order Notification",
                    "🔊 RINGING - Tap to view",
                    if (notifButtonValue == 1) "Stop Monitoring" else "Start Monitoring"
                )
                else -> Triple(
                    "Ring Monitoring Service",
                    when {
                        isRinging -> "🔊 RINGING - Tap to view"
                        notifButtonValue == 1 -> "📡 Active - Monitoring"
                        else -> "⏸️ Inactive - Tap to start"
                    },
                    if (notifButtonValue == 1) "Stop Monitoring" else "Start Monitoring"
                )
            }
        }

        val flagIcon = when (phorjp) {
            "ph" -> R.drawable.philippinesflag
            "jp" -> R.drawable.japan
            else -> R.drawable.ic_ring_active
        }

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
                if (notifButtonValue == 1) R.drawable.stop_icon else R.drawable.start_icon,
                toggleText,
                togglePendingIntent
            )
            .setStyle(androidx.media.app.NotificationCompat.MediaStyle()
                .setShowActionsInCompactView(0)
            )
            .build()
    }

    private fun updateNotification() {
        val notifButtonValue = sharedPreferences.getInt(NOTIF_BUTTON_PREF, 1)
        if (monitoringJob?.isActive == true && !isMonitoring) {
            isMonitoring = true
        } else if (monitoringJob?.isActive != true && isMonitoring) {
            isMonitoring = false
        }

        val notification = createNotification()
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        Log.d("RingMonitoringService", "Service destroyed")
        isServiceActive = false
        stopMonitoring()
        stopPeriodicRestart()
        cancelAlarms()
        releaseWakeLock()
        try {
            unregisterReceiver(alarmReceiver)
        } catch (e: IllegalArgumentException) {
            Log.w("RingMonitoringService", "Receiver not registered", e)
        }
        sharedPreferences.unregisterOnSharedPreferenceChangeListener(this)
        super.onDestroy()
    }
}