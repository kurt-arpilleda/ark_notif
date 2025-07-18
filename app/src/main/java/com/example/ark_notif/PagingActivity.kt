package com.example.ark_notif

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import coil.ImageLoader
import coil.compose.rememberAsyncImagePainter
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import kotlinx.coroutines.launch
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.runtime.saveable.rememberSaveable
import coil.compose.AsyncImage
import coil.request.ImageRequest
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.*
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeout
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.runtime.Composable
import java.text.SimpleDateFormat
import java.util.Locale

class PagingActivity : ComponentActivity() {
    private var loadingCountry by mutableStateOf<String?>(null)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val imageLoader = ImageLoader.Builder(this)
                .components {
                    if (android.os.Build.VERSION.SDK_INT >= 28) {
                        add(ImageDecoderDecoder.Factory())
                    } else {
                        add(GifDecoder.Factory())
                    }
                }
                .build()

            PagingScreen(imageLoader)
        }
    }

    @SuppressLint("HardwareIds")
    private fun retrieveDeviceId(): String {
        return try {
            Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown-device"
        } catch (e: Exception) {
            Log.e("PagingActivity", "Error getting device identifier: ${e.message}", e)
            "unknown-device"
        }
    }


    @Composable
    fun PagingPostItem(
        post: PagingPost,
        imageLoader: ImageLoader,
        currentLanguage: String,
        onAcknowledge: (Int, Int, Int) -> Unit,
        phOrJp: String
    ) {
        var selectedReply by remember { mutableStateOf(1) }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            shape = MaterialTheme.shapes.medium,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                // Requester profile with date (unchanged)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Start
                ) {
                    AsyncImage(
                        model =
                            rememberUrlWithFallback(
                                "http://192.168.254.163/V4/11-A%20Employee%20List%20V2/profilepictures/${post.picture}",
                                "http://126.209.7.246/V4/11-A%20Employee%20List%20V2/profilepictures/${post.picture}"
                            ),
                        contentDescription = if (currentLanguage == "ja") "リクエスト者のプロフィール" else "Requester Profile",
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape),
                        placeholder = painterResource(id = R.drawable.profile_placeholder),
                        error = painterResource(id = R.drawable.profile_placeholder),
                        imageLoader = imageLoader
                    )
                    Spacer(modifier = Modifier.width(12.dp))

                    Column {
                        Text(
                            text = "${post.firstName} ${post.surName}",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                        )

                        Text(
                            text = formatDateTime(post.dateTime),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Location text
                Text(
                    text = if (post.location == 8 || post.location == 9) {
                        post.locationText
                    } else {
                        if (currentLanguage == "ja") "行き先: ${post.locationText}" else "Go to: ${post.locationText}"
                    },
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontWeight = FontWeight.Bold
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Reply options dropdown
                val replyOptions = if (currentLanguage == "ja") {
                    listOf(
                        "今向かっています" to 1,
                        "会議中で行けません" to 2,
                        "5分後に向かいます" to 3,
                        "社外にいます" to 4
                    )
                } else {
                    listOf(
                        "I am going now" to 1,
                        "I cannot go now I am in a meeting" to 2,
                        "I will go after 5 minutes" to 3,
                        "I am outside the company" to 4
                    )
                }

                // Reply options dropdown (modern square design)
                var expanded by remember { mutableStateOf(false) }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .wrapContentSize(Alignment.TopStart)
                ) {
                    OutlinedButton(
                        onClick = { expanded = true },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RectangleShape, // Square corners
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.onSurface
                        ),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                    ) {
                        Text(
                            text = replyOptions.firstOrNull { it.second == selectedReply }?.first ?: "",
                            modifier = Modifier.weight(1f),
                            textAlign = TextAlign.Start
                        )
                        Icon(
                            imageVector = if (expanded) Icons.Filled.ArrowDropUp else Icons.Filled.ArrowDropDown,
                            contentDescription = null
                        )
                    }

                    DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false },
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface)
                    ) {
                        replyOptions.forEach { (text, value) ->
                            DropdownMenuItem(
                                text = { Text(text) },
                                onClick = {
                                    selectedReply = value
                                    expanded = false
                                },
                                modifier = Modifier.background(MaterialTheme.colorScheme.surface)
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))

                // Send button
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Button(
                        onClick = { onAcknowledge(post.pagingId, selectedReply, post.userIndex) },
                        modifier = Modifier.widthIn(min = 120.dp),
                        shape = MaterialTheme.shapes.large,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF3452B4),
                            contentColor = Color.White
                        )
                    ) {
                        Text(
                            text = if (currentLanguage == "ja") "送信" else "Send",
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
            }
        }
    }

    fun formatDateTime(input: String): String {
        return try {
            val inputFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            val date = inputFormat.parse(input)
            val outputFormat = SimpleDateFormat("MMM d, yyyy hh:mm a", Locale.getDefault())
            outputFormat.format(date ?: input)
        } catch (e: Exception) {
            input // fallback to original if parsing fails
        }
    }
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun PagingScreen(imageLoader: ImageLoader) {
        val context = LocalContext.current
        val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
        val scope = rememberCoroutineScope()
        val prefs = context.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val deviceId = remember { retrieveDeviceId() }

        var currentLanguage by rememberSaveable {
            mutableStateOf(prefs.getString("languageFlag", "en") ?: "en")
        }

        var phOrJp by rememberSaveable {
            mutableStateOf(prefs.getString("phorjp", "ph") ?: "ph")
        }

        // Employee profile state
        var employeeData by remember { mutableStateOf<EmployeeData?>(null) }
        var isLoading by remember { mutableStateOf(true) }
        var errorMessage by remember { mutableStateOf<String?>(null) }

        // Get app version
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        val versionName = packageInfo.versionName

        LaunchedEffect(deviceId) {
            isLoading = true
            errorMessage = null

            val apiService = RetrofitClient.instance

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
                val apiService = RetrofitClient.instance

                apiService.updateLanguageFlag(employee.idNumber, languageFlag).enqueue(object : Callback<BasicResponse> {
                    override fun onResponse(call: Call<BasicResponse>, response: Response<BasicResponse>) {
                        if (!response.isSuccessful || response.body()?.success != true) {
                            Log.e("PagingActivity", "Failed to update language flag on server: ${response.body()?.error}")
                        }
                    }
                    override fun onFailure(call: Call<BasicResponse>, t: Throwable) {
                        Log.e("PagingActivity", "Network error updating language flag", t)
                    }
                })
            }
        }

        fun getTranslatedText(englishText: String, japaneseText: String): String {
            return if (currentLanguage == "ja") japaneseText else englishText
        }

        val titleName = if (currentLanguage == "ja") "ページング通知" else "Paging Notif"

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
                                    // Show loading indicator
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(100.dp),
                                        color = Color.White
                                    )
                                } else if (errorMessage != null) {
                                    // Show error placeholder
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
                                    // Show actual profile
                                    val imageUrl = employeeData?.picture?.let { picture ->
                                            rememberUrlWithFallback(
                                                "http://192.168.254.163/V4/11-A%20Employee%20List%20V2/profilepictures/$picture",
                                                "http://126.209.7.246/V4/11-A%20Employee%20List%20V2/profilepictures/$picture"
                                            )
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

                                    // User name
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

//                        Spacer(modifier = Modifier.height(20.dp))
//                        // Manual Section
//                        Row(
//                            modifier = Modifier
//                                .fillMaxWidth()
//                                .padding(start = if (currentLanguage == "ja") 46.dp else 30.dp),
//                            verticalAlignment = Alignment.CenterVertically
//                        ) {
//                            Text(
//                                text = getTranslatedText("Manual", "手引き"),
//                                style = MaterialTheme.typography.bodyLarge.copy(
//                                    fontWeight = FontWeight.Bold
//                                )
//                            )
//
//                            Spacer(modifier = Modifier.width(15.dp))
//
//                            IconButton(
//                                onClick = { /* TODO: Add manual download functionality */ }
//                            ) {
//                                Icon(
//                                    imageVector = Icons.Default.MenuBook,
//                                    contentDescription = getTranslatedText("Manual", "手引き"),
//                                    modifier = Modifier.size(28.dp)
//                                )
//                            }
//                        }

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
                                    .clickable(enabled = loadingCountry == null) {
                                        if (phOrJp != "ph") {
                                            val editor = prefs.edit()
                                            editor.putString("phorjp", "ph")
                                            editor.apply()
                                            val intent = Intent(context, PagingActivity::class.java)
                                            context.startActivity(intent)
                                            (context as Activity).overridePendingTransition(
                                                R.anim.animate_fade_enter,
                                                R.anim.animate_fade_exit
                                            )
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                if (loadingCountry == "ph") {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        color = Color.Blue,
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Image(
                                        painter = rememberAsyncImagePainter(
                                            R.drawable.philippinesflag,
                                            imageLoader = imageLoader
                                        ),
                                        contentDescription = getTranslatedText("Philippines", "フィリピン"),
                                        modifier = Modifier.fillMaxSize()
                                    )
                                    if (phOrJp == "ph") {
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

                            Spacer(modifier = Modifier.width(30.dp))
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clickable(enabled = loadingCountry == null) {
                                        if (phOrJp != "jp") {
                                            val editor = prefs.edit()
                                            editor.putString("phorjp", "jp")
                                            editor.apply()
                                            val intent = Intent(context, PagingActivityJP::class.java)
                                            context.startActivity(intent)
                                            (context as Activity).overridePendingTransition(
                                                R.anim.animate_fade_enter,
                                                R.anim.animate_fade_exit
                                            )
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                if (loadingCountry == "jp") {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        color = Color.Blue,
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Image(
                                        painter = rememberAsyncImagePainter(
                                            R.drawable.japanflag,
                                            imageLoader = imageLoader
                                        ),
                                        contentDescription = getTranslatedText("Japan", "日本"),
                                        modifier = Modifier.fillMaxSize()
                                    )
                                    if (phOrJp == "jp") {
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
                                            R.drawable.philippinesflag,
                                            imageLoader = imageLoader
                                        ),
                                        contentDescription = getTranslatedText("Philippine Flag", "フィリピン国旗"),
                                        modifier = Modifier
                                            .size(40.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))

                                    // Department Name
                                    Text(
                                        text = titleName,
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
                    var pagingPosts by remember { mutableStateOf<List<PagingPost>>(emptyList()) }
                    var isLoadingPosts by remember { mutableStateOf(true) }
                    var errorLoadingPosts by remember { mutableStateOf<String?>(null) }

                    LaunchedEffect(deviceId, phOrJp, currentLanguage) {
                        isLoadingPosts = true
                        errorLoadingPosts = null

                        val apiService = RetrofitClient.instance

                        fun fetchPagingPosts() {
                            apiService.getPagingPosts(deviceId).enqueue(object : Callback<PagingPostsResponse> {
                                override fun onResponse(
                                    call: Call<PagingPostsResponse>,
                                    response: Response<PagingPostsResponse>
                                ) {
                                    isLoadingPosts = false
                                    if (response.isSuccessful && response.body()?.success == true) {
                                        pagingPosts = response.body()?.posts ?: emptyList()
                                    } else {
                                        errorLoadingPosts = response.body()?.error ?: "Failed to load paging posts"
                                    }
                                }

                                override fun onFailure(call: Call<PagingPostsResponse>, t: Throwable) {
                                    isLoadingPosts = false
                                    errorLoadingPosts = t.message ?: "Network error occurred"
                                }
                            })
                        }

                        fetchPagingPosts()

                        while (true) {
                            delay(5000)
                            fetchPagingPosts()
                        }
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        if (isLoadingPosts) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator()
                            }
                        } else if (errorLoadingPosts != null) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = getTranslatedText(
                                        "Error loading paging posts: $errorLoadingPosts",
                                        "ページング投稿の読み込みエラー: $errorLoadingPosts"
                                    ),
                                    color = Color.Red
                                )
                            }
                        }else if (pagingPosts.isEmpty()) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.Center
                            ) {
                                Image(
                                    painter = painterResource(id = R.drawable.questionman),
                                    contentDescription = getTranslatedText("No Notifications", "通知なし"),
                                    modifier = Modifier.size(150.dp) // Increased from 100dp to 150dp
                                )

                                Spacer(modifier = Modifier.height(24.dp)) // Increased spacing from 16dp to 24dp

                                Text(
                                    text = getTranslatedText(
                                        "No active paging notifications",
                                        "アクティブなページング通知はありません"
                                    ),
                                    style = MaterialTheme.typography.titleLarge.copy( // Changed from bodyMedium to titleLarge
                                        fontWeight = FontWeight.Bold,
                                        color = Color.Gray,
                                        fontSize = 20.sp // Explicitly setting larger font size
                                    ),
                                    textAlign = TextAlign.Center
                                )
                            }
                        } else {
                            // CENTER THE SINGLE ITEM, NORMAL LIST FOR MULTIPLE ITEMS
                            if (pagingPosts.size == 1) {
                                // Single item - center it vertically
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    PagingPostItem(
                                        post = pagingPosts[0],
                                        imageLoader = imageLoader,
                                        currentLanguage = currentLanguage,
                                        onAcknowledge = { pagingId, replyCode, userIndex ->
                                            val apiService = RetrofitClient.instance

                                            apiService.updatePagingStatus(
                                                pagingId,
                                                employeeData?.idNumber ?: "",
                                                replyCode,
                                                userIndex
                                            ).enqueue(
                                                object : Callback<BasicResponse> {
                                                    override fun onResponse(
                                                        call: Call<BasicResponse>,
                                                        response: Response<BasicResponse>
                                                    ) {
                                                        if (!response.isSuccessful || response.body()?.success != true) {
                                                            Log.e("PagingActivity", "Failed to update paging status")
                                                        } else {
                                                            pagingPosts = pagingPosts.filter { it.pagingId != pagingId }
                                                        }
                                                    }

                                                    override fun onFailure(call: Call<BasicResponse>, t: Throwable) {
                                                        Log.e("PagingActivity", "Network error updating paging status", t)
                                                    }
                                                }
                                            )
                                        },
                                        phOrJp = phOrJp
                                    )
                                }
                            } else {
                                // Multiple items - show as normal list
                                LazyColumn(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    items(pagingPosts) { post ->
                                        PagingPostItem(
                                            post = post,
                                            imageLoader = imageLoader,
                                            currentLanguage = currentLanguage,
                                            onAcknowledge = { pagingId, replyCode, userIndex ->
                                                val apiService = RetrofitClient.instance

                                                apiService.updatePagingStatus(
                                                    pagingId,
                                                    employeeData?.idNumber ?: "",
                                                    replyCode,
                                                    userIndex
                                                ).enqueue(
                                                    object : Callback<BasicResponse> {
                                                        override fun onResponse(
                                                            call: Call<BasicResponse>,
                                                            response: Response<BasicResponse>
                                                        ) {
                                                            if (!response.isSuccessful || response.body()?.success != true) {
                                                                Log.e("PagingActivity", "Failed to update paging status")
                                                            } else {
                                                                pagingPosts = pagingPosts.filter { it.pagingId != pagingId }
                                                            }
                                                        }

                                                        override fun onFailure(call: Call<BasicResponse>, t: Throwable) {
                                                            Log.e("PagingActivity", "Network error updating paging status", t)
                                                        }
                                                    }
                                                )
                                            },
                                            phOrJp = phOrJp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
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
}
