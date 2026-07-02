package com.example.receiver

import android.net.Uri

object MapsUtils {
    /**
     * Identifies if a given string contains a valid Google Maps link.
     */
    fun isGoogleMapsLink(text: String): Boolean {
        return (text.contains("google.") && (text.contains("/maps/") || text.contains("/maps?"))) ||
                text.contains("goo.gl")
    }

    /**
     * Extracts coordinates from a URL, prioritizing precise Google Maps patterns (!3d/!4d).
     */
    fun extractCoordinates(url: String): String? {
        val decodedUrl = Uri.decode(url)
        
        // 1. Target the '!3d...!4d...' pattern (The ACTUAL destination coordinates in place links)
        val preciseLatRegex = Regex("!3d([-+]?\\d+\\.\\d+)")
        val preciseLonRegex = Regex("!4d([-+]?\\d+\\.\\d+)")
        val latMatch = preciseLatRegex.find(decodedUrl)
        val lonMatch = preciseLonRegex.find(decodedUrl)
        if (latMatch != null && lonMatch != null) {
            return "${latMatch.groupValues[1]},${lonMatch.groupValues[1]}"
        }

        // 2. Target the 'query=lat,lon' pattern (Search API links)
        val queryRegex = Regex("query=([-+]?\\d+\\.\\d+),([-+]?\\d+\\.\\d+)")
        val queryMatch = queryRegex.find(decodedUrl)
        if (queryMatch != null) {
            return "${queryMatch.groupValues[1]},${queryMatch.groupValues[2]}"
        }

        // 3. Generic pattern for non-maps geo links
        if (!url.contains("google.") && !url.contains("goo.gl")) {
            val coordRegex = Regex("([-+]?\\d+\\.\\d+)\\s*,\\s*([-+]?\\d+\\.\\d+)")
            val match = coordRegex.find(decodedUrl)
            if (match != null) {
                return "${match.groupValues[1]},${match.groupValues[2]}"
            }
        }
        return null
    }

    /**
     * Extracts a place name from a Google Maps URL.
     */
    fun extractPlaceName(url: String): String? {
        val decodedUrl = Uri.decode(url)
        val placeRegex = Regex("/maps/place/([^/]+)")
        val match = placeRegex.find(decodedUrl)
        return match?.groupValues?.get(1)?.replace('+', ' ')
    }

    /**
     * Extracts DMS coordinates from a message title (e.g., from Google Maps share).
     */
    fun extractCoordinatesFromTitle(title: String): String? {
        val dmsRegex = Regex("(\\d+)°(\\d+)'([\\d.]+)\"([NS])\\s+(\\d+)°(\\d+)'([\\d.]+)\"([EW])")
        val match = dmsRegex.find(title)
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
                "waze://?ll=$coords&navigate=yes&q=${Uri.encode(placeName)}"
            } else {
                "waze://?ll=$coords&navigate=yes"
            }
        } else {
            "waze://?q=${Uri.encode(url)}&navigate=yes"
        }
    }

    /**
     * Formats a generic Maps URI for use with any map provider.
     */
    fun getGenericMapsUri(url: String): String {
        val isGoogleMaps = isGoogleMapsLink(url)
        return if (isGoogleMaps && !url.startsWith("http") && !url.startsWith("geo:")) {
            "geo:0,0?q=${Uri.encode(url)}"
        } else {
            url
        }
    }
}
