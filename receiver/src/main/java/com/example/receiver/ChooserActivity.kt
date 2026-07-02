package com.example.receiver

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.example.receiver.ui.theme.ReceiverTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class ChooserActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val url = intent.getStringExtra("url") ?: ""
        val title = intent.getStringExtra("title") ?: ""
        val mode = intent.getStringExtra("mode") ?: ""

        if (url.isBlank()) {
            finish()
            return
        }

        lifecycleScope.launch {
            val repository = ReceiverRepository(this@ChooserActivity)
            
            // Universal Auto-Copy Fix for Android 10+
            if (repository.copyToClipboard.first()) {
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("url", url)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this@ChooserActivity, R.string.text_copied_toast, Toast.LENGTH_SHORT).show()
                
                // Special handling for different auto-copy modes
                when (mode) {
                    "COPY_ONLY" -> {
                        closeInstant()
                        return@launch
                    }
                    "COPY_AND_OPEN_DIRECT" -> {
                        val intent = Intent(Intent.ACTION_VIEW, url.toUri()).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        }
                        try {
                            startActivity(intent)
                        } catch (_: Exception) {}
                        closeInstant()
                        return@launch
                    }
                }
            } else if (mode == "COPY_ONLY" || mode == "COPY_AND_OPEN_DIRECT") {
                if (mode == "COPY_AND_OPEN_DIRECT") {
                    val intent = Intent(Intent.ACTION_VIEW, url.toUri()).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    }
                    try {
                        startActivity(intent)
                    } catch (_: Exception) {}
                }
                closeInstant()
                return@launch
            }

            val autoDelay = repository.autoOpenDelay.first()
            val isMapsLink = MapsUtils.isGoogleMapsLink(url)
            
            val preferredApp = if (isMapsLink) {
                repository.autoOpenMapsApp.first()
            } else {
                repository.autoOpenGeoApp.first()
            }

            if (autoDelay == 0 && preferredApp != ReceiverRepository.APP_NONE) {
                executeAutoOpen(url, title, preferredApp)
                closeInstant()
                return@launch
            }

            setContent {
                ReceiverTheme {
                    ChooserDialog(
                        url = url,
                        title = title,
                        preferredApp = preferredApp,
                        initialDelay = autoDelay,
                        onDismiss = { closeInstant() },
                        onOptionSelected = { targetPackage, targetUri ->
                            openWithPackage(targetUri, targetPackage)
                            closeInstant()
                        }
                    )
                }
            }
        }
    }

    private fun closeInstant() {
        finish()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    private fun executeAutoOpen(url: String, title: String, preferredApp: String) {
        when (preferredApp) {
            ReceiverRepository.APP_MAPS -> {
                val coords = MapsUtils.extractCoordinates(url)
                val targetUri = if (coords != null) "geo:$coords?q=$coords" else url
                openWithPackage(targetUri, "com.google.android.apps.maps")
            }
            ReceiverRepository.APP_WAZE -> {
                val targetUri = MapsUtils.getWazeUri(url, title)
                openWithPackage(targetUri, "com.waze")
            }
            ReceiverRepository.APP_OTHER -> {
                val targetUri = MapsUtils.getGenericMapsUri(url)
                openWithPackage(targetUri, null)
            }
        }
    }

    private fun openWithPackage(uri: String, packageName: String?) {
        val intent = Intent(Intent.ACTION_VIEW, uri.toUri()).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            if (packageName != null) {
                setPackage(packageName)
            }
        }

        try {
            startActivity(intent)
        } catch (e: Exception) {
            if (packageName != null) {
                openWithPackage(uri, null)
            } else {
                Toast.makeText(this, "Error opening link", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun isPackageInstalled(packageName: String): Boolean {
        return try {
            packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun ChooserDialog(
        url: String, 
        title: String,
        preferredApp: String,
        initialDelay: Int,
        onDismiss: () -> Unit, 
        onOptionSelected: (String?, String) -> Unit
    ) {
        var timeLeft by remember { mutableIntStateOf(initialDelay) }
        var isAutoOpenEnabled by remember { mutableStateOf(preferredApp != ReceiverRepository.APP_NONE && initialDelay > 0) }

        if (isAutoOpenEnabled && timeLeft > 0) {
            LaunchedEffect(Unit) {
                while (timeLeft > 0) {
                    delay(1000)
                    timeLeft--
                }
                if (isAutoOpenEnabled) {
                    executeAutoOpen(url, title, preferredApp)
                    onDismiss()
                }
            }
        }

        BasicAlertDialog(
            onDismissRequest = onDismiss,
            modifier = Modifier.fillMaxWidth(),
            content = {
                Surface(
                    shape = RoundedCornerShape(28.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 6.dp
                ) {
                    Column(
                        modifier = Modifier
                            .padding(24.dp)
                            .fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = stringResource(R.string.chooser_title),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        
                        Box(
                            modifier = Modifier.fillMaxWidth().height(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            if (isAutoOpenEnabled && timeLeft > 0) {
                                Text(
                                    text = stringResource(R.string.auto_opening_msg, timeLeft),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(8.dp))

                        val scrollState = rememberScrollState()
                        Column(
                            modifier = Modifier
                                .weight(1f, fill = false)
                                .verticalScroll(scrollState),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            val isMapsLink = MapsUtils.isGoogleMapsLink(url)
                            
                            val isPinTitle = title.contains("Segnaposto", ignoreCase = true) || 
                                            title.contains("Pin", ignoreCase = true) ||
                                            title.contains("Marcador", ignoreCase = true) ||
                                            title.contains("Repère", ignoreCase = true) ||
                                            title.contains("Gesetzte Nadel", ignoreCase = true)

                            if (isPinTitle && isMapsLink) {
                                Text(
                                    text = stringResource(R.string.pin_warning_msg),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                    modifier = Modifier.padding(bottom = 12.dp)
                                )
                            }

                            val highlightContainer = MaterialTheme.colorScheme.primaryContainer
                            val highlightContent = MaterialTheme.colorScheme.onPrimaryContainer
                            val normalContainer = MaterialTheme.colorScheme.surfaceVariant
                            val normalContent = MaterialTheme.colorScheme.onSurfaceVariant

                            if (isPackageInstalled("com.google.android.apps.maps")) {
                                ChooserOption(
                                    icon = Icons.Default.Map,
                                    label = stringResource(R.string.app_maps),
                                    containerColor = if (preferredApp == ReceiverRepository.APP_MAPS) highlightContainer else normalContainer,
                                    contentColor = if (preferredApp == ReceiverRepository.APP_MAPS) highlightContent else normalContent,
                                    onClick = { 
                                        isAutoOpenEnabled = false
                                        val coords = MapsUtils.extractCoordinates(url)
                                        val targetUri = if (coords != null) "geo:$coords?q=$coords" else url
                                        onOptionSelected("com.google.android.apps.maps", targetUri) 
                                    }
                                )
                            }

                            if (isPackageInstalled("com.waze")) {
                                ChooserOption(
                                    iconPainter = painterResource(id = R.drawable.ic_waze),
                                    label = stringResource(R.string.app_waze),
                                    containerColor = if (preferredApp == ReceiverRepository.APP_WAZE) highlightContainer else normalContainer,
                                    contentColor = if (preferredApp == ReceiverRepository.APP_WAZE) highlightContent else normalContent,
                                    onClick = { 
                                        isAutoOpenEnabled = false
                                        val targetUri = MapsUtils.getWazeUri(url, title)
                                        onOptionSelected("com.waze", targetUri)
                                    }
                                )
                            }

                            ChooserOption(
                                icon = Icons.Default.Navigation,
                                label = stringResource(R.string.app_other),
                                containerColor = if (preferredApp == ReceiverRepository.APP_OTHER) highlightContainer else normalContainer,
                                contentColor = if (preferredApp == ReceiverRepository.APP_OTHER) highlightContent else normalContent,
                                onClick = { 
                                    isAutoOpenEnabled = false
                                    val targetUri = MapsUtils.getGenericMapsUri(url)
                                    onOptionSelected(null, targetUri) 
                                }
                            )

                            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                            ChooserOption(
                                icon = Icons.Default.ContentCopy,
                                label = stringResource(R.string.btn_copy),
                                onClick = {
                                    isAutoOpenEnabled = false
                                    val textToCopy = if (url.startsWith("geo:", ignoreCase = true)) {
                                        val qIndex = url.indexOf("q=")
                                        if (qIndex != -1) {
                                            val address = url.substring(qIndex + 2)
                                            Uri.decode(address)
                                        } else {
                                            url
                                        }
                                    } else {
                                        url
                                    }
                                    val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                                    val clip = ClipData.newPlainText("url", textToCopy)
                                    clipboard.setPrimaryClip(clip)
                                    Toast.makeText(this@ChooserActivity, R.string.text_copied_toast, Toast.LENGTH_SHORT).show()
                                    onDismiss()
                                }
                            )
                        }
                        
                        TextButton(
                            onClick = {
                                isAutoOpenEnabled = false
                                onDismiss()
                            },
                            modifier = Modifier.align(Alignment.End)
                        ) {
                            Text(stringResource(R.string.btn_cancel))
                        }
                    }
                }
            }
        )
    }

    @Composable
    fun ChooserOption(
        icon: ImageVector? = null,
        iconPainter: Painter? = null,
        label: String,
        containerColor: Color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
        onClick: () -> Unit
    ) {
        Button(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth().height(80.dp).padding(vertical = 6.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = containerColor,
                contentColor = contentColor
            ),
            shape = RoundedCornerShape(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Start
            ) {
                if (icon != null) {
                    Icon(icon, contentDescription = null, modifier = Modifier.size(32.dp))
                } else if (iconPainter != null) {
                    Icon(iconPainter, contentDescription = null, modifier = Modifier.size(32.dp))
                }
                Spacer(modifier = Modifier.width(20.dp))
                Text(text = label, style = MaterialTheme.typography.titleLarge)
            }
        }
    }
}
