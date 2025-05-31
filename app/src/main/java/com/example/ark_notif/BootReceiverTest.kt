package com.example.ark_notif

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.annotation.RequiresApi

class BootReceiverTest : BroadcastReceiver() {

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onReceive(context: Context, intent: Intent) {
        Log.d("BootReceiver", "BOOT received")
        val action = intent.action
        if (action == Intent.ACTION_BOOT_COMPLETED || action == "com.example.ark_notif.TEST_BOOT") {
                startMainActivity(context)
            }
    }
    @RequiresApi(Build.VERSION_CODES.O)
    private fun startMainActivity(context: Context) {
        val ringService = Intent(context, RingMonitoringService::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startService(ringService)
    }
}
