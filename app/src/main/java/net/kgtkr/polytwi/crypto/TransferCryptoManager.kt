package net.kgtkr.polytwi.crypto

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class TransferKeyPair(
    val publicKeyBase64Url: String,
    val privateKeyPkcs8Base64: String
)

@Serializable
data class EncryptedTransferEnvelope(
    val version: Int = 1,
    val recipientPublicKey: String,
    val ephemeralPublicKey: String,
    val salt: String,
    val iv: String,
    val ciphertext: String
)

object TransferCryptoManager {
    private const val EC_CURVE = "secp256r1"
    private const val QR_SCHEME = "polytwi"
    private const val QR_HOST = "transfer"
    private const val QR_PARAM_PK = "pk"
    private const val HKDF_INFO_PREFIX = "polytwi-transfer-v1"
    private const val GCM_TAG_BITS = 128

    private val json = Json { ignoreUnknownKeys = true }

    fun generateKeyPair(): TransferKeyPair {
        val keyPairGen = KeyPairGenerator.getInstance("EC")
        keyPairGen.initialize(ECGenParameterSpec(EC_CURVE), SecureRandom())
        val keyPair = keyPairGen.generateKeyPair()

        val ecPub = keyPair.public as ECPublicKey
        val ecPriv = keyPair.private as ECPrivateKey

        val pubBytes = WebPushCryptoManager.toUncompressedPoint(ecPub)
        val pubB64Url = Base64.getUrlEncoder().withoutPadding().encodeToString(pubBytes)
        val privPkcs8B64 = Base64.getEncoder().withoutPadding().encodeToString(ecPriv.encoded)

        return TransferKeyPair(
            publicKeyBase64Url = pubB64Url,
            privateKeyPkcs8Base64 = privPkcs8B64
        )
    }

    fun buildQrPayload(publicKeyBase64Url: String): String {
        return "$QR_SCHEME://$QR_HOST?$QR_PARAM_PK=$publicKeyBase64Url"
    }

    /**
     * Parses either a `polytwi://transfer?pk=...` URI or a raw Base64URL uncompressed P-256 public key.
     * Validates that it decodes to a valid 65-byte uncompressed P-256 EC point.
     */
    fun parsePublicKeyFromInput(rawInput: String): String? {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) return null

        val candidate = if (trimmed.startsWith("$QR_SCHEME://", ignoreCase = true)) {
            val query = trimmed.substringAfter('?', "")
            query.split('&')
                .map { it.split('=', limit = 2) }
                .firstOrNull { it.size == 2 && it[0] == QR_PARAM_PK }
                ?.get(1)
                ?.trim()
        } else {
            trimmed
        } ?: return null

        return try {
            val bytes = Base64.getUrlDecoder().decode(candidate)
            Rfc8291Decryptor.decodeUncompressedPoint(bytes)
            Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Computes a human-readable 12-hex-char fingerprint (e.g. "4F9A-12C8-E03B") for visual verification.
     */
    fun computeKeyFingerprint(publicKeyBase64Url: String): String {
        return try {
            val pubBytes = Base64.getUrlDecoder().decode(publicKeyBase64Url)
            val digest = MessageDigest.getInstance("SHA-256").digest(pubBytes)
            val hex = digest.take(6).joinToString("") { "%02X".format(it) }
            "${hex.substring(0, 4)}-${hex.substring(4, 8)}-${hex.substring(8, 12)}"
        } catch (_: Exception) {
            "INVALID"
        }
    }

    fun encryptPayload(plaintextJson: String, recipientPublicKeyBase64Url: String): String {
        val recipientPubBytes = Base64.getUrlDecoder().decode(recipientPublicKeyBase64Url)
        val recipientPublicKey = Rfc8291Decryptor.decodeUncompressedPoint(recipientPubBytes)

        val ephemeralKeyPairGen = KeyPairGenerator.getInstance("EC")
        ephemeralKeyPairGen.initialize(ECGenParameterSpec(EC_CURVE), SecureRandom())
        val ephemeralKeyPair = ephemeralKeyPairGen.generateKeyPair()
        val ephemeralPub = ephemeralKeyPair.public as ECPublicKey
        val ephemeralPriv = ephemeralKeyPair.private as ECPrivateKey
        val ephemeralPubBytes = WebPushCryptoManager.toUncompressedPoint(ephemeralPub)
        val ephemeralPubB64Url = Base64.getUrlEncoder().withoutPadding().encodeToString(ephemeralPubBytes)

        val sharedSecret = computeEcdhSecret(ephemeralPriv, recipientPublicKey)

        val random = SecureRandom()
        val salt = ByteArray(16).also { random.nextBytes(it) }
        val iv = ByteArray(12).also { random.nextBytes(it) }

        val aesKey = deriveAes256Key(
            sharedSecret = sharedSecret,
            salt = salt,
            ephemeralPubBytes = ephemeralPubBytes,
            recipientPubBytes = recipientPubBytes
        )

        val compressedPlaintext = gzipCompress(plaintextJson.toByteArray(StandardCharsets.UTF_8))

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        val aad = buildAad(ephemeralPubBytes, recipientPubBytes)
        cipher.updateAAD(aad)
        val ciphertextBytes = cipher.doFinal(compressedPlaintext)

        val envelope = EncryptedTransferEnvelope(
            version = 1,
            recipientPublicKey = Base64.getUrlEncoder().withoutPadding().encodeToString(recipientPubBytes),
            ephemeralPublicKey = ephemeralPubB64Url,
            salt = Base64.getUrlEncoder().withoutPadding().encodeToString(salt),
            iv = Base64.getUrlEncoder().withoutPadding().encodeToString(iv),
            ciphertext = Base64.getUrlEncoder().withoutPadding().encodeToString(ciphertextBytes)
        )

        return json.encodeToString(envelope)
    }

    fun decryptPayload(envelopeJson: String, recipientKeyPair: TransferKeyPair): String {
        val envelope = json.decodeFromString<EncryptedTransferEnvelope>(envelopeJson)
        require(envelope.version == 1) {
            "Unsupported backup format version: ${envelope.version}"
        }

        val expectedRecipientPubBytes = Base64.getUrlDecoder().decode(recipientKeyPair.publicKeyBase64Url)
        val actualRecipientPubBytes = Base64.getUrlDecoder().decode(envelope.recipientPublicKey)
        require(expectedRecipientPubBytes.contentEquals(actualRecipientPubBytes)) {
            "Key mismatch: this backup was encrypted for a different QR key (${computeKeyFingerprint(envelope.recipientPublicKey)}), " +
                "but this device's current key is ${computeKeyFingerprint(recipientKeyPair.publicKeyBase64Url)}."
        }

        val ephemeralPubBytes = Base64.getUrlDecoder().decode(envelope.ephemeralPublicKey)
        val ephemeralPublicKey = Rfc8291Decryptor.decodeUncompressedPoint(ephemeralPubBytes)
        val recipientPrivateKey = WebPushCryptoManager.decodePrivateKeyFromPkcs8(recipientKeyPair.privateKeyPkcs8Base64)

        val sharedSecret = computeEcdhSecret(recipientPrivateKey, ephemeralPublicKey)
        val salt = Base64.getUrlDecoder().decode(envelope.salt)
        val iv = Base64.getUrlDecoder().decode(envelope.iv)
        val ciphertextBytes = Base64.getUrlDecoder().decode(envelope.ciphertext)

        val aesKey = deriveAes256Key(
            sharedSecret = sharedSecret,
            salt = salt,
            ephemeralPubBytes = ephemeralPubBytes,
            recipientPubBytes = expectedRecipientPubBytes
        )

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        val aad = buildAad(ephemeralPubBytes, expectedRecipientPubBytes)
        cipher.updateAAD(aad)
        val compressedPlaintext = cipher.doFinal(ciphertextBytes)

        val plaintextBytes = gzipDecompress(compressedPlaintext)
        return String(plaintextBytes, StandardCharsets.UTF_8)
    }

    private fun computeEcdhSecret(privateKey: PrivateKey, publicKey: ECPublicKey): ByteArray {
        val keyAgreement = KeyAgreement.getInstance("ECDH")
        keyAgreement.init(privateKey)
        keyAgreement.doPhase(publicKey, true)
        return keyAgreement.generateSecret()
    }

    private fun buildAad(ephemeralPubBytes: ByteArray, recipientPubBytes: ByteArray): ByteArray {
        val prefix = HKDF_INFO_PREFIX.toByteArray(StandardCharsets.UTF_8)
        val out = ByteArray(prefix.size + ephemeralPubBytes.size + recipientPubBytes.size)
        System.arraycopy(prefix, 0, out, 0, prefix.size)
        System.arraycopy(ephemeralPubBytes, 0, out, prefix.size, ephemeralPubBytes.size)
        System.arraycopy(recipientPubBytes, 0, out, prefix.size + ephemeralPubBytes.size, recipientPubBytes.size)
        return out
    }

    private fun deriveAes256Key(
        sharedSecret: ByteArray,
        salt: ByteArray,
        ephemeralPubBytes: ByteArray,
        recipientPubBytes: ByteArray
    ): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        val prk = mac.doFinal(sharedSecret)

        val info = buildAad(ephemeralPubBytes, recipientPubBytes)
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        mac.update(info)
        mac.update(0x01.toByte())
        return mac.doFinal() // 32 bytes (256 bits)
    }

    private fun gzipCompress(input: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { gzip ->
            gzip.write(input)
        }
        return bos.toByteArray()
    }

    private fun gzipDecompress(compressed: ByteArray): ByteArray {
        ByteArrayInputStream(compressed).use { bis ->
            GZIPInputStream(bis).use { gzip ->
                return gzip.readBytes()
            }
        }
    }
}
