package com.example.sendtoscreenmate

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.example.sendtoscreenmate.ui.theme.SendToScreenMateTheme
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class MainActivity : ComponentActivity() {

    private lateinit var repository: WebhookRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        val action = intent?.action
        val type = intent?.type
        val data = intent?.data

        val isShare = (action == Intent.ACTION_SEND && type == "text/plain")
        val isGeo = ((action == Intent.ACTION_VIEW || action == "android.intent.action.NAVIGATE") && data?.scheme == "geo")

        if (isShare || isGeo) {
            setTheme(R.style.Theme_SendToScreenMate_Transparent)
            window.setBackgroundDrawableResource(android.R.color.transparent)
            super.onCreate(savedInstanceState)
            
            repository = WebhookRepository(this)

            setContent {
                SendToScreenMateTheme {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surface,
                            tonalElevation = 8.dp,
                            modifier = Modifier.size(120.dp),
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                                modifier = Modifier.padding(16.dp),
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(48.dp))
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = stringResource(R.string.sending_progress),
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            }
                        }
                    }
                }
            }
            handleIncomingData(intent)
        } else {
            setTheme(R.style.Theme_SendToScreenMate)
            enableEdgeToEdge()
            super.onCreate(savedInstanceState)
            repository = WebhookRepository(this)

            setContent {
                SendToScreenMateTheme {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background,
                    ) {
                        SettingsScreen(repository)
                    }
                }
            }
        }
    }

    private fun handleIncomingData(intent: Intent) {
        val extractedData = when (intent.action) {
            Intent.ACTION_SEND -> {
                val fullText = intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
                extractUrl(fullText)
            }
            Intent.ACTION_VIEW, "android.intent.action.NAVIGATE" -> intent.dataString
            else -> null
        }

        if (extractedData != null) {
            performSendData(extractedData)
        } else {
            Toast.makeText(this, getString(R.string.no_data_error), Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun performSendData(data: String) {
        if (data.length > 3000) {
            Toast.makeText(this, R.string.char_limit_exceeded, Toast.LENGTH_LONG).show()
            return
        }
        
        lifecycleScope.launch {
            val serviceType = repository.serviceType.first()
            if (serviceType == WebhookRepository.SERVICE_MACRODROID) {
                val url = repository.webhookUrl.first()
                sendToMacroDroid(url, data)
            } else {
                val server = repository.ntfyServer.first()
                val topic = repository.ntfyTopic.first()
                val secretKey = repository.secretKey.first()
                val encryptionActive = repository.encryptionEnabled.first()
                sendToNtfy(server, topic, secretKey, encryptionActive, data)
            }
        }
    }

    private fun extractUrl(text: String): String {
        val urlRegex = Regex("(https?://\\S+)")
        val match = urlRegex.find(text)
        return match?.value ?: text
    }

    private fun sendToMacroDroid(webhookUrl: String, text: String) {
        lifecycleScope.launch {
            val success = withContext(Dispatchers.IO) {
                try {
                    val client = OkHttpClient()
                    val url = webhookUrl.toHttpUrlOrNull()
                        ?.newBuilder()
                        ?.addQueryParameter("value", text)
                        ?.build()

                    if (url != null) {
                        val request = Request.Builder()
                            .url(url)
                            .get()
                            .build()

                        client.newCall(request).execute().use { response ->
                            response.isSuccessful
                        }
                    } else {
                        false
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    false
                }
            }

            if (success) {
                Toast.makeText(this@MainActivity, R.string.sent_ntfy, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@MainActivity, R.string.error_ntfy, Toast.LENGTH_SHORT).show()
            }
            if (intent?.action != null && (intent.action == Intent.ACTION_SEND || intent.action == Intent.ACTION_VIEW || intent.action == "android.intent.action.NAVIGATE")) {
                finish()
            }
        }
    }

    private fun sendToNtfy(server: String, topic: String, secretKey: String, encryptionEnabled: Boolean, text: String) {
        lifecycleScope.launch {
            val success = withContext(Dispatchers.IO) {
                try {
                    val client = OkHttpClient()
                    val baseUrl = if (server.endsWith("/")) server else "$server/"
                    val finalUrl = "$baseUrl$topic"
                    
                    val payload = if (encryptionEnabled && secretKey.isNotBlank()) {
                        Log.d("E2EE", "Sending ENCRYPTED message")
                        CryptoManager.encrypt(text, secretKey)
                    } else {
                        Log.d("E2EE", "Sending PLAIN TEXT message")
                        text
                    }
                    
                    val request = Request.Builder()
                        .url(finalUrl)
                        .post(payload.toRequestBody("text/plain".toMediaType()))
                        .build()

                    client.newCall(request).execute().use { response ->
                        response.isSuccessful
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    false
                }
            }

            if (success) {
                Toast.makeText(this@MainActivity, R.string.sent_ntfy, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@MainActivity, R.string.error_ntfy, Toast.LENGTH_SHORT).show()
            }
            if (intent?.action != null && (intent.action == Intent.ACTION_SEND || intent.action == Intent.ACTION_VIEW || intent.action == "android.intent.action.NAVIGATE")) {
                finish()
            }
        }
    }

    // Composable internal logic exposed for SettingsScreen
    fun triggerManualSend(data: String) {
        performSendData(data)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(repository: WebhookRepository) {
    val currentService by repository.serviceType.collectAsState(initial = WebhookRepository.SERVICE_NTFY)
    val savedMacroDroidUrl by repository.webhookUrl.collectAsState(initial = WebhookRepository.DEFAULT_URL)
    val savedNtfyServer by repository.ntfyServer.collectAsState(initial = WebhookRepository.DEFAULT_NTFY_SERVER)
    val savedNtfyTopic by repository.ntfyTopic.collectAsState(initial = "")
    val savedEncryptionEnabled by repository.encryptionEnabled.collectAsState(initial = true)

    var macroDroidUrl by remember { mutableStateOf("") }
    var ntfyServer by remember { mutableStateOf("") }
    var ntfyTopic by remember { mutableStateOf("") }
    var encryptionEnabled by remember { mutableStateOf(true) }
    var manualText by remember { mutableStateOf("") }
    
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    LaunchedEffect(savedMacroDroidUrl, savedNtfyServer, savedNtfyTopic, savedEncryptionEnabled) {
        macroDroidUrl = savedMacroDroidUrl
        ntfyServer = savedNtfyServer
        ntfyTopic = savedNtfyTopic
        encryptionEnabled = savedEncryptionEnabled
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
                .verticalScroll(rememberScrollState())
                .padding(bottom = 32.dp),
        ) {
            
            // Section: Input
            SectionHeader(stringResource(R.string.section_input))
            SettingsGroupCard {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.char_limit_label, manualText.length),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (manualText.length > 3000) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    OutlinedTextField(
                        value = manualText,
                        onValueChange = { if (it.length <= 3000) manualText = it },
                        placeholder = { Text(stringResource(R.string.manual_send_hint)) },
                        trailingIcon = {
                            IconButton(onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val data = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
                                if (data != null) {
                                    val newText = manualText + data
                                    if (newText.length <= 3000) {
                                        manualText = newText
                                    } else {
                                        manualText = newText.take(3000)
                                    }
                                }
                            }) {
                                Icon(
                                    imageVector = Icons.Default.ContentPaste,
                                    contentDescription = "Paste"
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp, max = 250.dp),
                        maxLines = 10
                    )
                    Button(
                        onClick = {
                            if (manualText.isNotBlank()) {
                                (context as MainActivity).triggerManualSend(manualText)
                                manualText = ""
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = manualText.isNotBlank()
                    ) {
                        Text(stringResource(R.string.btn_send))
                    }
                }
            }

            // Section: Service
            SectionHeader(stringResource(R.string.section_service))
            SettingsGroupCard {
                Column(modifier = Modifier.padding(vertical = 8.dp)) {
                    ServiceSelectionRow(
                        label = "ntfy.sh (Default)",
                        selected = currentService == WebhookRepository.SERVICE_NTFY,
                        onClick = { scope.launch { repository.saveServiceType(WebhookRepository.SERVICE_NTFY) } }
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f))
                    ServiceSelectionRow(
                        label = "MacroDroid",
                        selected = currentService == WebhookRepository.SERVICE_MACRODROID,
                        onClick = { scope.launch { repository.saveServiceType(WebhookRepository.SERVICE_MACRODROID) } }
                    )
                }
            }

            // Section: Configuration
            SectionHeader(stringResource(R.string.section_configuration))
            if (currentService == WebhookRepository.SERVICE_NTFY) {
                SettingsGroupCard {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        OutlinedTextField(
                            value = ntfyServer,
                            onValueChange = { 
                                ntfyServer = it
                                scope.launch { repository.saveNtfyServer(it) }
                            },
                            label = { Text(stringResource(R.string.ntfy_server_label)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = ntfyTopic,
                            onValueChange = { 
                                ntfyTopic = it
                                scope.launch { repository.saveNtfyTopic(it) }
                            },
                            label = { Text(stringResource(R.string.ntfy_topic_label)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(stringResource(R.string.encryption_label), style = MaterialTheme.typography.bodyLarge)
                            Switch(
                                checked = encryptionEnabled,
                                onCheckedChange = { 
                                    encryptionEnabled = it
                                    scope.launch { repository.saveEncryptionEnabled(it) }
                                }
                            )
                        }

                        val scanPrompt = stringResource(R.string.scan_qr)
                        val scannerLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
                            if (result.contents != null) {
                                val scannedData = result.contents
                                if (scannedData.contains("|")) {
                                    val parts = scannedData.split("|")
                                    if (parts.size >= 2) {
                                        val srv = parts[0]; val top = parts[1]
                                        val key = if (parts.size >= 3) parts[2] else ""
                                        scope.launch {
                                            repository.saveNtfyServer(srv); repository.saveNtfyTopic(top)
                                            repository.saveSecretKey(key); repository.saveEncryptionEnabled(key.isNotBlank())
                                            ntfyServer = srv; ntfyTopic = top; encryptionEnabled = key.isNotBlank()
                                            Toast.makeText(context, R.string.save_success_ntfy, Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            }
                        }

                        Button(
                            onClick = {
                                val options = ScanOptions().apply {
                                    setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                    setPrompt(scanPrompt)
                                    setBeepEnabled(false)
                                    setOrientationLocked(true)
                                    captureActivity = CaptureActivityPortrait::class.java
                                }
                                scannerLauncher.launch(options)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                        ) {
                            Text(stringResource(R.string.scan_qr))
                        }
                    }
                }
            } else {
                SettingsGroupCard {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        OutlinedTextField(
                            value = macroDroidUrl,
                            onValueChange = { 
                                macroDroidUrl = it
                                scope.launch { repository.saveWebhookUrl(it) }
                            },
                            label = { Text(stringResource(R.string.webhook_url_label)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    }
                }
            }

            // Section: Instructions
            SectionHeader(stringResource(R.string.section_instructions))
            SettingsGroupCard {
                Column(modifier = Modifier.padding(16.dp)) {
                    val instr = if (currentService == WebhookRepository.SERVICE_NTFY) R.string.ntfy_instructions else R.string.macrodroid_instructions
                    Text(
                        text = AnnotatedString.fromHtml(stringResource(instr)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
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

@Composable
fun ServiceSelectionRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
        RadioButton(selected = selected, onClick = onClick)
    }
}
