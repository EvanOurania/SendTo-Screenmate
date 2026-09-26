package com.example.receiver

import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object MapsUtils {
    // --- Google Maps Patterns ---
    private val GOOGLE_PRECISE_LAT = Regex("!3d([-+]?\\d+\\.\\d+)")
    private val GOOGLE_PRECISE_LON = Regex("!4d([-+]?\\d+\\.\\d+)")
    // Support for Google Directions/Waypoints (!1d LON, !2d LAT)
    private val GOOGLE_WAYPOINT_LON = Regex("!1d([-+]?\\d+\\.\\d+)")
    private val GOOGLE_WAYPOINT_LAT = Regex("!2d([-+]?\\d+\\.\\d+)")
    
    // --- Generic & Other Provider Patterns ---
    private val QUERY_COORDS = Regex("query=([-+]?\\d+\\.\\d+),([-+]?\\d+\\.\\d+)")
    private val LL_COORDS = Regex("ll=([-+]?\\d+\\.\\d+),([-+]?\\d+\\.\\d+)")
    private val AT_COORDS = Regex("@([-+]?\\d+\\.\\d+),([-+]?\\d+\\.\\d+)")
    private val LOC_COORDS = Regex("loc:([-+]?\\d+\\.\\d+)\\+([-+]?\\d+\\.\\d+)")
    private val GENERIC_COORDS = Regex("([-+]?\\d+\\.\\d+)\\s*,\\s*([-+]?\\d+\\.\\d+)")
    
    // --- Metadata & Misc ---
    private val PLACE_NAME_REGEX = Regex("/maps/place/([^/]+)")
    private val DMS_REGEX = Regex("(\\d+)°(\\d+)'([\\d.]+)\"([NS])\\s+(\\d+)°(\\d+)'([\\d.]+)\"([EW])")

    private val client = OkHttpClient.Builder()
        .followRedirects(false) // We want to manually inspect the 'Location' header
        .build()

    /**
     * Resolves shortened URLs (like goo.gl or maps.app.goo.gl) to their full versions.
     * This is a blocking network call and should be called from a background thread.
     */
    fun resolveShortLink(shortUrl: String): String {
        if (!shortUrl.contains("goo.gl") && !shortUrl.contains("bit.ly") && !shortUrl.contains("t.co")) {
            return shortUrl
        }
        
        return try {
            val request = Request.Builder().url(shortUrl).head().build()
            client.newCall(request).execute().use { response ->
                if (response.code == 301 || response.code == 302) {
                    response.header("Location") ?: shortUrl
                } else {
                    shortUrl
                }
            }
        } catch (e: Exception) {
            shortUrl
        }
    }

    /**
     * Identifies if a given string contains a valid Google Maps link.
     */
    fun isGoogleMapsLink(text: String): Boolean {
        return (text.contains("google.") && (text.contains("/maps/") || text.contains("/maps?"))) ||
                text.contains("goo.gl")
    }

    /**
     * Identifies if a given string contains a valid map link.
     */
    fun isMapLink(text: String): Boolean {
        return isGoogleMapsLink(text) ||
               text.contains("osm.org") || text.contains("waze.com") ||
               text.contains("bing.com/maps") || text.contains("apple.com/maps")
    }

    /**
     * Extracts coordinates from a URL, using multiple strategies inspired by GeoShare.
     */
    fun extractCoordinates(url: String): String? {
        val decodedUrl = try {
            URLDecoder.decode(url, StandardCharsets.UTF_8.toString())
        } catch (_: Exception) {
            url
        }

        // 1. Google Precise Destination (!3d / !4d)
        val gLat = GOOGLE_PRECISE_LAT.find(decodedUrl)
        val gLon = GOOGLE_PRECISE_LON.find(decodedUrl)
        if (gLat != null && gLon != null) return "${gLat.groupValues[1]},${gLon.groupValues[1]}"

        // 2. Google Waypoints (!1d LON / !2d LAT - note the swap in Google's internal format)
        val wLon = GOOGLE_WAYPOINT_LON.find(decodedUrl)
        val wLat = GOOGLE_WAYPOINT_LAT.find(decodedUrl)
        if (wLat != null && wLon != null) return "${wLat.groupValues[1]},${wLon.groupValues[1]}"

        // 3. Common patterns: query=, ll=, @lat,lon, loc:
        LL_COORDS.find(decodedUrl)?.let { return "${it.groupValues[1]},${it.groupValues[2]}" }
        QUERY_COORDS.find(decodedUrl)?.let { return "${it.groupValues[1]},${it.groupValues[2]}" }
        AT_COORDS.find(decodedUrl)?.let { return "${it.groupValues[1]},${it.groupValues[2]}" }
        LOC_COORDS.find(decodedUrl)?.let { return "${it.groupValues[1]},${it.groupValues[2]}" }

        // 4. Generic fallback
        GENERIC_COORDS.find(decodedUrl)?.let { return "${it.groupValues[1]},${it.groupValues[2]}" }

        return null
    }

    /**
     * Extracts a place name from a Google Maps URL.
     */
    fun extractPlaceName(url: String): String? {
        val decodedUrl = try {
            URLDecoder.decode(url, StandardCharsets.UTF_8.toString())
        } catch (_: Exception) {
            url
        }
        return PLACE_NAME_REGEX.find(decodedUrl)?.groupValues?.get(1)?.replace('+', ' ')
    }

    /**
     * Extracts DMS coordinates from a message title (e.g., from Google Maps share).
     */
    fun extractCoordinatesFromTitle(title: String): String? {
        val match = DMS_REGEX.find(title)
        if (match != null) {
            try {
                val lat = parseDmsToDecimal(match.groupValues[1], match.groupValues[2], match.groupValues[3], match.groupValues[4])
                val lon = parseDmsToDecimal(match.groupValues[5], match.groupValues[6], match.groupValues[7], match.groupValues[8])
                return "$lat,$lon"
            } catch (_: Exception) {}
        }
        return null
    }

    private fun parseDmsToDecimal(degrees: String, minutes: String, seconds: String, direction: String): Double {
        var decimal = degrees.toDouble() + (minutes.toDouble() / 60.0) + (seconds.toDouble() / 3600.0)
        if (direction == "S" || direction == "W") decimal *= -1.0
        return decimal
    }

    /**
     * Formats a Waze-specific URI, including place name as a label if available.
     */
    fun getWazeUri(url: String, title: String): String {
        val coords = extractCoordinates(url) ?: extractCoordinatesFromTitle(title)
        val placeName = extractPlaceName(url) ?: if (!title.contains("Segnaposto", true) && !title.contains("Pin", true)) title else null
        
        return if (coords != null) {
            if (placeName != null && placeName.isNotBlank()) {
                val encodedPlace = URLEncoder.encode(placeName, StandardCharsets.UTF_8.toString())
                "waze://?ll=$coords&navigate=yes&q=$encodedPlace"
            } else {
                "waze://?ll=$coords&navigate=yes"
            }
        } else {
            val encodedUrl = URLEncoder.encode(url, StandardCharsets.UTF_8.toString())
            "waze://?q=$encodedUrl&navigate=yes"
        }
    }

    /**
     * Formats a generic Maps URI for use with any map provider.
     */
    fun getGenericMapsUri(url: String): String {
        val isMap = isMapLink(url)
        return if (isMap && !url.startsWith("http") && !url.startsWith("geo:")) {
            val encodedUrl = URLEncoder.encode(url, StandardCharsets.UTF_8.toString())
            "geo:0,0?q=$encodedUrl"
        } else {
            url
        }
    }
}
