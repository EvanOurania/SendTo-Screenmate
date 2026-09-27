package com.example.receiver

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.example.receiver.ui.theme.ReceiverTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Shows the navigation chooser drawn over the other apps ("Display over other apps" permission)
 * instead of as an activity. Opening an activity pauses the apps below it and gives it a task of its
 * own, which makes a split screen flicker when the chooser closes; an overlay leaves them untouched.
 * Must be used from the main thread.
 */
object ChooserOverlay {
    @SuppressLint("StaticFieldLeak") // Holds only the application context, and only while shown
    private var current: OverlayWindow? = null

    fun canShow(context: Context): Boolean = Settings.canDrawOverlays(context)

    /** Replaces the chooser being shown, if any. */
    fun show(context: Context, url: String, title: String) {
        dismiss()
        current = OverlayWindow(context.applicationContext, url, title).also { it.show() }
    }

    fun dismiss() {
        current?.remove()
        current = null
    }

    private fun onClosed(window: OverlayWindow) {
        if (current === window) dismiss()
    }

    /** One chooser window. It provides the lifecycle that an activity would give to its Compose UI. */
    private class OverlayWindow(
        private val context: Context,
        private val url: String,
        private val title: String,
    ) : LifecycleOwner, SavedStateRegistryOwner {
        private val lifecycleRegistry = LifecycleRegistry(this)
        private val savedStateController = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = lifecycleRegistry
        override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        private val windowManager = context.getSystemService(WindowManager::class.java)
        private var root: FrameLayout? = null

        fun show() {
            savedStateController.performRestore(null)
            lifecycleRegistry.currentState = Lifecycle.State.RESUMED
            val launcher = NavigatorLauncher(context, url, title, scope)

            scope.launch {
                val settings = NavigatorLauncher.loadSettings(context, url)
                if (settings.delaySeconds == 0 && settings.preferredApp != ReceiverRepository.APP_NONE) {
                    launcher.open(settings.preferredApp)
                    onClosed(this@OverlayWindow)
                    return@launch
                }

                val composeView = ComposeView(context).apply {
                    setContent {
                        ReceiverTheme {
                            ChooserContent(url, title, settings, launcher, onClose = { onClosed(this@OverlayWindow) })
                        }
                    }
                }
                root = object : FrameLayout(context) {
                    // The Back button closes the chooser, as it would close an activity
                    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                            if (event.action == KeyEvent.ACTION_UP) onClosed(this@OverlayWindow)
                            return true
                        }
                        return super.dispatchKeyEvent(event)
                    }
                }.apply {
                    addView(composeView)
                    setViewTreeLifecycleOwner(this@OverlayWindow)
                    setViewTreeSavedStateRegistryOwner(this@OverlayWindow)
                }
                windowManager.addView(root, layoutParams())
            }
        }

        fun remove() {
            // Immediately and without animation, so nothing lingers on screen
            root?.let { windowManager.removeViewImmediate(it) }
            root = null
            lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
            scope.cancel()
        }

        private fun layoutParams() = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            0, // Focusable, so it gets the Back button
            PixelFormat.TRANSLUCENT,
        ).apply {
            title = "ScreenMate chooser"
        }
    }
}
