package net.kgtkr.polytwi.push

import android.content.Context
import android.os.PowerManager
import android.util.Log
import com.google.firebase.messaging.FirebaseMessaging
import java.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.kgtkr.polytwi.BuildConfig
import net.kgtkr.polytwi.account.AccountManager
import net.kgtkr.polytwi.crypto.Rfc8291Decryptor
import net.kgtkr.polytwi.crypto.WebPushCryptoManager
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class PushBridgeClient(
    private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val accountManager: AccountManager,
    private val notificationDispatcher: NotificationDispatcher,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "PushBridgeClient"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val baseUrl: String = BuildConfig.PUSH_BRIDGE_URL.trimEnd('/')

    private suspend fun getFcmToken(): String? {
        return try {
            FirebaseMessaging.getInstance().token.await()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to obtain FCM token", e)
            null
        }
    }

    suspend fun registerChannel(
        appServerKeyBase64Url: String,
        channelId: String? = null
    ): Triple<String, String, String>? =
        withContext(Dispatchers.IO) {
            if (baseUrl.isBlank()) {
                Log.e(TAG, "BuildConfig.PUSH_BRIDGE_URL is not configured")
                return@withContext null
            }

            val fcmToken = getFcmToken()
            if (fcmToken.isNullOrBlank()) {
                Log.e(TAG, "Cannot register channel: FCM token is null or blank")
                return@withContext null
            }

            try {
                val reqBodyObj = RegisterChannelRequest(
                    fcm_token = fcmToken,
                    app_server_key = appServerKeyBase64Url,
                    channel_id = channelId?.takeIf { it.isNotBlank() }
                )
                val bodyJson = json.encodeToString(reqBodyObj)
                val request = Request.Builder()
                    .url("$baseUrl/v1/channels")
                    .post(bodyJson.toRequestBody(JSON_MEDIA_TYPE))
                    .build()

                okHttpClient.newCall(request).execute().use { response ->
                    val respText = response.body?.string()
                    if (!response.isSuccessful || respText.isNullOrBlank()) {
                        Log.e(
                            TAG,
                            "registerChannel failed with HTTP ${response.code}: $respText"
                        )
                        return@withContext null
                    }

                    val parsed = json.decodeFromString<RegisterChannelResponse>(respText)
                    Log.i(
                        TAG,
                        "Registered channel ${parsed.channel_id} via bridge: ${parsed.push_endpoint}"
                    )
                    Triple(parsed.channel_id, parsed.push_endpoint, parsed.app_server_key)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error registering channel with bridge", e)
                null
            }
        }

    fun unregisterChannel(channelId: String) {
        if (baseUrl.isBlank() || channelId.isBlank()) return
        scope.launch(Dispatchers.IO) {
            try {
                val fcmToken = getFcmToken()
                val url = if (!fcmToken.isNullOrBlank()) {
                    val encodedToken = java.net.URLEncoder.encode(fcmToken, "UTF-8")
                    "$baseUrl/v1/channels/$channelId?fcm_token=$encodedToken"
                } else {
                    "$baseUrl/v1/channels/$channelId"
                }
                val request = Request.Builder()
                    .url(url)
                    .delete()
                    .build()

                okHttpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        Log.i(TAG, "Unregistered channel $channelId from bridge (device token)")
                    } else {
                        Log.w(
                            TAG,
                            "Failed to unregister channel $channelId: HTTP ${response.code}"
                        )
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error unregistering channel $channelId", e)
            }
        }
    }

    fun syncChannels(newFcmToken: String? = null) {
        if (baseUrl.isBlank()) return
        scope.launch(Dispatchers.IO) {
            try {
                val accounts = accountManager.accounts.value
                // Reset any legacy account that has an endpoint without a channelId
                accounts.forEach { acc ->
                    if (acc.endpoint != null && acc.channelId.isNullOrBlank()) {
                        accountManager.updateAccount(
                            acc.copy(endpoint = null, channelId = null, pushEnabled = false)
                        )
                    }
                }

                val channelIds = accountManager.accounts.value
                    .mapNotNull { it.channelId?.takeIf { id -> id.isNotBlank() } }

                if (channelIds.isEmpty()) return@launch

                val fcmToken = newFcmToken ?: getFcmToken() ?: return@launch
                val reqBodyObj = SyncChannelsRequest(
                    fcm_token = fcmToken,
                    channel_ids = channelIds
                )
                val bodyJson = json.encodeToString(reqBodyObj)
                val request = Request.Builder()
                    .url("$baseUrl/v1/channels/sync")
                    .post(bodyJson.toRequestBody(JSON_MEDIA_TYPE))
                    .build()

                okHttpClient.newCall(request).execute().use { response ->
                    val respText = response.body?.string()
                    if (!response.isSuccessful || respText.isNullOrBlank()) {
                        Log.w(TAG, "syncChannels failed with HTTP ${response.code}: $respText")
                        return@use
                    }

                    val syncResp = json.decodeFromString<SyncChannelsResponse>(respText)
                    val expiredSet = syncResp.expired_channel_ids.toSet()
                    val endpointsMap = syncResp.channel_endpoints

                    accountManager.accounts.value.forEach { acc ->
                        val cid = acc.channelId ?: return@forEach
                        if (cid in expiredSet) {
                            Log.w(
                                TAG,
                                "Bridge reported expired channel $cid for ${acc.name}. Clearing local endpoint for re-subscription."
                            )
                            accountManager.updateAccount(
                                acc.copy(endpoint = null, pushEnabled = false)
                            )
                        } else {
                            val latestEndpoint = endpointsMap[cid]
                            if (!latestEndpoint.isNullOrBlank() && latestEndpoint != acc.endpoint) {
                                accountManager.updateAccount(
                                    acc.copy(endpoint = latestEndpoint)
                                )
                            }
                        }
                    }

                    Log.d(
                        TAG,
                        "Synced ${syncResp.active_channel_ids.size} active channel(s) with bridge"
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error syncing channels with bridge", e)
            }
        }
    }

    fun handleChannelExpired(channelId: String) {
        val account = accountManager.getAccountByChannelId(channelId) ?: return
        Log.w(
            TAG,
            "Channel $channelId expired on Autopush for account ${account.name}. Resetting subscription endpoint."
        )
        accountManager.updateAccount(account.copy(endpoint = null, pushEnabled = false))
    }

    suspend fun handleNotificationPayload(fcmData: Map<String, String>) {
        val channelId = fcmData["channel_id"] ?: return
        val rawData = fcmData["data"] ?: return
        val account = accountManager.getAccountByChannelId(channelId) ?: run {
            Log.w(TAG, "Account not found for channelId: $channelId. Unregistering from bridge.")
            unregisterChannel(channelId)
            return
        }

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val wakeLock = powerManager?.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "PolyTwi:PushProcessing"
        )?.apply {
            acquire(5000L)
        }

        try {
            val privateKeyPkcs8 = account.privateKeyPkcs8Base64 ?: return
            val localPrivateKey = WebPushCryptoManager.decodePrivateKeyFromPkcs8(privateKeyPkcs8)
            val localPublicKeyBytes = Base64.getUrlDecoder().decode(account.p256dhBase64)
            val authSecret = Base64.getUrlDecoder().decode(account.authBase64)

            val encryptedPayload = Base64.getUrlDecoder().decode(rawData)

            val parsedHeaders: Map<String, String>? = fcmData["headers"]?.let { hdrJson ->
                try {
                    json.decodeFromString<Map<String, String>>(hdrJson)
                } catch (_: Exception) {
                    null
                }
            }

            fun getHeader(name: String): String? {
                val target = name.replace("-", "").replace("_", "").lowercase()
                return parsedHeaders?.entries?.firstOrNull {
                    it.key.replace("-", "").replace("_", "").lowercase() == target
                }?.value
            }

            val encoding = fcmData["encoding"]
                ?: getHeader("contentencoding")
                ?: getHeader("encoding")
            val encryptionHeader = fcmData["encryption"]
                ?: getHeader("encryption")
            val cryptoKeyHeader = fcmData["crypto_key"]
                ?: getHeader("cryptokey")

            Log.d(TAG, "Notification received for ${account.name}. Processing decryption...")

            val decryptedBytes = Rfc8291Decryptor.decrypt(
                encryptedPayload = encryptedPayload,
                encoding = encoding,
                localPrivateKey = localPrivateKey,
                localPublicKeyBytes = localPublicKeyBytes,
                authSecret = authSecret,
                encryptionHeader = encryptionHeader,
                cryptoKeyHeader = cryptoKeyHeader
            )

            val decryptedJson = String(decryptedBytes, Charsets.UTF_8)
            Log.i(TAG, "Notification successfully decrypted for ${account.name}")

            val notificationPayload =
                json.decodeFromString<TwitterNotificationPayload>(decryptedJson)
            notificationDispatcher.showNotification(
                accountId = account.id,
                accountName = account.name,
                payload = notificationPayload
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decrypt notification for account ${account.name}", e)
        } finally {
            try {
                if (wakeLock?.isHeld == true) {
                    wakeLock.release()
                }
            } catch (_: Exception) {
            }
        }
    }
}
