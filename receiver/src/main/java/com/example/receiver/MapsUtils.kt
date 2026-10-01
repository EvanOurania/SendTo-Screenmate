package com.example.receiver

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

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

    // --- Directions ---
    private const val DIRECTIONS_PATH = "/maps/dir/"
    // maps/dir/?api=1&destination=... and the older maps?saddr=...&daddr=...
    private val DESTINATION_PARAM = Regex("[?&](?:destination|daddr)=([^&#]+)")
    private val COORDS_ONLY = Regex("^([-+]?\\d+(?:\\.\\d+)?)\\s*,\\s*([-+]?\\d+(?:\\.\\d+)?)$")
    
    // --- Metadata & Misc ---
    private val PLACE_NAME_REGEX = Regex("/maps/place/([^/]+)")
    private val DMS_REGEX = Regex("(\\d+)°(\\d+)'([\\d.]+)\"([NS])\\s+(\\d+)°(\\d+)'([\\d.]+)\"([EW])")
    // Whole words only, so that e.g. "Pinerolo" or "Pizzeria Alpina" are not taken for a dropped pin
    private val DROPPED_PIN_TITLE = Regex("\\b(pin|segnaposto|marcador|repère|gesetzte nadel)\\b", RegexOption.IGNORE_CASE)

    private val client = OkHttpClient.Builder()
        .followRedirects(false) // We want to manually inspect the 'Location' header
        .callTimeout(5, TimeUnit.SECONDS) // Don't hold up opening the navigator on a slow network
        .build()

    /**
     * Resolves Google Maps short links (maps.app.goo.gl, goo.gl/maps) to their full versions.
     * This is a blocking network call and should be called from a background thread.
     */
    fun resolveShortLink(shortUrl: String): String {
        val host = shortUrl.toHttpUrlOrNull()?.host ?: return shortUrl
        if (host != "maps.app.goo.gl" && host != "goo.gl") {
            return shortUrl
        }

        return try {
            val request = Request.Builder().url(shortUrl).build()
            client.newCall(request).execute().use { response ->
                val location = response.header("Location")
                if (response.isRedirect && location != null && location.startsWith("http")) {
                    location
                } else {
                    shortUrl
                }
            }
        } catch (_: Exception) {
            shortUrl
        }
    }

    /**
     * Like [getWazeUri], but first expands Google Maps short links so Waze gets the exact destination.
     * Falls back to the original link when the expanded one contains neither coordinates nor a route.
     */
    suspend fun getWazeUriResolvingShortLink(url: String, title: String): String {
        val resolvedUrl = withContext(Dispatchers.IO) { resolveShortLink(url) }
        val isUsable = resolvedUrl != url &&
            (extractDirectionsDestination(resolvedUrl) != null || extractCoordinates(resolvedUrl) != null)
        return getWazeUri(if (isUsable) resolvedUrl else url, title)
    }

    /**
     * The destination of a Google Maps directions link: its coordinates ("lat,lon") when they can
     * be identified with certainty, otherwise its name/address. Null if [url] isn't a directions link.
     */
    fun extractDirectionsDestination(url: String): String? {
        DESTINATION_PARAM.find(url)?.let { match ->
            // Old multi-stop links look like daddr=Stop+to:Destination
            val destination = decode(match.groupValues[1]).substringAfterLast("to:").trim()
            return coordinatesOnly(destination) ?: destination.ifEmpty { null }
        }

        val dirIndex = url.indexOf(DIRECTIONS_PATH)
        if (dirIndex == -1) return null

        // maps/dir/<start>/<stop>/.../<destination>/@<map center>/data=<route details>
        // (split before decoding: an address may contain an encoded "/")
        val stops = url.substring(dirIndex + DIRECTIONS_PATH.length)
            .substringBefore('?')
            .split('/')
            .takeWhile { !it.startsWith("@") && !it.startsWith("data=") }
            .map { decode(it).trim() }
        val destination = stops.lastOrNull { it.isNotEmpty() } ?: return null
        coordinatesOnly(destination)?.let { return it }

        // The route details list one coordinate pair per named stop, in route order. Only when
        // every named stop has its pair is the last pair surely the destination's.
        val routeDetails = decode(url.substringAfter("/data=", ""))
        val lons = GOOGLE_WAYPOINT_LON.findAll(routeDetails).toList()
        val lats = GOOGLE_WAYPOINT_LAT.findAll(routeDetails).toList()
        val namedStops = stops.count { it.isNotEmpty() }
        if (lons.isNotEmpty() && lons.size == lats.size && lons.size == namedStops) {
            return "${lats.last().groupValues[1]},${lons.last().groupValues[1]}"
        }
        return destination
    }

    /** "lat,lon" if [text] consists of coordinates only (spaces removed), otherwise null. */
    private fun coordinatesOnly(text: String): String? =
        COORDS_ONLY.find(text)?.let { "${it.groupValues[1]},${it.groupValues[2]}" }

    private fun decode(text: String): String = try {
        URLDecoder.decode(text, StandardCharsets.UTF_8.toString())
    } catch (_: Exception) {
        text
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

        // 2. Google Waypoints (!1d LON / !2d LAT - note the swap in Google's internal format).
        // Waypoints are listed in route order: the last one is the destination, the first the start.
        val wLon = GOOGLE_WAYPOINT_LON.findAll(decodedUrl).lastOrNull()
        val wLat = GOOGLE_WAYPOINT_LAT.findAll(decodedUrl).lastOrNull()
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
     * Whether a shared title is Google Maps' generic name for a dropped pin rather than a real place name.
     */
    fun isDroppedPinTitle(title: String): Boolean = DROPPED_PIN_TITLE.containsMatchIn(title)

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
     * Formats the URI to open in Google Maps: a pin on the place, or on the route's destination,
     * when the link contains it, otherwise the link itself (e.g. a short link, which Maps resolves).
     */
    fun getMapsUri(url: String): String {
        // Routes: the destination only, as in getWazeUri. The map center (@...) lies somewhere along the route.
        extractDirectionsDestination(url)?.let { destination ->
            return if (COORDS_ONLY.matches(destination)) {
                "geo:$destination?q=$destination"
            } else {
                "geo:0,0?q=${URLEncoder.encode(destination, StandardCharsets.UTF_8.toString())}"
            }
        }

        val coords = extractCoordinates(url)
        return if (coords != null) "geo:$coords?q=$coords" else url
    }

    /**
     * Formats a Waze-specific URI, including place name as a label if available.
     */
    fun getWazeUri(url: String, title: String): String {
        // Routes: navigate to the destination only. The map center (@...) lies somewhere along the
        // route and the shared title may name another stop, so neither of them is used here.
        extractDirectionsDestination(url)?.let { destination ->
            return if (COORDS_ONLY.matches(destination)) {
                "waze://?ll=$destination&navigate=yes"
            } else {
                "waze://?q=${URLEncoder.encode(destination, StandardCharsets.UTF_8.toString())}&navigate=yes"
            }
        }

        val coords = extractCoordinates(url) ?: extractCoordinatesFromTitle(title)
        val placeName = extractPlaceName(url) ?: if (!isDroppedPinTitle(title)) title else null
        
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
