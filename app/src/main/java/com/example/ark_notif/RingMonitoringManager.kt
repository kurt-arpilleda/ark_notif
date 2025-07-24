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
        startRingMonitoringService()
        scheduleJobScheduler()
        scheduleWorkManager()
    }
    private fun startRingMonitoringService() {
        val intent = Intent(context, RingMonitoringService::class.java).apply {
            action = RingMonitoringService.ACTION_START_MONITORING
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
            .setPersisted(true)
            .setMinimumLatency(30 * 60 * 1000)
            .setOverrideDeadline(35 * 60 * 1000)
            .build()
        jobScheduler.schedule(jobInfo)
    }

    private fun scheduleWorkManager() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED)
            .setRequiresBatteryNotLow(true)
            .build()

        val workRequest = PeriodicWorkRequestBuilder<RingMonitoringWorker>(
            30, TimeUnit.MINUTES,
            5, TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .setInitialDelay(10, TimeUnit.MINUTES)
            .build()

        workManager.enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            workRequest
        )
    }
}

class RingMonitoringJobService : JobService() {
    private val scope = CoroutineScope(Dispatchers.IO)

    override fun onStartJob(params: JobParameters?): Boolean {
        scope.launch {
            try {
                if (!ServiceUtils.isServiceRunning(this@RingMonitoringJobService, RingMonitoringService::class.java)) {
                    val intent = Intent(this@RingMonitoringJobService, RingMonitoringService::class.java).apply {
                        action = RingMonitoringService.ACTION_START_MONITORING
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(intent)
                    } else {
                        startService(intent)
                    }
                }
            } finally {
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters?) = false
}

class RingMonitoringWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        return try {
            if (!ServiceUtils.isServiceRunning(applicationContext, RingMonitoringService::class.java)) {
                val intent = Intent(applicationContext, RingMonitoringService::class.java).apply {
                    action = RingMonitoringService.ACTION_START_MONITORING
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    applicationContext.startForegroundService(intent)
                } else {
                    applicationContext.startService(intent)
                }
            }
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}