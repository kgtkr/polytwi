package net.kgtkr.polytwi.crypto

import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

data class WebPushKeys(
    val publicKeyBytes: ByteArray, // 65 bytes uncompressed: 0x04 || X(32) || Y(32)
    val p256dhBase64Url: String,
    val authBytes: ByteArray,      // 16 random bytes
    val authBase64Url: String,
    val privateKey: PrivateKey,
    val privateKeyPkcs8Base64: String
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as WebPushKeys
        return p256dhBase64Url == other.p256dhBase64Url
    }

    override fun hashCode(): Int {
        return p256dhBase64Url.hashCode()
    }
}

object WebPushCryptoManager {
    private const val EC_CURVE = "secp256r1" // NIST P-256

    fun generateKeys(): WebPushKeys {
        val keyPairGen = KeyPairGenerator.getInstance("EC")
        val ecSpec = ECGenParameterSpec(EC_CURVE)
        keyPairGen.initialize(ecSpec, SecureRandom())
        val keyPair: KeyPair = keyPairGen.generateKeyPair()

        val ecPublicKey = keyPair.public as ECPublicKey
        val ecPrivateKey = keyPair.private as ECPrivateKey

        val publicKeyBytes = toUncompressedPoint(ecPublicKey)
        val p256dhBase64Url = Base64.getUrlEncoder().withoutPadding().encodeToString(publicKeyBytes)

        val authBytes = ByteArray(16)
        SecureRandom().nextBytes(authBytes)
        val authBase64Url = Base64.getUrlEncoder().withoutPadding().encodeToString(authBytes)

        val privateKeyPkcs8Base64 = Base64.getEncoder().withoutPadding().encodeToString(ecPrivateKey.encoded)

        return WebPushKeys(
            publicKeyBytes = publicKeyBytes,
            p256dhBase64Url = p256dhBase64Url,
            authBytes = authBytes,
            authBase64Url = authBase64Url,
            privateKey = ecPrivateKey,
            privateKeyPkcs8Base64 = privateKeyPkcs8Base64
        )
    }

    /**
     * Converts ECPublicKey to 65-byte uncompressed form (0x04 || X || Y)
     * with each coordinate strictly padded or trimmed to 32 bytes.
     */
    fun toUncompressedPoint(publicKey: ECPublicKey): ByteArray {
        val w = publicKey.w
        val xBytes = toFixed32Bytes(w.affineX)
        val yBytes = toFixed32Bytes(w.affineY)

        val result = ByteArray(65)
        result[0] = 0x04
        System.arraycopy(xBytes, 0, result, 1, 32)
        System.arraycopy(yBytes, 0, result, 33, 32)
        return result
    }

    private fun toFixed32Bytes(bigInt: BigInteger): ByteArray {
        val raw = bigInt.toByteArray()
        val result = ByteArray(32)
        if (raw.size == 32) {
            System.arraycopy(raw, 0, result, 0, 32)
        } else if (raw.size > 32) {
            // raw has leading 0x00 sign bit, skip leading bytes
            System.arraycopy(raw, raw.size - 32, result, 0, 32)
        } else {
            // raw is less than 32 bytes, right-align with leading zeros
            System.arraycopy(raw, 0, result, 32 - raw.size, raw.size)
        }
        return result
    }

    fun decodePrivateKeyFromPkcs8(base64Pkcs8: String): PrivateKey {
        val keyBytes = Base64.getDecoder().decode(base64Pkcs8)
        val keySpec = PKCS8EncodedKeySpec(keyBytes)
        val keyFactory = KeyFactory.getInstance("EC")
        return keyFactory.generatePrivate(keySpec)
    }
}
