package com.example.receiver

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import android.net.Uri
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class NtfyListenerService : Service() {

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private var listeningJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastReceivedUrl: String? = null
    private var lastReceivedTitle: String? = null
    private var lastMessageTime: Long = 0

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS) // Disable timeout for long polling/streaming
        .connectTimeout(1, TimeUnit.MINUTES)
        .build()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        acquireWakeLock()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification(getString(R.string.notification_start)))
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Receiver::NtfyListener")
        wakeLock?.acquire(24 * 60 * 60 * 1000L) // 24 hours
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        when (action) {
            ACTION_STOP -> {
                updateNotification(getString(R.string.notification_stopping))
                stopSelf()
                return START_NOT_STICKY
            }
        }

        startListening()
        return START_STICKY
    }

    private fun startListening() {
        listeningJob?.cancel()
        listeningJob = serviceScope.launch {
            val repository = ReceiverRepository(this@NtfyListenerService)
            val historyRepo = HistoryRepository(this@NtfyListenerService)
            
            val topic = repository.ntfyTopic.first()
            val server = repository.ntfyServer.first()
            val secretKey = repository.secretKey.first()
            val copyToClipboard = repository.copyToClipboard.first()
            val persistedLastTime = repository.lastMessageTime.first()

            // Initialize lastReceivedUrl from history for the "Reopen" button
            if (lastReceivedUrl == null) {
                historyRepo.historyItems.first().firstOrNull()?.let {
                    lastReceivedUrl = formatTargetUrl(it.url)
                    lastReceivedTitle = it.title
                }
            }

            if (topic.isBlank()) {
                updateNotification(getString(R.string.notification_topic_not_set))
                return@launch
            }

            updateNotification(getString(R.string.notification_listening, topic, server))
            
            if (lastMessageTime == 0L) {
                lastMessageTime = persistedLastTime
            }

            while (isActive) {
                try {
                    val sinceParam = if (lastMessageTime == 0L) "all" else lastMessageTime.toString()
                    val url = "${server.trimEnd('/')}/$topic/json?since=$sinceParam"
                    val request = Request.Builder()
                        .url(url)
                        .build()

                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            updateNotification("Error: ${response.code}")
                            delay(10000)
                            return@use
                        }

                        val reader = response.body.source().inputStream().bufferedReader()
                        reader.let { br ->
                            while (isActive) {
                                val line = br.readLine() ?: break
                                processLine(line, secretKey, copyToClipboard)
                                updateNotification(getString(R.string.notification_listening, topic, server))
                            }
                        }
                    }
                } catch (_: Exception) {
                    if (isActive) {
                        updateNotification(getString(R.string.notification_conn_lost))
                        delay(5000)
                    }
                }
            }
        }
    }

    private fun processLine(line: String, secretKey: String, copyToClipboard: Boolean) {
        try {
            val json = JSONObject(line)
            
            // Store last message time to resume properly after disconnect
            val time = json.optLong("time")
            if (time > 0) {
                lastMessageTime = time
                // Persist to storage
                serviceScope.launch {
                    val repository = ReceiverRepository(this@NtfyListenerService)
                    repository.saveLastMessageTime(time)
                }
            }

            if (json.optString("event") == "message") {
                val rawMessage = json.optString("message")
                
                val decryptedMessage = if (secretKey.isNotBlank()) {
                    try {
                        CryptoManager.decrypt(rawMessage, secretKey)
                    } catch (_: Exception) {
                        rawMessage
                    }
                } else {
                    rawMessage
                }

                // Try to parse the message as JSON to get the title and URL
                var displayTitle: String
                var targetUrl: String

                val messageToParse = decryptedMessage.trim()
                if (messageToParse.startsWith("{") && messageToParse.endsWith("}")) {
                    try {
                        val msgJson = JSONObject(messageToParse)
                        targetUrl = msgJson.optString("url")
                        displayTitle = msgJson.optString("title")
                    } catch (_: Exception) {
                        targetUrl = decryptedMessage
                        displayTitle = ""
                    }
                } else {
                    targetUrl = decryptedMessage
                    displayTitle = ""
                }

                if (targetUrl.isBlank()) return

                // Note: Background clipboard access is restricted on Android 10+.
                // We now handle auto-copy inside ChooserActivity which is a foreground activity.

                // --- IMPROVED HISTORY TITLE EXTRACTION ---
                // If title is blank or generic, try to extract it from the URL
                val refinedTitle = if (displayTitle.isBlank() || displayTitle.lowercase() == "location") {
                    val extracted = extractPlaceNameFromUrl(targetUrl)
                    extracted ?: displayTitle
                } else {
                    displayTitle
                }

                // Add to history
                serviceScope.launch {
                    val historyRepo = HistoryRepository(this@NtfyListenerService)
                    historyRepo.addHistoryItem(refinedTitle, targetUrl, time)
                }

                // --- CRITICAL FIX 1 & 4: Detection & Correct URL passing ---
                // Detect if there's a Google Maps link ANYWHERE in the DECRYPTED message
                val isMapsLink = MapsUtils.isGoogleMapsLink(decryptedMessage)
                
                // Extract the cleanest possible URL for the Chooser
                val rawMapsUrl = if (targetUrl.contains("http")) {
                    targetUrl 
                } else if (decryptedMessage.contains("http")) {
                    // Extract link from text if it's not the primary URL field
                    val match = Regex("https?://[^\\s\\n\\r]+").find(decryptedMessage)
                    match?.value ?: targetUrl
                } else {
                    targetUrl
                }

                val finalUrl = formatTargetUrl(targetUrl)

                // If it's a URL/Location, we process it normally.
                // If it's plain text, we still process it if auto-copy is enabled.
                if (finalUrl.isNotBlank() || decryptedMessage.isNotBlank()) {
                    if (finalUrl.isNotBlank()) {
                        lastReceivedUrl = finalUrl
                        lastReceivedTitle = refinedTitle
                        updateNotification(getString(R.string.notification_title))
                    }
                    
                    serviceScope.launch {
                        val repository = ReceiverRepository(this@NtfyListenerService)
                        val autoCopyEnabled = repository.copyToClipboard.first()
                        val autoDelay = repository.autoOpenDelay.first()
                        val preferredApp = if (isMapsLink) {
                            repository.autoOpenMapsApp.first()
                        } else {
                            repository.autoOpenGeoApp.first()
                        }

                        // THE SPLIT-SCREEN SAVER: If delay is 0, launch directly from Service.
                        // This bypasses ChooserActivity task manipulation and keeps split-screen intact.
                        if (autoDelay == 0 && preferredApp != ReceiverRepository.APP_NONE && finalUrl.isNotBlank()) {
                            // Still handle auto-copy if enabled
                            if (autoCopyEnabled) {
                                val intent = Intent(this@NtfyListenerService, ChooserActivity::class.java).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                                    val textToCopy = if (targetUrl.isNotBlank()) targetUrl else decryptedMessage
                                    putExtra("url", textToCopy)
                                    putExtra("mode", "COPY_ONLY")
                                }
                                startActivity(intent)
                            }

                            // Build the final URI for direct launch
                            val coords = extractCoordinates(rawMapsUrl)
                            val targetUri = if (preferredApp == ReceiverRepository.APP_WAZE) {
                                if (coords != null) {
                                    "waze://?ll=$coords&navigate=yes"
                                } else {
                                    "waze://?q=${Uri.encode(rawMapsUrl)}&navigate=yes"
                                }
                            } else if (preferredApp == ReceiverRepository.APP_MAPS) {
                                if (coords != null) {
                                    "geo:$coords?q=$coords"
                                } else {
                                    rawMapsUrl
                                }
                            } else {
                                finalUrl
                            }

                            val directIntent = Intent(Intent.ACTION_VIEW, targetUri.toUri()).apply {
                                // MINIMAL FLAGS: NEW_TASK is required from service, SINGLE_TOP preserves the split-screen activity
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                            }
                            try {
                                startActivity(directIntent)
                            } catch (_: Exception) {}
                            return@launch
                        }

                        if (finalUrl.isNotBlank() && (isMapsLink || finalUrl.startsWith("geo:"))) {
                            // For locations with delay, use ChooserActivity
                            val intent = Intent(this@NtfyListenerService, ChooserActivity::class.java).apply {
                                // Minimalist flags are safer for split-screen
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                putExtra("url", rawMapsUrl) 
                                putExtra("title", displayTitle)
                            }
                            startActivity(intent)
                        } else if (autoCopyEnabled) {
                            // For generic text/links, trigger ChooserActivity for background copy bypass
                            val intent = Intent(this@NtfyListenerService, ChooserActivity::class.java).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                val textToCopy = if (targetUrl.isNotBlank()) targetUrl else decryptedMessage
                                putExtra("url", textToCopy)
                                
                                if (finalUrl.isBlank()) {
                                    putExtra("mode", "COPY_ONLY")
                                } else {
                                    putExtra("mode", "COPY_AND_OPEN_DIRECT")
                                }
                            }
                            startActivity(intent)
                        } else if (finalUrl.isNotBlank()) {
                            // No copy, but we have a valid URL: just open it normally
                            openUrl(finalUrl)
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // Ignore parse errors
        }
    }

    private fun formatTargetUrl(targetUrl: String): String {
        // Detect raw Google Maps links (e.g. from "Send as Location" or "Send Text/Link")
        val isGoogleMaps = MapsUtils.isGoogleMapsLink(targetUrl)

        return if (targetUrl.startsWith("http") || targetUrl.startsWith("geo:") || isGoogleMaps) {
            if (isGoogleMaps && !targetUrl.startsWith("geo:") && !targetUrl.startsWith("http")) {
                "geo:0,0?q=${Uri.encode(targetUrl)}"
            } else {
                targetUrl
            }
        } else {
            ""
        }
    }

    private fun extractPlaceNameFromUrl(url: String): String? {
        val decodedUrl = Uri.decode(url)
        val placeRegex = Regex("/maps/place/([^/]+)")
        val match = placeRegex.find(decodedUrl)
        return match?.groupValues?.get(1)?.replace('+', ' ')
    }

    private fun extractCoordinates(url: String): String? {
        val decodedUrl = Uri.decode(url)
        val preciseLatRegex = Regex("!3d([-+]?\\d+\\.\\d+)")
        val preciseLonRegex = Regex("!4d([-+]?\\d+\\.\\d+)")
        val latMatch = preciseLatRegex.find(decodedUrl)
        val lonMatch = preciseLonRegex.find(decodedUrl)
        if (latMatch != null && lonMatch != null) {
            return "${latMatch.groupValues[1]},${lonMatch.groupValues[1]}"
        }
        val queryRegex = Regex("query=([-+]?\\d+\\.\\d+),([-+]?\\d+\\.\\d+)")
        val queryMatch = queryRegex.find(decodedUrl)
        if (queryMatch != null) {
            return "${queryMatch.groupValues[1]},${queryMatch.groupValues[2]}"
        }
        if (!url.contains("google.") && !url.contains("goo.gl")) {
            val coordRegex = Regex("([-+]?\\d+\\.\\d+)\\s*,\\s*([-+]?\\d+\\.\\d+)")
            val match = coordRegex.find(decodedUrl)
            if (match != null) {
                return "${match.groupValues[1]},${match.groupValues[2]}"
            }
        }
        return null
    }

    private fun copyToClipboard(text: String) {
        try {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("received text", text)
            clipboard.setPrimaryClip(clip)
            
            CoroutineScope(Dispatchers.Main).launch {
                Toast.makeText(applicationContext, R.string.text_copied_toast, Toast.LENGTH_SHORT).show()
            }
        } catch (_: Exception) {}
    }

    private fun openUrl(url: String) {
        // If it's a location or Maps link, use our custom chooser
        val isMaps = MapsUtils.isGoogleMapsLink(url)
        val isGeo = url.startsWith("geo:")

        if (isMaps || isGeo) {
            val intent = Intent(this, ChooserActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra("url", url)
                // We don't have a title here for notification reopens, so we'll pass an empty string
                putExtra("title", "")
            }
            startActivity(intent)
        } else {
            // Direct browser opening for standard links
            val intent = Intent(Intent.ACTION_VIEW, url.toUri()).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            try {
                startActivity(intent)
            } catch (_: Exception) {}
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Ntfy Listener Service",
                NotificationManager.IMPORTANCE_DEFAULT,
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(content: String): Notification {
        val stopIntent = Intent(this, NtfyListenerService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE
        )

        val mainIntent = Intent(this, MainActivity::class.java)
        val mainPendingIntent = PendingIntent.getActivity(
            this, 0, mainIntent, PendingIntent.FLAG_IMMUTABLE
        )

        // THE FIX: Title is now static "In ascolto", Content shows the last link name
        val notificationTitle = getString(R.string.notification_title)
        val notificationContent = if (!lastReceivedTitle.isNullOrBlank()) {
            lastReceivedTitle!!
        } else {
            getString(R.string.notification_active)
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(notificationTitle)
            .setContentText(notificationContent)
            .setSmallIcon(R.drawable.ic_notification) // THE FIX: Monochrome icon for modern Android
            .setContentIntent(mainPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.btn_stop), stopPendingIntent)

        lastReceivedUrl?.let { url ->
            val isMaps = MapsUtils.isGoogleMapsLink(url)
            val isGeo = url.startsWith("geo:")
            
            val reopenIntent = if (isMaps || isGeo) {
                Intent(this, ChooserActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra("url", url)
                }
            } else {
                Intent(Intent.ACTION_VIEW, url.toUri()).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }

            val reopenPendingIntent = PendingIntent.getActivity(
                this, 1, reopenIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.addAction(android.R.drawable.ic_menu_revert, getString(R.string.btn_reopen), reopenPendingIntent)
        }

        return builder.build()
    }

    private fun updateNotification(content: String) {
        val notification = createNotification(content)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        serviceJob.cancel()
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "ntfy_listener_channel"
        private const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "STOP_SERVICE"

        @Suppress("DEPRECATION")
        fun isRunning(context: Context): Boolean {
            val manager = context.getSystemService(ACTIVITY_SERVICE) as ActivityManager
            for (service in manager.getRunningServices(Int.MAX_VALUE)) {
                if (NtfyListenerService::class.java.name == service.service.className) {
                    return true
                }
            }
            return false
        }
    }
}
