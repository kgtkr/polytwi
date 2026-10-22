package net.kgtkr.polytwi.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.security.interfaces.ECPublicKey

class WebPushCryptoTest {

    @Test
    fun testKeyGenerationAndUncompressedFormat() {
        val keys = WebPushCryptoManager.generateKeys()
        assertNotNull(keys.p256dhBase64Url)
        assertNotNull(keys.authBase64Url)
        assertNotNull(keys.privateKeyPkcs8Base64)

        // Public key must be exactly 65 bytes starting with 0x04
        assertEquals(65, keys.publicKeyBytes.size)
        assertEquals(0x04.toByte(), keys.publicKeyBytes[0])

        // Verify restoration of uncompressed point
        val restoredPub = Rfc8291Decryptor.decodeUncompressedPoint(keys.publicKeyBytes)
        assertNotNull(restoredPub)

        // Verify roundtrip private key parsing
        val restoredPriv = WebPushCryptoManager.decodePrivateKeyFromPkcs8(keys.privateKeyPkcs8Base64)
        assertNotNull(restoredPriv)
    }

    @Test
    fun testParseHeaderValue() {
        // Semicolon separated
        val header1 = "keyid=p256dh;dh=BEl4abc123"
        assertEquals("BEl4abc123", Rfc8291Decryptor.parseHeaderValue(header1, "dh"))

        // Comma separated (typical when VAPID and DH are combined)
        val header2 = "p256ecdsa=BM5oEo0x..., dh=BEl4abc123"
        assertEquals("BEl4abc123", Rfc8291Decryptor.parseHeaderValue(header2, "dh"))

        // Quoted value
        val header3 = "dh=\"BEl4abc123\"; p256ecdsa=\"BM5oEo0x...\""
        assertEquals("BEl4abc123", Rfc8291Decryptor.parseHeaderValue(header3, "dh"))

        // Encryption salt with rs
        val encHeader = "salt=Wk71abc; rs=4096"
        assertEquals("Wk71abc", Rfc8291Decryptor.parseHeaderValue(encHeader, "salt"))
    }
}
