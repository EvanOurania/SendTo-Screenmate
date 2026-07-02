package com.example.sendtoscreenmate

object MapsUtils {
    /**
     * Identifies if a given string contains a valid Google Maps link.
     */
    fun isGoogleMapsLink(text: String): Boolean {
        return (text.contains("google.") && (text.contains("/maps/") || text.contains("/maps?"))) ||
                text.contains("goo.gl")
    }
}
