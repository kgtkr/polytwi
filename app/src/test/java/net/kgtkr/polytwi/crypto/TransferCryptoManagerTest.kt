package net.kgtkr.polytwi.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class TransferCryptoManagerTest {
    @Test
    fun testQrPayloadAndPublicKeyParsing() {
        val keyPair = TransferCryptoManager.generateKeyPair()
        val qrUri = TransferCryptoManager.buildQrPayload(keyPair.publicKeyBase64Url)

        // Raw base64url parsing
        assertEquals(
            keyPair.publicKeyBase64Url,
            TransferCryptoManager.parsePublicKeyFromInput(keyPair.publicKeyBase64Url)
        )

        // QR URI parsing
        assertEquals(
            keyPair.publicKeyBase64Url,
            TransferCryptoManager.parsePublicKeyFromInput(qrUri)
        )
        assertNull(TransferCryptoManager.parsePublicKeyFromInput("not-a-valid-key"))
        assertNull(TransferCryptoManager.parsePublicKeyFromInput(""))
    }

    @Test
    fun testEncryptAndDecryptRoundTrip() {
        val recipientKeyPair = TransferCryptoManager.generateKeyPair()
        val sampleBundleJson = """{"version":1,"exportedAtMs":1700000000,"accounts":[{"account":{"id":"123","name":"Test","profileName":"account_123"}}]}"""

        val encryptedEnvelope = TransferCryptoManager.encryptPayload(
            plaintextJson = sampleBundleJson,
            recipientPublicKeyBase64Url = recipientKeyPair.publicKeyBase64Url
        )
        assertNotNull(encryptedEnvelope)

        val decryptedJson = TransferCryptoManager.decryptPayload(
            envelopeJson = encryptedEnvelope,
            recipientKeyPair = recipientKeyPair
        )
        assertEquals(sampleBundleJson, decryptedJson)
    }

    @Test
    fun testDecryptWithWrongRecipientKeyFails() {
        val recipientKeyPair = TransferCryptoManager.generateKeyPair()
        val wrongKeyPair = TransferCryptoManager.generateKeyPair()
        val sampleBundleJson = """{"hello":"world"}"""

        val encryptedEnvelope = TransferCryptoManager.encryptPayload(
            plaintextJson = sampleBundleJson,
            recipientPublicKeyBase64Url = recipientKeyPair.publicKeyBase64Url
        )

        assertThrows(IllegalArgumentException::class.java) {
            TransferCryptoManager.decryptPayload(
                envelopeJson = encryptedEnvelope,
                recipientKeyPair = wrongKeyPair
            )
        }
    }
}
