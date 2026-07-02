package com.example.receiver

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.example.receiver.ui.theme.ReceiverTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ReceiverTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    MainScreen()
                }
            }
        }
    }
}

@Composable
fun MainScreen() {
    var currentTab by remember { mutableIntStateOf(0) }
    
    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    icon = { Icon(Icons.Default.History, contentDescription = null) },
                    label = { Text(stringResource(R.string.section_history)) },
                    selected = currentTab == 0,
                    onClick = { currentTab = 0 }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    label = { Text(stringResource(R.string.settings_title)) },
                    selected = currentTab == 1,
                    onClick = { currentTab = 1 }
                )
            }
        }
    ) { padding ->
        // We don't apply padding here to allow the list to scroll "under" the bar
        if (currentTab == 0) {
            HistoryScreen(padding)
        } else {
            ReceiverScreen(padding)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(navigationPadding: PaddingValues) {
    val context = LocalContext.current
    val repository = remember { HistoryRepository(context) }
    val historyItems by repository.historyItems.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var showClearConfirm by remember { mutableStateOf(false) }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text(stringResource(R.string.clear_history_confirm_title)) },
            text = { Text(stringResource(R.string.clear_history_confirm_msg)) },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch { repository.clearHistory() }
                        showClearConfirm = false
                    }
                ) {
                    Text(stringResource(R.string.btn_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text(stringResource(R.string.btn_cancel))
                }
            }
        )
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.section_history), fontWeight = FontWeight.Medium) },
                actions = {
                    if (historyItems.isNotEmpty()) {
                        IconButton(onClick = { showClearConfirm = true }) {
                            Icon(Icons.Default.Delete, contentDescription = "Clear History")
                        }
                    }
                },
                scrollBehavior = scrollBehavior
            )
        }
    ) { scaffoldPadding ->
        if (historyItems.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(scaffoldPadding)
                    .padding(navigationPadding),
                contentAlignment = Alignment.Center
            ) {
                Text(stringResource(R.string.no_history), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                // Add top bar padding (scaffoldPadding) and nav bar padding (navigationPadding)
                contentPadding = PaddingValues(
                    top = scaffoldPadding.calculateTopPadding() + 16.dp,
                    bottom = navigationPadding.calculateBottomPadding() + 32.dp,
                    start = 16.dp,
                    end = 16.dp
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(historyItems) { item ->
                    HistoryItemCard(item) {
                        val isGoogleMaps = MapsUtils.isGoogleMapsLink(item.url)

                        if (item.url.startsWith("http") || item.url.startsWith("geo:") || isGoogleMaps) {
                            val isLocation = item.url.startsWith("geo:") || isGoogleMaps
                            
                            if (isLocation) {
                                val intent = Intent(context, ChooserActivity::class.java).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    putExtra("url", item.url) 
                                    putExtra("title", item.title) // THE FIX: Pass the title to detect pins
                                }
                                context.startActivity(intent)
                            } else {
                                val intent = Intent(Intent.ACTION_VIEW, item.url.toUri()).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                try {
                                    context.startActivity(intent)
                                } catch (_: Exception) {
                                    copyText(context, item.url)
                                }
                            }
                        } else {
                            copyText(context, item.url)
                        }
                    }
                }
            }
        }
    }
}

private fun copyText(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText("received text", text)
    clipboard.setPrimaryClip(clip)
    Toast.makeText(context, R.string.text_copied_toast, Toast.LENGTH_SHORT).show()
}

@Composable
fun HistoryItemCard(item: HistoryItem, onClick: () -> Unit) {
    val sdf = remember { SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()) }
    val dateStr = remember(item.timestamp) { sdf.format(Date(item.timestamp)) }

    Card(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    text = item.title.ifBlank { "Location/Link" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = dateStr,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = item.url,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 2
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiverScreen(navigationPadding: PaddingValues) {
    val context = LocalContext.current
    val repository = remember { ReceiverRepository(context) }
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    var isBatteryOptimized by remember { mutableStateOf(value = false) }
    var canDrawOverlays by remember { mutableStateOf(value = false) }
    var isServiceRunning by remember { mutableStateOf<Boolean?>(null) } // THE FIX: null means "checking"
    var showGenerateDialog by remember { mutableStateOf(false) }

    fun checkPermissions() {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        isBatteryOptimized = !powerManager.isIgnoringBatteryOptimizations(context.packageName)
        canDrawOverlays = Settings.canDrawOverlays(context)
    }

    fun restartServiceIfRunning() {
        if (isServiceRunning == true) {
            val stopIntent = Intent(context, NtfyListenerService::class.java).apply {
                action = NtfyListenerService.ACTION_STOP
            }
            context.startService(stopIntent)
            
            val startIntent = Intent(context, NtfyListenerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(startIntent)
            } else {
                context.startService(startIntent)
            }
        }
    }

    LaunchedEffect(Unit) {
        while (isActive) {
            isServiceRunning = NtfyListenerService.isRunning(context)
            delay(1000)
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        checkPermissions()
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { _ -> }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                permissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
    
    // Use null as initial value to detect when DataStore has actually finished loading
    val savedTopicNullable by repository.ntfyTopic.collectAsState(initial = null)
    val savedServerNullable by repository.ntfyServer.collectAsState(initial = null)
    val savedKeyNullable by repository.secretKey.collectAsState(initial = null)
    val savedAutoOpenDelayNullable by repository.autoOpenDelay.collectAsState(initial = null)
    
    val savedCopyToClipboard by repository.copyToClipboard.collectAsState(initial = false)
    val savedAutoOpenMapsApp by repository.autoOpenMapsApp.collectAsState(initial = ReceiverRepository.APP_NONE)
    val savedAutoOpenGeoApp by repository.autoOpenGeoApp.collectAsState(initial = ReceiverRepository.APP_NONE)
    
    // THE MASTER FIX: Data is ready only when all essential flows have emitted at least once
    val isSettingsReady = savedTopicNullable != null && savedServerNullable != null && 
                         savedKeyNullable != null && savedAutoOpenDelayNullable != null && isServiceRunning != null

    val savedTopic = savedTopicNullable ?: ""
    val savedServer = savedServerNullable ?: "https://ntfy.sh"
    val savedKey = savedKeyNullable ?: ""
    val savedAutoOpenDelay = savedAutoOpenDelayNullable ?: 5

    var topic by remember(savedTopic) { mutableStateOf(savedTopic) }
    var server by remember(savedServer) { mutableStateOf(savedServer) }
    var secretKey by remember(savedKey) { mutableStateOf(savedKey) }
    var copyToClipboard by remember { mutableStateOf(false) }
    var autoOpenMapsApp by remember { mutableStateOf(ReceiverRepository.APP_NONE) }
    var autoOpenGeoApp by remember { mutableStateOf(ReceiverRepository.APP_NONE) }
    var autoOpenDelay by remember { mutableIntStateOf(5) }
    
    LaunchedEffect(savedTopic) { topic = savedTopic }
    LaunchedEffect(savedServer) { server = savedServer }
    LaunchedEffect(savedKey) { secretKey = savedKey }
    LaunchedEffect(savedCopyToClipboard) { copyToClipboard = savedCopyToClipboard }
    LaunchedEffect(savedAutoOpenMapsApp) { autoOpenMapsApp = savedAutoOpenMapsApp }
    LaunchedEffect(savedAutoOpenGeoApp) { autoOpenGeoApp = savedAutoOpenGeoApp }
    LaunchedEffect(savedAutoOpenDelay) { autoOpenDelay = savedAutoOpenDelay }

    if (showGenerateDialog) {
        AlertDialog(
            onDismissRequest = { showGenerateDialog = false },
            title = { Text(stringResource(R.string.generate_confirm_title)) },
            text = { Text(stringResource(R.string.generate_confirm_msg)) },
            confirmButton = {
                Button(
                    onClick = {
                        val newTopic = "sm_" + UUID.randomUUID().toString().replace("-", "").take(16)
                        val newKey = CryptoManager.generateSecretKey()
                        
                        // Update local states immediately
                        topic = newTopic
                        secretKey = newKey
                        
                        scope.launch { 
                            repository.saveNtfyConfig(newTopic, server)
                            repository.saveSecretKey(newKey)
                            restartServiceIfRunning()
                        }
                        showGenerateDialog = false
                    }
                ) {
                    Text(stringResource(R.string.btn_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showGenerateDialog = false }) {
                    Text(stringResource(R.string.btn_cancel))
                }
            }
        )
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.settings_title), fontWeight = FontWeight.Medium) },
                scrollBehavior = scrollBehavior
            )
        }
    ) { scaffoldPadding ->
        Column(
            modifier = Modifier
                .padding(top = scaffoldPadding.calculateTopPadding())
                .fillMaxSize()
        ) {
            // Scrollable Content
            AnimatedVisibility(
                visible = isSettingsReady,
                enter = fadeIn(animationSpec = tween(durationMillis = 500))
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // THE ORIGINAL BANNER DESIGN (Restored exactly)
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (isServiceRunning == true) 
                                MaterialTheme.colorScheme.primary 
                            else 
                                MaterialTheme.colorScheme.surfaceVariant
                        ),
                        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                        shape = RoundedCornerShape(0.dp), // NO rounded corners
                        modifier = Modifier.fillMaxWidth().zIndex(1f) // Edge to edge
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(12.dp)
                                        .background(
                                            color = if (isServiceRunning == true) Color(0xFF4CAF50) else Color(0xFFF44336) ,
                                            shape = CircleShape
                                        )
                                )
                                Text(
                                    text = stringResource(
                                        if (isServiceRunning == true) R.string.service_running else R.string.service_not_running
                                    ),
                                    color = if (isServiceRunning == true) 
                                        MaterialTheme.colorScheme.onPrimary 
                                    else 
                                        MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            if (isServiceRunning == false) {
                                Button(
                                    onClick = {
                                        scope.launch {
                                            repository.saveNtfyConfig(topic, server)
                                            repository.saveSecretKey(secretKey)
                                            val intent = Intent(context, NtfyListenerService::class.java)
                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                                context.startForegroundService(intent)
                                            } else {
                                                context.startService(intent)
                                            }
                                            Toast.makeText(context, R.string.save_success, Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    modifier = Modifier.height(48.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.secondary,
                                        contentColor = MaterialTheme.colorScheme.onSecondary
                                    ),
                                    shape = RoundedCornerShape(16.dp)
                                ) {
                                    Text(
                                        text = stringResource(R.string.btn_start),
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                    )
                                }
                            } else if (isServiceRunning == true) {
                                Button(
                                    onClick = {
                                        val intent = Intent(context, NtfyListenerService::class.java).apply {
                                            action = NtfyListenerService.ACTION_STOP
                                        }
                                        context.startService(intent)
                                        Toast.makeText(context, R.string.stop_ntfy, Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier.height(48.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.errorContainer,
                                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                                    ),
                                    shape = RoundedCornerShape(16.dp)
                                ) {
                                    Text(
                                        text = stringResource(R.string.btn_stop),
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                    )
                                }
                            }
                        }
                    }

                    // Scrollable part of the settings
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(bottom = navigationPadding.calculateBottomPadding() + 32.dp),
                    ) {
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        // Section: Permissions
                        if (isBatteryOptimized || !canDrawOverlays) {
                            SectionHeader(stringResource(R.string.section_permissions))
                            SettingsGroupCard {
                                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(
                                        text = stringResource(R.string.permissions_required),
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                    
                                    if (isBatteryOptimized) {
                                        Button(
                                            onClick = {
                                                @SuppressLint("BatteryLife")
                                                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                                    data = "package:${context.packageName}".toUri()
                                                }
                                                context.startActivity(intent)
                                            },
                                            modifier = Modifier.fillMaxWidth(),
                                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                        ) {
                                            Text(stringResource(R.string.disable_battery))
                                        }
                                    }

                                    if (!canDrawOverlays) {
                                        Button(
                                            onClick = {
                                                val intent = Intent(
                                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                                    "package:${context.packageName}".toUri()
                                                )
                                                context.startActivity(intent)
                                            },
                                            modifier = Modifier.fillMaxWidth(),
                                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                        ) {
                                            Text(stringResource(R.string.enable_overlay))
                                        }
                                    }
                                }
                            }
                        }

                        // Section: Pairing
                        SectionHeader(stringResource(R.string.section_pairing))
                        SettingsGroupCard {
                            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                
                                TextField(
                                    value = topic,
                                    onValueChange = { 
                                        topic = it
                                        scope.launch { 
                                            repository.saveNtfyConfig(it, server)
                                            restartServiceIfRunning()
                                        }
                                    },
                                    label = { Text(stringResource(R.string.ntfy_topic_label)) },
                                    modifier = Modifier.fillMaxWidth()
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Button(
                                        onClick = { showGenerateDialog = true },
                                        modifier = Modifier.weight(1f).height(64.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                        shape = RoundedCornerShape(16.dp)
                                    ) {
                                        Text(
                                            text = stringResource(R.string.generate_topic),
                                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                        )
                                    }
                                    
                                    // THE FIX STEP 1: Always render the button but keep it disabled if topic is blank.
                                    // This keeps the Row structure stable and prevents the "jumping" effect.
                                    Button(
                                        onClick = {
                                            if (topic.isNotBlank()) {
                                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                                val clip = ClipData.newPlainText("ntfy topic", topic)
                                                clipboard.setPrimaryClip(clip)
                                                Toast.makeText(context, R.string.text_copied_toast, Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        modifier = Modifier.weight(1f).height(64.dp),
                                        enabled = topic.isNotBlank(),
                                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                        shape = RoundedCornerShape(16.dp)
                                    ) {
                                        Text(
                                            text = stringResource(R.string.topic_copied),
                                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                        )
                                    }
                                }

                                // THE FIX STEP 2: Always reserve space for the QR code to avoid layout jumping
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(260.dp), // Increased height to accommodate multi-line text (200dp QR + text + spacers)
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (topic.isNotBlank() && secretKey.isNotBlank()) {
                                        val qrContent = "$server|$topic|$secretKey"
                                        val qrBitmap = remember(qrContent) {
                                            QRCodeGenerator.generate(qrContent, 512)
                                        }
                                        
                                        Column(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalAlignment = Alignment.CenterHorizontally
                                        ) {
                                            Image(
                                                bitmap = qrBitmap.asImageBitmap(),
                                                contentDescription = "QR Code",
                                                modifier = Modifier.size(200.dp)
                                            )
                                            Spacer(modifier = Modifier.height(16.dp))
                                            Text(
                                                text = stringResource(R.string.qr_instructions),
                                                style = MaterialTheme.typography.bodyMedium, // Increased size
                                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                            )
                                        }
                                    }
                                }

                                HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f))

                                TextField(
                                    value = server,
                                    onValueChange = { 
                                        server = it
                                        scope.launch { 
                                            repository.saveNtfyConfig(topic, it)
                                            restartServiceIfRunning()
                                        }
                                    },
                                    label = { Text(stringResource(R.string.ntfy_server_label)) },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }

                        // Section: Auto-Open
                        SectionHeader(stringResource(R.string.section_auto_open))
                        SettingsGroupCard {
                            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                
                                Text(stringResource(R.string.auto_open_delay_label, autoOpenDelay), style = MaterialTheme.typography.bodyLarge)
                                Slider(
                                    value = autoOpenDelay.toFloat(),
                                    onValueChange = { 
                                        val newVal = it.toInt()
                                        autoOpenDelay = newVal
                                        scope.launch { repository.saveAutoOpenDelay(newVal) }
                                    },
                                    valueRange = 0f..30f,
                                    steps = 30
                                )

                                HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f))

                                Text(stringResource(R.string.auto_open_maps_label), style = MaterialTheme.typography.titleSmall)
                                AutoOpenAppSelector(
                                    selectedApp = autoOpenMapsApp,
                                    onAppSelected = {
                                        autoOpenMapsApp = it
                                        scope.launch { repository.saveAutoOpenMapsApp(it) }
                                    }
                                )

                                HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f))

                                Text(stringResource(R.string.auto_open_geo_label), style = MaterialTheme.typography.titleSmall)
                                AutoOpenAppSelector(
                                    selectedApp = autoOpenGeoApp,
                                    onAppSelected = {
                                        autoOpenGeoApp = it
                                        scope.launch { repository.saveAutoOpenGeoApp(it) }
                                    }
                                )
                            }
                        }

                        // Section: General
                        SectionHeader(stringResource(R.string.settings_title))
                        SettingsGroupCard {
                            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(stringResource(R.string.copy_to_clipboard), style = MaterialTheme.typography.bodyLarge)
                                        Text(stringResource(R.string.copy_note), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Switch(
                                        checked = copyToClipboard,
                                        onCheckedChange = { 
                                            copyToClipboard = it
                                            scope.launch { repository.saveCopyToClipboard(it) }
                                        }
                                    )
                                }
                            }
                        }

                        // Section: Instructions
                        SectionHeader(stringResource(R.string.section_instructions))
                        SettingsGroupCard {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = AnnotatedString.fromHtml(stringResource(R.string.ntfy_instructions)),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(32.dp))
                    }
                }
            }
            
            if (!isSettingsReady) {
                // Background placeholder while data is loading to avoid any visual jump
                Box(modifier = Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
fun AutoOpenAppSelector(selectedApp: String, onAppSelected: (String) -> Unit) {
    Column {
        AutoOpenRow(
            label = stringResource(R.string.app_none),
            selected = selectedApp == ReceiverRepository.APP_NONE,
            onClick = { onAppSelected(ReceiverRepository.APP_NONE) }
        )
        AutoOpenRow(
            label = stringResource(R.string.app_maps),
            selected = selectedApp == ReceiverRepository.APP_MAPS,
            onClick = { onAppSelected(ReceiverRepository.APP_MAPS) }
        )
        AutoOpenRow(
            label = stringResource(R.string.app_waze),
            selected = selectedApp == ReceiverRepository.APP_WAZE,
            onClick = { onAppSelected(ReceiverRepository.APP_WAZE) }
        )
        AutoOpenRow(
            label = stringResource(R.string.app_other),
            selected = selectedApp == ReceiverRepository.APP_OTHER,
            onClick = { onAppSelected(ReceiverRepository.APP_OTHER) }
        )
    }
}

@Composable
fun AutoOpenRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun SectionHeader(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 8.dp),
        style = MaterialTheme.typography.labelLarge.copy(
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            letterSpacing = 1.sp
        )
    )
}

@Composable
fun SettingsGroupCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        content()
    }
}
