package com.example.receiver

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.core.net.toUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first

/** Auto-open settings that apply to a location link. */
data class AutoOpenSettings(val preferredApp: String, val delaySeconds: Int)

/**
 * Opens a location link in the navigator apps. Shared by [ChooserOverlay] and [ChooserActivity].
 * [context] may be the application context: activities are started as new tasks.
 */
class NavigatorLauncher(
    private val context: Context,
    private val url: String,
    title: String,
    scope: CoroutineScope,
) {
    // Prepared in the background right away (it may need to expand a short link over the network),
    // so Waze opens without delay when the countdown ends or it's tapped
    private val wazeUri: Deferred<String> = scope.async { MapsUtils.getWazeUriResolvingShortLink(url, title) }

    fun isInstalled(packageName: String): Boolean = try {
        context.packageManager.getPackageInfo(packageName, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    suspend fun open(app: String) {
        when (app) {
            ReceiverRepository.APP_MAPS -> openInMaps()
            ReceiverRepository.APP_WAZE -> openInWaze()
            ReceiverRepository.APP_OTHER -> openInOtherApp()
        }
    }

    fun openInMaps() {
        openWithPackage(MapsUtils.getMapsUri(url), MAPS_PACKAGE)
    }

    suspend fun openInWaze() {
        openWithPackage(wazeUri.await(), WAZE_PACKAGE)
    }

    fun openInOtherApp() {
        openWithPackage(MapsUtils.getGenericMapsUri(url), null)
    }

    /** Copies the link, or just the address for a geo: link. */
    fun copyToClipboard() {
        val qIndex = url.indexOf("q=")
        val textToCopy = if (url.startsWith("geo:", ignoreCase = true) && qIndex != -1) {
            Uri.decode(url.substring(qIndex + 2))
        } else {
            url
        }
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("url", textToCopy))
        Toast.makeText(context, R.string.text_copied_toast, Toast.LENGTH_SHORT).show()
    }

    private fun openWithPackage(uri: String, packageName: String?) {
        val intent = Intent(Intent.ACTION_VIEW, uri.toUri()).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            setPackage(packageName)
        }
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
            if (packageName != null) {
                openWithPackage(uri, null) // App not installed: let Android pick one
            } else {
                Toast.makeText(context, R.string.error_opening_link, Toast.LENGTH_SHORT).show()
            }
        }
    }

    companion object {
        const val MAPS_PACKAGE = "com.google.android.apps.maps"
        const val WAZE_PACKAGE = "com.waze"

        suspend fun loadSettings(context: Context, url: String): AutoOpenSettings {
            val repository = ReceiverRepository(context)
            val preferredApp = if (MapsUtils.isGoogleMapsLink(url)) {
                repository.autoOpenMapsApp.first()
            } else {
                repository.autoOpenGeoApp.first()
            }
            return AutoOpenSettings(preferredApp, repository.autoOpenDelay.first())
        }
    }
}
