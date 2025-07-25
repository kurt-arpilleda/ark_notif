package com.example.ark_notif

import android.annotation.SuppressLint
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.app.job.JobService
import android.app.job.JobParameters
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle
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
        private const val WORK_NAME = "RingMonitoringWork"
        private const val TAG = "RingMonitoringManager"

        fun getInstance(context: Context): RingMonitoringManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: RingMonitoringManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val jobScheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
    private val workManager = WorkManager.getInstance(context)

    fun startMonitoring() {
        Log.d(TAG, "Starting comprehensive monitoring system")

        startRingMonitoringService()

        scheduleJobScheduler()

        scheduleWorkManager()
        schedulePeriodicRestart()
    }

    fun stopMonitoring() {
        Log.d(TAG, "Stopping monitoring system")

        // Cancel JobScheduler
        jobScheduler.cancel(JOB_ID)

        // Cancel WorkManager
        workManager.cancelUniqueWork(WORK_NAME)
        workManager.cancelUniqueWork("${WORK_NAME}_Restart")

        // Stop the service
        val intent = Intent(context, RingMonitoringService::class.java).apply {
            action = RingMonitoringService.ACTION_STOP_MONITORING
        }
        context.stopService(intent)
    }

    private fun startRingMonitoringService() {
        val intent = Intent(context, RingMonitoringService::class.java).apply {
            action = RingMonitoringService.ACTION_START_MONITORING
            putExtra("restart_from_manager", true)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    private fun scheduleJobScheduler() {
        val componentName = ComponentName(context, RingMonitoringJobService::class.java)
        val jobInfo = JobInfo.Builder(JOB_ID, componentName)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPersisted(true)
            .setRequiresCharging(false)
            .setRequiresDeviceIdle(false)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    setMinimumLatency(4 * 60 * 1000) // 4 minutes minimum
                    setOverrideDeadline(5 * 60 * 1000) // 5 minutes minimum
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
    }

    private fun scheduleWorkManager() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(false)
            .setRequiresCharging(false)
            .setRequiresDeviceIdle(false)
            .setRequiresStorageNotLow(false)
            .build()

        val workRequest = PeriodicWorkRequestBuilder<RingMonitoringWorker>(
            15, TimeUnit.MINUTES, // Repeat every 15 minutes
            5, TimeUnit.MINUTES   // Flex interval of 5 minutes
        )
            .setConstraints(constraints)
            .setInitialDelay(1, TimeUnit.MINUTES)
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

        Log.d(TAG, "WorkManager scheduled")
    }

    private fun schedulePeriodicRestart() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val restartRequest = PeriodicWorkRequestBuilder<ServiceRestartWorker>(
            30, TimeUnit.MINUTES, // Restart every 30 minutes
            10, TimeUnit.MINUTES  // Flex interval
        )
            .setConstraints(constraints)
            .setInitialDelay(5, TimeUnit.MINUTES)
            .build()

        workManager.enqueueUniquePeriodicWork(
            "${WORK_NAME}_Restart",
            ExistingPeriodicWorkPolicy.KEEP,
            restartRequest
        )

        Log.d(TAG, "Periodic restart scheduled")
    }
}

// JobService for API 21+
@SuppressLint("SpecifyJobSchedulerIdRange")
class RingMonitoringJobService : JobService() {
    companion object {
        private const val TAG = "RingMonitoringJobService"
    }

    private val scope = CoroutineScope(Dispatchers.IO)

    override fun onStartJob(params: JobParameters?): Boolean {
        Log.d(TAG, "Job started")

        scope.launch {
            try {
                // Check if service is running, if not start it
                if (!ServiceUtils.isServiceRunning(this@RingMonitoringJobService, RingMonitoringService::class.java)) {
                    Log.d(TAG, "Service not running, starting it")
                    val intent = Intent(this@RingMonitoringJobService, RingMonitoringService::class.java).apply {
                        action = RingMonitoringService.ACTION_START_MONITORING
                        putExtra("restart_from_job", true)
                    }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(intent)
                    } else {
                        startService(intent)
                    }
                }

                // Reschedule the job
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    val componentName = ComponentName(this@RingMonitoringJobService, RingMonitoringJobService::class.java)
                    val jobInfo = JobInfo.Builder(1001, componentName)
                        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                        .setMinimumLatency(4 * 60 * 1000) // 4 minutes minimum
                        .setOverrideDeadline(5 * 60 * 1000) // 5 minutes minimum
                        .setPersisted(true)
                        .build()

                    val jobScheduler = getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
                    jobScheduler.schedule(jobInfo)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error in job execution", e)
            } finally {
                jobFinished(params, false)
            }
        }

        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        Log.d(TAG, "Job stopped")
        return false
    }
}

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

            // Get service info if it's running
            val serviceInfo = ServiceUtils.getServiceInfo(applicationContext, RingMonitoringService::class.java)

            if (serviceInfo != null) {
                Log.d(TAG, "Service is running since ${serviceInfo.activeSince}ms ago")
                Log.d(TAG, "Service process: ${serviceInfo.process}")
                Log.d(TAG, "Service client count: ${serviceInfo.clientCount}")
            } else {
                Log.d(TAG, "Service not running, starting from WorkManager")
                val intent = Intent(applicationContext, RingMonitoringService::class.java).apply {
                    action = RingMonitoringService.ACTION_START_MONITORING
                    putExtra("restart_from_worker", true)
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    applicationContext.startForegroundService(intent)
                } else {
                    applicationContext.startService(intent)
                }
            }

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "WorkManager execution failed", e)
            Result.retry()
        }
    }
}

// Worker for periodic service restart
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

            // Force restart the service to ensure it's fresh
            val stopIntent = Intent(applicationContext, RingMonitoringService::class.java).apply {
                action = RingMonitoringService.ACTION_STOP_MONITORING
            }
            applicationContext.stopService(stopIntent)

            // Wait a moment then restart
            kotlinx.coroutines.delay(2000)

            val startIntent = Intent(applicationContext, RingMonitoringService::class.java).apply {
                action = RingMonitoringService.ACTION_START_MONITORING
                putExtra("periodic_restart", true)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                applicationContext.startForegroundService(startIntent)
            } else {
                applicationContext.startService(startIntent)
            }

            Log.d(TAG, "Service restarted successfully")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Service restart failed", e)
            Result.retry()
        }
    }
}