package com.example.ark_notif

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.app.job.JobService
import android.app.job.JobParameters
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.work.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class RingMonitoringManager private constructor(private val context: Context) {

    companion object {
        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var INSTANCE: RingMonitoringManager? = null
        private const val JOB_ID = 1001
        private const val ALARM_JOB_ID = 1002
        private const val WORK_NAME = "RingMonitoringWork"
        private const val RESTART_WORK_NAME = "ServiceRestartWork"
        private const val ALARM_WORK_NAME = "AlarmTriggeredWork"
        private const val TAG = "RingMonitoringManager"
        private const val ALARM_INTERVAL = 60 * 1000L // 1 minute for Android 14+
        private const val REQUEST_CODE_ALARM = 12345

        fun getInstance(context: Context): RingMonitoringManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: RingMonitoringManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val jobScheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
    private val workManager = WorkManager.getInstance(context)
    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var batteryReceiver: BroadcastReceiver? = null
    private var dozeReceiver: BroadcastReceiver? = null

    fun startMonitoring() {
        Log.d(TAG, "Starting comprehensive monitoring system for Android ${Build.VERSION.SDK_INT}")

        // Request battery optimization whitelist for Android 14+
        requestBatteryOptimizationWhitelist()

        // Start the core service first
        startRingMonitoringService()

        // Use multiple scheduling mechanisms for redundancy
        scheduleJobScheduler()
        scheduleWorkManager()
        scheduleAlarmManager() // Critical for Android 14+
        schedulePeriodicRestart()

        // Register system receivers for Android 14+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            registerSystemReceivers()
            scheduleNetworkCallback()
        }

        Log.d(TAG, "All monitoring systems activated")
    }

    fun stopMonitoring() {
        Log.d(TAG, "Stopping monitoring system")

        // Cancel all scheduled jobs
        jobScheduler.cancel(JOB_ID)
        jobScheduler.cancel(ALARM_JOB_ID)

        // Cancel WorkManager tasks
        workManager.cancelUniqueWork(WORK_NAME)
        workManager.cancelUniqueWork(RESTART_WORK_NAME)
        workManager.cancelUniqueWork(ALARM_WORK_NAME)

        // Cancel alarm
        cancelAlarmManager()

        // Unregister receivers
        unregisterSystemReceivers()
        unregisterNetworkCallback()

        // Stop the service
        val intent = Intent(context, RingMonitoringService::class.java).apply {
            action = RingMonitoringService.ACTION_STOP_MONITORING
        }
        context.stopService(intent)
    }

    @SuppressLint("BatteryLife")
    private fun requestBatteryOptimizationWhitelist() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (!powerManager.isIgnoringBatteryOptimizations(context.packageName)) {
                    Log.d(TAG, "Requesting battery optimization whitelist")
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = android.net.Uri.parse("package:${context.packageName}")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    try {
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not request battery optimization whitelist", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking battery optimization", e)
        }
    }

    private fun startRingMonitoringService() {
        val intent = Intent(context, RingMonitoringService::class.java).apply {
            action = RingMonitoringService.ACTION_START_MONITORING
            putExtra("restart_from_manager", true)
            putExtra("timestamp", System.currentTimeMillis())
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            Log.d(TAG, "Ring monitoring service started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start service", e)
        }
    }

    private fun scheduleJobScheduler() {
        try {
            val componentName = ComponentName(context, RingMonitoringJobService::class.java)
            val jobInfo = JobInfo.Builder(JOB_ID, componentName)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .setRequiresCharging(false)
                .setRequiresDeviceIdle(false)
                .apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        // For Android 14+, use more aggressive scheduling
                        setMinimumLatency(15 * 1000) // 15 seconds minimum
                        setOverrideDeadline(45 * 1000) // 45 seconds maximum
                        setExpedited(true) // Mark as expedited for higher priority
                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        setMinimumLatency(30 * 1000) // 30 seconds minimum
                        setOverrideDeadline(60 * 1000) // 1 minute maximum
                    } else {
                        setPeriodic(15 * 60 * 1000) // 15 minutes for older versions
                    }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        setRequiresBatteryNotLow(false)
                        setRequiresStorageNotLow(false)
                    }
                }
                .build()

            val result = jobScheduler.schedule(jobInfo)
            Log.d(TAG, "JobScheduler scheduled with result: $result")

            // Schedule alarm-triggered job for Android 14+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val alarmJobInfo = JobInfo.Builder(ALARM_JOB_ID, ComponentName(context, AlarmTriggeredJobService::class.java))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPersisted(true)
                    .setRequiresCharging(false)
                    .setRequiresDeviceIdle(false)
                    .setExpedited(true)
                    .build()

                jobScheduler.schedule(alarmJobInfo)
                Log.d(TAG, "Alarm-triggered JobScheduler scheduled")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule JobScheduler", e)
        }
    }

    private fun scheduleWorkManager() {
        try {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(false)
                .setRequiresCharging(false)
                .setRequiresDeviceIdle(false)
                .setRequiresStorageNotLow(false)
                .build()

            // Main monitoring work
            val workRequest = PeriodicWorkRequestBuilder<RingMonitoringWorker>(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) 15 else 15, // More frequent for Android 14+
                TimeUnit.MINUTES,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) 3 else 5, // Smaller flex for Android 14+
                TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .setInitialDelay(30, TimeUnit.SECONDS) // Start sooner
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .build()

            workManager.enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                workRequest
            )

            // Alarm-triggered work for Android 14+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val alarmWorkRequest = OneTimeWorkRequestBuilder<AlarmTriggeredWorker>()
                    .setConstraints(constraints)
                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    .build()

                workManager.enqueueUniqueWork(
                    ALARM_WORK_NAME,
                    ExistingWorkPolicy.REPLACE,
                    alarmWorkRequest
                )
            }

            Log.d(TAG, "WorkManager scheduled")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule WorkManager", e)
        }
    }

    internal fun scheduleAlarmManager() {
        try {
            val intent = Intent(context, AlarmReceiver::class.java).apply {
                action = "com.example.ark_notif.ALARM_TRIGGER"
            }

            val pendingIntent = PendingIntent.getBroadcast(
                context,
                REQUEST_CODE_ALARM,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val triggerTime = SystemClock.elapsedRealtime() + ALARM_INTERVAL

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerTime,
                    pendingIntent
                )
            } else {
                alarmManager.setExact(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerTime,
                    pendingIntent
                )
            }

            Log.d(TAG, "AlarmManager scheduled for ${ALARM_INTERVAL}ms")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule AlarmManager", e)
        }
    }

    private fun cancelAlarmManager() {
        try {
            val intent = Intent(context, AlarmReceiver::class.java).apply {
                action = "com.example.ark_notif.ALARM_TRIGGER"
            }

            val pendingIntent = PendingIntent.getBroadcast(
                context,
                REQUEST_CODE_ALARM,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            alarmManager.cancel(pendingIntent)
            Log.d(TAG, "AlarmManager cancelled")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cancel AlarmManager", e)
        }
    }

    private fun schedulePeriodicRestart() {
        try {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val restartRequest = PeriodicWorkRequestBuilder<ServiceRestartWorker>(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) 20 else 30, // More frequent for Android 14+
                TimeUnit.MINUTES,
                5, TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .setInitialDelay(2, TimeUnit.MINUTES)
                .build()

            workManager.enqueueUniquePeriodicWork(
                RESTART_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                restartRequest
            )

            Log.d(TAG, "Periodic restart scheduled")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule periodic restart", e)
        }
    }

    private fun registerSystemReceivers() {
        try {
            // Battery state receiver
            batteryReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    Log.d(TAG, "Battery state changed, ensuring service is running")
                    ensureServiceRunning()
                }
            }

            val batteryFilter = IntentFilter().apply {
                addAction(Intent.ACTION_BATTERY_CHANGED)
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
            }

            context.registerReceiver(batteryReceiver, batteryFilter)

            // Doze mode receiver
            dozeReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    when (intent?.action) {
                        PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED -> {
                            val isIdle = powerManager.isDeviceIdleMode
                            Log.d(TAG, "Device idle mode changed: $isIdle")
                            if (!isIdle) {
                                // Device exited doze mode, ensure service is running
                                ensureServiceRunning()
                            }
                        }
                    }
                }
            }

            val dozeFilter = IntentFilter().apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
                }
            }

            context.registerReceiver(dozeReceiver, dozeFilter)

            Log.d(TAG, "System receivers registered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register system receivers", e)
        }
    }

    private fun unregisterSystemReceivers() {
        try {
            batteryReceiver?.let { context.unregisterReceiver(it) }
            dozeReceiver?.let { context.unregisterReceiver(it) }
            batteryReceiver = null
            dozeReceiver = null
            Log.d(TAG, "System receivers unregistered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unregister system receivers", e)
        }
    }

    private fun scheduleNetworkCallback() {
        try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    super.onAvailable(network)
                    Log.d(TAG, "Network available, ensuring service is running")
                    ensureServiceRunning()
                }

                override fun onLost(network: Network) {
                    super.onLost(network)
                    Log.d(TAG, "Network lost")
                }
            }

            val networkRequest = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()

            connectivityManager.registerNetworkCallback(networkRequest, networkCallback!!)
            Log.d(TAG, "Network callback registered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register network callback", e)
        }
    }

    private fun unregisterNetworkCallback() {
        try {
            networkCallback?.let {
                val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                connectivityManager.unregisterNetworkCallback(it)
                networkCallback = null
                Log.d(TAG, "Network callback unregistered")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unregister network callback", e)
        }
    }

    private fun ensureServiceRunning() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (!ServiceUtils.isServiceRunning(context, RingMonitoringService::class.java)) {
                    Log.d(TAG, "Service not running, restarting")
                    startRingMonitoringService()

                    // Reschedule alarm
                    scheduleAlarmManager()

                    // Send keep alive
                    kotlinx.coroutines.delay(1000)
                    RingMonitoringService.sendKeepAlive(context)
                } else {
                    // Service is running, just send keep alive
                    RingMonitoringService.sendKeepAlive(context)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error ensuring service is running", e)
            }
        }
    }
}

// Enhanced JobService for better Android 14+ compatibility
class RingMonitoringJobService : JobService() {
    companion object {
        private const val TAG = "RingMonitoringJobService"
    }

    private val scope = CoroutineScope(Dispatchers.IO)

    override fun onStartJob(params: JobParameters?): Boolean {
        Log.d(TAG, "Job started with ID: ${params?.jobId}")

        scope.launch {
            try {
                // Ensure service is running
                if (!ServiceUtils.isServiceRunning(this@RingMonitoringJobService, RingMonitoringService::class.java)) {
                    Log.d(TAG, "Service not running, starting it")
                    RingMonitoringService.startService(this@RingMonitoringJobService)
                } else {
                    // Service is running, send keep alive
                    RingMonitoringService.sendKeepAlive(this@RingMonitoringJobService)
                }

                // Reschedule for continuous operation
                rescheduleJob()

            } catch (e: Exception) {
                Log.e(TAG, "Error in job execution", e)
            } finally {
                jobFinished(params, false)
            }
        }

        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        Log.d(TAG, "Job stopped with ID: ${params?.jobId}")
        return false // Don't reschedule if stopped by system
    }

    private fun rescheduleJob() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val componentName = ComponentName(this, RingMonitoringJobService::class.java)
                val jobInfo = JobInfo.Builder(1001, componentName)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setMinimumLatency(15 * 1000) // 15 seconds for Android 14+
                    .setOverrideDeadline(45 * 1000) // 45 seconds maximum
                    .setPersisted(true)
                    .apply {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                            setExpedited(true)
                        }
                    }
                    .build()

                val jobScheduler = getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
                jobScheduler.schedule(jobInfo)
                Log.d(TAG, "Job rescheduled")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to reschedule job", e)
        }
    }
}

// New JobService specifically for alarm-triggered jobs
class AlarmTriggeredJobService : JobService() {
    companion object {
        private const val TAG = "AlarmTriggeredJobService"
    }

    override fun onStartJob(params: JobParameters?): Boolean {
        Log.d(TAG, "Alarm-triggered job started")

        CoroutineScope(Dispatchers.IO).launch {
            try {
                RingMonitoringService.sendKeepAlive(this@AlarmTriggeredJobService)
            } catch (e: Exception) {
                Log.e(TAG, "Error in alarm-triggered job", e)
            } finally {
                jobFinished(params, false)
            }
        }

        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        Log.d(TAG, "Alarm-triggered job stopped")
        return false
    }
}

// Enhanced Workers
class RingMonitoringWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "RingMonitoringWorker"
    }

    override suspend fun doWork(): Result {
        return try {
            Log.d(TAG, "WorkManager worker executing")

            val serviceInfo = ServiceUtils.getServiceInfo(applicationContext, RingMonitoringService::class.java)

            if (serviceInfo != null) {
                Log.d(TAG, "Service is running since ${serviceInfo.activeSince}ms ago")
                RingMonitoringService.sendKeepAlive(applicationContext)
            } else {
                Log.d(TAG, "Service not running, starting from WorkManager")
                RingMonitoringService.startService(applicationContext)

                // Wait and send keep alive
                kotlinx.coroutines.delay(2000)
                RingMonitoringService.sendKeepAlive(applicationContext)
            }

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "WorkManager execution failed", e)
            Result.retry()
        }
    }
}

class ServiceRestartWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "ServiceRestartWorker"
    }

    override suspend fun doWork(): Result {
        return try {
            Log.d(TAG, "Periodic service restart worker executing")

            // For Android 14+, be more gentle with restarts
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // Just send keep alive instead of full restart
                RingMonitoringService.sendKeepAlive(applicationContext)

                // Only restart if service is truly not running
                if (!ServiceUtils.isServiceRunning(applicationContext, RingMonitoringService::class.java)) {
                    RingMonitoringService.startService(applicationContext)
                }
            } else {
                // Full restart for older versions
                RingMonitoringService.stopService(applicationContext)
                kotlinx.coroutines.delay(2000)
                RingMonitoringService.startService(applicationContext)
            }

            Log.d(TAG, "Service maintenance completed")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Service restart failed", e)
            Result.retry()
        }
    }
}

class AlarmTriggeredWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "AlarmTriggeredWorker"
    }

    override suspend fun doWork(): Result {
        return try {
            Log.d(TAG, "Alarm-triggered worker executing")

            // Ensure service is running
            if (!ServiceUtils.isServiceRunning(applicationContext, RingMonitoringService::class.java)) {
                RingMonitoringService.startService(applicationContext)
            } else {
                RingMonitoringService.sendKeepAlive(applicationContext)
            }

            // Reschedule alarm
            RingMonitoringManager.getInstance(applicationContext).scheduleAlarmManager()

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Alarm-triggered worker failed", e)
            Result.retry()
        }
    }
}

// Alarm receiver for Android 14+ compatibility
class AlarmReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "AlarmReceiver"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null) return

        Log.d(TAG, "Alarm received: ${intent?.action}")

        try {
            // Trigger alarm-triggered worker
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val workRequest = OneTimeWorkRequestBuilder<AlarmTriggeredWorker>()
                .setConstraints(constraints)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()

            WorkManager.getInstance(context).enqueue(workRequest)

            RingMonitoringManager.getInstance(context).scheduleAlarmManager()

        } catch (e: Exception) {
            Log.e(TAG, "Error processing alarm", e)
        }
    }
}