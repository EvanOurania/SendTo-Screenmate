package com.example.sendtoscreenmate

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.example.sendtoscreenmate.ui.theme.SendToScreenMateTheme
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

class QuickSendActivity : ComponentActivity() {

    private lateinit var repository: WebhookRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        // Set transparent theme programmatically just in case
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
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingData(intent)
    }

    private fun handleIncomingData(intent: Intent) {
        val fullText = intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT) ?: ""
        val dataString = intent.dataString ?: ""
        
        var url = extractUrl(fullText)
        if (url.isBlank() && dataString.startsWith("geo:")) {
            url = dataString
        }
        
        var title = ""
        if (intent.action == Intent.ACTION_SEND) {
            if (subject.isNotBlank()) {
                title = subject.trim()
            } else if (url.isNotBlank()) {
                val textBeforeUrl = fullText.substringBefore(url).trim()
                if (textBeforeUrl.isNotBlank()) {
                    title = textBeforeUrl.split("\n", "·", " - ").first().trim()
                }
            }
            if (title.isBlank() && url.startsWith("geo:")) {
                title = extractGeoLabel(url)
            }
        } else if (intent.action == Intent.ACTION_VIEW || intent.action == "android.intent.action.NAVIGATE") {
            title = extractGeoLabel(dataString).ifBlank { "Position" }
            if (url.isBlank()) url = dataString
        }

        if (url.isNotBlank()) {
            val geoLabel = if (url.startsWith("geo:")) extractGeoLabel(url) else ""
            if (title.isBlank()) {
                title = if (MapsUtils.isGoogleMapsLink(url) || url.startsWith("geo:")) "Location" else "Link"
            }
            performSendData(url, title)
        } else if (fullText.isNotBlank()) {
            performSendData(fullText, title.ifBlank { "Text Message" })
        } else {
            Toast.makeText(this, getString(R.string.no_data_error), Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun performSendData(data: String, title: String) {
        val json = JSONObject().apply {
            put("url", data)
            put("title", title)
        }
        val finalPayload = json.toString()

        if (finalPayload.length > 3000) {
            Toast.makeText(this, R.string.char_limit_exceeded, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        
        lifecycleScope.launch {
            val serviceType = repository.serviceType.first()
            if (serviceType == WebhookRepository.SERVICE_MACRODROID) {
                val webhookUrl = repository.webhookUrl.first()
                sendToMacroDroid(webhookUrl, data)
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
        if (text.trim().startsWith("geo:", ignoreCase = true)) return text.trim()
        val urlRegex = Regex("((https?://|geo:)[^\\s\\n\\r]+)")
        val match = urlRegex.find(text)
        return match?.value ?: ""
    }

    private fun extractGeoLabel(geoUri: String): String {
        try {
            val qIndex = geoUri.indexOf("q=")
            if (qIndex != -1) {
                var value = geoUri.substring(qIndex + 2)
                val endDelimiters = charArrayOf('&', '@', '#')
                var firstDelimiter = -1
                for (d in endDelimiters) {
                    val idx = value.indexOf(d)
                    if (idx != -1 && (firstDelimiter == -1 || idx < firstDelimiter)) {
                        firstDelimiter = idx
                    }
                }
                if (firstDelimiter != -1) value = value.substring(0, firstDelimiter)
                val decoded = try {
                    URLDecoder.decode(value, StandardCharsets.UTF_8.name()).trim()
                } catch (_: Exception) {
                    value.replace("%20", " ").replace("+", " ").trim()
                }
                val labelMatch = Regex("\\((.+)\\)").find(decoded)
                if (labelMatch != null) return labelMatch.groupValues[1].trim()
                return decoded
            }
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
        } catch (_: Exception) {}
        return ""
    }

    private fun sendToMacroDroid(webhookUrl: String, text: String) {
        lifecycleScope.launch {
            val success = withContext(Dispatchers.IO) {
                try {
                    val client = OkHttpClient()
                    val url = webhookUrl.toHttpUrlOrNull()?.newBuilder()?.addQueryParameter("value", text)?.build()
                    if (url != null) {
                        val request = Request.Builder().url(url).get().build()
                        client.newCall(request).execute().use { it.isSuccessful }
                    } else false
                } catch (_: Exception) { false }
            }
            if (success) Toast.makeText(this@QuickSendActivity, R.string.sent_ntfy, Toast.LENGTH_SHORT).show()
            else Toast.makeText(this@QuickSendActivity, R.string.error_ntfy, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun sendToNtfy(server: String, topic: String, secretKey: String, encryptionEnabled: Boolean, payload: String) {
        lifecycleScope.launch {
            val success = withContext(Dispatchers.IO) {
                try {
                    val client = OkHttpClient()
                    val baseUrl = if (server.endsWith("/")) server else "$server/"
                    val finalUrl = "$baseUrl$topic"
                    val encryptedPayload = if (encryptionEnabled && secretKey.isNotBlank()) CryptoManager.encrypt(payload, secretKey) else payload
                    val request = Request.Builder().url(finalUrl).post(encryptedPayload.toRequestBody("text/plain".toMediaType())).build()
                    client.newCall(request).execute().use { it.isSuccessful }
                } catch (_: Exception) { false }
            }
            if (success) Toast.makeText(this@QuickSendActivity, R.string.sent_ntfy, Toast.LENGTH_SHORT).show()
            else Toast.makeText(this@QuickSendActivity, R.string.error_ntfy, Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
