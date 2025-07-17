package com.example.ark_notif

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sharedPreferences = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val phOrJp = sharedPreferences.getString("phorjp", null)

        when (phOrJp) {
            "ph" -> {
                startActivity(Intent(this, PhilippineActivity::class.java))
                finish()
            }
            "jp" -> {
                startActivity(Intent(this, JapanActivity::class.java))
                finish()
            }
            else -> {
                setContent {
                    CountrySelectionDialog { selectedCountry ->
                        sharedPreferences.edit().putString("phorjp", selectedCountry).apply()

                        val intent = when (selectedCountry) {
                            "ph" -> Intent(this, PhilippineActivity::class.java)
                            "jp" -> Intent(this, JapanActivity::class.java)
                            else -> Intent(this, PhilippineActivity::class.java)
                        }
                        startActivity(intent)
                        finish()
                    }
                }
            }
        }
    }

    @Composable
    fun CountrySelectionDialog(onCountrySelected: (String) -> Unit) {
        AlertDialog(
            onDismissRequest = {},
            title = {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "PH or JP",
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            },
            text = {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.philippinesflag),
                        contentDescription = "Philippines",
                        modifier = Modifier
                            .size(80.dp)
                            .clickable { onCountrySelected("ph") }
                    )
                    Spacer(modifier = Modifier.width(45.dp))
                    Image(
                        painter = painterResource(id = R.drawable.japan),
                        contentDescription = "Japan",
                        modifier = Modifier
                            .size(80.dp)
                            .clickable { onCountrySelected("jp") }
                    )
                }
            },
            confirmButton = {},
            containerColor = MaterialTheme.colorScheme.surface,
            textContentColor = MaterialTheme.colorScheme.onSurface,
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)
        )
    }
}