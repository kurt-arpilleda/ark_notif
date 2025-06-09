package com.example.ark_notif

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.example.ark_notif.ui.theme.Ark_notifTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val coroutineScope = CoroutineScope(Dispatchers.Main)
    private lateinit var appUpdateService: AppUpdateService
    private lateinit var connectivityReceiver: NetworkUtils.ConnectivityReceiver
    private lateinit var ringMonitoringManager: RingMonitoringManager

    companion object {
        private const val REQUEST_OVERLAY_PERMISSION = 101
        private const val REQUEST_BATTERY_OPTIMIZATION = 102
    }

    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Toast.makeText(this, "Notification permission granted", Toast.LENGTH_SHORT).show()
            startServicesIfReady()
        } else {
            Toast.makeText(this, "Notification permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        appUpdateService = AppUpdateService(this)
        ringMonitoringManager = RingMonitoringManager.getInstance(this)

        setContent {
            val context = LocalContext.current
            val prefs = remember { context.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE) }
            var country by remember { mutableStateOf(prefs.getString("phorjp", null)) }

            if (country == null) {
                CountrySelectionDialog { selected ->
                    prefs.edit { putString("phorjp", selected) }
                    country = selected
                }
            } else {
                MainAppContent(country!!)
            }
        }
    }

    @Composable
    fun MainAppContent(countryCode: String) {
        val context = LocalContext.current

        LaunchedEffect(Unit) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !Settings.canDrawOverlays(context)) {
                requestOverlayPermission()
            }
            checkAndRequestNotificationPermission()
        }

        registerReceiver()

        Ark_notifTheme {
            Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                Column(
                    modifier = Modifier
                        .padding(innerPadding)
                        .fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    RingStatusView(countryCode)

                    Spacer(modifier = Modifier.height(32.dp))

                    MonitoringControls()

                    Spacer(modifier = Modifier.height(16.dp))

                    Button(onClick = { openBatteryOptimizationSettings() }) {
                        Text("Open Battery Optimization Settings")
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
                    Text("PH or JP", fontWeight = FontWeight.Bold, fontSize = 20.sp)
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
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)
        )
    }

    private fun registerReceiver() {
        connectivityReceiver = NetworkUtils.ConnectivityReceiver {
            checkForUpdates()
        }
        val filter = IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION)
        registerReceiver(connectivityReceiver, filter)
    }

    private fun checkForUpdates() {
        coroutineScope.launch {
            if (NetworkUtils.isNetworkAvailable(this@MainActivity)) {
                appUpdateService.checkForAppUpdate()
            }
        }
    }

    private fun checkAndRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when {
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED -> startServicesIfReady()

                shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) -> {
                    Toast.makeText(
                        this,
                        "Notification permission is needed to alert you with ring notifications.",
                        Toast.LENGTH_LONG
                    ).show()
                    requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }

                else -> {
                    requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        } else {
            startServicesIfReady()
        }
    }

    private fun startServicesIfReady() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || Settings.canDrawOverlays(this)) {
            ringMonitoringManager.startMonitoring()
            Toast.makeText(this, "Monitoring system started", Toast.LENGTH_SHORT).show()
            checkBatteryOptimization()
        } else {
            Toast.makeText(this, "Overlay permission required to start the service", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(connectivityReceiver)
        } catch (e: IllegalArgumentException) {
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_OVERLAY_PERMISSION -> {
                if (Settings.canDrawOverlays(this)) {
                    Toast.makeText(this, "Overlay permission granted!", Toast.LENGTH_SHORT).show()
                    checkBatteryOptimization()
                    startServicesIfReady()
                } else {
                    Toast.makeText(this, "Overlay permission is required to start the service", Toast.LENGTH_SHORT).show()
                }
            }
            REQUEST_BATTERY_OPTIMIZATION -> {
                if (isBatteryOptimizationDisabled()) {
                    Toast.makeText(this, "Battery optimization disabled - better performance", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun checkBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!isBatteryOptimizationDisabled()) {
                showBatteryOptimizationDialog()
            }
        }
    }

    private fun isBatteryOptimizationDisabled(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(PowerManager::class.java)
            return powerManager.isIgnoringBatteryOptimizations(packageName)
        }
        return true
    }

    private fun showBatteryOptimizationDialog() {
        Toast.makeText(
            this,
            "Please disable battery optimization for better performance.",
            Toast.LENGTH_LONG
        ).show()
        openBatteryOptimizationSettings()
    }

    private fun openBatteryOptimizationSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:$packageName"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivityForResult(intent, REQUEST_BATTERY_OPTIMIZATION)
        } else {
            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            startActivity(intent)
        }
    }

    private fun requestOverlayPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        startActivityForResult(intent, REQUEST_OVERLAY_PERMISSION)
    }

    @Composable
    private fun MonitoringControls() {
        var isMonitoring by remember { mutableStateOf(false) }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Button(
                onClick = {
                    if (isMonitoring) {
                        ringMonitoringManager.stopMonitoring()
                        Toast.makeText(this@MainActivity, "Monitoring stopped", Toast.LENGTH_SHORT).show()
                    } else {
                        ringMonitoringManager.startMonitoring()
                        Toast.makeText(this@MainActivity, "Monitoring started", Toast.LENGTH_SHORT).show()
                    }
                    isMonitoring = !isMonitoring
                }
            ) {
                Text(if (isMonitoring) "Stop Monitoring" else "Start Monitoring")
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = if (isMonitoring) "Status: Active" else "Status: Inactive",
                fontSize = 16.sp,
                color = if (isMonitoring) Color.Green else Color.Red
            )
        }
    }

    @Composable
    fun RingStatusView(countryCode: String) {
        val context = LocalContext.current
        val prefs = remember { context.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE) }
        var currentCountry by remember { mutableStateOf(countryCode) }

        val iconRes = when (currentCountry) {
            "ph" -> R.drawable.philippinesflag
            "jp" -> R.drawable.japan
            else -> R.drawable.ic_ring_active
        }

        val title = when (currentCountry) {
            "ph" -> "NG Ring Monitoring Service"
            "jp" -> "NG リング監視サービス"
            else -> "NG Ring Monitoring Service"
        }

        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                painter = painterResource(id = iconRes),
                contentDescription = "Monitoring Status",
                tint = Color.Unspecified,
                modifier = Modifier
                    .size(120.dp)
                    .clickable {
                        // Toggle value and save to SharedPreferences
                        currentCountry = if (currentCountry == "ph") "jp" else "ph"
                        prefs.edit { putString("phorjp", currentCountry) }
                    }
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = title,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Black
            )
        }
    }
}
