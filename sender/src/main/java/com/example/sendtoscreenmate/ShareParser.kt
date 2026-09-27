package com.example.sendtoscreenmate

import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/** What to send for a shared or typed text: a link (or the text itself) and a title for it. */
data class PreparedMessage(val data: String, val title: String)

/** Extracts the link and a title from what the user shares or types. */
object ShareParser {

    /**
     * Uses the first link in [text] if there is one, otherwise the whole text. The title is [subject]
     * (given by some sharing apps), else the text before the link (e.g. the place name in a Google
     * Maps share), else a generic one.
     */
    fun prepareSharedText(text: String, subject: String = ""): PreparedMessage {
        val url = extractUrl(text)
        if (url.isBlank()) return PreparedMessage(text, subject.trim().ifBlank { "Text Message" })

        var title = subject.trim()
        if (title.isBlank()) {
            val textBeforeUrl = text.substringBefore(url).trim()
            if (textBeforeUrl.isNotBlank()) {
                title = textBeforeUrl.split("\n", "·", " - ").first().trim()
            }
        }
        if (title.isBlank() && url.startsWith("geo:")) {
            title = extractGeoLabel(url)
        }
        if (title.isBlank()) {
            title = if (MapsUtils.isGoogleMapsLink(url) || url.startsWith("geo:")) "Location" else "Link"
        }
        return PreparedMessage(url, title)
    }

    /** For a geo: link opened with this app (e.g. from a "navigate to" button). */
    fun prepareGeoLink(geoUri: String): PreparedMessage =
        PreparedMessage(geoUri, extractGeoLabel(geoUri).ifBlank { "Position" })

    /** The first link (http, https or geo) in [text], or an empty string if there is none. */
    fun extractUrl(text: String): String {
        if (text.trim().startsWith("geo:", ignoreCase = true)) return text.trim()
        val urlRegex = Regex("((https?://|geo:)[^\\s\\n\\r]+)")
        val match = urlRegex.find(text)
        return match?.value ?: ""
    }

    /** The place name or address in a geo: link (its q= parameter or its label), or an empty string. */
    fun extractGeoLabel(geoUri: String): String {
        try {
            val qIndex = geoUri.indexOf("q=")
            if (qIndex != -1) {
                var value = geoUri.substring(qIndex + 2)
                val endDelimiters = charArrayOf('&', '@', '#')
                var firstDelimiter = -1
                for (d in endDelimiters) {
                    val idx = value.indexOf(d)
                    if (idx != -1 && (firstDelimiter == -1 || idx < firstDelimiter)) firstDelimiter = idx
                }
                if (firstDelimiter != -1) value = value.substring(0, firstDelimiter)
                val decoded = try {
                    URLDecoder.decode(value, StandardCharsets.UTF_8.name()).trim()
                } catch (_: Exception) {
                    value.replace("%20", " ").replace("+", " ").trim()
                }
                val labelMatch = Regex("\\((.+)\\)").find(decoded)
                if (labelMatch != null) return labelMatch.groupValues[1].trim()
                return decoded
            }
            val labelRegex = Regex("\\(([^)]+)\\)")
            val labelMatch = labelRegex.find(geoUri)
            if (labelMatch != null) {
                val value = labelMatch.groupValues[1]
                return try {
                    URLDecoder.decode(value, StandardCharsets.UTF_8.name()).trim()
                } catch (_: Exception) {
                    value.trim()
                }
            }
        } catch (_: Exception) {}
        return ""
    }
}
