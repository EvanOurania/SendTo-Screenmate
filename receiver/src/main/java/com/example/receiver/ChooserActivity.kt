package com.example.receiver

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import com.example.receiver.ui.theme.ReceiverTheme
import kotlinx.coroutines.launch

/**
 * The navigation chooser as an activity: used from the app and the notification, and instead of
 * [ChooserOverlay] when the "Display over other apps" permission is missing.
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
