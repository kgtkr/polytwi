package net.kgtkr.polytwi.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlValidatorTest {

    @Test
    fun testIsAllowedWebUrl() {
        assertTrue(UrlValidator.isAllowedWebUrl("https://x.com"))
        assertTrue(UrlValidator.isAllowedWebUrl("https://x.com/home"))
        assertTrue(UrlValidator.isAllowedWebUrl("https://x.com/notifications"))
        assertTrue(UrlValidator.isAllowedWebUrl("https://mobile.x.com/home"))
        assertTrue(UrlValidator.isAllowedWebUrl("https://twitter.com"))
        assertTrue(UrlValidator.isAllowedWebUrl("https://mobile.twitter.com/status/123"))
        assertTrue(UrlValidator.isAllowedWebUrl("https://sub.sub.x.com/"))
        assertTrue(UrlValidator.isAllowedWebUrl("https://x.com/search?q=\"keyword\"&f=live"))
        assertTrue(UrlValidator.isAllowedWebUrl("https://x.com/search?q=a|b"))

        // Disallowed schemes
        assertFalse(UrlValidator.isAllowedWebUrl("http://x.com"))
        assertFalse(UrlValidator.isAllowedWebUrl("javascript:alert(1)"))
        assertFalse(UrlValidator.isAllowedWebUrl("file:///android_asset/something"))
        assertFalse(UrlValidator.isAllowedWebUrl("intent://x.com"))

        // Disallowed hosts / userinfo spoofing
        assertFalse(UrlValidator.isAllowedWebUrl("https://x.com@evil.com/"))
        assertFalse(UrlValidator.isAllowedWebUrl("https://evil.com"))
        assertFalse(UrlValidator.isAllowedWebUrl("https://notx.com"))
        assertFalse(UrlValidator.isAllowedWebUrl("https://x.com.evil.com"))
        assertFalse(UrlValidator.isAllowedWebUrl("https://twitter.com.attacker.com"))
        assertFalse(UrlValidator.isAllowedWebUrl(null))
        assertFalse(UrlValidator.isAllowedWebUrl(""))
        assertFalse(UrlValidator.isAllowedWebUrl("   "))
    }

    @Test
    fun testIsAllowedCpftOrigin() {
        assertTrue(UrlValidator.isAllowedCpftOrigin("https://x.com"))
        assertTrue(UrlValidator.isAllowedCpftOrigin("https://twitter.com"))
        assertTrue(UrlValidator.isAllowedCpftOrigin("https://cpft-options.local/options.html"))

        assertFalse(UrlValidator.isAllowedCpftOrigin("https://accounts.google.com"))
        assertFalse(UrlValidator.isAllowedCpftOrigin("https://appleid.apple.com"))
        assertFalse(UrlValidator.isAllowedCpftOrigin("https://attacker.com"))
    }

    @Test
    fun testIsAllowedImageHost() {
        assertTrue(UrlValidator.isAllowedImageHost("https://pbs.twimg.com/media/abc?format=jpg&name=orig"))
        assertTrue(UrlValidator.isAllowedImageHost("https://abs.twimg.com/profile_images/123.jpg"))
        assertTrue(UrlValidator.isAllowedImageHost("https://ton.twitter.com/123.png"))
        assertTrue(UrlValidator.isAllowedImageHost("https://x.com/image.png"))

        assertFalse(UrlValidator.isAllowedImageHost("http://pbs.twimg.com/media/abc.jpg"))
        assertFalse(UrlValidator.isAllowedImageHost("https://attacker.com/evil.jpg"))
        assertFalse(UrlValidator.isAllowedImageHost("https://twimg.com.attacker.com/evil.jpg"))
    }
}
