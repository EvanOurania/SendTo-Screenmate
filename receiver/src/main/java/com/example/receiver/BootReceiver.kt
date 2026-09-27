package com.example.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in START_ACTIONS) return

        val repository = ReceiverRepository(context)
        val pendingResult = goAsync() // Keeps the process alive while the settings are read
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val topic = repository.ntfyTopic.first()
                if (topic.isNotBlank()) {
                    val serviceIntent = Intent(context, NtfyListenerService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }
                }
            } catch (e: Exception) {
                Log.e("BootReceiver", "Could not start the listener after ${intent.action}", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private val START_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            // A new version was installed: Android stops the service and doesn't restart it by itself
            Intent.ACTION_MY_PACKAGE_REPLACED,
            // Fast boot / resume from standby, used instead of BOOT_COMPLETED by some devices
            // (e.g. Android car head units)
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON",
        )
    }
}
