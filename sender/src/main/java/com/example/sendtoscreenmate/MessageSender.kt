package com.example.sendtoscreenmate

import android.content.Context
import android.widget.Toast
import androidx.annotation.StringRes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
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
    // Gives up on the receipt stream when nothing arrives for this long
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
        val receiptUrl = "$serverUrl/$cleanTopic$DELIVERY_RECEIPT_TOPIC_SUFFIX/json"

        return withContext(Dispatchers.IO) {
            // Listen for the Receiver's receipt *before* publishing: ntfy.sh stores messages a second
            // or two after receiving them, so a receipt sent before we listen would be neither
            // delivered to us live nor found among the stored ones yet
            val receipts = openReceiptStream(receiptUrl)
            try {
                val published = publish(serverUrl, cleanTopic, body) ?: return@withContext SendResult.FAILED
                withContext(Dispatchers.Main) { onPublished() }

                val messageId = published.optString("id")
                val sentTime = published.optLong("time")
                val delivered = when {
                    messageId.isEmpty() -> false
                    receipts != null -> awaitReceipt(receipts, messageId) || hasStoredReceipt(receiptUrl, messageId, sentTime)
                    else -> {
                        // Couldn't listen: give the Receiver time to answer, then look among the stored receipts
                        delay(DELIVERY_RECEIPT_TIMEOUT_MS)
                        hasStoredReceipt(receiptUrl, messageId, sentTime)
                    }
                }
                if (delivered) SendResult.DELIVERED else SendResult.NOT_CONFIRMED
            } finally {
                receipts?.close()
            }
        }
    }

    /** Publishes [body] and returns ntfy's answer (the published message, with its id and time), or null. */
    private fun publish(serverUrl: String, topic: String, body: String): JSONObject? = try {
        val request = Request.Builder()
            .url("$serverUrl/$topic")
            .post(body.toRequestBody("text/plain".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            if (response.isSuccessful) JSONObject(response.body.string()) else null
        }
    } catch (_: Exception) {
        null
    }

    /** Subscribes to the receipt topic and waits until the server confirms it ("open" event), or null. */
    private fun openReceiptStream(receiptUrl: String): Response? {
        val response = try {
            receiptClient.newCall(Request.Builder().url(receiptUrl).build()).execute()
        } catch (_: Exception) {
            return null
        }
        return try {
            if (response.isSuccessful && response.body.source().readUtf8Line() != null) response else null
        } catch (_: Exception) {
            null
        } ?: run {
            response.close()
            null
        }
    }

    /** Reads the receipt stream until [messageId] arrives; false when nothing arrives in time. */
    private fun awaitReceipt(receipts: Response, messageId: String): Boolean = try {
        val source = receipts.body.source()
        generateSequence { source.readUtf8Line() }.any { isReceipt(it, messageId) }
    } catch (_: Exception) {
        false // Read timeout: no receipt within DELIVERY_RECEIPT_TIMEOUT_MS
    }

    /** Looks for the receipt of [messageId] among those the server has stored since [sentTime]. */
    private fun hasStoredReceipt(receiptUrl: String, messageId: String, sentTime: Long): Boolean = try {
        val since = if (sentTime > 0) sentTime.toString() else "all"
        client.newCall(Request.Builder().url("$receiptUrl?poll=1&since=$since").build()).execute().use { response ->
            response.isSuccessful && response.body.string().lineSequence().any { isReceipt(it, messageId) }
        }
    } catch (_: Exception) {
        false
    }

    private fun isReceipt(line: String, messageId: String): Boolean = try {
        val event = JSONObject(line)
        event.optString("event") == "message" && event.optString("message") == messageId
    } catch (_: Exception) {
        false
    }
}
