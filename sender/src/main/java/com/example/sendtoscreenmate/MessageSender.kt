package com.example.sendtoscreenmate

import android.content.Context
import android.widget.Toast
import androidx.annotation.StringRes
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/** Outcome of a send attempt, with the message to show to the user. */
enum class SendResult(
    @StringRes val messageRes: Int,
    private val isLongMessage: Boolean = false,
    /** The message left this device (whether or not the Receiver confirmed it). */
    val isSent: Boolean = false,
) {
    /** Sent through MacroDroid, which cannot confirm delivery. */
    SENT(R.string.sent_ntfy, isSent = true),
    /** The Receiver confirmed it got the message. */
    DELIVERED(R.string.delivered, isSent = true),
    /** Sent, but the Receiver didn't confirm in time (off or offline): it gets it once back online. */
    NOT_CONFIRMED(R.string.not_confirmed, isLongMessage = true, isSent = true),
    FAILED(R.string.error_ntfy),
    TOO_LONG(R.string.char_limit_exceeded, isLongMessage = true),
    MISSING_KEY(R.string.missing_key_error, isLongMessage = true),
    INVALID_TOPIC(R.string.invalid_topic_error, isLongMessage = true);

    fun showToast(context: Context) {
        Toast.makeText(context, messageRes, if (isLongMessage) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }
}

/** Sends links and text to the Receiver, shared by MainActivity and QuickSendActivity. */
object MessageSender {
    private const val MAX_PAYLOAD_LENGTH = 3000
    // ntfy turns longer messages into file attachments, which the Receiver can't read. Encryption and
    // non-ASCII characters (accents, emoji) make a message bigger than its number of characters.
    private const val NTFY_MAX_MESSAGE_BYTES = 4096

    // The Receiver publishes the id of each message it gets on "<topic>_ack".
    // Must match DELIVERY_RECEIPT_TOPIC_SUFFIX in the Receiver's NtfyListenerService.
    private const val DELIVERY_RECEIPT_TOPIC_SUFFIX = "_ack"
    private const val DELIVERY_RECEIPT_TIMEOUT_MS = 10_000L

    private val client = OkHttpClient()
    // The receipt topic is streamed until the receipt arrives or we give up
    private val receiptClient = OkHttpClient.Builder()
        .readTimeout(DELIVERY_RECEIPT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    /**
     * Sends [data] with its [title] through the service chosen in the settings (ntfy or MacroDroid).
     * With ntfy it then waits for the Receiver to confirm; [onPublished] runs as soon as the message
     * has left, before that wait.
     */
    suspend fun send(
        repository: WebhookRepository,
        data: String,
        title: String,
        onPublished: () -> Unit = {},
    ): SendResult {
        val payload = JSONObject().apply {
            put("url", data)
            put("title", title)
        }.toString()
        if (payload.length > MAX_PAYLOAD_LENGTH) return SendResult.TOO_LONG

        return if (repository.serviceType.first() == WebhookRepository.SERVICE_MACRODROID) {
            sendToMacroDroid(repository.webhookUrl.first(), data)
        } else {
            sendToNtfy(
                server = repository.ntfyServer.first(),
                topic = repository.ntfyTopic.first(),
                secretKey = repository.secretKey.first(),
                encryptionEnabled = repository.encryptionEnabled.first(),
                payload = payload,
                onPublished = onPublished,
            )
        }
    }

    private suspend fun sendToMacroDroid(webhookUrl: String, text: String): SendResult = withContext(Dispatchers.IO) {
        try {
            val url = webhookUrl.toHttpUrlOrNull()?.newBuilder()?.addQueryParameter("value", text)?.build()
                ?: return@withContext SendResult.FAILED
            val request = Request.Builder().url(url).get().build()
            val success = client.newCall(request).execute().use { it.isSuccessful }
            if (success) SendResult.SENT else SendResult.FAILED
        } catch (_: Exception) {
            SendResult.FAILED
        }
    }

    private suspend fun sendToNtfy(
        server: String,
        topic: String,
        secretKey: String,
        encryptionEnabled: Boolean,
        payload: String,
        onPublished: () -> Unit,
    ): SendResult {
        // Encryption on but no key: refuse rather than silently sending in plain text
        if (encryptionEnabled && secretKey.isBlank()) return SendResult.MISSING_KEY
        val cleanTopic = topic.trim()
        if (!WebhookRepository.isValidTopic(cleanTopic)) return SendResult.INVALID_TOPIC

        val body = if (encryptionEnabled) CryptoManager.encrypt(payload, secretKey) else payload
        if (body.toByteArray(Charsets.UTF_8).size > NTFY_MAX_MESSAGE_BYTES) return SendResult.TOO_LONG

        val serverUrl = WebhookRepository.normalizeServerUrl(server)
        // ntfy answers with the published message, whose id and time we need to find its receipt
        val published = withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("$serverUrl/$cleanTopic")
                    .post(body.toRequestBody("text/plain".toMediaType()))
                    .build()
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) JSONObject(response.body.string()) else null
                }
            } catch (_: Exception) {
                null
            }
        } ?: return SendResult.FAILED
        onPublished()

        val delivered = withTimeoutOrNull(DELIVERY_RECEIPT_TIMEOUT_MS) {
            awaitDeliveryReceipt(serverUrl, cleanTopic, published.optString("id"), published.optLong("time"))
        } ?: false
        return if (delivered) SendResult.DELIVERED else SendResult.NOT_CONFIRMED
    }

    /** Waits on the receipt topic for the Receiver to publish [messageId]. */
    private suspend fun awaitDeliveryReceipt(serverUrl: String, topic: String, messageId: String, sentTime: Long): Boolean {
        if (messageId.isEmpty()) return false
        // since=<time> also returns a receipt that arrived before we started listening
        val url = "$serverUrl/$topic$DELIVERY_RECEIPT_TOPIC_SUFFIX/json?since=${if (sentTime > 0) sentTime else "all"}"
        val call = receiptClient.newCall(Request.Builder().url(url).build())
        return withContext(Dispatchers.IO) {
            // Blocking reads ignore coroutine cancellation (e.g. the timeout): abort the call instead
            val callGuard = launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    awaitCancellation()
                } finally {
                    call.cancel()
                }
            }
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) return@use false
                    val source = response.body.source()
                    var received = false
                    while (!received) {
                        val line = source.readUtf8Line() ?: break
                        val event = JSONObject(line)
                        received = event.optString("event") == "message" && event.optString("message") == messageId
                    }
                    received
                }
            } catch (_: Exception) {
                false
            } finally {
                callGuard.cancel()
            }
        }
    }

    /** The first link (http, https or geo) in [text], or an empty string if there is none. */
    fun extractUrl(text: String): String {
        if (text.trim().startsWith("geo:", ignoreCase = true)) return text.trim()
        val urlRegex = Regex("((https?://|geo:)[^\\s\\n\\r]+)")
        val match = urlRegex.find(text)
        return match?.value ?: ""
    }

    /** The place name or address in a geo: link (its q= parameter or its label), or an empty string. */
    fun extractGeoLabel(geoUri: String): String {
        try {
            val qIndex = geoUri.indexOf("q=")
            if (qIndex != -1) {
                var value = geoUri.substring(qIndex + 2)
                val endDelimiters = charArrayOf('&', '@', '#')
                var firstDelimiter = -1
                for (d in endDelimiters) {
                    val idx = value.indexOf(d)
                    if (idx != -1 && (firstDelimiter == -1 || idx < firstDelimiter)) firstDelimiter = idx
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
}
