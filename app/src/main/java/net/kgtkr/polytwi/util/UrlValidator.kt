package net.kgtkr.polytwi.util

import java.net.URI

object UrlValidator {
    private val ALLOWED_WEB_HOST_REGEX = Regex(
        """^(?:[a-zA-Z0-9-]+\.)*(?:x\.com|twitter\.com)$""",
        RegexOption.IGNORE_CASE
    )

    private val ALLOWED_IMAGE_HOST_REGEX = Regex(
        """^(?:[a-zA-Z0-9-]+\.)*(?:twimg\.com|twitter\.com|x\.com)$""",
        RegexOption.IGNORE_CASE
    )

    private fun extractSchemeAndHost(url: String): Pair<String, String>? {
        val trimmed = url.trim()
        val withoutQuery = trimmed.substringBefore('#').substringBefore('?')
        try {
            val uri = URI(withoutQuery)
            val scheme = uri.scheme?.lowercase()
            val host = uri.host?.lowercase()
            val userInfo = uri.userInfo
            if (scheme != null && host != null && userInfo == null) {
                return Pair(scheme, host)
            }
        } catch (_: Exception) {
        }
        return null
    }

    /**
     * Checks if a URL is safe to be loaded inside the privileged X/Twitter WebView.
     * Enforces HTTPS scheme and x.com / twitter.com domain (including subdomains).
     */
    fun isAllowedWebUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val (scheme, host) = extractSchemeAndHost(url) ?: return false
        return scheme == "https" && ALLOWED_WEB_HOST_REGEX.matches(host)
    }

    /**
     * Checks if an origin/URL is permitted to invoke PushJsBridge methods.
     */
    fun isAllowedPushOrigin(url: String?): Boolean {
        return isAllowedWebUrl(url)
    }

    /**
     * Checks if an origin/URL is permitted to access CpftConfigBridge methods.
     * Allows x.com / twitter.com domains or the internal cpft-options.local virtual host.
     */
    fun isAllowedCpftOrigin(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val (scheme, host) = extractSchemeAndHost(url) ?: return false
        if (host == "cpft-options.local") return true
        return scheme == "https" && ALLOWED_WEB_HOST_REGEX.matches(host)
    }

    /**
     * Checks if a URL points to a trusted image host (twimg.com, twitter.com, x.com) via HTTPS.
     */
    fun isAllowedImageHost(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val (scheme, host) = extractSchemeAndHost(url) ?: return false
        return scheme == "https" && ALLOWED_IMAGE_HOST_REGEX.matches(host)
    }
}
