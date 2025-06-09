package com.example.ark_notif

import android.app.ActivityManager
import android.content.Context
import android.util.Log

object ServiceUtils {
    private const val TAG = "ServiceUtils"

    fun isServiceRunning(context: Context, serviceClass: Class<*>): Boolean {
        return try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val services = activityManager.getRunningServices(Integer.MAX_VALUE)

            for (service in services) {
                if (serviceClass.name == service.service.className) {
                    Log.d(TAG, "Service ${serviceClass.simpleName} is running")
                    return true
                }
            }
            Log.d(TAG, "Service ${serviceClass.simpleName} is not running")
            false
        } catch (e: Exception) {
            Log.e(TAG, "Error checking if service is running", e)
            false
        }
    }

    fun getServiceInfo(context: Context, serviceClass: Class<*>): ActivityManager.RunningServiceInfo? {
        return try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val services = activityManager.getRunningServices(Integer.MAX_VALUE)

            for (service in services) {
                if (serviceClass.name == service.service.className) {
                    return service
                }
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "Error getting service info", e)
            null
        }
    }
}