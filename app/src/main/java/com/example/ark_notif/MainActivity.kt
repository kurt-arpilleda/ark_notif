package com.example.ark_notif

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sharedPreferences = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val phOrJp = sharedPreferences.getString("phorjp", null)

        when (phOrJp) {
            "ph" -> {
                startActivity(Intent(this, PhilippineActivity::class.java))
            }
            "jp" -> {
                startActivity(Intent(this, JapanActivity::class.java))
            }
            else -> {
                startActivity(Intent(this, PhilippineActivity::class.java))
            }
        }
        finish()
    }
}
