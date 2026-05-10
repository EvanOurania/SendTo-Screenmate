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
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class NtfyListenerService : Service() {

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private var listeningJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

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
        if (action == ACTION_STOP) {
            updateNotification(getString(R.string.notification_stopping))
            stopSelf()
            return START_NOT_STICKY
        }

        startListening()
        return START_STICKY
    }

    private fun startListening() {
        listeningJob?.cancel()
        listeningJob = serviceScope.launch {
            val repository = ReceiverRepository(this@NtfyListenerService)
            val topic = repository.ntfyTopic.first()
            val server = repository.ntfyServer.first()
            val secretKey = repository.secretKey.first()
            val copyToClipboard = repository.copyToClipboard.first()

            if (topic.isBlank()) {
                updateNotification(getString(R.string.notification_topic_not_set))
                return@launch
            }

            updateNotification(getString(R.string.notification_listening, topic, server))
            
            val url = "${server.trimEnd('/')}/$topic/json"
            
            while (isActive) {
                try {
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
                            }
                        }
                    }
                } catch (_: Exception) {
                    if (isActive) {
                        updateNotification(getString(R.string.notification_conn_lost))
                        delay(10000)
                    }
                }
            }
        }
    }

    private fun processLine(line: String, secretKey: String, copyToClipboard: Boolean) {
        try {
            val json = JSONObject(line)
            if (json.optString("event") == "message") {
                val rawMessage = json.optString("message")
                
                // Il ricevitore tenta sempre di decriptare se c'è una chiave, 
                // ma gestisce il fallback al testo chiaro se fallisce o se la chiave è vuota.
                val decryptedMessage = if (secretKey.isNotBlank()) {
                    try {
                        CryptoManager.decrypt(rawMessage, secretKey)
                    } catch (_: Exception) {
                        rawMessage
                    }
                } else {
                    rawMessage
                }

                if (copyToClipboard) {
                    copyToClipboard(decryptedMessage)
                }

                if (decryptedMessage.startsWith("http") || decryptedMessage.startsWith("geo:")) {
                    openUrl(decryptedMessage)
                }
            }
        } catch (_: Exception) {
            // Ignore parse errors
        }
    }

    private fun copyToClipboard(text: String) {
        try {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("received text", text)
            clipboard.setPrimaryClip(clip)
            
            // Per mostrare il toast dal servizio, dobbiamo usare il Dispatcher Main
            CoroutineScope(Dispatchers.Main).launch {
                Toast.makeText(applicationContext, R.string.text_copied_toast, Toast.LENGTH_SHORT).show()
            }
        } catch (_: Exception) {}
    }

    private fun openUrl(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, url.toUri()).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(intent)
        } catch (_: Exception) {
            // Ignore errors
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Ntfy Listener Service",
                NotificationManager.IMPORTANCE_LOW,
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

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(content)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(mainPendingIntent)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.btn_stop), stopPendingIntent)
            .build()
    }

    private fun updateNotification(content: String) {
        val notification = createNotification(content)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Log or handle task removal if necessary
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
