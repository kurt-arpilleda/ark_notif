package com.example.ark_notif

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.annotation.RequiresApi

class BootReceiverKO : BroadcastReceiver() {

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onReceive(context: Context, intent: Intent) {
        if (Intent.ACTION_BOOT_COMPLETED == intent.action) {
            // Check overlay permission for Android 10+ and request if not granted
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !Settings.canDrawOverlays(context)) {
                requestOverlayPermission(context)
            } else {
                startMainActivity(context)
            }
        }
    }

    private fun requestOverlayPermission(context: Context) {
        val overlayIntent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        ).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(overlayIntent)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun startMainActivity(context: Context) {
        val mainActivityIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK // Start the activity outside of the app's normal flow
        }
        context.startActivity(mainActivityIntent)
    }
}
