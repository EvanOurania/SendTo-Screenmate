package com.example.receiver

import org.json.JSONObject

/** How a received link is opened automatically. */
enum class OpenAction {
    /** Straight into the chosen navigator, without the chooser (auto-open delay 0). */
    NAVIGATOR,
    /** Through ChooserActivity and its countdown. */
    CHOOSER,
    /** With the default app for the link (e.g. the browser for a web page). */
    DEFAULT_APP,
}

/** A decrypted message from the Sender: JSON {"url": ..., "title": ...} or just a link or text. */
data class IncomingMessage(
    /** The link (or text) that was sent. */
    val targetUrl: String,
    /** The title sent with it (e.g. the place name), possibly empty. */
    val displayTitle: String,
    /** Title for history and notification: [displayTitle], or the place name found in the link. */
    val refinedTitle: String,
    val isMapsLink: Boolean,
    /** The http(s) link to give to the navigators. */
    val rawMapsUrl: String,
    /** The link to open (and to reopen from the notification). */
    val finalUrl: String,
) {
    /** Locations go to the navigator; other links (e.g. web pages) open with their default app. */
    val isLocation: Boolean get() = isMapsLink || finalUrl.startsWith("geo:")

    fun openAction(autoOpenDelay: Int, preferredApp: String): OpenAction = when {
        !isLocation -> OpenAction.DEFAULT_APP
        autoOpenDelay == 0 && preferredApp != ReceiverRepository.APP_NONE -> OpenAction.NAVIGATOR
        else -> OpenAction.CHOOSER
    }

    companion object {
        private val HTTP_LINK = Regex("https?://[^\\s\\n\\r]+")

        /** Null when the message has no link or text in it. */
        fun parse(decryptedMessage: String): IncomingMessage? {
            var targetUrl = decryptedMessage
            var displayTitle = ""
            val messageToParse = decryptedMessage.trim()
            if (messageToParse.startsWith("{") && messageToParse.endsWith("}")) {
                try {
                    val msgJson = JSONObject(messageToParse)
                    targetUrl = msgJson.optString("url")
                    displayTitle = msgJson.optString("title")
                } catch (_: Exception) {
                    // Not JSON after all: keep the whole text
                }
            }
            if (targetUrl.isBlank()) return null

            // If the title is blank or generic, try to extract it from the URL
            val refinedTitle = if (displayTitle.isBlank() || displayTitle.lowercase() == "location") {
                MapsUtils.extractPlaceName(targetUrl) ?: displayTitle
            } else {
                displayTitle
            }

            // Detect a Google Maps link anywhere in the message, and the cleanest URL for the navigators
            val rawMapsUrl = when {
                targetUrl.contains("http") -> targetUrl
                decryptedMessage.contains("http") -> HTTP_LINK.find(decryptedMessage)?.value ?: targetUrl
                else -> targetUrl
            }

            return IncomingMessage(
                targetUrl = targetUrl,
                displayTitle = displayTitle,
                refinedTitle = refinedTitle,
                isMapsLink = MapsUtils.isGoogleMapsLink(decryptedMessage),
                rawMapsUrl = rawMapsUrl,
                finalUrl = MapsUtils.getGenericMapsUri(targetUrl),
            )
        }

        /**
         * Whether a message sent at [messageTime] is at most [maxAgeSeconds] old, compared with the
         * latest [serverTime] seen (both from the ntfy server's clock, so the device clock doesn't matter).
         */
        fun isRecent(messageTime: Long, serverTime: Long, maxAgeSeconds: Long): Boolean =
            messageTime <= 0 || serverTime - messageTime <= maxAgeSeconds
    }
}
