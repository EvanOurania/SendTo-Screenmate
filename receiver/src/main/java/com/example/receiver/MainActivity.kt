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
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.res.painterResource
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
import kotlin.math.roundToInt

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
                    icon = { Icon(painterResource(R.drawable.ic_history), contentDescription = null) },
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
                            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.btn_clear_history))
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
                                // The title is passed to detect dropped pins
                                ChooserOverlay.open(context, item.url, item.title)
                            } else {
                                val intent = Intent(Intent.ACTION_VIEW, item.url.toUri()).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
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

private fun startListenerService(context: Context) {
    val intent = Intent(context, NtfyListenerService::class.java)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(intent)
    } else {
        context.startService(intent)
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
                    text = item.title.ifBlank { stringResource(R.string.history_untitled) },
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
    
    val savedCopyToClipboard by repository.copyToClipboard.collectAsState(initial = ReceiverRepository.DEFAULT_COPY_TO_CLIPBOARD)
    val savedOnlyEncrypted by repository.onlyEncrypted.collectAsState(initial = ReceiverRepository.DEFAULT_ONLY_ENCRYPTED)
    val savedShowRestart by repository.showRestartButton.collectAsState(initial = ReceiverRepository.DEFAULT_SHOW_RESTART_BUTTON)
    val savedShowStop by repository.showStopButton.collectAsState(initial = ReceiverRepository.DEFAULT_SHOW_STOP_BUTTON)
    val savedShowReopen by repository.showReopenButton.collectAsState(initial = ReceiverRepository.DEFAULT_SHOW_REOPEN_BUTTON)
    val savedAutoOpenMapsApp by repository.autoOpenMapsApp.collectAsState(initial = ReceiverRepository.DEFAULT_AUTO_OPEN_APP)
    val savedAutoOpenGeoApp by repository.autoOpenGeoApp.collectAsState(initial = ReceiverRepository.DEFAULT_AUTO_OPEN_APP)
    val savedChooserMapsApps by repository.chooserMapsApps.collectAsState(initial = ReceiverRepository.DEFAULT_CHOOSER_APPS)
    val savedChooserGeoApps by repository.chooserGeoApps.collectAsState(initial = ReceiverRepository.DEFAULT_CHOOSER_APPS)
    
    var textFieldsLoaded by remember { mutableStateOf(false) }

    // THE MASTER FIX: Data is ready only when all essential flows have emitted at least once
    val isSettingsReady = savedTopicNullable != null && savedServerNullable != null && 
                         savedKeyNullable != null && savedAutoOpenDelayNullable != null && isServiceRunning != null &&
                         textFieldsLoaded

    val savedTopic = savedTopicNullable ?: ""
    val savedServer = savedServerNullable ?: "https://ntfy.sh"
    val savedKey = savedKeyNullable ?: ""
    val savedAutoOpenDelay = savedAutoOpenDelayNullable ?: ReceiverRepository.DEFAULT_AUTO_OPEN_DELAY

    var topic by remember { mutableStateOf("") }
    var server by remember { mutableStateOf("") }
    var secretKey by remember { mutableStateOf("") }
    var copyToClipboard by remember { mutableStateOf(ReceiverRepository.DEFAULT_COPY_TO_CLIPBOARD) }
    var onlyEncrypted by remember { mutableStateOf(ReceiverRepository.DEFAULT_ONLY_ENCRYPTED) }
    var showRestartButton by remember { mutableStateOf(ReceiverRepository.DEFAULT_SHOW_RESTART_BUTTON) }
    var showStopButton by remember { mutableStateOf(ReceiverRepository.DEFAULT_SHOW_STOP_BUTTON) }
    var showReopenButton by remember { mutableStateOf(ReceiverRepository.DEFAULT_SHOW_REOPEN_BUTTON) }
    var autoOpenMapsApp by remember { mutableStateOf(ReceiverRepository.DEFAULT_AUTO_OPEN_APP) }
    var autoOpenGeoApp by remember { mutableStateOf(ReceiverRepository.DEFAULT_AUTO_OPEN_APP) }
    var chooserMapsApps by remember { mutableStateOf(ReceiverRepository.DEFAULT_CHOOSER_APPS) }
    var chooserGeoApps by remember { mutableStateOf(ReceiverRepository.DEFAULT_CHOOSER_APPS) }
    // The apps besides Google Maps and Waze that can open each kind of location
    val otherMapsApps by produceState(emptyList<OtherApp>()) {
        value = NavigatorLauncher.findOtherApps(context, NavigatorLauncher.GOOGLE_MAPS_SAMPLE_LINKS)
    }
    val otherGeoApps by produceState(emptyList<OtherApp>()) {
        value = NavigatorLauncher.findOtherApps(context, listOf(NavigatorLauncher.GEO_SAMPLE_LINK))
    }
    var autoOpenDelay by remember { mutableIntStateOf(ReceiverRepository.DEFAULT_AUTO_OPEN_DELAY) }
    
    // Fill the text fields from storage only once: re-filling them after every save would
    // overwrite what the user is still typing (lost characters, jumping cursor)
    val storedTextLoaded = savedTopicNullable != null && savedServerNullable != null && savedKeyNullable != null
    LaunchedEffect(storedTextLoaded) {
        if (storedTextLoaded && !textFieldsLoaded) {
            topic = savedTopic
            server = savedServer
            secretKey = savedKey
            textFieldsLoaded = true
        }
    }
    LaunchedEffect(savedCopyToClipboard) { copyToClipboard = savedCopyToClipboard }
    LaunchedEffect(savedOnlyEncrypted) { onlyEncrypted = savedOnlyEncrypted }
    LaunchedEffect(savedShowRestart) { showRestartButton = savedShowRestart }
    LaunchedEffect(savedShowStop) { showStopButton = savedShowStop }
    LaunchedEffect(savedShowReopen) { showReopenButton = savedShowReopen }
    LaunchedEffect(savedAutoOpenMapsApp) { autoOpenMapsApp = savedAutoOpenMapsApp }
    LaunchedEffect(savedAutoOpenGeoApp) { autoOpenGeoApp = savedAutoOpenGeoApp }
    LaunchedEffect(savedChooserMapsApps) { chooserMapsApps = savedChooserMapsApps }
    LaunchedEffect(savedChooserGeoApps) { chooserGeoApps = savedChooserGeoApps }
    LaunchedEffect(savedAutoOpenDelay) { autoOpenDelay = savedAutoOpenDelay }

    var showQr by remember { mutableStateOf(false) }

    // New topic and key: start listening right away and show the QR code to pair the Sender
    fun generatePairing() {
        val newTopic = "sm_" + UUID.randomUUID().toString().replace("-", "").take(16)
        val newKey = CryptoManager.generateSecretKey()

        // Update local states immediately
        topic = newTopic
        secretKey = newKey
        showQr = true

        scope.launch {
            repository.saveNtfyConfig(newTopic, server)
            repository.saveSecretKey(newKey)
            // A running service reconnects by itself when the settings change
            if (isServiceRunning != true) {
                startListenerService(context)
                Toast.makeText(context, R.string.save_success, Toast.LENGTH_SHORT).show()
            }
        }
    }

    if (showGenerateDialog) {
        AlertDialog(
            onDismissRequest = { showGenerateDialog = false },
            title = { Text(stringResource(R.string.generate_confirm_title)) },
            text = { Text(stringResource(R.string.generate_confirm_msg)) },
            confirmButton = {
                Button(
                    onClick = {
                        generatePairing()
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
                                            startListenerService(context)
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
                                        onClick = {
                                            // Nothing to invalidate yet on first setup: no need to warn
                                            if (topic.isBlank() && secretKey.isBlank()) generatePairing() else showGenerateDialog = true
                                        },
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

                                // The QR code contains the secret key: show it only while pairing a Sender
                                if (topic.isNotBlank() && secretKey.isNotBlank()) {
                                    if (showQr) {
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
                                                contentDescription = stringResource(R.string.qr_instructions),
                                                modifier = Modifier.size(200.dp)
                                            )
                                            Spacer(modifier = Modifier.height(16.dp))
                                            Text(
                                                text = stringResource(R.string.qr_instructions),
                                                style = MaterialTheme.typography.bodyMedium, // Increased size
                                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                            )
                                            TextButton(onClick = { showQr = false }) {
                                                Text(stringResource(R.string.hide_qr))
                                            }
                                        }
                                    } else {
                                        OutlinedButton(
                                            onClick = { showQr = true },
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Text(stringResource(R.string.show_qr))
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
                                    onValueChange = { autoOpenDelay = it.roundToInt() },
                                    // Save once when the finger is lifted, not on every drag movement
                                    onValueChangeFinished = {
                                        scope.launch { repository.saveAutoOpenDelay(autoOpenDelay) }
                                    },
                                    valueRange = 0f..30f,
                                    steps = 29 // 29 stops between the ends = one per second
                                )

                                HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f))

                                Text(stringResource(R.string.auto_open_maps_label), style = MaterialTheme.typography.titleSmall)
                                ChooserAppsSelector(
                                    shownApps = chooserMapsApps,
                                    defaultApp = autoOpenMapsApp,
                                    otherApps = otherMapsApps,
                                    onShownAppsChange = {
                                        chooserMapsApps = it
                                        scope.launch { repository.saveChooserMapsApps(it) }
                                    },
                                    onDefaultAppChange = {
                                        autoOpenMapsApp = it
                                        scope.launch { repository.saveAutoOpenMapsApp(it) }
                                    }
                                )

                                HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f))

                                Text(stringResource(R.string.auto_open_geo_label), style = MaterialTheme.typography.titleSmall)
                                ChooserAppsSelector(
                                    shownApps = chooserGeoApps,
                                    defaultApp = autoOpenGeoApp,
                                    otherApps = otherGeoApps,
                                    onShownAppsChange = {
                                        chooserGeoApps = it
                                        scope.launch { repository.saveChooserGeoApps(it) }
                                    },
                                    onDefaultAppChange = {
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
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(stringResource(R.string.pref_only_encrypted), style = MaterialTheme.typography.bodyLarge)
                                        Text(stringResource(R.string.pref_only_encrypted_note), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Switch(
                                        checked = onlyEncrypted,
                                        // Without a key there is nothing to decrypt with
                                        enabled = secretKey.isNotBlank(),
                                        onCheckedChange = {
                                            onlyEncrypted = it
                                            scope.launch { repository.saveOnlyEncrypted(it) }
                                        }
                                    )
                                }
                            }
                        }

                        // Section: Notification Buttons
                        SectionHeader(stringResource(R.string.pref_notification_buttons))
                        SettingsGroupCard {
                            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(stringResource(R.string.pref_show_restart), style = MaterialTheme.typography.bodyLarge)
                                    Switch(
                                        checked = showRestartButton,
                                        onCheckedChange = { 
                                            showRestartButton = it
                                            scope.launch { repository.saveShowRestartButton(it) }
                                        }
                                    )
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(stringResource(R.string.pref_show_stop), style = MaterialTheme.typography.bodyLarge)
                                    Switch(
                                        checked = showStopButton,
                                        onCheckedChange = { 
                                            showStopButton = it
                                            scope.launch { repository.saveShowStopButton(it) }
                                        }
                                    )
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(stringResource(R.string.pref_show_reopen), style = MaterialTheme.typography.bodyLarge)
                                    Switch(
                                        checked = showReopenButton,
                                        onCheckedChange = { 
                                            showReopenButton = it
                                            scope.launch { repository.saveShowReopenButton(it) }
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

/**
 * The apps to show in the chooser (checkboxes) and the one that opens by itself (radio buttons). The
 * latter is always shown, and so is the only app left ticked, so that the chooser always has one.
 * Google Maps and Waze are saved as APP_MAPS and APP_WAZE, the other apps by package name.
 */
@Composable
fun ChooserAppsSelector(
    shownApps: Set<String>,
    defaultApp: String,
    otherApps: List<OtherApp>,
    onShownAppsChange: (Set<String>) -> Unit,
    onDefaultAppChange: (String) -> Unit,
) {
    val listedApps = listOf(ReceiverRepository.APP_MAPS, ReceiverRepository.APP_WAZE) + otherApps.map { it.packageName }
    val onlyShownApp = listedApps.filter { it in shownApps || it == defaultApp }.singleOrNull()

    @Composable
    fun AppRow(app: String, label: String) {
        ChooserAppRow(
            label = label,
            shown = app in shownApps || app == defaultApp,
            canHide = app != defaultApp && app != onlyShownApp,
            isDefault = app == defaultApp,
            onShownChange = { shown -> onShownAppsChange(if (shown) shownApps + app else shownApps - app) },
            onSetDefault = {
                onDefaultAppChange(app)
                // Still shown if another app becomes the default
                if (app !in shownApps) onShownAppsChange(shownApps + app)
            }
        )
    }

    Column {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.chooser_apps_show),
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(48.dp)
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(text = stringResource(R.string.chooser_apps_default), style = MaterialTheme.typography.labelSmall)
        }
        AppRow(ReceiverRepository.APP_MAPS, stringResource(R.string.app_maps))
        AppRow(ReceiverRepository.APP_WAZE, stringResource(R.string.app_waze))
        for (app in otherApps) {
            AppRow(app.packageName, app.label)
        }
        // No auto-open
        ChooserAppRow(
            label = stringResource(R.string.app_none),
            shown = null,
            canHide = false,
            isDefault = defaultApp == ReceiverRepository.APP_NONE,
            onShownChange = {},
            onSetDefault = { onDefaultAppChange(ReceiverRepository.APP_NONE) }
        )
    }
}

/** [shown] is null for a row without the checkbox. */
@Composable
fun ChooserAppRow(
    label: String,
    shown: Boolean?,
    canHide: Boolean,
    isDefault: Boolean,
    onShownChange: (Boolean) -> Unit,
    onSetDefault: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(48.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (shown != null) {
            Checkbox(checked = shown, onCheckedChange = onShownChange, enabled = !shown || canHide)
        } else {
            Spacer(modifier = Modifier.width(48.dp))
        }
        Text(text = label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        RadioButton(selected = isDefault, onClick = onSetDefault)
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
