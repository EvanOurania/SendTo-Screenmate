package com.example.sendtoscreenmate

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.fromHtml
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
import org.json.JSONObject
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

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
                        MainScreen(repository)
                    }
                }
            }
        }
    }

    private fun handleIncomingData(intent: Intent) {
        val fullText = intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT) ?: ""
        val dataString = intent.dataString ?: ""
        
        var url = extractUrl(fullText)
        
        // If we don't find a URL in the text, check if the intent has a geo: data scheme
        if (url.isBlank() && dataString.startsWith("geo:")) {
            url = dataString
        }
        
        var title = ""
        
        if (intent.action == Intent.ACTION_SEND) {
            // Priority to Subject
            if (subject.isNotBlank()) {
                title = subject.trim()
            } else if (url.isNotBlank()) {
                // Extract what precedes the URL
                val textBeforeUrl = fullText.substringBefore(url).trim()
                if (textBeforeUrl.isNotBlank()) {
                    // Add " - " to delimiters for apps like Electra
                    title = textBeforeUrl.split("\n", "·", " - ").first().trim()
                }
            }
            
            // If title is still blank and it's a geo link, look for the label
            if (title.isBlank() && url.startsWith("geo:")) {
                title = extractGeoLabel(url)
            }
        } else if (intent.action == Intent.ACTION_VIEW || intent.action == "android.intent.action.NAVIGATE") {
            // For direct geo: links
            title = extractGeoLabel(dataString).ifBlank { "Position" }
            if (url.isBlank()) url = dataString
        }

        if (url.isNotBlank()) {
            val geoLabel = if (url.startsWith("geo:")) extractGeoLabel(url) else ""
            performSendData(url, title.ifBlank { geoLabel.ifBlank { "Location" } })
        } else if (fullText.isNotBlank()) {
            performSendData(fullText, title.ifBlank { "Text Message" })
        } else {
            Toast.makeText(this, getString(R.string.no_data_error), Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun performSendData(data: String, title: String) {
        // Prepare a JSON payload so we can include the title
        val json = JSONObject().apply {
            put("url", data)
            put("title", title)
        }
        val finalPayload = json.toString()

        if (finalPayload.length > 3000) {
            Toast.makeText(this, R.string.char_limit_exceeded, Toast.LENGTH_LONG).show()
            return
        }
        
        lifecycleScope.launch {
            val serviceType = repository.serviceType.first()
            if (serviceType == WebhookRepository.SERVICE_MACRODROID) {
                val url = repository.webhookUrl.first()
                sendToMacroDroid(url, data) // MacroDroid still expects just the URL/Value
            } else {
                val server = repository.ntfyServer.first()
                val topic = repository.ntfyTopic.first()
                val secretKey = repository.secretKey.first()
                val encryptionActive = repository.encryptionEnabled.first()
                sendToNtfy(server, topic, secretKey, encryptionActive, finalPayload)
            }
        }
    }

    private fun extractUrl(text: String): String {
        // If the text is already just a geo: URI, take it all
        if (text.trim().startsWith("geo:", ignoreCase = true)) return text.trim()
        
        // Permissive regex to capture the entire link even with special characters
        // We include [^\\s] to grab everything until the first whitespace
        val urlRegex = Regex("((https?://|geo:)[^\\s\\n\\r]+)")
        val match = urlRegex.find(text)
        return match?.value ?: ""
    }

    private fun extractGeoLabel(geoUri: String): String {
        try {
            // 1. Search for q= parameter (common for text addresses)
            val qIndex = geoUri.indexOf("q=")
            if (qIndex != -1) {
                var value = geoUri.substring(qIndex + 2)
                
                // Stop at common delimiters
                val endDelimiters = charArrayOf('&', '@', '#')
                var firstDelimiter = -1
                for (d in endDelimiters) {
                    val idx = value.indexOf(d)
                    if (idx != -1 && (firstDelimiter == -1 || idx < firstDelimiter)) {
                        firstDelimiter = idx
                    }
                }
                
                if (firstDelimiter != -1) {
                    value = value.substring(0, firstDelimiter)
                }
                
                // Decode (handles %20, +, etc)
                val decoded = try {
                    URLDecoder.decode(value, StandardCharsets.UTF_8.name()).trim()
                } catch (_: Exception) {
                    value.replace("%20", " ").replace("+", " ").trim()
                }

                // If result contains parentheses, extract only the content (the label)
                // This cleans cases like "45.123,9.123(Place Name)"
                val labelMatch = Regex("\\((.+)\\)").find(decoded)
                if (labelMatch != null) {
                    return labelMatch.groupValues[1].trim()
                }
                
                return decoded
            }

            // 2. Fallback: search for label in parentheses (standard geo: format)
            val labelRegex = Regex("\\(([^)]+)\\)")
            val labelMatch = labelRegex.find(geoUri)
            if (labelMatch != null) {
                val value = labelMatch.groupValues[1]
                return try {
                    URLDecoder.decode(value, StandardCharsets.UTF_8.name()).trim()
                } catch (_: Exception) {
                    value.trim()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return ""
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
            if (isShareOrViewIntent()) {
                finish()
            }
        }
    }

    private fun sendToNtfy(server: String, topic: String, secretKey: String, encryptionEnabled: Boolean, payload: String) {
        lifecycleScope.launch {
            val success = withContext(Dispatchers.IO) {
                try {
                    val client = OkHttpClient()
                    val baseUrl = if (server.endsWith("/")) server else "$server/"
                    val finalUrl = "$baseUrl$topic"
                    
                    val encryptedPayload = if (encryptionEnabled && secretKey.isNotBlank()) {
                        CryptoManager.encrypt(payload, secretKey)
                    } else {
                        payload
                    }
                    
                    val request = Request.Builder()
                        .url(finalUrl)
                        .post(encryptedPayload.toRequestBody("text/plain".toMediaType()))
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
            if (isShareOrViewIntent()) {
                finish()
            }
        }
    }

    private fun isShareOrViewIntent(): Boolean {
        return intent?.action != null && (intent.action == Intent.ACTION_SEND || intent.action == Intent.ACTION_VIEW || intent.action == "android.intent.action.NAVIGATE")
    }

    // Composable internal logic exposed for Screens
    fun triggerManualSend(data: String) {
        val url = extractUrl(data)
        var title = ""
        var finalData = data

        if (url.isNotBlank()) {
            finalData = url
            val textBeforeUrl = data.substringBefore(url).trim()
            if (textBeforeUrl.isNotBlank()) {
                title = textBeforeUrl.split("\n", "·", " - ").first().trim()
            }
            if (title.isBlank() && url.startsWith("geo:")) {
                title = extractGeoLabel(url)
            }
            if (title.isBlank()) title = "Location"
        } else {
            title = "Text Message"
        }
        performSendData(finalData, title)
    }
}

@Composable
fun MainScreen(repository: WebhookRepository) {
    var currentTab by remember { mutableIntStateOf(0) }
    
    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    icon = { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null) },
                    label = { Text(stringResource(R.string.btn_send)) },
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
        if (currentTab == 0) {
            SendScreen(padding)
        } else {
            SettingsScreen(repository, padding)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SendScreen(navigationPadding: PaddingValues) {
    var manualText by remember { mutableStateOf("") }
    val context = LocalContext.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.app_name), fontWeight = FontWeight.Medium) },
                scrollBehavior = scrollBehavior
            )
        }
    ) { scaffoldPadding ->
        Column(
            modifier = Modifier
                .padding(top = scaffoldPadding.calculateTopPadding())
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = navigationPadding.calculateBottomPadding() + 32.dp),
        ) {
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
                                    manualText = if (newText.length <= 3000) {
                                        newText
                                    } else {
                                        newText.take(3000)
                                    }
                                }
                            }) {
                                Icon(
                                    imageVector = Icons.Default.ContentPaste,
                                    contentDescription = "Paste"
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 150.dp, max = 300.dp),
                        maxLines = 15
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
            
            Spacer(modifier = Modifier.height(24.dp))
            SettingsGroupCard {
                Column(modifier = Modifier.padding(16.dp)) {
                    val instr = stringResource(R.string.manual_send_note)
                    Text(
                        text = instr,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(repository: WebhookRepository, navigationPadding: PaddingValues) {
    val currentService by repository.serviceType.collectAsState(initial = WebhookRepository.SERVICE_NTFY)
    val savedMacroDroidUrl by repository.webhookUrl.collectAsState(initial = WebhookRepository.DEFAULT_URL)
    val savedNtfyServer by repository.ntfyServer.collectAsState(initial = WebhookRepository.DEFAULT_NTFY_SERVER)
    val savedNtfyTopic by repository.ntfyTopic.collectAsState(initial = "")
    val savedEncryptionEnabled by repository.encryptionEnabled.collectAsState(initial = true)

    var macroDroidUrl by remember { mutableStateOf("") }
    var ntfyServer by remember { mutableStateOf("") }
    var ntfyTopic by remember { mutableStateOf("") }
    var encryptionEnabled by remember { mutableStateOf(true) }
    
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
    ) { scaffoldPadding ->
        Column(
            modifier = Modifier
                .padding(top = scaffoldPadding.calculateTopPadding())
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = navigationPadding.calculateBottomPadding() + 32.dp),
        ) {
            
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
