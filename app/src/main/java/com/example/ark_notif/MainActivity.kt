package com.example.ark_notif

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
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
import android.util.Log
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.compose.rememberAsyncImagePainter
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import com.example.ark_notif.ui.theme.Ark_notifTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeout
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : ComponentActivity() {
    private val coroutineScope = CoroutineScope(Dispatchers.Main)
    private lateinit var appUpdateService: AppUpdateService
    private lateinit var connectivityReceiver: NetworkUtils.ConnectivityReceiver
    private lateinit var ringMonitoringManager: RingMonitoringManager

    companion object {
        private const val REQUEST_OVERLAY_PERMISSION = 101
        private const val REQUEST_BATTERY_OPTIMIZATION = 102
        private const val REQUEST_UNKNOWN_APP_SOURCES = 103
    }

    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Toast.makeText(this, "Notification permission granted", Toast.LENGTH_SHORT).show()
            checkInstallUnknownAppsPermission()
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

            // Observe system dark mode
            val isSystemInDarkTheme = isSystemInDarkTheme()

            Ark_notifTheme(darkTheme = isSystemInDarkTheme) {
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
    }

    private fun isSystemInDarkTheme(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
        } else {
            // For older versions, you might want to use a default or shared preference
            false
        }
    }

    @SuppressLint("HardwareIds")
    private fun retrieveDeviceId(): String {
        return try {
            Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown-device"
        } catch (e: Exception) {
            Log.e("MainActivity", "Error getting device identifier: ${e.message}", e)
            "unknown-device"
        }
    }
    @Composable
    fun rememberUrlWithFallback(primaryUrl: String, fallbackUrl: String): String {
        val cacheKey = "$primaryUrl|$fallbackUrl"

        var currentUrl by remember(cacheKey) { mutableStateOf(primaryUrl) }
        var resolved by remember(cacheKey) { mutableStateOf(false) }

        LaunchedEffect(cacheKey) {
            if (!resolved) {
                val primaryDeferred = async(Dispatchers.IO) { isUrlReachable(primaryUrl) }
                val fallbackDeferred = async(Dispatchers.IO) { isUrlReachable(fallbackUrl) }

                val firstAvailableUrl = select<String?> {
                    primaryDeferred.onAwait { reachable ->
                        if (reachable) primaryUrl else null
                    }
                    fallbackDeferred.onAwait { reachable ->
                        if (reachable) fallbackUrl else null
                    }
                }

                currentUrl = firstAvailableUrl ?: primaryUrl // fallback to primary if none reachable
                resolved = true
            }
        }

        return currentUrl
    }

    suspend fun isUrlReachable(url: String): Boolean {
        return try {
            withTimeout(2000) {
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.requestMethod = "HEAD"
                connection.connectTimeout = 2000
                connection.readTimeout = 2000
                connection.responseCode == HttpURLConnection.HTTP_OK
            }
        } catch (e: Exception) {
            false
        }
    }
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun MainAppContent(countryCode: String) {
        val context = LocalContext.current
        val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
        val scope = rememberCoroutineScope()
        val prefs = context.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val deviceId = remember { retrieveDeviceId() }
        var showInstruction by remember { mutableStateOf(true) }

        var currentLanguage by remember { mutableStateOf(prefs.getString("languageFlag", "en") ?: "en") }
        var employeeData by remember { mutableStateOf<EmployeeData?>(null) }
        var isLoading by remember { mutableStateOf(true) }
        var errorMessage by remember { mutableStateOf<String?>(null) }

        // Fetch profile data
        LaunchedEffect(deviceId, countryCode) {
            isLoading = true
            errorMessage = null

            val apiService = if (countryCode == "jp") {
                RetrofitClientJP.instance
            } else {
                RetrofitClient.instance
            }

            apiService.getProfile(deviceId).enqueue(object : Callback<ProfileResponse> {
                override fun onResponse(call: Call<ProfileResponse>, response: Response<ProfileResponse>) {
                    isLoading = false
                    if (response.isSuccessful && response.body()?.success == true) {
                        employeeData = response.body()?.employee
                        employeeData?.languageFlag?.let { langFlag ->
                            val lang = when (langFlag) {
                                "1" -> "en"
                                "2" -> "ja"
                                else -> "en"
                            }
                            if (lang != currentLanguage) {
                                currentLanguage = lang
                                prefs.edit().putString("languageFlag", lang).apply()
                            }
                        }
                    } else {
                        errorMessage = response.body()?.error ?: "Failed to load profile"
                    }
                }

                override fun onFailure(call: Call<ProfileResponse>, t: Throwable) {
                    isLoading = false
                    errorMessage = t.message ?: "Network error occurred"
                }
            })
        }

        fun updateLanguagePreference(language: String) {
            val languageFlag = when (language) {
                "en" -> "1"
                "ja" -> "2"
                else -> "1"
            }
            val editor = prefs.edit()
            editor.putString("languageFlag", language)
            editor.apply()
            currentLanguage = language

            employeeData?.let { employee ->
                val apiService = if (countryCode == "jp") {
                    RetrofitClientJP.instance
                } else {
                    RetrofitClient.instance
                }

                apiService.updateLanguageFlag(employee.idNumber, languageFlag).enqueue(object : Callback<BasicResponse> {
                    override fun onResponse(call: Call<BasicResponse>, response: Response<BasicResponse>) {
                        if (!response.isSuccessful || response.body()?.success != true) {
                            Log.e("MainActivity", "Failed to update language flag on server: ${response.body()?.error}")
                        }
                    }

                    override fun onFailure(call: Call<BasicResponse>, t: Throwable) {
                        Log.e("MainActivity", "Network error updating language flag", t)
                    }
                })
            }
        }

        fun updateCountryPreference(country: String) {
            val apiService = if (country == "jp") {
                RetrofitClientJP.instance
            } else {
                RetrofitClient.instance
            }
            apiService.getProfile(deviceId).enqueue(object : Callback<ProfileResponse> {
                override fun onResponse(call: Call<ProfileResponse>, response: Response<ProfileResponse>) {
                    if (response.isSuccessful && response.body()?.success == true) {
                        prefs.edit { putString("phorjp", country) }
                        restartActivity()
                    } else {
                        val message = if (country == "jp") {
                            if (currentLanguage == "ja") {
                                "まずアークログジャパンにログインしてください"
                            } else {
                                "Please login first to ark log japan"
                            }
                        } else {
                            if (currentLanguage == "ja") {
                                "まずアークログフィリピンにログインしてください"
                            } else {
                                "Please login first to ark log philippines"
                            }
                        }
                        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                    }
                }

                override fun onFailure(call: Call<ProfileResponse>, t: Throwable) {
                    val message = if (currentLanguage == "ja") {
                        "ネットワークエラーが発生しました"
                    } else {
                        "Network error occurred"
                    }
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                }
            })
        }

        fun getTranslatedText(englishText: String, japaneseText: String): String {
            return if (currentLanguage == "ja") japaneseText else englishText
        }

        LaunchedEffect(Unit) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !Settings.canDrawOverlays(context)) {
                requestOverlayPermission()
            } else {
                checkAndRequestNotificationPermission()
            }
        }

        registerReceiver()

        val imageLoader = ImageLoader.Builder(context)
            .components {
                if (Build.VERSION.SDK_INT >= 28) {
                    add(ImageDecoderDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
            }
            .build()

        val iconRes = when (countryCode) {
            "ph" -> R.drawable.philippinesflag
            "jp" -> R.drawable.japan
            else -> R.drawable.ic_ring_active
        }

        val title = when (countryCode) {
            "ph" -> "Ring Alert Monitoring Service"
            "jp" -> "着信アラート監視サービス"
            else -> "Ring Alert Monitoring Service"
        }

        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ModalDrawerSheet(
                    drawerShape = RectangleShape,
                    modifier = Modifier.width(280.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                    ) {
                        // Header with profile section
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF2053B3))
                        ) {
                            Column(
                                modifier = Modifier
                                    .padding(vertical = 20.dp)
                                    .align(Alignment.Center),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                if (isLoading) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(100.dp),
                                        color = Color.White
                                    )
                                } else if (errorMessage != null) {
                                    Image(
                                        painter = painterResource(id = R.drawable.profile_placeholder),
                                        contentDescription = "Error loading profile",
                                        modifier = Modifier
                                            .size(100.dp)
                                            .clip(CircleShape)
                                            .background(Color.Gray),
                                        contentScale = ContentScale.Crop
                                    )
                                    Text(
                                        text = "Error",
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            color = Color.White
                                        )
                                    )
                                } else {
                                    val imageUrl = employeeData?.picture?.let { picture ->
                                        if (countryCode == "jp") {
                                            rememberUrlWithFallback(
                                                "http://192.168.1.213/V4/11-A%20Employee%20List%20V2/profilepictures/$picture",
                                                "http://220.157.175.232/V4/11-A%20Employee%20List%20V2/profilepictures/$picture"
                                            )
                                        } else {
                                            rememberUrlWithFallback(
                                                "http://192.168.254.163/V4/11-A%20Employee%20List%20V2/profilepictures/$picture",
                                                "http://126.209.7.246/V4/11-A%20Employee%20List%20V2/profilepictures/$picture"
                                            )
                                        }
                                    }

                                    AsyncImage(
                                        model = imageUrl ?: "",
                                        contentDescription = getTranslatedText("Profile Image", "プロフィール画像"),
                                        modifier = Modifier
                                            .size(100.dp)
                                            .clip(CircleShape)
                                            .background(Color.Gray),
                                        contentScale = ContentScale.Crop,
                                        placeholder = painterResource(id = R.drawable.profile_placeholder),
                                        error = painterResource(id = R.drawable.profile_placeholder)
                                    )

                                    Spacer(modifier = Modifier.height(8.dp))

                                    Text(
                                        text = employeeData?.let { "${it.firstName} ${it.surName}" }
                                            ?: getTranslatedText("User Name", "ユーザー名"),
                                        style = MaterialTheme.typography.titleMedium.copy(
                                            color = Color.White,
                                            fontWeight = FontWeight.Bold
                                        )
                                    )

                                    Spacer(modifier = Modifier.height(4.dp))

                                    Text(
                                        text = employeeData?.let { "ID: ${it.idNumber}" }
                                            ?: "ID: unknown",
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            color = Color.White
                                        )
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(20.dp))

                        // Language selection
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = if (currentLanguage == "ja") 35.dp else 16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = getTranslatedText("Language", "言語"),
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    fontWeight = FontWeight.Bold
                                )
                            )

                            Spacer(modifier = Modifier.width(25.dp))
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.clickable {
                                    updateLanguagePreference("en")
                                }
                            ) {
                                Image(
                                    painter = rememberAsyncImagePainter(
                                        R.drawable.americanflag,
                                        imageLoader = imageLoader
                                    ),
                                    contentDescription = getTranslatedText("English", "英語"),
                                    modifier = Modifier.size(40.dp)
                                )
                                if (currentLanguage == "en") {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Box(
                                        modifier = Modifier
                                            .width(40.dp)
                                            .height(2.dp)
                                            .background(Color.Blue)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(30.dp))
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.clickable {
                                    updateLanguagePreference("ja")
                                }
                            ) {
                                Image(
                                    painter = rememberAsyncImagePainter(
                                        R.drawable.japaneseflag,
                                        imageLoader = imageLoader
                                    ),
                                    contentDescription = getTranslatedText("Japanese", "日本語"),
                                    modifier = Modifier.size(40.dp)
                                )
                                if (currentLanguage == "ja") {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Box(
                                        modifier = Modifier
                                            .width(40.dp)
                                            .height(2.dp)
                                            .background(Color.Blue)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(20.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = getTranslatedText("Keyboard", "キーボード"),
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    fontWeight = FontWeight.Bold
                                )
                            )

                            Spacer(modifier = Modifier.width(15.dp))

                            IconButton(
                                onClick = {
                                    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                                    imm.showInputMethodPicker()
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Keyboard,
                                    contentDescription = getTranslatedText("Keyboard", "キーボード"),
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.weight(1f))

                        // Country Section
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = getTranslatedText("Country", "国"),
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    fontWeight = FontWeight.Bold
                                )
                            )

                            Spacer(modifier = Modifier.width(25.dp))

                            // Philippines Flag
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clickable {
                                        if (countryCode != "ph") {
                                            updateCountryPreference("ph")
                                        }
                                    }
                            ) {
                                Image(
                                    painter = rememberAsyncImagePainter(
                                        R.drawable.philippinesflag,
                                        imageLoader = imageLoader
                                    ),
                                    contentDescription = getTranslatedText("Philippines", "フィリピン"),
                                    modifier = Modifier.fillMaxSize()
                                )

                                if (countryCode == "ph") {
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.BottomCenter)
                                            .width(40.dp)
                                            .height(2.dp)
                                            .background(Color.Blue)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(30.dp))
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clickable {
                                        if (countryCode != "jp") {
                                            updateCountryPreference("jp")
                                        }
                                    }
                            ) {
                                Image(
                                    painter = rememberAsyncImagePainter(
                                        R.drawable.japanflag,
                                        imageLoader = imageLoader
                                    ),
                                    contentDescription = getTranslatedText("Japan", "日本"),
                                    modifier = Modifier.fillMaxSize()
                                )

                                if (countryCode == "jp") {
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.BottomCenter)
                                            .width(40.dp)
                                            .height(2.dp)
                                            .background(Color.Blue)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        ) {
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                topBar = {
                    Column(
                        modifier = Modifier
                            .statusBarsPadding()
                            .fillMaxWidth()
                    ) {
                        // Top row with settings and close buttons
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF3452B4))
                                .padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Settings icon in top-left - now opens drawer
                            IconButton(
                                onClick = { scope.launch { drawerState.open() } },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Settings,
                                    contentDescription = getTranslatedText("Settings", "設定"),
                                    tint = Color.White,
                                    modifier = Modifier.size(28.dp)
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .background(Color.Red, shape = CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                IconButton(
                                    onClick = { (context as? Activity)?.let { ActivityCompat.finishAffinity(it) } },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Close,
                                        contentDescription = getTranslatedText("Close App", "アプリを閉じる"),
                                        tint = Color.White,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }
                        }
                        TopAppBar(
                            title = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(end = 30.dp)
                                ) {
                                    Image(
                                        painter = rememberAsyncImagePainter(
                                            if (countryCode == "ph") R.drawable.philippinesflag else R.drawable.japanflag,
                                            imageLoader = imageLoader
                                        ),
                                        contentDescription = if (countryCode == "ph")
                                            getTranslatedText("Philippine Flag", "フィリピン国旗")
                                        else
                                            getTranslatedText("Japan Flag", "日本国旗"),
                                        modifier = Modifier
                                            .size(40.dp)
                                            .clickable {
                                                val newCountry = if (countryCode == "ph") "jp" else "ph"
                                                updateCountryPreference(newCountry)
                                            }
                                    )

                                    Spacer(modifier = Modifier.width(10.dp))

                                    Text(
                                        text = title,
                                        style = MaterialTheme.typography.titleMedium.copy(
                                            fontSize = 30.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White
                                        )
                                    )
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = Color(0xFF3452B4)
                            )
                        )
                    }
                }
            ) { paddingValues ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentAlignment = Alignment.TopCenter
                ) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                            Box(modifier = Modifier.padding(innerPadding)) {
                                if (showInstruction) {
                                    InstructionDialog {
                                        showInstruction = false
                                    }
                                }

                                Column(
                                    modifier = Modifier.fillMaxSize(),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    RingStatusView(countryCode)

                                    Spacer(modifier = Modifier.height(32.dp))

                                    MonitoringControls()

                                    Spacer(modifier = Modifier.height(16.dp))

                                    Button(
                                        onClick = { openBatteryOptimizationSettings() },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.primary,
                                            contentColor = MaterialTheme.colorScheme.onPrimary
                                        )
                                    ) {
                                        Text(getTranslatedText(
                                            "Open Battery Optimization Settings",
                                            "バッテリー最適化設定を開く"
                                        ))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun restartActivity() {
        val intent = Intent(this, MainActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        finish()
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
                ) == PackageManager.PERMISSION_GRANTED -> checkInstallUnknownAppsPermission()

                shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) -> {
                    requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }

                else -> {
                    requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        } else {
            checkInstallUnknownAppsPermission()
        }
    }

    private fun checkInstallUnknownAppsPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!packageManager.canRequestPackageInstalls()) {
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivityForResult(intent, REQUEST_UNKNOWN_APP_SOURCES)
            } else {
                startServicesIfReady()
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

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_OVERLAY_PERMISSION -> {
                if (Settings.canDrawOverlays(this)) {
                    checkAndRequestNotificationPermission()
                } else {
                    Toast.makeText(this, "Overlay permission is required to start the service", Toast.LENGTH_SHORT).show()
                }
            }
            REQUEST_UNKNOWN_APP_SOURCES -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    if (packageManager.canRequestPackageInstalls()) {
                        startServicesIfReady()
                    } else {
                        Toast.makeText(this, "Install Unknown Apps permission is required", Toast.LENGTH_SHORT).show()
                    }
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
                openBatteryOptimizationSettings()
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

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(connectivityReceiver)
        } catch (e: IllegalArgumentException) {
            // Receiver was not registered, ignore
        }
    }

    @Composable
    private fun MonitoringControls() {
        var isMonitoring by remember { mutableStateOf(false) }
        val context = LocalContext.current
        val currentLanguage = remember {
            context.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
                .getString("languageFlag", "en") ?: "en"
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Button(
                onClick = {
                    if (isMonitoring) {
                        ringMonitoringManager.stopMonitoring()
                        Toast.makeText(
                            this@MainActivity,
                            if (currentLanguage == "ja") "監視を停止しました" else "Monitoring stopped",
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        ringMonitoringManager.startMonitoring()
                        Toast.makeText(
                            this@MainActivity,
                            if (currentLanguage == "ja") "監視を開始しました" else "Monitoring started",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    isMonitoring = !isMonitoring
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Text(if (isMonitoring) {
                    if (currentLanguage == "ja") "監視を停止" else "Stop Monitoring"
                } else {
                    if (currentLanguage == "ja") "監視を開始" else "Start Monitoring"
                })
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = if (isMonitoring) {
                    if (currentLanguage == "ja") "ステータス: アクティブ" else "Status: Active"
                } else {
                    if (currentLanguage == "ja") "ステータス: 非アクティブ" else "Status: Inactive"
                },
                fontSize = 16.sp,
                color = if (isMonitoring) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
            )
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

    @Composable
    fun InstructionDialog(onDismiss: () -> Unit) {
        AlertDialog(
            onDismissRequest = {},
            title = {
                Text(
                    text = "Important Setup / 重要な設定",
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            text = {
                Column {
                    Text(
                        text = """
        Please do the following in your device settings:
        - Enable Auto Start or App Launch for this app.
        - Disable or do not restrict this app in the power saving management.

        次の設定を端末の設定画面で行ってください:
        - このアプリの自動起動（またはアプリ起動）を有効にしてください。
        - 電池節約機能でこのアプリを制限しないでください。
    """.trimIndent(),
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Text("OK")
                }
            },
            containerColor = MaterialTheme.colorScheme.surface,
            textContentColor = MaterialTheme.colorScheme.onSurface,
            properties = DialogProperties(
                dismissOnBackPress = false,
                dismissOnClickOutside = false
            )
        )
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
            "ph" -> "Ring Alert Monitoring Service (Arktech Philippines)"
            "jp" -> "着信アラート監視サービス (Arktech Japan)"
            else -> "Ring Alert Monitoring Service"
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
                        currentCountry = if (currentCountry == "ph") "jp" else "ph"
                        prefs.edit { putString("phorjp", currentCountry) }
                    }
            )

            Spacer(modifier = Modifier.height(24.dp))

            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = title,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}