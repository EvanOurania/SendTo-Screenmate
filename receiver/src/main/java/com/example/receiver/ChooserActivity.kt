package com.example.receiver

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.example.receiver.ui.theme.ReceiverTheme
import kotlinx.coroutines.launch

/**
 * The navigation chooser as an activity, when the "Display over other apps" permission is missing.
 * The notification's "Reopen" button opens it too, because only an activity makes the notification
 * shade close: with the permission it just hands over to [ChooserOverlay] without being drawn.
 */
class ChooserActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val url = intent.getStringExtra("url") ?: ""
        val title = intent.getStringExtra("title") ?: ""

        if (url.isBlank()) {
            finish()
            return
        }

        if (ChooserOverlay.canShow(this)) {
            ChooserOverlay.show(this, url, title)
            closeInstant()
            return
        }

        enableEdgeToEdge() // Keeps the status bar transparent too
        val launcher = NavigatorLauncher(this, url, title, lifecycleScope)
        lifecycleScope.launch {
            val settings = NavigatorLauncher.loadSettings(this@ChooserActivity, url)
            if (settings.delaySeconds == 0 && settings.preferredApp != ReceiverRepository.APP_NONE) {
                launcher.open(settings.preferredApp)
                closeInstant()
                return@launch
            }

            setContent {
                ReceiverTheme {
                    ChooserContent(url, title, settings, launcher, onClose = ::closeInstant)
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
}
