package com.example.sendtoscreenmate

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.example.sendtoscreenmate.ui.theme.SendToScreenMateTheme
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var repository: WebhookRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    /** [onSent] runs only if the message was actually delivered. */
    fun triggerManualSend(data: String, onSent: () -> Unit) {
        val url = MessageSender.extractUrl(data)
        var title = ""
        var finalData = data

        if (url.isNotBlank()) {
            finalData = url
            val textBeforeUrl = data.substringBefore(url).trim()
            if (textBeforeUrl.isNotBlank()) {
                title = textBeforeUrl.split("\n", "·", " - ").first().trim()
            }
            if (title.isBlank() && url.startsWith("geo:")) {
                title = MessageSender.extractGeoLabel(url)
            }
            if (title.isBlank()) {
                title = if (MapsUtils.isGoogleMapsLink(url) || url.startsWith("geo:")) "Location" else "Link"
            }
        } else {
            title = "Text Message"
        }
        performSendData(finalData, title, onSent)
    }

    fun triggerAddressSend(data: String, onSent: () -> Unit) {
        val isMapsLink = MapsUtils.isGoogleMapsLink(data)
        val isGeo = data.trim().startsWith("geo:", ignoreCase = true)
        
        if (isMapsLink || isGeo) {
            triggerManualSend(data, onSent)
        } else {
            val geoUri = "geo:0,0?q=${Uri.encode(data)}"
            performSendData(geoUri, data.take(50), onSent)
        }
    }

    private fun performSendData(data: String, title: String, onSent: () -> Unit) {
        lifecycleScope.launch {
            val result = MessageSender.send(repository, data, title)
            result.showToast(this@MainActivity)
            if (result == SendResult.SENT) onSent()
        }
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
                                    painter = painterResource(R.drawable.ic_content_paste),
                                    contentDescription = stringResource(R.string.paste)
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 150.dp, max = 300.dp),
                        maxLines = 15
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Button(
                            onClick = {
                                if (manualText.isNotBlank()) {
                                    // Keep the text if sending fails, so it can be retried
                                    (context as MainActivity).triggerManualSend(manualText) { manualText = "" }
                                }
                            },
                            modifier = Modifier.weight(1f).height(64.dp),
                            enabled = manualText.isNotBlank(),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.btn_send),
                                textAlign = TextAlign.Center
                            )
                        }
                        Button(
                            onClick = {
                                if (manualText.isNotBlank()) {
                                    (context as MainActivity).triggerAddressSend(manualText) { manualText = "" }
                                }
                            },
                            modifier = Modifier.weight(1f).height(64.dp),
                            enabled = manualText.isNotBlank(),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.btn_send_location),
                                textAlign = TextAlign.Center
                            )
                        }
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
    val savedEncryptionEnabled by repository.encryptionEnabled.collectAsState(initial = true)

    var macroDroidUrl by remember { mutableStateOf("") }
    var ntfyServer by remember { mutableStateOf("") }
    var ntfyTopic by remember { mutableStateOf("") }
    var encryptionEnabled by remember { mutableStateOf(true) }
    
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    // Fill the text fields from storage only once: re-filling them after every save would
    // overwrite what the user is still typing (lost characters, jumping cursor)
    LaunchedEffect(Unit) {
        macroDroidUrl = repository.webhookUrl.first()
        ntfyServer = repository.ntfyServer.first()
        ntfyTopic = repository.ntfyTopic.first()
    }
    LaunchedEffect(savedEncryptionEnabled) {
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
                        label = stringResource(R.string.service_ntfy_default),
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
