package net.kgtkr.polytwi.injector

import android.webkit.JavascriptInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.kgtkr.polytwi.account.AccountManager
import net.kgtkr.polytwi.crypto.WebPushCryptoManager
import net.kgtkr.polytwi.push.NotificationData
import net.kgtkr.polytwi.push.NotificationDispatcher
import net.kgtkr.polytwi.push.PushBridgeClient
import net.kgtkr.polytwi.push.TwitterNotificationPayload
import net.kgtkr.polytwi.util.UrlValidator

class PushJsBridge(
    private val accountId: String,
    private val accountManager: AccountManager,
    private val pushBridgeClient: PushBridgeClient,
    private val notificationDispatcher: NotificationDispatcher,
    private val scope: CoroutineScope,
    private val bridgeToken: String,
    private val currentUrlProvider: () -> String? = { null }
) {
    companion object {
        // Default X.com VAPID public key (base64url)
        const val DEFAULT_X_VAPID_KEY =
            "BM5oEo0xDUpgylKDTlsd8pZmxQA1leYINiY-rSscWYK_3tWAkz4VMbtf1MLE_Yyd6iII6o-e3Q9TCN5vZMzVMEs"
    }

    private fun isAuthorized(token: String?): Boolean {
        if (bridgeToken.isEmpty() || token != bridgeToken) {
            android.util.Log.w("PushJsBridge", "Blocked call with invalid bridge token")
            return false
        }
        val currentUrl = currentUrlProvider()
        if (!UrlValidator.isAllowedPushOrigin(currentUrl)) {
            android.util.Log.w("PushJsBridge", "Blocked call from untrusted origin: $currentUrl")
            return false
        }
        return true
    }

    @JavascriptInterface
    fun getExistingSubscription(token: String?): String? {
        if (!isAuthorized(token)) return null
        val account = accountManager.getAccountById(accountId) ?: return null
        if (account.pushEnabled && account.channelId != null && account.endpoint != null && account.p256dhBase64 != null && account.authBase64 != null) {
            val jsonMap = mapOf(
                "endpoint" to account.endpoint,
                "p256dh" to account.p256dhBase64,
                "auth" to account.authBase64,
                "appServerKey" to (account.appServerKeyBase64?.takeIf { it.isNotBlank() } ?: DEFAULT_X_VAPID_KEY)
            )
            return Json.encodeToString(jsonMap)
        }
        return null
    }

    @JavascriptInterface
    fun subscribe(token: String?, appServerKeyBase64Url: String): String? {
        if (!isAuthorized(token)) return null
        return runBlocking(Dispatchers.IO) {
            try {
                var account = accountManager.getAccountById(accountId) ?: return@runBlocking null
                val effectiveServerKey = appServerKeyBase64Url.takeIf { it.isNotBlank() } ?: DEFAULT_X_VAPID_KEY

                // Ensure key pair and stable logical channelId exist
                val logicalChannelId = account.channelId?.takeIf { it.isNotBlank() }
                    ?: java.util.UUID.randomUUID().toString()

                if (account.p256dhBase64 == null || account.authBase64 == null || account.privateKeyPkcs8Base64 == null) {
                    val keys = WebPushCryptoManager.generateKeys()
                    account = account.copy(
                        channelId = logicalChannelId,
                        p256dhBase64 = keys.p256dhBase64Url,
                        authBase64 = keys.authBase64Url,
                        privateKeyPkcs8Base64 = keys.privateKeyPkcs8Base64,
                        appServerKeyBase64 = effectiveServerKey
                    )
                    accountManager.updateAccount(account)
                } else if (account.channelId != logicalChannelId) {
                    account = account.copy(channelId = logicalChannelId)
                    accountManager.updateAccount(account)
                }

                // Register or reuse logicalChannelId on the bridge without destroying other devices' subscriptions
                val result = pushBridgeClient.registerChannel(effectiveServerKey, logicalChannelId)
                if (result != null) {
                    val (channelId, endpoint, _) = result

                    val updatedAccount = account.copy(
                        channelId = channelId,
                        endpoint = endpoint,
                        appServerKeyBase64 = effectiveServerKey,
                        pushEnabled = true
                    )
                    accountManager.updateAccount(updatedAccount)

                    val jsonMap = mapOf(
                        "endpoint" to endpoint,
                        "p256dh" to updatedAccount.p256dhBase64!!,
                        "auth" to updatedAccount.authBase64!!,
                        "appServerKey" to effectiveServerKey
                    )
                    return@runBlocking Json.encodeToString(jsonMap)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            null
        }
    }

    @JavascriptInterface
    fun unsubscribe(token: String?): Boolean {
        if (!isAuthorized(token)) return false
        val account = accountManager.getAccountById(accountId) ?: return false
        if (account.channelId != null || account.endpoint != null) {
            // Keep channelId, endpoint, and WebPush keys intact so re-enabling notifications
            // or multi-device sharing continues using the same logical channel and keys.
            accountManager.updateAccount(account.copy(pushEnabled = false))
            return true
        }
        return false
    }

    @JavascriptInterface
    fun showTestNotification(token: String?, title: String, body: String) {
        if (!isAuthorized(token)) return
        val account = accountManager.getAccountById(accountId)
        val accountName = account?.name ?: "X"
        scope.launch {
            notificationDispatcher.showNotification(
                accountId = accountId,
                accountName = accountName,
                payload = TwitterNotificationPayload(
                    title = title,
                    body = body,
                    data = NotificationData(url = "https://x.com/notifications")
                )
            )
        }
    }
}
