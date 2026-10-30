package net.kgtkr.polytwi.transfer

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.CookieManager
import androidx.core.content.FileProvider
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewFeature
import java.io.File
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.kgtkr.polytwi.account.AccountManager
import net.kgtkr.polytwi.crypto.TransferCryptoManager
import net.kgtkr.polytwi.crypto.TransferKeyPair
import net.kgtkr.polytwi.injector.CpftConfigBridge
import net.kgtkr.polytwi.model.Account
import net.kgtkr.polytwi.push.PushBridgeClient
import net.kgtkr.polytwi.webview.WebViewPool

@Serializable
data class ExportedCookie(
    val name: String,
    val value: String,
    val domain: String = ".x.com",
    val path: String = "/",
    val secure: Boolean = true,
    val httpOnly: Boolean = false,
    val sameSite: String = "None"
)

@Serializable
data class ExportedAccountData(
    val account: Account,
    val cookies: List<ExportedCookie> = emptyList()
)

@Serializable
data class SessionTransferBundle(
    val version: Int = 1,
    val exportedAtMs: Long,
    val activeAccountId: String? = null,
    val accounts: List<ExportedAccountData>,
    val cpftConfigJson: String? = null
)

class SessionTransferManager(
    private val context: Context,
    private val accountManager: AccountManager,
    private val cpftConfigBridge: CpftConfigBridge,
    private val pushBridgeClient: PushBridgeClient,
    private val webViewPool: WebViewPool
) {
    companion object {
        private val COOKIE_QUERY_URLS = listOf(
            "https://x.com" to ".x.com",
            "https://api.x.com" to ".x.com",
            "https://mobile.twitter.com" to ".twitter.com",
            "https://twitter.com" to ".twitter.com",
            "https://api.twitter.com" to ".twitter.com"
        )

        private val HTTP_ONLY_COOKIE_NAMES = setOf(
            "auth_token",
            "kdt",
            "att",
            "_twitter_sess"
        )

        private val SAMESITE_LAX_COOKIE_NAMES = setOf(
            "ct0"
        )
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun getOrCreateKeyPair(): TransferKeyPair {
        val saved = accountManager.getSavedTransferKeyPair()
        if (saved != null) {
            return TransferKeyPair(
                publicKeyBase64Url = saved.first,
                privateKeyPkcs8Base64 = saved.second
            )
        }
        return regenerateKeyPair()
    }

    fun regenerateKeyPair(): TransferKeyPair {
        val generated = TransferCryptoManager.generateKeyPair()
        accountManager.saveTransferKeyPair(
            publicKeyBase64Url = generated.publicKeyBase64Url,
            privateKeyPkcs8Base64 = generated.privateKeyPkcs8Base64
        )
        return generated
    }

    /**
     * Collects all accounts, their WebView profile cookies, and CPFT settings,
     * encrypts them for [recipientPublicKeyBase64Url], writes a `.ptbackup` file in cacheDir,
     * and returns its FileProvider Uri ready for Quick Share / ACTION_SEND.
     */
    suspend fun createEncryptedBackupFile(recipientPublicKeyBase64Url: String): Uri {
        val bundle = withContext(Dispatchers.Main) {
            val accounts = accountManager.accounts.value
            val activeId = accountManager.activeAccountId.value
            val exportedAccounts = accounts.map { acc ->
                val cookies = extractCookiesForAccount(acc)
                ExportedAccountData(
                    account = acc,
                    cookies = cookies
                )
            }
            val cpftJson = cpftConfigBridge.getAllConfig()
            SessionTransferBundle(
                version = 1,
                exportedAtMs = System.currentTimeMillis(),
                activeAccountId = activeId,
                accounts = exportedAccounts,
                cpftConfigJson = cpftJson
            )
        }

        return withContext(Dispatchers.IO) {
            val plaintextJson = json.encodeToString(bundle)
            val encryptedEnvelopeJson = TransferCryptoManager.encryptPayload(
                plaintextJson = plaintextJson,
                recipientPublicKeyBase64Url = recipientPublicKeyBase64Url
            )

            val transferDir = File(context.cacheDir, "transfer").apply {
                if (!exists()) mkdirs()
                // Clean up older temporary backup files
                listFiles()?.forEach { it.delete() }
            }

            val fingerprint = TransferCryptoManager.computeKeyFingerprint(recipientPublicKeyBase64Url)
                .replace("-", "")
                .lowercase()
            val outFile = File(transferDir, "polytwi-backup-$fingerprint.ptbackup")
            outFile.writeText(encryptedEnvelopeJson, StandardCharsets.UTF_8)

            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                outFile
            )
        }
    }

    fun buildShareIntent(backupUri: Uri): Intent {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_STREAM, backupUri)
            clipData = ClipData.newRawUri("PolyTwi Encrypted Backup", backupUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(sendIntent, "Send Encrypted Backup (Quick Share)")
    }

    /**
     * Reads an encrypted `.ptbackup` Uri, decrypts it with this device's transfer keypair,
     * imports accounts, cookies, and CPFT settings, and syncs push channels with the bridge.
     * Returns the number of imported accounts.
     */
    suspend fun importEncryptedBackupUri(
        uri: Uri,
        replaceExisting: Boolean
    ): Int {
        val keyPair = getOrCreateKeyPair()

        val bundle = withContext(Dispatchers.IO) {
            val envelopeJson = context.contentResolver.openInputStream(uri)?.use { input ->
                input.readBytes().toString(StandardCharsets.UTF_8)
            } ?: throw IllegalArgumentException("Unable to read selected backup file")

            val decryptedJson = TransferCryptoManager.decryptPayload(envelopeJson, keyPair)
            json.decodeFromString<SessionTransferBundle>(decryptedJson)
        }

        require(bundle.accounts.isNotEmpty()) {
            "Backup file contains no accounts"
        }

        withContext(Dispatchers.Main) {
            val importedAccounts = bundle.accounts.map { it.account }
            val importedChannelIds = importedAccounts.mapNotNull { it.channelId }.toSet()

            // Remove pooled WebViews for accounts being updated so they reload with imported cookies
            importedAccounts.forEach { acc ->
                webViewPool.removeWebView(acc.id)
            }

            val removedAccounts = accountManager.importAccounts(
                importedAccounts = importedAccounts,
                importedActiveId = bundle.activeAccountId,
                replaceExisting = replaceExisting
            )

            // Clean up any replaced local accounts (e.g., fresh install default "Account 1")
            removedAccounts.forEach { removed ->
                removed.channelId?.let { cid ->
                    if (cid !in importedChannelIds) {
                        pushBridgeClient.unregisterChannel(cid)
                    }
                }
                webViewPool.removeWebView(removed.id)
                if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
                    try {
                        ProfileStore.getInstance().deleteProfile(removed.profileName)
                    } catch (_: Exception) {
                    }
                }
            }

            // Restore cookies into each imported account's WebView profile
            bundle.accounts.forEach { exportedAcc ->
                restoreCookiesForAccount(exportedAcc.account, exportedAcc.cookies)
            }

            // Restore CPFT settings if present
            bundle.cpftConfigJson?.takeIf { it.isNotBlank() }?.let { cpftJson ->
                cpftConfigBridge.mergeConfig(cpftJson)
            }
        }

        // Register this device's FCM token for all imported logical channelIds on autopush-fcm-bridge
        pushBridgeClient.syncChannels()

        return bundle.accounts.size
    }

    private fun getCookieManagerForAccount(account: Account): CookieManager {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            val profile = ProfileStore.getInstance().getOrCreateProfile(account.profileName)
            return profile.cookieManager
        }
        return CookieManager.getInstance()
    }

    private fun extractCookiesForAccount(account: Account): List<ExportedCookie> {
        val cookieManager = getCookieManagerForAccount(account)
        cookieManager.flush()

        // Map keyed by (domain, name) to deduplicate
        val collected = LinkedHashMap<Pair<String, String>, ExportedCookie>()

        for ((url, defaultDomain) in COOKIE_QUERY_URLS) {
            val rawCookieHeader = cookieManager.getCookie(url) ?: continue
            val pairs = rawCookieHeader.split(';')
            for (pair in pairs) {
                val trimmed = pair.trim()
                if (trimmed.isEmpty()) continue
                val eqIdx = trimmed.indexOf('=')
                if (eqIdx <= 0) continue

                val name = trimmed.substring(0, eqIdx).trim()
                val value = trimmed.substring(eqIdx + 1).trim()
                if (name.isEmpty()) continue

                val isHttpOnly = name in HTTP_ONLY_COOKIE_NAMES
                val sameSite = if (name in SAMESITE_LAX_COOKIE_NAMES) "Lax" else "None"

                val key = defaultDomain to name
                if (!collected.containsKey(key)) {
                    collected[key] = ExportedCookie(
                        name = name,
                        value = value,
                        domain = defaultDomain,
                        path = "/",
                        secure = true,
                        httpOnly = isHttpOnly,
                        sameSite = sameSite
                    )
                }
            }
        }

        return collected.values.toList()
    }

    private fun restoreCookiesForAccount(account: Account, cookies: List<ExportedCookie>) {
        val cookieManager = getCookieManagerForAccount(account)
        cookieManager.setAcceptCookie(true)

        val maxAge = 60 * 60 * 24 * 365 * 5 // 5 years

        for (cookie in cookies) {
            val domains = when {
                cookie.domain.endsWith("x.com") -> listOf("https://x.com" to ".x.com", "https://twitter.com" to ".twitter.com")
                cookie.domain.endsWith("twitter.com") -> listOf("https://twitter.com" to ".twitter.com", "https://x.com" to ".x.com")
                else -> listOf("https://${cookie.domain.trimStart('.')}" to cookie.domain)
            }

            for ((targetUrl, domainAttr) in domains) {
                val sb = StringBuilder()
                sb.append("${cookie.name}=${cookie.value}")
                sb.append("; Domain=$domainAttr")
                sb.append("; Path=${cookie.path.ifBlank { "/" }}")
                sb.append("; Max-Age=$maxAge")
                if (cookie.secure) {
                    sb.append("; Secure")
                }
                if (cookie.httpOnly) {
                    sb.append("; HttpOnly")
                }
                if (cookie.sameSite.isNotBlank()) {
                    sb.append("; SameSite=${cookie.sameSite}")
                }
                cookieManager.setCookie(targetUrl, sb.toString())
            }
        }

        cookieManager.flush()
    }
}
