package com.example.receiver

object MapsUtils {
    /**
     * Identifies if a given string contains a valid Google Maps link.
     * Uses a specific pattern to avoid false positives with other Google services.
     */
    fun isGoogleMapsLink(text: String): Boolean {
        return (text.contains("google.") && (text.contains("/maps/") || text.contains("/maps?"))) ||
                text.contains("goo.gl")
    }
}
