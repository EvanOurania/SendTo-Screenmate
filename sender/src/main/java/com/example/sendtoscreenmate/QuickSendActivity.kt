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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.example.sendtoscreenmate.ui.theme.SendToScreenMateTheme
import kotlinx.coroutines.launch

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
        
        var url = MessageSender.extractUrl(fullText)
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
                title = MessageSender.extractGeoLabel(url)
            }
        } else if (intent.action == Intent.ACTION_VIEW || intent.action == "android.intent.action.NAVIGATE") {
            title = MessageSender.extractGeoLabel(dataString).ifBlank { "Position" }
            if (url.isBlank()) url = dataString
        }

        if (url.isNotBlank()) {
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
        lifecycleScope.launch {
            MessageSender.send(repository, data, title).showToast(this@QuickSendActivity)
            finish()
        }
    }
}
