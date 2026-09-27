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
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import android.net.Uri
import okhttp3.Call
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
    private var lastMessageId: String = ""
    private var latestServerTime: Long = 0
    private var pendingAutoOpen: Job? = null
    private val networkAvailable = Channel<Unit>(Channel.CONFLATED)

    /**
     * Reconnects right away when the device gets back online or switches network (e.g. Wi-Fi to
     * mobile data), instead of waiting for the retry delay or for the dead connection to time out.
     */
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        private var currentNetwork: Network? = null
        private var hasSeenNetwork = false

        override fun onAvailable(network: Network) {
            // Not on the first call, made at registration while the listener is already connecting
            val isNewNetwork = hasSeenNetwork && network != currentNetwork
            currentNetwork = network
            hasSeenNetwork = true
            networkAvailable.trySend(Unit) // Ends a pending retry delay
            if (isNewNetwork) {
                Log.d("NtfyListener", "Network changed - reconnecting")
                serviceScope.launch(Dispatchers.Main) { startListening() }
            }
        }

        override fun onLost(network: Network) {
            if (network == currentNetwork) currentNetwork = null
        }
    }

    private var prefShowRestartBtn = true
    private var prefShowStopBtn = true
    private var prefShowReopenBtn = true

    private val client = OkHttpClient.Builder()
        // ntfy sends a keepalive line every 45s: if nothing arrives for longer than that,
        // the connection is dead (e.g. after a Wi-Fi/mobile switch) and we must reconnect
        .readTimeout(90, TimeUnit.SECONDS)
        .connectTimeout(1, TimeUnit.MINUTES)
        .build()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        acquireWakeLock()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification(getString(R.string.notification_start)))
        observeConnectionSettings()
        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(networkCallback)
    }

    /**
     * Reconnects when topic, server or secret key change in the settings, waiting until the user
     * stops typing so we reconnect once instead of on every keystroke.
     */
    private fun observeConnectionSettings() {
        val repository = ReceiverRepository(this)
        serviceScope.launch(Dispatchers.Main) {
            combine(repository.ntfyTopic, repository.ntfyServer, repository.secretKey) { topic, server, key ->
                Triple(topic, server, key)
            }
                .distinctUntilChanged()
                .drop(1) // The current values are already used by startListening()
                .collectLatest {
                    delay(SETTINGS_CHANGE_DEBOUNCE_MS)
                    Log.d("NtfyListener", "Connection settings changed - restarting listener")
                    startListening()
                }
        }
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        val lock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Receiver::NtfyListener").apply {
            setReferenceCounted(false) // Acquiring it again just extends the timeout
        }
        wakeLock = lock
        // The timeout is only a safety net: renew it well before it expires, for as long as the service runs
        serviceScope.launch {
            while (isActive) {
                lock.acquire(WAKE_LOCK_TIMEOUT_MS)
                delay(WAKE_LOCK_RENEW_INTERVAL_MS)
            }
        }
    }

    private fun closeNotificationPanel() {
        try {
            @Suppress("DEPRECATION")
            sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS))
        } catch (_: SecurityException) {
            // Android 12+ restricts this, but usually allows it if triggered by a notification action
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        when (action) {
            ACTION_STOP -> {
                closeNotificationPanel()
                updateNotification(getString(R.string.notification_stopping))
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RESTART -> {
                closeNotificationPanel()
                Toast.makeText(this, getString(R.string.toast_restarted), Toast.LENGTH_SHORT).show()
                
                // Show "Restarting..." then start listening immediately
                updateNotification(getString(R.string.notification_restarting))
                startListening()
                return START_STICKY
            }
        }

        startListening()
        return START_STICKY
    }

    private fun startListening() {
        Log.d("NtfyListener", "startListening() called - cancelling previous job if any")
        listeningJob?.cancel()
        listeningJob = serviceScope.launch {
            Log.d("NtfyListener", "Coroutine started: fetching preferences")
            val repository = ReceiverRepository(this@NtfyListenerService)
            val historyRepo = HistoryRepository(this@NtfyListenerService)
            
            // Monitor preferences
            launch { repository.showRestartButton.collect { prefShowRestartBtn = it } }
            launch { repository.showStopButton.collect { prefShowStopBtn = it } }
            launch { repository.showReopenButton.collect { prefShowReopenBtn = it } }
            
            val topic = repository.ntfyTopic.first().trim()
            val server = ReceiverRepository.normalizeServerUrl(repository.ntfyServer.first())
            val secretKey = repository.secretKey.first()
            val persistedLastTime = repository.lastMessageTime.first()
            val persistedLastId = repository.lastMessageId.first()

            // Initialize lastReceivedUrl from history for the "Reopen" button
            if (lastReceivedUrl == null) {
                historyRepo.historyItems.first().firstOrNull()?.let {
                    lastReceivedUrl = MapsUtils.getGenericMapsUri(it.url)
                    lastReceivedTitle = it.title
                }
            }

            if (topic.isBlank()) {
                updateNotification(getString(R.string.notification_topic_not_set))
                return@launch
            }
            if (!ReceiverRepository.isValidTopic(topic)) {
                updateNotification(getString(R.string.notification_topic_invalid))
                return@launch
            }

            if (lastMessageTime == 0L) {
                lastMessageTime = persistedLastTime
            }
            if (lastMessageId.isEmpty()) {
                lastMessageId = persistedLastId
            }

            var retryDelayMs = INITIAL_RETRY_DELAY_MS
            while (isActive) {
                try {
                    // Resume right after the last handled message. A message id is exclusive, while a
                    // timestamp is inclusive and would deliver the last message a second time.
                    // With nothing stored (first run) skip old messages instead of replaying all of them.
                    val sinceParam = when {
                        lastMessageId.isNotEmpty() -> lastMessageId
                        lastMessageTime > 0 -> lastMessageTime.toString()
                        else -> "none"
                    }
                    val url = "${server.trimEnd('/')}/$topic/json?since=$sinceParam"
                    val request = Request.Builder()
                        .url(url)
                        .build()

                    Log.d("NtfyListener", "Executing HTTP Request to $url")
                    val call = client.newCall(request)
                    val callGuard = cancelCallWhenCancelled(call)
                    try {
                        call.execute().use { response ->
                            if (!response.isSuccessful) {
                                Log.e("NtfyListener", "HTTP Error: ${response.code}")
                                updateNotification("Error: ${response.code}")
                                delay(10000)
                                return@use
                            }

                            // Success! Update notification if we just reconnected
                            Log.d("NtfyListener", "Connection successful! Listening for stream...")
                            updateNotification(getString(R.string.notification_listening, topic, server))
                            retryDelayMs = INITIAL_RETRY_DELAY_MS

                            val reader = response.body.source().inputStream().bufferedReader()
                            reader.let { br ->
                                while (isActive) {
                                    val line = br.readLine() ?: break
                                    if (!isActive) break
                                    Log.d("NtfyListener", "Received line from stream")
                                    // processLine() updates the notification itself when something changes
                                    processLine(line, secretKey)
                                }
                                Log.d("NtfyListener", "Stream ended or coroutine inactive")
                            }
                        }
                    } finally {
                        callGuard.cancel()
                    }
                } catch (e: Exception) {
                    Log.e("NtfyListener", "Exception in connection loop", e)
                    if (isActive) {
                        updateNotification(getString(R.string.notification_conn_lost))
                        waitBeforeReconnect(retryDelayMs)
                        // Space out retries while the server stays unreachable, to save battery
                        retryDelayMs = (retryDelayMs * 2).coerceAtMost(MAX_RETRY_DELAY_MS)
                    }
                }
            }
        }
    }

    private fun processLine(line: String, secretKey: String) {
        try {
            val json = JSONObject(line)
            
            // Store last message time to resume properly after disconnect
            val time = json.optLong("time")
            if (time > 0) {
                lastMessageTime = time
                latestServerTime = maxOf(latestServerTime, time)
                // Persist to storage
                serviceScope.launch {
                    val repository = ReceiverRepository(this@NtfyListenerService)
                    repository.saveLastMessageTime(time)
                }
            }

            if (json.optString("event") == "message") {
                val messageId = json.optString("id")
                if (messageId.isNotEmpty()) {
                    lastMessageId = messageId
                    serviceScope.launch {
                        val repository = ReceiverRepository(this@NtfyListenerService)
                        repository.saveLastMessageId(messageId)
                    }
                }

                val rawMessage = json.optString("message")
                
                val decryptedMessage = if (secretKey.isNotBlank()) {
                    try {
                        CryptoManager.decrypt(rawMessage, secretKey)
                    } catch (_: Exception) {
                        if (CryptoManager.looksEncrypted(rawMessage)) {
                            // Encrypted with a different key: its content is unreadable, don't try to open it
                            Log.w("NtfyListener", "Ignoring message encrypted with a different secret key")
                            updateNotification(getString(R.string.notification_message_rejected))
                            return
                        }
                        // Sent in plain text (encryption turned off in the Sender)
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

                // --- IMPROVED HISTORY TITLE EXTRACTION ---
                // If title is blank or generic, try to extract it from the URL
                val refinedTitle = if (displayTitle.isBlank() || displayTitle.lowercase() == "location") {
                    val extracted = MapsUtils.extractPlaceName(targetUrl)
                    extracted ?: displayTitle
                } else {
                    displayTitle
                }

                // Add to history
                serviceScope.launch {
                    val historyRepo = HistoryRepository(this@NtfyListenerService)
                    val historyTimestamp = if (time > 0) time * 1000 else System.currentTimeMillis()
                    historyRepo.addHistoryItem(refinedTitle, targetUrl, historyTimestamp)
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

                val finalUrl = MapsUtils.getGenericMapsUri(targetUrl)

                // If it's a URL/Location, we process it normally.
                // If it's plain text, we still process it if auto-copy is enabled.
                if (finalUrl.isNotBlank() || decryptedMessage.isNotBlank()) {
                    if (finalUrl.isNotBlank()) {
                        lastReceivedUrl = finalUrl
                        lastReceivedTitle = refinedTitle
                        updateNotification(getString(R.string.notification_title))
                    }

                    // After a reconnect the server replays the missed messages in a quick burst:
                    // only the newest one opens, and only if it is still recent. All of them
                    // are already in the history and the newest one stays in the notification.
                    val isRecent = time <= 0 || latestServerTime - time <= MAX_AUTO_OPEN_AGE_SECONDS
                    if (!isRecent) return
                    pendingAutoOpen?.cancel()
                    pendingAutoOpen = serviceScope.launch {
                        delay(BURST_DEBOUNCE_MS)
                        val repository = ReceiverRepository(this@NtfyListenerService)
                        val autoCopyEnabled = repository.copyToClipboard.first()
                        val autoDelay = repository.autoOpenDelay.first()
                        val preferredApp = if (isMapsLink) {
                            repository.autoOpenMapsApp.first()
                        } else {
                            repository.autoOpenGeoApp.first()
                        }
                        // Only locations go to the navigator; other links (web pages) open normally below
                        val isLocation = isMapsLink || finalUrl.startsWith("geo:")

                        if (autoCopyEnabled) {
                            copyReceivedText(targetUrl)
                        }

                        // THE SPLIT-SCREEN SAVER: If delay is 0, launch directly from Service.
                        // This bypasses ChooserActivity task manipulation and keeps split-screen intact.
                        if (isLocation && autoDelay == 0 && preferredApp != ReceiverRepository.APP_NONE) {
                            // Build the final URI for direct launch
                            val targetUri = if (preferredApp == ReceiverRepository.APP_WAZE) {
                                MapsUtils.getWazeUriResolvingShortLink(rawMapsUrl, displayTitle)
                            } else if (preferredApp == ReceiverRepository.APP_MAPS) {
                                val coords = MapsUtils.extractCoordinates(rawMapsUrl)
                                if (coords != null) "geo:$coords?q=$coords" else rawMapsUrl
                            } else {
                                finalUrl
                            }

                            // Target the chosen app explicitly, otherwise a geo: link may open in another navigator
                            val targetPackage = when (preferredApp) {
                                ReceiverRepository.APP_MAPS -> "com.google.android.apps.maps"
                                ReceiverRepository.APP_WAZE -> "com.waze"
                                else -> null
                            }
                            val directIntent = Intent(Intent.ACTION_VIEW, targetUri.toUri()).apply {
                                // MINIMAL FLAGS: NEW_TASK is required from service, SINGLE_TOP preserves the split-screen activity
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                                setPackage(targetPackage)
                            }
                            try {
                                startActivity(directIntent)
                            } catch (_: Exception) {
                                // Chosen app not installed: let Android pick one, like ChooserActivity does
                                if (targetPackage != null) {
                                    try {
                                        startActivity(directIntent.setPackage(null))
                                    } catch (_: Exception) {}
                                }
                            }
                            return@launch
                        }

                        if (isLocation) {
                            // For locations with delay, use ChooserActivity
                            val intent = Intent(this@NtfyListenerService, ChooserActivity::class.java).apply {
                                // Minimalist flags are safer for split-screen
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                putExtra("url", rawMapsUrl) 
                                putExtra("title", displayTitle)
                            }
                            startActivity(intent)
                        } else {
                            openUrl(finalUrl)
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // Ignore parse errors
        }
    }

    /**
     * Android 10+ only forbids *reading* the clipboard from the background, not writing to it,
     * so no activity has to be opened for this.
     */
    private suspend fun copyReceivedText(text: String) = withContext(Dispatchers.Main) {
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("url", text))
        Toast.makeText(this@NtfyListenerService, R.string.text_copied_toast, Toast.LENGTH_SHORT).show()
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
        
        val restartIntent = Intent(this, NtfyListenerService::class.java).apply {
            action = ACTION_RESTART
        }
        val restartPendingIntent = PendingIntent.getService(
            this, 2, restartIntent, PendingIntent.FLAG_IMMUTABLE
        )

        val mainIntent = Intent(this, MainActivity::class.java)
        val mainPendingIntent = PendingIntent.getActivity(
            this, 0, mainIntent, PendingIntent.FLAG_IMMUTABLE
        )

        // THE FIX: Title is now static "In ascolto", Content shows the last link name
        val isSpecialStatus = content == getString(R.string.notification_restarting) ||
            content == getString(R.string.notification_conn_lost) ||
            content == getString(R.string.notification_message_rejected) ||
            content == getString(R.string.notification_topic_not_set) ||
            content == getString(R.string.notification_topic_invalid)
        
        val notificationTitle = if (isSpecialStatus) {
            content
        } else {
            getString(R.string.notification_title)
        }
        
        val notificationContent = if (!lastReceivedTitle.isNullOrBlank()) {
            lastReceivedTitle!!
        } else if (!isSpecialStatus && content.isNotBlank()) {
            content
        } else {
            getString(R.string.notification_active)
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(notificationTitle)
            .setContentText(notificationContent)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(mainPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true) // Status updates must not ring or vibrate every time
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)

        if (prefShowRestartBtn) {
            builder.addAction(android.R.drawable.ic_popup_sync, getString(R.string.btn_restart), restartPendingIntent)
        }
        
        if (prefShowStopBtn) {
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.btn_stop), stopPendingIntent)
        }

        lastReceivedUrl?.let { url ->
            if (prefShowReopenBtn) {
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
        }

        return builder.build()
    }

    private fun updateNotification(content: String) {
        val notification = createNotification(content)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    /**
     * OkHttp's blocking reads ignore coroutine cancellation: without this, a stopped or restarted
     * listener would keep its old connection open (and keep handling messages) until the next line arrives.
     */
    private fun CoroutineScope.cancelCallWhenCancelled(call: Call): Job =
        launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                call.cancel()
            }
        }

    /** Waits [delayMs] before the next connection attempt, or less if a network becomes available. */
    private suspend fun waitBeforeReconnect(delayMs: Long) {
        networkAvailable.tryReceive() // Ignore a signal from before the connection failed
        withTimeoutOrNull(delayMs) { networkAvailable.receive() }
    }

    override fun onDestroy() {
        try {
            getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback)
        } catch (_: IllegalArgumentException) {
            // Not registered
        }
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
        const val ACTION_RESTART = "RESTART_SERVICE"

        private const val SETTINGS_CHANGE_DEBOUNCE_MS = 1500L
        private const val WAKE_LOCK_TIMEOUT_MS = 24 * 60 * 60 * 1000L
        private const val WAKE_LOCK_RENEW_INTERVAL_MS = 12 * 60 * 60 * 1000L
        private const val BURST_DEBOUNCE_MS = 500L
        private const val INITIAL_RETRY_DELAY_MS = 5_000L
        private const val MAX_RETRY_DELAY_MS = 60_000L
        // Links older than this (e.g. sent while the device was off) go to history but don't open by themselves
        private const val MAX_AUTO_OPEN_AGE_SECONDS = 30 * 60L

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
