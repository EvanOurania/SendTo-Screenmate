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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.example.receiver.ui.theme.ReceiverTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ReceiverTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    ReceiverScreen()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiverScreen() {
    val context = LocalContext.current
    val repository = remember { ReceiverRepository(context) }
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    var isBatteryOptimized by remember { mutableStateOf(value = false) }
    var canDrawOverlays by remember { mutableStateOf(value = false) }
    var isServiceRunning by remember { mutableStateOf(value = false) }
    var showGenerateDialog by remember { mutableStateOf(false) }

    fun checkPermissions() {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        isBatteryOptimized = !powerManager.isIgnoringBatteryOptimizations(context.packageName)
        canDrawOverlays = Settings.canDrawOverlays(context)
    }

    fun restartServiceIfRunning() {
        if (isServiceRunning) {
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
    
    val savedTopic by repository.ntfyTopic.collectAsState(initial = "")
    val savedServer by repository.ntfyServer.collectAsState(initial = "https://ntfy.sh")
    val savedKey by repository.secretKey.collectAsState(initial = "")
    val savedCopyToClipboard by repository.copyToClipboard.collectAsState(initial = false)
    
    var topic by remember { mutableStateOf("") }
    var server by remember { mutableStateOf("") }
    var secretKey by remember { mutableStateOf("") }
    var copyToClipboard by remember { mutableStateOf(false) }
    
    LaunchedEffect(savedTopic) { topic = savedTopic }
    LaunchedEffect(savedServer) { server = savedServer }
    LaunchedEffect(savedKey) { secretKey = savedKey }
    LaunchedEffect(savedCopyToClipboard) { copyToClipboard = savedCopyToClipboard }

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
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            // Fixed Status Card with Shadow
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (isServiceRunning) 
                        MaterialTheme.colorScheme.primary 
                    else 
                        MaterialTheme.colorScheme.surfaceVariant
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                shape = RoundedCornerShape(0.dp),
                modifier = Modifier.fillMaxWidth().zIndex(1f)
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
                                    color = if (isServiceRunning) Color(0xFF4CAF50) else Color(0xFFF44336) ,
                                    shape = CircleShape
                                )
                        )
                        Text(
                            text = stringResource(
                                if (isServiceRunning) R.string.service_running else R.string.service_not_running
                            ),
                            color = if (isServiceRunning) 
                                MaterialTheme.colorScheme.onPrimary 
                            else 
                                MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    if (!isServiceRunning) {
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
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.secondary,
                                contentColor = MaterialTheme.colorScheme.onSecondary
                            )
                        ) {
                            Text(stringResource(R.string.btn_start))
                        }
                    } else {
                        Button(
                            onClick = {
                                val intent = Intent(context, NtfyListenerService::class.java).apply {
                                    action = NtfyListenerService.ACTION_STOP
                                }
                                context.startService(intent)
                                Toast.makeText(context, R.string.stop_ntfy, Toast.LENGTH_SHORT).show()
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer
                            )
                        ) {
                            Text(stringResource(R.string.btn_stop))
                        }
                    }
                }
            }

            // Scrollable Content
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 32.dp),
            ) {
                
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
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                                ) {
                                    Text(stringResource(R.string.enable_overlay))
                                }
                            }
                        }
                    }
                }

                // Section: Configuration
                SectionHeader(stringResource(R.string.section_configuration))
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

                        if (topic.isNotBlank()) {
                            TextButton(
                                onClick = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    val clip = ClipData.newPlainText("ntfy topic", topic)
                                    clipboard.setPrimaryClip(clip)
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(stringResource(R.string.topic_copied))
                            }
                        }
                        
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

                        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.encryption_label), style = MaterialTheme.typography.bodyLarge)
                                Text(stringResource(R.string.encryption_note), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        
                        Button(
                            onClick = { showGenerateDialog = true },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                        ) {
                            Text(stringResource(R.string.generate_topic))
                        }
                        
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
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
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

