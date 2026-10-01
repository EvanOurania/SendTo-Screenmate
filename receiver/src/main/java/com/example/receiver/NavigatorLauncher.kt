package com.example.receiver

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.core.graphics.drawable.toBitmap
import androidx.core.net.toUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** Chooser and auto-open settings that apply to a location link. */
data class AutoOpenSettings(val preferredApp: String, val delaySeconds: Int, val chooserApps: Set<String>)

/** An installed app, besides Google Maps and Waze, that can open locations. */
class OtherApp(val packageName: String, val label: String, val icon: Bitmap)

/**
 * Opens a location link in the navigator apps. Shared by [ChooserOverlay] and [ChooserActivity].
 * [context] may be the application context: activities are started as new tasks.
 */
class NavigatorLauncher(
    private val context: Context,
    private val url: String,
    private val title: String,
    scope: CoroutineScope,
) {
    // Expanded in the background right away (a short link needs the network), so the navigator
    // opens without delay when the countdown ends or it's tapped
    private val fullUrl: Deferred<String> = scope.async { MapsUtils.expandShortLink(url) }

    fun isInstalled(packageName: String): Boolean = try {
        context.packageManager.getPackageInfo(packageName, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    /** [app] is [ReceiverRepository.APP_MAPS], [ReceiverRepository.APP_WAZE] or another app's package name. */
    suspend fun open(app: String) {
        when (app) {
            ReceiverRepository.APP_NONE -> {}
            ReceiverRepository.APP_MAPS -> openInMaps()
            ReceiverRepository.APP_WAZE -> openInWaze()
            else -> openInApp(app)
        }
    }

    suspend fun openInMaps() {
        openWithPackage(MapsUtils.getMapsUri(url, fullUrl.await()), MAPS_PACKAGE)
    }

    suspend fun openInWaze() {
        openWithPackage(MapsUtils.getWazeUri(url, fullUrl.await(), title), WAZE_PACKAGE)
    }

    suspend fun openInApp(packageName: String) {
        openWithPackage(otherAppUri(), packageName)
    }

    // The full link: on Android 12+ Google Play services opens short links, and always in Google Maps
    private suspend fun otherAppUri(): String = MapsUtils.getGenericMapsUri(fullUrl.await())

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

        const val GEO_SAMPLE_LINK = "geo:0,0"
        // The two forms Google Maps short links expand to
        val GOOGLE_MAPS_SAMPLE_LINKS = listOf("https://www.google.com/maps/place/0,0", "https://maps.google.com/?q=0,0")

        /**
         * The installed apps that can open one of [links], sorted by name, except Google Maps and Waze
         * (they're handled apart) and the ScreenMate apps. They're looked for among the apps that open
         * geo: links: for a Google Maps link, Android 12+ would only list the app verified for it.
         */
        suspend fun findOtherApps(context: Context, links: List<String>): List<OtherApp> = withContext(Dispatchers.IO) {
            val packageManager = context.packageManager
            val screenMatePrefix = context.packageName.substringBeforeLast('.') + "."
            packageManager.queryIntentActivities(Intent(Intent.ACTION_VIEW, GEO_SAMPLE_LINK.toUri()), PackageManager.MATCH_DEFAULT_ONLY)
                .map { it.activityInfo.applicationInfo }
                .distinctBy { it.packageName }
                .filter { it.packageName != MAPS_PACKAGE && it.packageName != WAZE_PACKAGE && !it.packageName.startsWith(screenMatePrefix) }
                // Some of them only open addresses and coordinates, not Google Maps links
                .filter { app -> links.any { canOpen(packageManager, it, app.packageName) } }
                .map { toOtherApp(context, it) }
                .sortedBy { it.label.lowercase() }
        }

        /** The app with this package name, or null if it isn't installed (or [packageName] is e.g. APP_MAPS). */
        fun loadApp(context: Context, packageName: String): OtherApp? = try {
            toOtherApp(context, context.packageManager.getApplicationInfo(packageName, 0))
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }

        private fun canOpen(packageManager: PackageManager, link: String, packageName: String): Boolean {
            val intent = Intent(Intent.ACTION_VIEW, link.toUri()).setPackage(packageName)
            return packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY).isNotEmpty()
        }

        private fun toOtherApp(context: Context, info: ApplicationInfo): OtherApp {
            val packageManager = context.packageManager
            val iconSize = (32 * context.resources.displayMetrics.density).toInt()
            val icon = packageManager.getApplicationIcon(info).toBitmap(iconSize, iconSize)
            return OtherApp(info.packageName, packageManager.getApplicationLabel(info).toString(), icon)
        }

        suspend fun loadSettings(context: Context, url: String): AutoOpenSettings {
            val repository = ReceiverRepository(context)
            return if (MapsUtils.isGoogleMapsLink(url)) {
                AutoOpenSettings(repository.autoOpenMapsApp.first(), repository.autoOpenDelay.first(), repository.chooserMapsApps.first())
            } else {
                AutoOpenSettings(repository.autoOpenGeoApp.first(), repository.autoOpenDelay.first(), repository.chooserGeoApps.first())
            }
        }
    }
}
