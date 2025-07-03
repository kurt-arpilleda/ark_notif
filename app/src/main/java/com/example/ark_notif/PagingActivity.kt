package com.example.ark_notif

import android.app.Activity
import android.content.Context
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

class PagingActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // Create the ImageLoader here to avoid recreating it on recomposition
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

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun PagingScreen(imageLoader: ImageLoader) {
        val context = LocalContext.current
        val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
        val scope = rememberCoroutineScope()
        val prefs = context.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)

        var currentLanguage by remember {
            mutableStateOf(prefs.getString("languageFlag", "en") ?: "en")
        }

        var phOrJp by remember { mutableStateOf(prefs.getString("phorjp", "ph") ?: "ph") }

        // Get app version
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        val versionName = packageInfo.versionName

        fun updateLanguagePreference(language: String) {
            val editor = prefs.edit()
            editor.putString("languageFlag", language)
            editor.apply()
            currentLanguage = language
        }

        fun updateCountryPreference(country: String) {
            val editor = prefs.edit()
            editor.putString("phorjp", country)
            editor.apply()
            phOrJp = country
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
                                Image(
                                    painter = painterResource(id = R.drawable.profile_placeholder),
                                    contentDescription = getTranslatedText("Profile Image", "プロフィール画像"),
                                    modifier = Modifier
                                        .size(100.dp)
                                        .clip(CircleShape)
                                        .background(Color.Gray),
                                    contentScale = ContentScale.Crop
                                )

                                Spacer(modifier = Modifier.height(8.dp))

                                // User name placeholder
                                Text(
                                    text = getTranslatedText("User Name", "ユーザー名"),
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold
                                    )
                                )

                                Spacer(modifier = Modifier.height(4.dp))

                                Text(
                                    text = "ID: 12345",
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        color = Color.White
                                    )
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(20.dp))

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

                        Spacer(modifier = Modifier.height(20.dp))
                        // Manual Section
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = if (currentLanguage == "ja") 46.dp else 30.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = getTranslatedText("Manual", "手引き"),
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    fontWeight = FontWeight.Bold
                                )
                            )

                            Spacer(modifier = Modifier.width(15.dp))

                            IconButton(
                                onClick = { /* TODO: Add manual download functionality */ }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.MenuBook,
                                    contentDescription = getTranslatedText("Manual", "手引き"),
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
                                    .clickable { updateCountryPreference("ph") }
                            ) {
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

                            Spacer(modifier = Modifier.width(30.dp))
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clickable { updateCountryPreference("jp") }
                            ) {
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
                                    // Country Flag Image (changes based on current selection)
                                    Image(
                                        painter = rememberAsyncImagePainter(
                                            if (phOrJp == "ph") R.drawable.philippinesflag else R.drawable.japanflag,
                                            imageLoader = imageLoader
                                        ),
                                        contentDescription = if (phOrJp == "ph")
                                            getTranslatedText("Philippine Flag", "フィリピン国旗")
                                        else
                                            getTranslatedText("Japan Flag", "日本国旗"),
                                        modifier = Modifier
                                            .size(40.dp)
                                            .clickable {
                                                // Toggle between countries
                                                val newCountry = if (phOrJp == "ph") "jp" else "ph"
                                                updateCountryPreference(newCountry)

                                                // TODO: Add navigation to respective country activity
                                                // For now, just showing the toggle functionality
                                            }
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
                // Content area with translated text
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = getTranslatedText("Paging Content Area", "ページングコンテンツエリア"),
                            style = MaterialTheme.typography.bodyLarge,
                            color = Color.Gray
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        Text(
                            text = getTranslatedText(
                                "Current Language: English",
                                "現在の言語: 日本語"
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.Gray
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = getTranslatedText(
                                "Country: ${if (phOrJp == "ph") "Philippines" else "Japan"}",
                                "国: ${if (phOrJp == "ph") "フィリピン" else "日本"}"
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.Gray
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        // Additional info showing flag selection
                        Text(
                            text = getTranslatedText(
                                "Current Flag: ${if (phOrJp == "ph") "🇵🇭 Philippines" else "🇯🇵 Japan"}",
                                "現在の国旗: ${if (phOrJp == "ph") "🇵🇭 フィリピン" else "🇯🇵 日本"}"
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.Gray
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = getTranslatedText(
                                "Tap the flag in the header to switch countries",
                                "ヘッダーの国旗をタップして国を切り替えます"
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 32.dp)
                        )
                    }
                }
            }
        }
    }
}