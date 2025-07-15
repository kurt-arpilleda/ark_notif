package com.example.ark_notif

// Add these imports to your existing imports
import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VolumeDown
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
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
import kotlin.math.roundToInt
import android.content.ContentValues
import android.database.Cursor
import android.provider.MediaStore
import androidx.compose.material.icons.filled.Add
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

class MainActivity : ComponentActivity() {
    private val coroutineScope = CoroutineScope(Dispatchers.Main)
    private lateinit var appUpdateService: AppUpdateService
    private var connectivityReceiver: NetworkUtils.ConnectivityReceiver? = null
    private lateinit var ringMonitoringManager: RingMonitoringManager

    companion object {
        private const val REQUEST_OVERLAY_PERMISSION = 101
        private const val REQUEST_BATTERY_OPTIMIZATION = 102
        private const val REQUEST_UNKNOWN_APP_SOURCES = 103
        private const val RINGTONE_PREF_KEY = "selected_ringtone_uri"
        private const val RINGTONE_NAME_PREF_KEY = "selected_ringtone_name"
    }
    private fun saveSelectedRingtone(uri: String, name: String) {
        getSharedPreferences("AppPrefs", Context.MODE_PRIVATE).edit {
            putString(RINGTONE_PREF_KEY, uri)
            putString(RINGTONE_NAME_PREF_KEY, name)
        }
    }

    private fun getSavedRingtoneUri(): String? {
        return getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
            .getString(RINGTONE_PREF_KEY, null)
    }

    private fun getSavedRingtoneName(): String? {
        return getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
            .getString(RINGTONE_NAME_PREF_KEY, null)
    }
    private val selectAudioLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { audioUri ->
            handleSelectedAudio(audioUri)
        }
    }
    private fun handleSelectedAudio(uri: Uri) {
        try {
            val inputStream: InputStream? = contentResolver.openInputStream(uri)
            inputStream?.let { stream ->
                // Get original filename
                val originalName = getFileNameFromUri(uri) ?: "custom_ringtone"
                val fileName = if (originalName.contains('.')) {
                    originalName
                } else {
                    "$originalName.mp3"
                }

                // Create ringtones directory if it doesn't exist
                val ringtonesDir = File(filesDir, "ringtones")
                if (!ringtonesDir.exists()) {
                    ringtonesDir.mkdirs()
                }

                // Create the file
                val file = File(ringtonesDir, fileName)
                val outputStream = FileOutputStream(file)

                // Copy the file
                stream.copyTo(outputStream)
                stream.close()
                outputStream.close()

                // Add to media store as alarm ringtone
                addToMediaStore(file, true)

                // Refresh media store to make it appear immediately
                sendBroadcast(Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(file)))

                Toast.makeText(this, "Alarm ringtone added successfully", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error handling selected audio", e)
            Toast.makeText(this, "Error adding ringtone: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    private fun getFileNameFromUri(uri: Uri): String? {
        var result: String? = null
        if (uri.scheme == "content") {
            val cursor: Cursor? = contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    result = it.getString(it.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME))
                }
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/')
            if (cut != -1) {
                result = result?.substring(cut!! + 1)
            }
        }
        return result
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
    private fun checkWriteSettingsPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.System.canWrite(this)) {
                val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS)
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        checkWriteSettingsPermission()
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
    @Composable
    fun getTranslatedText(englishText: String, japaneseText: String): String {
        val context = LocalContext.current
        val currentLanguage = remember {
            context.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
                .getString("languageFlag", "en") ?: "en"
        }
        return if (currentLanguage == "ja") japaneseText else englishText
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

        var currentLanguage by remember {
            mutableStateOf(
                if (countryCode == "jp") {
                    prefs.getString("languageFlagJP", "en") ?: "en"
                } else {
                    prefs.getString("languageFlag", "en") ?: "en"
                }
            )
        }
        var employeeData by remember { mutableStateOf<EmployeeData?>(null) }
        var isLoading by remember { mutableStateOf(true) }
        var errorMessage by remember { mutableStateOf<String?>(null) }

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
                                // Save to appropriate key based on country
                                if (countryCode == "jp") {
                                    prefs.edit().putString("languageFlagJP", lang).apply()
                                } else {
                                    prefs.edit().putString("languageFlag", lang).apply()
                                }
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

            // Save to appropriate key based on country
            if (countryCode == "jp") {
                editor.putString("languageFlagJP", language)
            } else {
                editor.putString("languageFlag", language)
            }
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

        val title = getTranslatedText(
            "Notification Service",
            "通知サービス"
        )
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
                                    modifier = Modifier.fillMaxWidth()
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

                                    Spacer(modifier = Modifier.width(5.dp))

                                    Text(
                                        text = title,
                                        style = MaterialTheme.typography.titleMedium.copy(
                                            fontSize = 27.sp,
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
                                    InstructionDialog(
                                        currentLanguage = currentLanguage,
                                        onDismiss = { showInstruction = false }
                                    )
                                }

                                Column(
                                    modifier = Modifier.fillMaxSize(),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    RingStatusView(
                                        countryCode = countryCode,
                                        currentLanguage = currentLanguage
                                    )
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
        connectivityReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (e: IllegalArgumentException) {
                // Receiver was not registered, ignore
            }
        }

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
        connectivityReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (e: IllegalArgumentException) {
                // Receiver was not registered, ignore
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

    @Composable
    fun InstructionDialog(
        currentLanguage: String,
        onDismiss: () -> Unit
    ) {
        fun getTranslatedText(englishText: String, japaneseText: String): String {
            return if (currentLanguage == "ja") japaneseText else englishText
        }

        AlertDialog(
            onDismissRequest = {},
            title = {
                Text(
                    text = getTranslatedText(
                        "Important Setup",
                        "重要な設定"
                    ),
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            text = {
                Column {
                    Text(
                        text = getTranslatedText(
                            """
                        Please do the following in your device settings:
                        - Enable Auto Start or App Launch for this app.
                        - Disable or do not restrict this app in the power saving management.
                        """.trimIndent(),
                            """
                        次の設定を端末の設定画面で行ってください:
                        - このアプリの自動起動（またはアプリ起動）を有効にしてください。
                        - 電池節約機能でこのアプリを制限しないでください。
                        """.trimIndent()
                        ),
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
                    Text(getTranslatedText("OK", "了解"))
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
    fun RingStatusView(
        countryCode: String,
        currentLanguage: String
    ) {
        val context = LocalContext.current
        // Audio manager for volume control
        val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }

        // Volume state
        var currentVolume by remember {
            mutableStateOf(audioManager.getStreamVolume(AudioManager.STREAM_ALARM))
        }
        val maxVolume = remember { audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM) }

        // Ringtone states
        var showRingtoneDialog by remember { mutableStateOf(false) }
        var currentRingtone by remember {
            mutableStateOf(
                getSavedRingtoneUri() ?:
                RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_ALARM)?.toString() ?: ""
            )
        }
        var currentRingtoneName by remember {
            mutableStateOf(
                getSavedRingtoneName() ?:
                getRingtoneName(context, currentRingtone)
            )
        }
        fun getTranslatedText(englishText: String, japaneseText: String): String {
            return if (currentLanguage == "ja") japaneseText else englishText
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Alarm Icon
            Icon(
                imageVector = Icons.Default.Alarm,
                contentDescription = getTranslatedText("Alarm", "アラーム"),
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Current Volume Level Text
            Text(
                text = getTranslatedText("Volume", "音量"),
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = FontWeight.Bold
                ),
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "$currentVolume / $maxVolume",
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Medium
                ),
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Modern Volume Control Slider
            VolumeSlider(
                volume = currentVolume,
                maxVolume = maxVolume,
                currentLanguage = currentLanguage,
                onVolumeChange = { newVolume ->
                    currentVolume = newVolume
                    audioManager.setStreamVolume(AudioManager.STREAM_ALARM, newVolume, 0)
                }
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Ringtone Selection
            Text(
                text = getTranslatedText("Ringtone", "着信音"),
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold
                ),
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Ringtone Dropdown
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showRingtoneDialog = true },
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = getTranslatedText("Current Ringtone", "現在の着信音"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = currentRingtoneName,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Icon(
                        imageVector = Icons.Default.ArrowDropDown,
                        contentDescription = getTranslatedText("Select Ringtone", "着信音を選択"),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Ringtone Selection Dialog
            if (showRingtoneDialog) {
                RingtoneSelectionDialog(
                    context = context,
                    currentRingtone = currentRingtone,
                    currentLanguage = currentLanguage,
                    onRingtoneSelected = { uri, name ->
                        currentRingtone = uri
                        currentRingtoneName = name
                        // Save to SharedPreferences
                        saveSelectedRingtone(uri, name)
                        // Set as default alarm ringtone
                        RingtoneManager.setActualDefaultRingtoneUri(
                            context,
                            RingtoneManager.TYPE_ALARM,
                            Uri.parse(uri)
                        )
                        showRingtoneDialog = false
                    },
                    onDismiss = { showRingtoneDialog = false },
                    countryCode = countryCode
                )
            }
        }
    }

    @Composable
    fun VolumeSlider(
        volume: Int,
        maxVolume: Int,
        currentLanguage: String,
        onVolumeChange: (Int) -> Unit
    ) {
        var sliderPosition by remember { mutableStateOf(volume.toFloat()) }

        LaunchedEffect(volume) {
            sliderPosition = volume.toFloat()
        }

        fun getTranslatedText(englishText: String, japaneseText: String): String {
            return if (currentLanguage == "ja") japaneseText else englishText
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Volume Icons Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = {
                        if (volume > 0) {
                            onVolumeChange(volume - 1)
                        }
                    }
                ) {
                    Icon(
                        imageVector = Icons.Default.VolumeDown,
                        contentDescription = getTranslatedText("Volume Down", "音量を下げる"),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }

                // Custom Modern Slider
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .padding(horizontal = 16.dp)
                ) {
                    ModernVolumeSlider(
                        value = sliderPosition,
                        onValueChange = {
                            sliderPosition = it
                            onVolumeChange(it.roundToInt())
                        },
                        valueRange = 0f..maxVolume.toFloat(),
                        modifier = Modifier.fillMaxSize()
                    )
                }

                IconButton(
                    onClick = {
                        if (volume < maxVolume) {
                            onVolumeChange(volume + 1)
                        }
                    }
                ) {
                    Icon(
                        imageVector = Icons.Default.VolumeUp,
                        contentDescription = getTranslatedText("Volume Up", "音量を上げる"),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // Volume percentage
            Text(
                text = "${(volume * 100 / maxVolume)}%",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }


    @Composable
    fun ModernVolumeSlider(
        value: Float,
        onValueChange: (Float) -> Unit,
        valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
        modifier: Modifier = Modifier
    ) {
        var isDragging by remember { mutableStateOf(false) }
        val trackColor = MaterialTheme.colorScheme.outline
        val activeTrackColor = MaterialTheme.colorScheme.primary
        val thumbColor = MaterialTheme.colorScheme.primary

        Canvas(
            modifier = modifier
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { isDragging = true },
                        onDragEnd = { isDragging = false }
                    ) { _, _ ->
                        // Handle drag if needed
                    }
                }
                .pointerInput(Unit) {
                    detectDragGestures { change, _ ->
                        val newValue = (change.position.x / size.width) * (valueRange.endInclusive - valueRange.start) + valueRange.start
                        onValueChange(newValue.coerceIn(valueRange))
                    }
                }
        ) {
            val trackHeight = 8.dp.toPx()
            val thumbRadius = 12.dp.toPx()
            val trackY = size.height / 2

            val normalizedValue = (value - valueRange.start) / (valueRange.endInclusive - valueRange.start)
            val thumbX = normalizedValue * size.width

            // Draw track
            drawLine(
                color = trackColor,
                start = Offset(0f, trackY),
                end = Offset(size.width, trackY),
                strokeWidth = trackHeight,
                cap = StrokeCap.Round
            )

            // Draw active track
            drawLine(
                color = activeTrackColor,
                start = Offset(0f, trackY),
                end = Offset(thumbX, trackY),
                strokeWidth = trackHeight,
                cap = StrokeCap.Round
            )

            // Draw thumb
            drawCircle(
                color = thumbColor,
                radius = if (isDragging) thumbRadius * 1.2f else thumbRadius,
                center = Offset(thumbX, trackY)
            )
        }
    }

    private fun addToMediaStore(file: File, isAlarm: Boolean = false) {
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DATA, file.absolutePath)
            put(MediaStore.Audio.Media.TITLE, file.nameWithoutExtension)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg")
            put(MediaStore.Audio.Media.IS_ALARM, isAlarm) // Mark specifically as alarm tone
            put(MediaStore.Audio.Media.IS_RINGTONE, false)
            put(MediaStore.Audio.Media.IS_NOTIFICATION, false)
            put(MediaStore.Audio.Media.IS_MUSIC, false)
        }

        try {
            val uri = contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)

            // For Android Q and above, we need to set the ringtone type explicitly
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && isAlarm) {
                val alarmValues = ContentValues().apply {
                    put(MediaStore.Audio.Media.IS_ALARM, true)
                }
                uri?.let {
                    contentResolver.update(it, alarmValues, null, null)
                }
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error adding to media store", e)
        }
    }

    fun getCustomRingtones(context: Context): List<RingtoneInfo> {
        val customRingtones = mutableListOf<RingtoneInfo>()
        val ringtonesDir = File(context.filesDir, "ringtones")

        if (ringtonesDir.exists()) {
            ringtonesDir.listFiles()?.forEach { file ->
                if (file.isFile && (file.extension.equals("mp3", ignoreCase = true) ||
                            file.extension.equals("wav", ignoreCase = true) ||
                            file.extension.equals("m4a", ignoreCase = true))) {
                    val uri = Uri.fromFile(file).toString()
                    customRingtones.add(RingtoneInfo(file.nameWithoutExtension, uri))
                }
            }
        }

        return customRingtones
    }
    private fun isAlarmRingtone(context: Context, uri: Uri): Boolean {
        val projection = arrayOf(MediaStore.Audio.Media.IS_ALARM)
        val cursor = context.contentResolver.query(uri, projection, null, null, null)

        cursor?.use {
            if (it.moveToFirst()) {
                val isAlarm = it.getInt(it.getColumnIndexOrThrow(MediaStore.Audio.Media.IS_ALARM))
                return isAlarm == 1
            }
        }

        return false
    }
    @Composable
    fun RingtoneSelectionDialog(
        context: Context,
        currentRingtone: String,
        currentLanguage: String,
        onRingtoneSelected: (String, String) -> Unit,
        onDismiss: () -> Unit,
        countryCode: String,
    ) {
        val ringtones = remember { getRingtones(context) }
        var selectedRingtone by remember { mutableStateOf(currentRingtone) }
        var selectedRingtoneName by remember {
            mutableStateOf(getRingtoneName(context, currentRingtone))
        }
        val allRingtones = remember { getRingtones(context) }
        val alarmRingtones = remember { allRingtones.filter { isAlarmRingtone(context, Uri.parse(it.uri)) } }
        val otherRingtones = remember { allRingtones.filterNot { isAlarmRingtone(context, Uri.parse(it.uri)) } }
        var currentPlayingRingtone by remember { mutableStateOf<Ringtone?>(null) }
        val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }

        fun getTranslatedText(englishText: String, japaneseText: String): String {
            return if (currentLanguage == "ja") japaneseText else englishText
        }

        fun playRingtone(uri: String) {
            currentPlayingRingtone?.stop()
            val ringtone = RingtoneManager.getRingtone(context, Uri.parse(uri))
            ringtone.streamType = AudioManager.STREAM_ALARM
            ringtone.play()
            currentPlayingRingtone = ringtone
        }

        Dialog(onDismissRequest = {
            currentPlayingRingtone?.stop()
            onDismiss()
        }) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(700.dp)
                    .padding(16.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = getTranslatedText("Select Ringtone", "着信音を選択"),
                            style = MaterialTheme.typography.headlineSmall.copy(
                                fontWeight = FontWeight.Bold
                            ),
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        // Add Custom Ringtone Button
                        IconButton(
                            onClick = {
                                (context as MainActivity).selectAudioLauncher.launch("audio/*")
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = getTranslatedText("Add Custom Ringtone", "カスタム着信音を追加"),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    LazyColumn(
                        modifier = Modifier.weight(1f)
                    ) {
                        item {
                            Text(
                                text = getTranslatedText("Alarm Tones", "アラーム音"),
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(16.dp, 8.dp)
                            )
                        }

                        items(alarmRingtones) { ringtone ->
                            RingtoneItem(
                                ringtone = ringtone,
                                isSelected = ringtone.uri == selectedRingtone,
                                onClick = {
                                    selectedRingtone = ringtone.uri
                                    selectedRingtoneName = ringtone.name
                                    playRingtone(ringtone.uri)
                                }
                            )
                        }

                        item {
                            Text(
                                text = getTranslatedText("Other Tones", "その他の音"),
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(16.dp, 8.dp)
                            )
                        }

                        items(otherRingtones) { ringtone ->
                            RingtoneItem(
                                ringtone = ringtone,
                                isSelected = ringtone.uri == selectedRingtone,
                                onClick = {
                                    selectedRingtone = ringtone.uri
                                    selectedRingtoneName = ringtone.name
                                    playRingtone(ringtone.uri)
                                }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        TextButton(onClick = {
                            currentPlayingRingtone?.stop()
                            onDismiss()
                        }) {
                            Text(getTranslatedText("Cancel", "キャンセル"))
                        }

                        Button(onClick = {
                            currentPlayingRingtone?.stop()
                            onRingtoneSelected(selectedRingtone, selectedRingtoneName)
                            onDismiss()
                        }) {
                            Text(getTranslatedText("Select", "選択"))
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun RingtoneItem(
        ringtone: RingtoneInfo,
        isSelected: Boolean,
        onClick: () -> Unit
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .clickable { onClick() },
            colors = CardDefaults.cardColors(
                containerColor = if (isSelected)
                    MaterialTheme.colorScheme.primaryContainer
                else
                    MaterialTheme.colorScheme.surface
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = isSelected,
                    onClick = onClick,
                    colors = RadioButtonDefaults.colors(
                        selectedColor = MaterialTheme.colorScheme.primary
                    )
                )

                Spacer(modifier = Modifier.width(12.dp))

                Text(
                    text = ringtone.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (isSelected)
                        MaterialTheme.colorScheme.onPrimaryContainer
                    else
                        MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }

    // Helper data class and functions
    data class RingtoneInfo(
        val name: String,
        val uri: String
    )


    // Replace your existing getRingtones method with this updated version
    fun getRingtones(context: Context): List<RingtoneInfo> {
        val ringtones = mutableListOf<RingtoneInfo>()

        // Add custom ringtones first
        ringtones.addAll(getCustomRingtones(context))

        // Add system ringtones
        val ringtoneManager = RingtoneManager(context)
        ringtoneManager.setType(RingtoneManager.TYPE_ALARM)

        val cursor = ringtoneManager.cursor
        while (cursor.moveToNext()) {
            val title = cursor.getString(RingtoneManager.TITLE_COLUMN_INDEX)
            val uri = ringtoneManager.getRingtoneUri(cursor.position).toString()
            ringtones.add(RingtoneInfo(title, uri))
        }
        cursor.close()

        return ringtones
    }

    fun getRingtoneName(context: Context, uri: String): String {
        return try {
            if (uri.isEmpty()) return "Default"
            val ringtone = RingtoneManager.getRingtone(context, Uri.parse(uri))
            ringtone.getTitle(context) ?: "Unknown"
        } catch (e: Exception) {
            "Default"
        }
    }
}