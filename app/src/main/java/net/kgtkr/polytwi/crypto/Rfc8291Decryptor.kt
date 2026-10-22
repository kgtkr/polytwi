package net.kgtkr.polytwi.crypto

import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object Rfc8291Decryptor {

    /**
     * Decrypt Web Push message using either RFC 8291 ("aes128gcm") or Draft 03 ("aesgcm").
     */
    fun decrypt(
        encryptedPayload: ByteArray,
        encoding: String?,
        localPrivateKey: PrivateKey,
        localPublicKeyBytes: ByteArray, // 65 bytes
        authSecret: ByteArray,           // 16 bytes
        encryptionHeader: String? = null,
        cryptoKeyHeader: String? = null
    ): ByteArray {
        val normEncoding = (encoding ?: "aes128gcm").trim().lowercase()

        return if (normEncoding == "aes128gcm") {
            decryptAes128Gcm(encryptedPayload, localPrivateKey, localPublicKeyBytes, authSecret)
        } else if (normEncoding == "aesgcm") {
            decryptAesGcm(encryptedPayload, localPrivateKey, localPublicKeyBytes, authSecret, encryptionHeader, cryptoKeyHeader)
        } else {
            // Default attempt aes128gcm
            decryptAes128Gcm(encryptedPayload, localPrivateKey, localPublicKeyBytes, authSecret)
        }
    }

    /**
     * RFC 8291 (aes128gcm) decryption:
     * Header structure:
     * - salt: 16 bytes
     * - rs: 4 bytes (record size, big endian integer)
     * - idlen: 1 byte (length of keyid)
     * - keyid: idlen bytes (server public key, 65 bytes for p256)
     * - ciphertext + 16 bytes auth tag
     */
    private fun decryptAes128Gcm(
        payload: ByteArray,
        localPrivateKey: PrivateKey,
        localPublicKeyBytes: ByteArray,
        authSecret: ByteArray
    ): ByteArray {
        if (payload.size < 16 + 4 + 1 + 65 + 16) {
            throw IllegalArgumentException("Payload too short for aes128gcm: ${payload.size} bytes")
        }

        val salt = payload.copyOfRange(0, 16)
        val rs = ByteBuffer.wrap(payload, 16, 4).int
        val idlen = payload[20].toInt() and 0xFF
        if (idlen != 65) {
            throw IllegalArgumentException("Expected 65-byte P-256 public key, got $idlen")
        }
        val serverPublicKeyBytes = payload.copyOfRange(21, 21 + 65)
        val ciphertext = payload.copyOfRange(21 + 65, payload.size)

        val serverPublicKey = decodeUncompressedPoint(serverPublicKeyBytes)
        val ecdhSecret = computeEcdhSecret(localPrivateKey, serverPublicKey)

        // RFC 8291 Section 3.2
        // PRK_key = HKDF-Extract(salt=authSecret, IKM=ecdhSecret)
        val prkKey = hkdfExtract(authSecret, ecdhSecret)

        // key_info = "WebPush: info\0" || localPublicKey || serverPublicKey
        val keyInfo = ByteBuffer.allocate("WebPush: info\u0000".toByteArray(Charsets.US_ASCII).size + 65 + 65).apply {
            put("WebPush: info\u0000".toByteArray(Charsets.US_ASCII))
            put(localPublicKeyBytes)
            put(serverPublicKeyBytes)
        }.array()

        // IKM = HKDF-Expand(PRK_key, key_info, 32)
        val ikm = hkdfExpand(prkKey, keyInfo, 32)

        // RFC 8188 Section 2.2
        // PRK = HKDF-Extract(salt=salt, IKM=ikm)
        val prk = hkdfExtract(salt, ikm)

        // CEK = HKDF-Expand(PRK, "Content-Encoding: aes128gcm\0", 16)
        val cek = hkdfExpand(prk, "Content-Encoding: aes128gcm\u0000".toByteArray(Charsets.US_ASCII), 16)

        // NONCE = HKDF-Expand(PRK, "Content-Encoding: nonce\0", 12)
        val nonce = hkdfExpand(prk, "Content-Encoding: nonce\u0000".toByteArray(Charsets.US_ASCII), 12)

        // AES-128-GCM Decrypt
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val gcmSpec = GCMParameterSpec(128, nonce)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(cek, "AES"), gcmSpec)
        val decryptedWithPadding = cipher.doFinal(ciphertext)

        // RFC 8188 padding removal:
        // Records end with delimiter 0x02 (final record) or 0x01 (intermediate), followed by zeroes
        var endIdx = decryptedWithPadding.size - 1
        while (endIdx >= 0 && decryptedWithPadding[endIdx] == 0x00.toByte()) {
            endIdx--
        }
        if (endIdx < 0 || decryptedWithPadding[endIdx] != 0x02.toByte()) {
            // Some implementations might not have trailing zeros or use standard delimiter
            if (endIdx >= 0 && decryptedWithPadding[endIdx] == 0x01.toByte()) {
                // intermediate
            } else {
                return decryptedWithPadding
            }
        }

        return decryptedWithPadding.copyOfRange(0, endIdx)
    }

    /**
     * Draft-03 (aesgcm) decryption
     */
    private fun decryptAesGcm(
        payload: ByteArray,
        localPrivateKey: PrivateKey,
        localPublicKeyBytes: ByteArray,
        authSecret: ByteArray,
        encryptionHeader: String?,
        cryptoKeyHeader: String?
    ): ByteArray {
        val salt = parseHeaderValue(encryptionHeader, "salt")?.let {
            Base64.getUrlDecoder().decode(it)
        } ?: throw IllegalArgumentException("Missing salt in Encryption header")

        val dhBase64 = parseHeaderValue(cryptoKeyHeader, "dh")
            ?: throw IllegalArgumentException("Missing dh in Crypto-Key header")
        val serverPublicKeyBytes = Base64.getUrlDecoder().decode(dhBase64)
        val serverPublicKey = decodeUncompressedPoint(serverPublicKeyBytes)

        val ecdhSecret = computeEcdhSecret(localPrivateKey, serverPublicKey)

        val prkKey = hkdfExtract(authSecret, ecdhSecret)
        val ikm = hkdfExpand(prkKey, "Content-Encoding: auth\u0000".toByteArray(Charsets.US_ASCII), 32)

        // context = "P-256\0" || 0x00 || 0x41 || localPublicKey || 0x00 || 0x41 || serverPublicKey
        val context = ByteBuffer.allocate("P-256\u0000".length + 2 + 65 + 2 + 65).apply {
            put("P-256\u0000".toByteArray(Charsets.US_ASCII))
            putShort(65.toShort())
            put(localPublicKeyBytes)
            putShort(65.toShort())
            put(serverPublicKeyBytes)
        }.array()

        val prk = hkdfExtract(salt, ikm)

        val cekInfo = ByteBuffer.allocate("Content-Encoding: aesgcm\u0000".length + context.size).apply {
            put("Content-Encoding: aesgcm\u0000".toByteArray(Charsets.US_ASCII))
            put(context)
        }.array()
        val cek = hkdfExpand(prk, cekInfo, 16)

        val nonceInfo = ByteBuffer.allocate("Content-Encoding: nonce\u0000".length + context.size).apply {
            put("Content-Encoding: nonce\u0000".toByteArray(Charsets.US_ASCII))
            put(context)
        }.array()
        val nonce = hkdfExpand(prk, nonceInfo, 12)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val gcmSpec = GCMParameterSpec(128, nonce)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(cek, "AES"), gcmSpec)
        val decryptedWithPadding = cipher.doFinal(payload)

        // In aesgcm, first 2 bytes are padding length
        if (decryptedWithPadding.size >= 2) {
            val padLen = ByteBuffer.wrap(decryptedWithPadding, 0, 2).short.toInt() and 0xFFFF
            val startIdx = 2 + padLen
            if (startIdx <= decryptedWithPadding.size) {
                return decryptedWithPadding.copyOfRange(startIdx, decryptedWithPadding.size)
            }
        }

        return decryptedWithPadding
    }

    fun parseHeaderValue(header: String?, key: String): String? {
        if (header == null) return null
        // First try splitting by ';' or ','
        val parts = header.split(';', ',')
        for (part in parts) {
            val kv = part.trim().split("=", limit = 2)
            if (kv.size == 2 && kv[0].trim().equals(key, ignoreCase = true)) {
                return kv[1].trim().trim('"').replace("\n", "").replace("\r", "")
            }
        }
        // Regex fallback: key="value" or key=value
        val regex = Regex("""\b${Regex.escape(key)}\s*=\s*"?([A-Za-z0-9_-]+)"?""", RegexOption.IGNORE_CASE)
        return regex.find(header)?.groupValues?.get(1)
    }

    private fun computeEcdhSecret(privateKey: PrivateKey, publicKey: ECPublicKey): ByteArray {
        val keyAgreement = KeyAgreement.getInstance("ECDH")
        keyAgreement.init(privateKey)
        keyAgreement.doPhase(publicKey, true)
        return keyAgreement.generateSecret()
    }

    fun decodeUncompressedPoint(encoded: ByteArray): ECPublicKey {
        if (encoded.size != 65 || encoded[0] != 0x04.toByte()) {
            throw IllegalArgumentException("Invalid uncompressed point format")
        }
        val xBytes = encoded.copyOfRange(1, 33)
        val yBytes = encoded.copyOfRange(33, 65)
        val x = BigInteger(1, xBytes)
        val y = BigInteger(1, yBytes)
        val point = ECPoint(x, y)

        val params = AlgorithmParameters.getInstance("EC").apply {
            init(ECGenParameterSpec("secp256r1"))
        }.getParameterSpec(ECParameterSpec::class.java)

        val spec = ECPublicKeySpec(point, params)
        val keyFactory = KeyFactory.getInstance("EC")
        return keyFactory.generatePublic(spec) as ECPublicKey
    }

    // HKDF-Extract(salt, IKM) -> PRK
    private fun hkdfExtract(salt: ByteArray, ikm: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        val effectiveSalt = if (salt.isEmpty()) ByteArray(32) else salt
        mac.init(SecretKeySpec(effectiveSalt, "HmacSHA256"))
        return mac.doFinal(ikm)
    }

    // HKDF-Expand(PRK, info, L) -> OKM
    private fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(prk, "HmacSHA256"))

        val numBlocks = (length + 31) / 32
        var currentT = ByteArray(0)
        val okm = ByteArray(length)
        var offset = 0

        for (i in 1..numBlocks) {
            mac.update(currentT)
            mac.update(info)
            mac.update(i.toByte())
            currentT = mac.doFinal()

            val toCopy = Math.min(32, length - offset)
            System.arraycopy(currentT, 0, okm, offset, toCopy)
            offset += toCopy
        }
        return okm
    }
}
