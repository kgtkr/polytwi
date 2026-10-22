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
import net.kgtkr.polytwi.push.AutopushClient
import net.kgtkr.polytwi.push.NotificationData
import net.kgtkr.polytwi.push.NotificationDispatcher
import net.kgtkr.polytwi.push.TwitterNotificationPayload

class PushJsBridge(
    private val accountId: String,
    private val accountManager: AccountManager,
    private val autopushClient: AutopushClient,
    private val notificationDispatcher: NotificationDispatcher,
    private val scope: CoroutineScope
) {
    companion object {
        // Default X.com VAPID public key (base64url)
        const val DEFAULT_X_VAPID_KEY =
            "BM5oEo0xDUpgylKDTlsd8pZmxQA1leYINiY-rSscWYK_3tWAkz4VMbtf1MLE_Yyd6iII6o-e3Q9TCN5vZMzVMEs"
    }

    @JavascriptInterface
    fun getExistingSubscription(): String? {
        val account = accountManager.getAccountById(accountId) ?: return null
        if (account.endpoint != null && account.p256dhBase64 != null && account.authBase64 != null) {
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
    fun subscribe(appServerKeyBase64Url: String): String? {
        return runBlocking(Dispatchers.IO) {
            try {
                var account = accountManager.getAccountById(accountId) ?: return@runBlocking null
                val effectiveServerKey = appServerKeyBase64Url.takeIf { it.isNotBlank() } ?: DEFAULT_X_VAPID_KEY

                // Ensure key pair exists
                if (account.p256dhBase64 == null || account.authBase64 == null || account.privateKeyPkcs8Base64 == null) {
                    val keys = WebPushCryptoManager.generateKeys()
                    account = account.copy(
                        p256dhBase64 = keys.p256dhBase64Url,
                        authBase64 = keys.authBase64Url,
                        privateKeyPkcs8Base64 = keys.privateKeyPkcs8Base64,
                        appServerKeyBase64 = effectiveServerKey
                    )
                    accountManager.updateAccount(account)
                }

                // Reuse existing channel/endpoint if already registered, or register with Autopush
                val result = autopushClient.registerChannel(effectiveServerKey)
                if (result != null) {
                    val (channelId, endpoint, _) = result
                    val updatedAccount = account.copy(
                        channelId = channelId,
                        endpoint = endpoint,
                        appServerKeyBase64 = effectiveServerKey
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
    fun unsubscribe(): Boolean {
        val account = accountManager.getAccountById(accountId) ?: return false
        val channelId = account.channelId
        if (channelId != null) {
            autopushClient.unregisterChannel(channelId)
            accountManager.updateAccount(account.copy(channelId = null, endpoint = null))
            return true
        }
        return false
    }

    @JavascriptInterface
    fun showTestNotification(title: String, body: String) {
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
