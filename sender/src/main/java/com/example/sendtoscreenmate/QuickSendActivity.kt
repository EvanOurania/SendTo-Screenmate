package com.example.sendtoscreenmate

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.example.sendtoscreenmate.ui.theme.SendToScreenMateTheme
import kotlinx.coroutines.launch

class QuickSendActivity : ComponentActivity() {

    private lateinit var repository: WebhookRepository
    private var statusText by mutableIntStateOf(R.string.sending_progress)
    // Once the message has left, the user can close the popup instead of waiting for the confirmation
    private var isPublished by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        // Set transparent theme programmatically just in case
        setTheme(R.style.Theme_SendToScreenMate_Transparent)
        window.setBackgroundDrawableResource(android.R.color.transparent)
        super.onCreate(savedInstanceState)
        
        repository = WebhookRepository(this)

        setContent {
            SendToScreenMateTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            enabled = isPublished,
                        ) {
                            Toast.makeText(this@QuickSendActivity, R.string.sent_ntfy, Toast.LENGTH_SHORT).show()
                            finish()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 8.dp,
                        modifier = Modifier.widthIn(min = 120.dp, max = 240.dp),
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier.padding(16.dp),
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(48.dp))
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = stringResource(statusText),
                                style = MaterialTheme.typography.labelMedium,
                                textAlign = TextAlign.Center,
                            )
                            if (isPublished) {
                                Text(
                                    text = stringResource(R.string.tap_to_close),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                )
                            }
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

        val message = when {
            // Shared text, e.g. a place from Google Maps
            intent.action == Intent.ACTION_SEND && fullText.isNotBlank() -> ShareParser.prepareSharedText(fullText, subject)
            // A geo: link opened or "navigated to" with this app
            dataString.startsWith("geo:") -> ShareParser.prepareGeoLink(dataString)
            else -> null
        }

        if (message != null) {
            performSendData(message.data, message.title)
        } else {
            Toast.makeText(this, getString(R.string.no_data_error), Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun performSendData(data: String, title: String) {
        lifecycleScope.launch {
            val result = MessageSender.send(repository, data, title, onPublished = {
                statusText = R.string.waiting_confirmation
                isPublished = true
            })
            result.showToast(this@QuickSendActivity)
            finish()
        }
    }
}
